package software.sava.typesafe.evals.docs;

import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;
import software.sava.typesafe.evals.corpus.PublicRepoGate;
import software.sava.typesafe.evals.jev.JevRunner;
import software.sava.typesafe.evals.report.Tsv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/// Experiment C1 end to end: documented members of every public checkout named, REAL and
/// SWAPPED arms in separate requests, Design 1's decision table, and Design 2's blind-labeled
/// sample drawn from the "possibly stale at HEAD" members plus random fill.
///
/// Arguments: `--checkouts <dir>` `--repos <name,...>` `--out <dir>` `--recordings <dir>`
/// `--mode record|replay|corpus` `--concurrency <n>` `--per-repo <n>` `--sample <n>`
/// `--labels-top <sheet>` `--labels-sample <sheet>` `--visibility-cache <file>`
public final class DocExperiment {

  public static final int STALE_CANDIDATE_CAP = 100;

  public record Config(Path checkouts, List<String> repos, Path out, Path recordings, String mode, int concurrency,
                       int perRepo, int sample, Path labelsTop, Path labelsSample, Path visibilityCache) {

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
      final var out = Path.of(map.getOrDefault("out", "build/experiments/docs"));
      return new Config(
          Path.of(map.get("checkouts")),
          List.of(map.get("repos").split(",")),
          out,
          Path.of(map.getOrDefault("recordings", out.resolve("recordings").toString())),
          map.getOrDefault("mode", "record"),
          Integer.parseInt(map.getOrDefault("concurrency", "4")),
          Integer.parseInt(map.getOrDefault("per-repo", "300")),
          Integer.parseInt(map.getOrDefault("sample", "0")),
          map.containsKey("labels-top") ? Path.of(map.get("labels-top")) : null,
          map.containsKey("labels-sample") ? Path.of(map.get("labels-sample")) : null,
          Path.of(map.getOrDefault("visibility-cache", out.resolve("visibility.tsv").toString()))
      );
    }
  }

  /// @param spend null in corpus mode
  public record Summary(int repos, int skipped, int rows, int withSwap, int staleCandidates, int sampled, JevRunner.Totals spend,
                        DocBars.Verdict verdict, DocBars.SampleVerdict sampleVerdict) {
  }

  private DocExperiment() {
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
    final var rows = new ArrayList<DocCorpus.Row>();
    final var staleKeys = new HashSet<String>(); // path#key of members whose comment predates their last body change
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
      final var repoRows = new DocCorpus(name, checkout, git, config.perRepo()).rows();
      rows.addAll(repoRows);
      if (config.sample() > 0) {
        staleKeys.addAll(staleCandidates(name, repoRows, new HistoryMiner(git, HistoryMiner.MAIN_SOURCES).mine()));
      }
    }
    rows.sort(java.util.Comparator.comparing(DocCorpus.Row::id));
    final var sample = sample(rows, staleKeys, config.sample());
    try {
      Files.createDirectories(config.out());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create " + config.out(), e);
    }
    writeRows(rows, sample, staleKeys, config.out().resolve("rows.tsv"));
    final int withSwap = (int) rows.stream().filter(DocCorpus.Row::hasSwap).count();
    final int staleCount = (int) rows.stream().filter(r -> staleKeys.contains(r.repo() + '#' + r.path() + '#' + r.key())).count();
    if ("corpus".equals(config.mode())) {
      final var summary = new Summary(config.repos().size(), skipped, rows.size(), withSwap, staleCount, sample.size(), null, null, null);
      writeReport(config.out().resolve("report.md"), summary, rows, List.of(), List.of(), List.of());
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var row : rows) {
      requests.put(row.id() + "#real", DocQuestions.request(row.real()));
      if (row.hasSwap()) {
        requests.put(row.id() + "#swapped", DocQuestions.request(row.swapped()));
      }
    }
    final var outcomes = jev.run(requests);
    if ("record".equals(config.mode())) {
      jev.prune(requests);
    }
    final var scores = new LinkedHashMap<String, DocScore>();
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
        scores.put(outcome.id(), DocScore.of(outcome.response()));
      }
    }
    final var pairs = new ArrayList<DocBars.Pair>();
    final var scored = new ArrayList<DocBars.Scored>();
    for (final var row : rows) {
      final var real = scores.get(row.id() + "#real");
      if (real == null) {
        continue;
      }
      scored.add(new DocBars.Scored(row, real));
      final var swapped = scores.get(row.id() + "#swapped");
      if (swapped != null) {
        pairs.add(new DocBars.Pair(row, real, swapped));
      }
    }
    final var sampleIds = new HashSet<String>();
    for (final var row : sample) {
      sampleIds.add(row.id());
    }
    final var sampleScored = scored.stream().filter(s -> sampleIds.contains(s.row().id())).toList();
    writeScores(scored, scores, config.out().resolve("jev.tsv"));
    writeLabelingSheet(DocBars.top(scored).stream().map(DocBars.Scored::row).toList(), config.out().resolve("labeling-sheet-top.tsv"));
    writeLabelingSheet(sample, config.out().resolve("labeling-sheet-sample.tsv"));
    final var labelsTop = read(config.labelsTop());
    final var labelsSample = read(config.labelsSample());
    final var verdict = DocBars.verdict(pairs, scored, labelsTop);
    final var sampleVerdict = labelsSample.isEmpty() ? null : DocBars.sample(sampleScored, labelsSample);
    final var summary = new Summary(config.repos().size(), skipped, rows.size(), withSwap, staleCount, sample.size(), jev.totals(outcomes),
        verdict, sampleVerdict);
    writeReport(config.out().resolve("report.md"), summary, rows, pairs, scored, sampleScored);
    return summary;
  }

  private static Map<String, String> read(final Path labels) {
    return labels != null && Files.isRegularFile(labels) ? DocLabels.read(labels).byKey() : Map.of();
  }

  /// `repo#path#key` of members with a body-only edit whose comment is unchanged since.
  static Set<String> staleCandidates(final String repo, final List<DocCorpus.Row> rows, final List<HistoryMiner.Event> events) {
    final var headComment = new LinkedHashMap<String, String>();
    for (final var row : rows) {
      headComment.put(row.path() + '#' + row.key(), row.rawComment());
    }
    final var out = new HashSet<String>();
    // walk in commit order: the latest body-only event decides
    final var latest = new LinkedHashMap<String, Boolean>();
    for (final var event : events) {
      final var k = event.path() + '#' + event.key();
      if (!headComment.containsKey(k)) {
        continue;
      }
      if (event.bodyChanged() && !event.commentChanged() && event.newComment() != null) {
        latest.put(k, event.newComment().equals(headComment.get(k)));
      } else if (event.commentChanged()) {
        latest.put(k, Boolean.FALSE);
      }
    }
    for (final var entry : latest.entrySet()) {
      if (entry.getValue()) {
        out.add(repo + '#' + entry.getKey());
      }
    }
    return out;
  }

  /// Up to STALE_CANDIDATE_CAP stale candidates in id order, then random documented members
  /// to `size` (seeded, so the sample is reproducible).
  static List<DocCorpus.Row> sample(final List<DocCorpus.Row> rows, final Set<String> staleKeys, final int size) {
    if (size <= 0) {
      return List.of();
    }
    final var picked = new ArrayList<DocCorpus.Row>();
    final var rest = new ArrayList<DocCorpus.Row>();
    for (final var row : rows) {
      if (staleKeys.contains(row.repo() + '#' + row.path() + '#' + row.key()) && picked.size() < Math.min(STALE_CANDIDATE_CAP, size)) {
        picked.add(row);
      } else {
        rest.add(row);
      }
    }
    final var random = new Random(DocBars.SEED);
    while (picked.size() < size && !rest.isEmpty()) {
      picked.add(rest.remove(random.nextInt(rest.size())));
    }
    picked.sort(java.util.Comparator.comparing(DocCorpus.Row::id));
    return picked;
  }

  static void writeRows(final List<DocCorpus.Row> rows, final List<DocCorpus.Row> sample, final Set<String> staleKeys, final Path file) {
    final var sampleIds = new HashSet<String>();
    for (final var row : sample) {
      sampleIds.add(row.id());
    }
    final var tsv = new Tsv("row_id", "repo", "path", "member", "kind", "comment_chars", "body_lines", "swapped_from", "mismatch_real",
        "mismatch_swapped", "identifiers_missing", "sample");
    for (final var row : rows) {
      final boolean stale = staleKeys.contains(row.repo() + '#' + row.path() + '#' + row.key());
      tsv.row(row.id(), row.repo(), row.path(), row.key().toString(), row.kind(), row.commentChars(), row.bodyLines(), row.swappedFrom(),
          fmt(row.mismatchReal()), row.hasSwap() ? fmt(row.mismatchSwapped()) : "", String.join(" ", row.identifiersMissing()),
          sampleIds.contains(row.id()) ? (stale ? "stale-candidate" : "random") : (stale ? "stale-candidate-unsampled" : ""));
    }
    tsv.write(file);
  }

  static void writeScores(final List<DocBars.Scored> scored, final Map<String, DocScore> scores, final Path file) {
    final var tsv = new Tsv("row_id", "arm", "choice", "p_consistent", "p_contradicted", "p_not_checkable", "confidence", "names_missing",
        "mismatch_baseline");
    for (final var s : scored) {
      tsv.row(s.row().id(), "real", s.real().choice(), fmt(s.real().pConsistent()), fmt(s.real().pContradicted()),
          fmt(s.real().pNotCheckable()), fmt(s.real().confidence()), fmt(s.real().namesMissing()), fmt(s.row().mismatchReal()));
      final var swapped = scores.get(s.row().id() + "#swapped");
      if (swapped != null) {
        tsv.row(s.row().id(), "swapped", swapped.choice(), fmt(swapped.pConsistent()), fmt(swapped.pContradicted()),
            fmt(swapped.pNotCheckable()), fmt(swapped.confidence()), fmt(swapped.namesMissing()), fmt(s.row().mismatchSwapped()));
      }
    }
    tsv.write(file);
  }

  /// Blind: rows in id order, the REAL comment and source, no scores.
  static void writeLabelingSheet(final List<DocCorpus.Row> rows, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "repo", "member", "comment", "member_source");
    final var ordered = new ArrayList<>(rows);
    ordered.sort(java.util.Comparator.comparing(DocCorpus.Row::id));
    for (final var row : ordered) {
      tsv.row(row.id(), "", "", row.repo(), row.key().toString(), row.real().comment(), row.real().memberSource());
    }
    tsv.write(file);
  }

  static String fmt(final double value) {
    return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
  }

  static void writeReport(final Path file, final Summary summary, final List<DocCorpus.Row> rows, final List<DocBars.Pair> pairs,
                          final List<DocBars.Scored> scored, final List<DocBars.Scored> sampleScored) {
    final var out = new StringBuilder();
    out.append("# Experiment C1: doc comments versus member bodies\n\n## Corpus\n\n");
    out.append("| repositories | skipped | documented members | with a swap | stale candidates | sampled |\n| --- | --- | --- | --- | --- | --- |\n");
    out.append("| ").append(summary.repos()).append(" | ").append(summary.skipped()).append(" | ").append(summary.rows()).append(" | ")
        .append(summary.withSwap()).append(" | ").append(summary.staleCandidates()).append(" | ").append(summary.sampled()).append(" |\n\n");
    final var perRepo = new TreeMap<String, int[]>();
    for (final var row : rows) {
      final var c = perRepo.computeIfAbsent(row.repo(), k -> new int[2]);
      c[0]++;
      if (row.hasSwap()) {
        c[1]++;
      }
    }
    out.append("| repository | members | with a swap |\n| --- | --- | --- |\n");
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
          .append(pairs.size()).append(" rows with both arms scored, ").append(scored.size()).append(" REAL rows scored.\n\n");
      appendDesign1(out, summary.verdict());
      appendChoices(out, pairs, scored);
      appendTop(out, scored);
      appendDesign2(out, summary.sampleVerdict(), sampleScored.size());
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }

  static void appendDesign1(final StringBuilder out, final DocBars.Verdict verdict) {
    out.append("## Design 1: swapped comments (pre-registered decision table, first match wins)\n\n");
    out.append("AUROC ").append(fmt(verdict.auroc())).append(" (bootstrap 95% ").append(fmt(verdict.interval()[0])).append(" to ")
        .append(fmt(verdict.interval()[1])).append("), identifier-mismatch baseline ").append(fmt(verdict.baselineAuroc())).append(".\n\n");
    out.append("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : verdict.checks()) {
      out.append("| ").append(check.name()).append(" | ").append(fmt(check.value())).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append("| **decision** | **").append(verdict.decision()).append("** | | |\n\n");
  }

  static void appendChoices(final StringBuilder out, final List<DocBars.Pair> pairs, final List<DocBars.Scored> scored) {
    final var real = new TreeMap<String, Integer>();
    final var swapped = new TreeMap<String, Integer>();
    for (final var s : scored) {
      real.merge(s.real().choice(), 1, Integer::sum);
    }
    for (final var p : pairs) {
      swapped.merge(p.swapped().choice(), 1, Integer::sum);
    }
    out.append("Choices, REAL arm: ").append(real).append("; SWAPPED arm: ").append(swapped).append(".\n\n");
  }

  static void appendTop(final StringBuilder out, final List<DocBars.Scored> scored) {
    out.append("## Top REAL rows by P(contradicted)\n\n| row | P(contradicted) | confidence | names_missing | mismatch baseline |\n| --- | --- | --- | --- | --- |\n");
    for (final var s : DocBars.top(scored)) {
      out.append("| ").append(s.row().id()).append(" | ").append(fmt(s.real().pContradicted())).append(" | ").append(fmt(s.real().confidence()))
          .append(" | ").append(fmt(s.real().namesMissing())).append(" | ").append(fmt(s.row().mismatchReal())).append(" |\n");
    }
    out.append('\n');
  }

  static void appendDesign2(final StringBuilder out, final DocBars.SampleVerdict verdict, final int sampled) {
    out.append("## Design 2: the real population (blind-labeled sample)\n\n");
    if (verdict == null) {
      out.append(sampled).append(" sampled rows scored; no labels yet (fill `labeling-sheet-sample.tsv` and rerun with `--labels-sample`).\n");
      return;
    }
    out.append(verdict.labeled()).append(" labeled rows: ").append(verdict.contradicted()).append(" contradicted, ")
        .append(verdict.consistent()).append(" consistent, ").append(verdict.notCheckable()).append(" not checkable; prevalence of contradicted among decided rows ")
        .append(fmt(verdict.prevalence())).append(". AUROC ").append(fmt(verdict.auroc())).append(" (bootstrap 95% ").append(fmt(verdict.interval()[0]))
        .append(" to ").append(fmt(verdict.interval()[1])).append("); identifier-mismatch baseline ").append(fmt(verdict.mismatchAuroc()))
        .append(", comment-length predictor ").append(fmt(verdict.lengthAuroc())).append(".\n\n");
    out.append("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : verdict.checks()) {
      out.append("| ").append(check.name()).append(" | ").append(fmt(check.value())).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append('\n');
  }
}
