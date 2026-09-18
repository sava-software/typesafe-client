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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/// Experiment D end to end: every historical change to a documented member of every public
/// checkout named, scored once, the decision table, a blind sheet of the top BODY_ONLY rows,
/// and a blind noise sample of CO_EDIT rows.
///
/// Arguments: `--checkouts <dir>` `--repos <name,...>` `--out <dir>` `--recordings <dir>`
/// `--mode record|replay|corpus` `--concurrency <n>` `--labels-top <sheet>` `--labels-noise <sheet>`
/// `--visibility-cache <file>`
public final class DriftExperiment {

  public record Config(Path checkouts, List<String> repos, Path out, Path recordings, String mode, int concurrency,
                       Path labelsTop, Path labelsNoise, Path visibilityCache) {

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
          map.containsKey("labels-top") ? Path.of(map.get("labels-top")) : null,
          map.containsKey("labels-noise") ? Path.of(map.get("labels-noise")) : null,
          Path.of(map.getOrDefault("visibility-cache", out.resolve("visibility.tsv").toString()))
      );
    }
  }

  /// @param spend null in corpus mode
  public record Summary(int repos, int skipped, int rows, int coEdits, int bodyOnly, JevRunner.Totals spend, DriftBars.Verdict verdict) {
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
      final var result = DriftCorpus.rows(name, new HistoryMiner(git, HistoryMiner.MAIN_SOURCES).mine());
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
    if ("corpus".equals(config.mode())) {
      final var summary = new Summary(config.repos().size(), skipped, rows.size(), coEdits, rows.size() - coEdits, null, null);
      writeReport(config.out().resolve("report.md"), summary, rows, excluded, List.of());
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var row : rows) {
      requests.put(row.id(), DriftQuestions.request(row.state()));
    }
    final var outcomes = jev.run(requests);
    if ("record".equals(config.mode())) {
      jev.prune(requests);
    }
    final var scores = new LinkedHashMap<String, DriftScore>();
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
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
    writeTopSheet(DriftBars.topBodyOnly(scored), config.out().resolve("labeling-sheet-top.tsv"));
    writeNoiseSheet(DriftBars.noiseSample(rows), config.out().resolve("labeling-sheet-noise.tsv"));
    final var topLabels = config.labelsTop() != null && Files.isRegularFile(config.labelsTop())
        ? DriftLabels.read(config.labelsTop(), DriftLabels.TOP_LABELS).byKey() : Map.<String, String>of();
    final var noiseLabels = config.labelsNoise() != null && Files.isRegularFile(config.labelsNoise())
        ? DriftLabels.read(config.labelsNoise(), DriftLabels.NOISE_LABELS).byKey() : Map.<String, String>of();
    final var verdict = DriftBars.verdict(scored, topLabels, noiseLabels);
    final var summary = new Summary(config.repos().size(), skipped, rows.size(), coEdits, rows.size() - coEdits, jev.totals(outcomes), verdict);
    writeReport(config.out().resolve("report.md"), summary, rows, excluded, scored);
    return summary;
  }

  static void writeRows(final List<DriftCorpus.Row> rows, final Path file) {
    final var tsv = new Tsv("row_id", "repo", "commit", "path", "member", "kind", "class", "comment_chars", "diff_size", "overlap");
    for (final var row : rows) {
      tsv.row(row.id(), row.repo(), row.commit(), row.path(), row.key().toString(), row.kind(), row.klass(), row.commentChars(),
          row.diffSize(), fmt(row.overlap()));
    }
    tsv.write(file);
  }

  static void writeScores(final List<DriftBars.Scored> scored, final Path file) {
    final var baseline = DriftBars.baselineScores(scored);
    final var tsv = new Tsv("row_id", "class", "choice", "p_affected", "p_unaffected", "p_not_checkable", "confidence", "baseline");
    for (final var s : scored) {
      tsv.row(s.row().id(), s.row().klass(), s.score().choice(), fmt(s.score().pAffected()), fmt(s.score().pUnaffected()),
          fmt(s.score().pNotCheckable()), fmt(s.score().confidence()), fmt(baseline.get(s.row().id())));
    }
    tsv.write(file);
  }

  /// Blind: the top BODY_ONLY rows in id order, comment, change, and new source, no scores.
  static void writeTopSheet(final List<DriftBars.Scored> top, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "repo", "commit", "member", "comment", "change", "new_source");
    final var ordered = new ArrayList<>(top);
    ordered.sort(java.util.Comparator.comparing(s -> s.row().id()));
    for (final var s : ordered) {
      final var row = s.row();
      tsv.row(row.id(), "", "", row.repo(), row.commit(), row.key().toString(), row.state().comment(), row.state().change(), row.state().newSource());
    }
    tsv.write(file);
  }

  /// Blind: the CO_EDIT noise sample with the old and new comment and the change.
  static void writeNoiseSheet(final List<DriftCorpus.Row> sample, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "repo", "commit", "member", "old_comment", "new_comment", "change");
    for (final var row : sample) {
      tsv.row(row.id(), "", "", row.repo(), row.commit(), row.key().toString(), row.oldComment(), row.newComment(), row.state().change());
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
    out.append("| repositories | skipped | rows | CO_EDIT | BODY_ONLY |\n| --- | --- | --- | --- | --- |\n| ").append(summary.repos())
        .append(" | ").append(summary.skipped()).append(" | ").append(summary.rows()).append(" | ").append(summary.coEdits()).append(" | ")
        .append(summary.bodyOnly()).append(" |\n\n");
    out.append("Excluded events: ").append(excluded).append("\n\n");
    final var perRepo = new TreeMap<String, int[]>();
    for (final var row : rows) {
      final var c = perRepo.computeIfAbsent(row.repo(), k -> new int[2]);
      c[row.klass().equals(DriftCorpus.CO_EDIT) ? 0 : 1]++;
    }
    out.append("| repository | CO_EDIT | BODY_ONLY |\n| --- | --- | --- |\n");
    for (final var e : perRepo.entrySet()) {
      out.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ").append(e.getValue()[1]).append(" |\n");
    }
    out.append('\n');
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
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }

  static void appendBars(final StringBuilder out, final DriftBars.Verdict verdict) {
    out.append("## Bars (pre-registered decision table, first match wins)\n\n");
    out.append("AUROC ").append(fmt(verdict.auroc())).append(" (bootstrap 95% ").append(fmt(verdict.interval()[0])).append(" to ")
        .append(fmt(verdict.interval()[1])).append("), best deterministic baseline (diff size or comment-diff overlap, rank-scaled) ")
        .append(fmt(verdict.baselineAuroc())).append(".\n\n");
    out.append("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : verdict.checks()) {
      out.append("| ").append(check.name()).append(" | ").append(fmt(check.value())).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append("| **decision** | **").append(verdict.decision()).append("** | | |\n\n");
    if (verdict.noiseRead() == 0) {
      out.append("Noise estimate: the CO_EDIT sample has not been read yet (`labeling-sheet-noise.tsv`).\n\n");
    } else {
      out.append("Noise estimate: ").append(verdict.noiseRelated()).append(" of ").append(verdict.noiseRead())
          .append(" sampled CO_EDIT comment edits were about the body change; a perfect judge's separation ceiling at that rate is about ")
          .append(fmt(verdict.ceiling())).append(".\n\n");
    }
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
    out.append("## Top BODY_ONLY rows by P(affected)\n\n| row | P(affected) | confidence | diff lines | overlap |\n| --- | --- | --- | --- | --- |\n");
    for (final var s : DriftBars.topBodyOnly(scored)) {
      out.append("| ").append(s.row().id()).append(" | ").append(fmt(s.score().pAffected())).append(" | ").append(fmt(s.score().confidence()))
          .append(" | ").append(s.row().diffSize()).append(" | ").append(fmt(s.row().overlap())).append(" |\n");
    }
    out.append('\n');
  }
}
