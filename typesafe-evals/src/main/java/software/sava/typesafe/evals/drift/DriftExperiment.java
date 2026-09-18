package software.sava.typesafe.evals.drift;

import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;
import software.sava.typesafe.evals.corpus.PublicRepoGate;
import software.sava.typesafe.evals.docs.HistoryMiner;
import software.sava.typesafe.evals.jev.JevRunner;
import software.sava.typesafe.evals.report.Tsv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/// Experiment D end to end: one row per documented member's latest change in every public
/// checkout named, scored once (the pair arm) and once more per changed file (the batched
/// arm), the amended decision table, and one shuffled blind sheet of every row.
///
/// Arguments: `--checkouts <dir>` `--repos <name,...>` `--out <dir>` `--recordings <dir>`
/// `--mode record|replay|corpus` `--concurrency <n>` `--labels <sheet>` `--visibility-cache <file>`
public final class DriftExperiment {

  public record Config(Path checkouts, List<String> repos, Path out, Path recordings, String mode, int concurrency, Path labels,
                       Path visibilityCache) {

    public static Config parse(final String[] args) {
      final var map = new LinkedHashMap<String, String>();
      for (int i = 0; i + 1 < args.length; i += 2) {
        if (!args[i].startsWith("--")) {
          throw new IllegalArgumentException("expected --option value, got " + args[i]);
        }
        map.put(args[i].substring(2), args[i + 1]);
      }
      for (final var required : List.of("checkouts", "repos")) {
        if (!map.containsKey(required)) {
          throw new IllegalArgumentException("--" + required + " is required");
        }
      }
      final var out = Path.of(map.getOrDefault("out", "build/experiments/drift"));
      return new Config(
          Path.of(map.get("checkouts")),
          List.of(map.get("repos").split(",")),
          out,
          Path.of(map.getOrDefault("recordings", out.resolve("recordings").toString())),
          map.getOrDefault("mode", "record"),
          Integer.parseInt(map.getOrDefault("concurrency", "4")),
          map.containsKey("labels") ? Path.of(map.get("labels")) : null,
          Path.of(map.getOrDefault("visibility-cache", out.resolve("visibility.tsv").toString()))
      );
    }
  }

  /// @param spend null in corpus mode
  /// @param batch the batched arm's numbers; scores are NaN in corpus mode
  public record Summary(int repos, int skipped, int rows, int coEdits, int bodyOnly, int commits, JevRunner.Totals spend,
                        DriftBars.Verdict verdict, BatchSummary batch) {
  }

  /// @param changedOnlyAuroc positives over changed-member negatives (like the pair arm)
  /// @param meanRequestAuroc mean within-request AUROC over requests holding both classes
  public record BatchSummary(int batches, int requests, int candidates, int positives, long inputTokens, double pooledAuroc,
                             double changedOnlyAuroc, double meanRequestAuroc, int requestsWithBoth) {
  }

  private DriftExperiment() {
  }

  public static void main(final String[] args) {
    run(Config.parse(args), null, ProcessCommandRunner.INSTANCE);
  }

  static JevRunner runnerFor(final Config config) {
    return new JevRunner(
        "replay".equals(config.mode()) ? JevRunner.replayOnly(config.recordings()) : JevRunner.recording(config.recordings()),
        config.concurrency());
  }

  public static Summary run(final Config config, final JevRunner runner, final CommandRunner commands) {
    final var gate = new PublicRepoGate(commands, config.visibilityCache());
    final var rows = new ArrayList<DriftCorpus.Row>();
    final var excluded = new TreeMap<String, Integer>();
    final var mined = new LinkedHashMap<String, List<HistoryMiner.Event>>();
    final var miners = new LinkedHashMap<String, HistoryMiner>();
    int skipped = 0;
    for (final var name : config.repos()) {
      final var checkout = config.checkouts().resolve(name);
      if (!Files.isDirectory(checkout)) {
        skipped++;
        continue;
      }
      final var git = new GitRepo(checkout, commands);
      final String origin;
      try {
        origin = git.originOwnerRepo();
      } catch (final RuntimeException notARepo) {
        skipped++;
        continue;
      }
      if (!gate.isPublic(origin)) {
        skipped++;
        continue;
      }
      final var miner = new HistoryMiner(git, HistoryMiner.MAIN_SOURCES);
      final var events = miner.mine();
      mined.put(name, events);
      miners.put(name, miner);
      final var result = DriftCorpus.rows(name, events);
      rows.addAll(result.rows());
      for (final var ex : result.excluded()) {
        excluded.merge(ex.reason(), ex.count(), Integer::sum);
      }
    }
    rows.sort(java.util.Comparator.comparing(DriftCorpus.Row::id));
    try {
      Files.createDirectories(config.out());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create " + config.out(), e);
    }
    writeRows(rows, config.out().resolve("rows.tsv"));
    final int coEdits = (int) rows.stream().filter(r -> r.klass().equals(DriftCorpus.CO_EDIT)).count();
    final int commits = (int) rows.stream().map(r -> r.repo() + '#' + r.commit()).distinct().count();
    final var batches = new ArrayList<DriftBatch.Batch>();
    for (final var name : miners.keySet()) {
      batches.addAll(DriftBatch.build(name, rows, mined.get(name), miners.get(name)));
    }
    writeBatches(batches, config.out().resolve("batches.tsv"));
    writeLabelingSheet(rows, config.out().resolve("labeling-sheet.tsv"));
    if ("corpus".equals(config.mode())) {
      final var summary = new Summary(config.repos().size(), skipped, rows.size(), coEdits, rows.size() - coEdits, commits, null, null,
          new BatchSummary(batches.size(), 0, batches.stream().mapToInt(b -> b.candidates().size()).sum(),
              (int) batches.stream().flatMap(b -> b.candidates().stream()).filter(DriftBatch.Candidate::positive).count(), 0,
              Double.NaN, Double.NaN, Double.NaN, (int) batches.stream().filter(DriftBatch.Batch::hasBothClasses).count()));
      writeReport(config.out().resolve("report.md"), summary, rows, excluded, List.of());
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var row : rows) {
      requests.put(row.id(), DriftQuestions.request(row.state()));
    }
    final var batchById = new LinkedHashMap<String, DriftBatch.Batch>();
    for (final var batch : batches) {
      requests.put(batch.id() + "#batch", batch.request());
      batchById.put(batch.id() + "#batch", batch);
    }
    final var outcomes = jev.run(requests);
    if ("record".equals(config.mode())) {
      jev.prune(requests);
    }
    final var scores = new LinkedHashMap<String, DriftScore>();
    final var batchScored = new ArrayList<DriftBatch.Scored>();
    long batchTokens = 0;
    int batchRequests = 0;
    for (final var outcome : outcomes) {
      if (!outcome.succeeded()) {
        continue;
      }
      final var batch = batchById.get(outcome.id());
      if (batch != null) {
        batchRequests++;
        if (outcome.response().usage() != null) {
          batchTokens += outcome.response().usage().inputTokens();
        }
        batchScored.addAll(DriftBatch.scores(batch, outcome.response()));
      } else {
        scores.put(outcome.id(), DriftScore.of(outcome.response()));
      }
    }
    final var scored = new ArrayList<DriftBars.Scored>();
    for (final var row : rows) {
      final var score = scores.get(row.id());
      if (score != null) {
        scored.add(new DriftBars.Scored(row, score));
      }
    }
    writeScores(scored, config.out().resolve("jev.tsv"));
    writeBatchScores(batchScored, config.out().resolve("batched.tsv"));
    final var labels = config.labels() != null && Files.isRegularFile(config.labels()) ? DriftLabels.read(config.labels()).byKey() : Map.<String, String>of();
    final var verdict = DriftBars.verdict(scored, labels);
    final var batchSummary = new BatchSummary(batches.size(), batchRequests, batchScored.size(),
        (int) batchScored.stream().filter(s -> s.candidate().positive()).count(), batchTokens,
        DriftBatch.pooledAuroc(batchScored, false), DriftBatch.pooledAuroc(batchScored, true), DriftBatch.meanRequestAuroc(batchScored),
        (int) batches.stream().filter(DriftBatch.Batch::hasBothClasses).count());
    final var summary = new Summary(config.repos().size(), skipped, rows.size(), coEdits, rows.size() - coEdits, commits, jev.totals(outcomes),
        verdict, batchSummary);
    writeReport(config.out().resolve("report.md"), summary, rows, excluded, scored);
    return summary;
  }

  static void writeRows(final List<DriftCorpus.Row> rows, final Path file) {
    final var tsv = new Tsv("row_id", "repo", "commit", "path", "member", "kind", "class", "comment_chars", "diff_size", "overlap",
        "lines_before", "lines_after", "abstract_toggle");
    for (final var row : rows) {
      tsv.row(row.id(), row.repo(), row.commit(), row.path(), row.key().toString(), row.kind(), row.klass(), row.commentChars(),
          row.diffSize(), fmt(row.overlap()), row.linesBefore(), row.linesAfter(), row.abstractToggle());
    }
    tsv.write(file);
  }

  static void writeScores(final List<DriftBars.Scored> scored, final Path file) {
    final var tsv = new Tsv("row_id", "class", "choice", "score", "p_contradicted", "p_needs_addition", "p_unaffected", "p_not_checkable", "confidence");
    for (final var s : scored) {
      tsv.row(s.row().id(), s.row().klass(), s.score().choice(), fmt(s.score().affected()), fmt(s.score().pContradicted()),
          fmt(s.score().pNeedsAddition()), fmt(s.score().pUnaffected()), fmt(s.score().pNotCheckable()), fmt(s.score().confidence()));
    }
    tsv.write(file);
  }

  static void writeBatches(final List<DriftBatch.Batch> batches, final Path file) {
    final var tsv = new Tsv("batch_id", "repo", "commit", "path", "candidates_shown", "candidates_total", "positives", "changed_members", "diff_chars");
    for (final var b : batches) {
      tsv.row(b.id(), b.repo(), b.commit(), b.path(), b.candidates().size(), b.candidatesTotal(),
          b.candidates().stream().filter(DriftBatch.Candidate::positive).count(),
          b.candidates().stream().filter(DriftBatch.Candidate::memberChanged).count(), b.change().length());
    }
    tsv.write(file);
  }

  static void writeBatchScores(final List<DriftBatch.Scored> scored, final Path file) {
    final var tsv = new Tsv("batch_id", "candidate", "member", "positive", "member_changed", "p_affected");
    for (final var s : scored) {
      tsv.row(s.batch().id(), s.candidate().index(), s.candidate().key().toString(), s.candidate().positive(), s.candidate().memberChanged(),
          fmt(s.pAffected()));
    }
    tsv.write(file);
  }

  /// Blind: every row of both classes in one seeded shuffle, with no class, rank, or score
  /// shown; the comment, the diff rendering, and the new source.
  static void writeLabelingSheet(final List<DriftCorpus.Row> rows, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "repo", "member", "comment", "change", "new_source");
    final var shuffled = new ArrayList<>(rows);
    Collections.shuffle(shuffled, new Random(DriftBars.SEED));
    for (final var row : shuffled) {
      tsv.row(row.id(), "", "", row.repo(), row.key().toString(), row.state().comment(), row.diffText(), row.state().newSource());
    }
    tsv.write(file);
  }

  static String fmt(final double value) {
    return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
  }

  static void writeReport(final Path file, final Summary summary, final List<DriftCorpus.Row> rows, final Map<String, Integer> excluded,
                          final List<DriftBars.Scored> scored) {
    final var out = new StringBuilder();
    out.append("# Experiment D: doc drift at change time\n\n## Corpus\n\n");
    out.append("| repositories | skipped | rows | CO_EDIT | BODY_ONLY | commits |\n| --- | --- | --- | --- | --- | --- |\n| ").append(summary.repos())
        .append(" | ").append(summary.skipped()).append(" | ").append(summary.rows()).append(" | ").append(summary.coEdits()).append(" | ")
        .append(summary.bodyOnly()).append(" | ").append(summary.commits()).append(" |\n\n");
    out.append("Excluded events: ").append(excluded).append("\n\n");
    final var perRepo = new TreeMap<String, int[]>();
    int toggles = 0;
    for (final var row : rows) {
      final var c = perRepo.computeIfAbsent(row.repo(), k -> new int[2]);
      c[row.klass().equals(DriftCorpus.CO_EDIT) ? 0 : 1]++;
      if (row.abstractToggle()) {
        toggles++;
      }
    }
    out.append("| repository | CO_EDIT | BODY_ONLY |\n| --- | --- | --- |\n");
    for (final var e : perRepo.entrySet()) {
      out.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" |\n");
    }
    out.append("\nAbstract-toggle rows (a member gained or lost a body): ").append(toggles).append(".\n\n");
    if (summary.spend() == null) {
      out.append("## Jev\n\nNot scored (corpus mode).\n");
    } else {
      final var spend = summary.spend();
      out.append("## Jev\n\nRequests ").append(spend.requests()).append(" (").append(spend.succeeded()).append(" answered), input tokens ")
          .append(spend.inputTokens()).append(", cost $").append(String.format(Locale.ROOT, "%.4f", spend.dollars()))
          .append("; recording hits ").append(spend.hits()).append(", misses ").append(spend.misses()).append("; ")
          .append(scored.size()).append(" rows scored.\n\n");
      appendBars(out, summary.verdict());
      appendChoices(out, scored);
      appendTop(out, scored);
      appendBatch(out, summary.batch(), summary.spend(), scored.size());
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }

  static void appendBars(final StringBuilder out, final DriftBars.Verdict verdict) {
    out.append("## Bars (pre-registered decision table as amended, first match wins)\n\n");
    out.append("AUROC ").append(fmt(verdict.auroc())).append(" (commit bootstrap 95% ").append(fmt(verdict.interval()[0])).append(" to ")
        .append(fmt(verdict.interval()[1])).append("; separation ").append(verdict.separation()).append("); without abstract-toggle rows ")
        .append(fmt(verdict.aurocNoToggle())).append(".\n\n");
    out.append("| deterministic baseline | AUROC | two-sided |\n| --- | --- | --- |\n");
    for (final var b : verdict.baselines()) {
      out.append("| ").append(b.name()).append(" | ").append(fmt(b.auroc())).append(" | ").append(fmt(b.twoSided())).append(" |\n");
    }
    out.append("\n| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : verdict.checks()) {
      out.append("| ").append(check.name()).append(" | ").append(fmt(check.value())).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append("| **decision** | **").append(verdict.decision()).append("** | | |\n\n");
    if (Double.isNaN(verdict.ceiling())) {
      out.append("Oracle ceiling: the blind sheet has not been read yet (`labeling-sheet.tsv`).\n\n");
    } else {
      out.append("Oracle ceiling: readers marked ").append(fmt(verdict.oracleRho())).append(" of CO_EDIT rows and ").append(fmt(verdict.oracleBeta()))
          .append(" of BODY_ONLY rows affected, so a perfect judge of the readers' labels would separate the classes at about ")
          .append(fmt(verdict.ceiling())).append(".\n\n");
    }
    if (verdict.topRate().of() > 0) {
      out.append("Value bar: top ").append(DriftBars.TOP_N).append(" BODY_ONLY rows ").append(rate(verdict.topRate())).append(" affected; the rest ")
          .append(rate(verdict.restRate())).append(".\n\n");
    }
  }

  static String rate(final DriftBars.Rate rate) {
    return rate.of() == 0 ? "n/a" : rate.count() + " of " + rate.of() + " = " + fmt(rate.rate()) + " (" + fmt(rate.lower()) + " to " + fmt(rate.upper()) + ")";
  }

  static void appendChoices(final StringBuilder out, final List<DriftBars.Scored> scored) {
    final var co = new TreeMap<String, Integer>();
    final var body = new TreeMap<String, Integer>();
    for (final var s : scored) {
      (s.row().klass().equals(DriftCorpus.CO_EDIT) ? co : body).merge(s.score().choice(), 1, Integer::sum);
    }
    out.append("Choices, CO_EDIT: ").append(co).append("; BODY_ONLY: ").append(body).append(".\n\n");
  }

  static void appendTop(final StringBuilder out, final List<DriftBars.Scored> scored) {
    out.append("## Top BODY_ONLY rows by score\n\n| row | score | confidence | diff lines | overlap |\n| --- | --- | --- | --- | --- |\n");
    final var ranked = DriftBars.rankedBodyOnly(scored);
    for (final var s : ranked.subList(0, Math.min(DriftBars.TOP_N, ranked.size()))) {
      out.append("| ").append(s.row().id()).append(" | ").append(fmt(s.score().affected())).append(" | ").append(fmt(s.score().confidence()))
          .append(" | ").append(s.row().diffSize()).append(" | ").append(fmt(s.row().overlap())).append(" |\n");
    }
    out.append('\n');
  }

  static void appendBatch(final StringBuilder out, final BatchSummary batch, final JevRunner.Totals spend, final int pairRows) {
    out.append("## Batched arm: one request per changed file, one Noul per candidate comment (reported, no bar)\n\n");
    out.append("| batches | requests answered | candidates judged | positives | requests with both classes | input tokens |\n| --- | --- | --- | --- | --- | --- |\n| ")
        .append(batch.batches()).append(" | ").append(batch.requests()).append(" | ").append(batch.candidates()).append(" | ").append(batch.positives())
        .append(" | ").append(batch.requestsWithBoth()).append(" | ").append(batch.inputTokens()).append(" |\n\n");
    out.append("AUROC pooled, positives over all negatives: ").append(fmt(batch.pooledAuroc())).append("; over changed-member negatives only (like the pair arm): ")
        .append(fmt(batch.changedOnlyAuroc())).append("; mean within-request AUROC: ").append(fmt(batch.meanRequestAuroc())).append(".\n\n");
    if (batch.candidates() > 0 && pairRows > 0 && spend != null) {
      final long pairTokens = spend.inputTokens() - batch.inputTokens();
      out.append("Cost per judged comment: batched ").append(batch.inputTokens() / Math.max(1, batch.candidates())).append(" input tokens in ")
          .append(String.format(Locale.ROOT, "%.2f", (double) batch.requests() / Math.max(1, batch.candidates()))).append(" requests; pair arm ")
          .append(pairTokens / Math.max(1, pairRows)).append(" input tokens in 1 request.\n\n");
    }
  }
}
