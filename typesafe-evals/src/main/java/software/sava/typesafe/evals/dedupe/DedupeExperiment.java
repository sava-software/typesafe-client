package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.evals.jev.JevRunner;
import software.sava.typesafe.evals.report.Tsv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Experiment B end to end: read the journals, block pairs, select the labeling set, write
/// the blind labeling sheet, score every selected pair with Jev in two arms (prose only,
/// and prose plus code-computed facts), cluster the named workflow, and compute the bars
/// when a labels file is present. Every Jev exchange is recorded, so a rerun replays.
///
/// Arguments: `--projects <dir,dir>` `--out <dir>` `--recordings <dir>`
/// `--mode record|replay|corpus` `--concurrency <n>` `--top-jaccard <n>`
/// `--named <workflow>` `--labels <file>`.
public final class DedupeExperiment {

  public static final String NAMED_WORKFLOW = "wf_82d378e6-c04";
  public static final int TOP_JACCARD = 60;

  public record Config(List<Path> projects,
                       Path out,
                       Path recordings,
                       String mode,
                       int concurrency,
                       int topJaccard,
                       String namedWorkflow,
                       Path labels) {

    /// Options come in `--name value` pairs; a trailing name without a value is ignored.
    public static Config parse(final String[] args) {
      final var map = new LinkedHashMap<String, String>();
      for (int i = 0; i + 1 < args.length; i += 2) {
        if (!args[i].startsWith("--")) {
          throw new IllegalArgumentException("expected --option value, got " + args[i]);
        }
        map.put(args[i].substring(2), args[i + 1]);
      }
      final var projects = new ArrayList<Path>();
      for (final var dir : map.getOrDefault("projects", "").split(",")) {
        if (!dir.isBlank()) {
          projects.add(Path.of(dir.strip()));
        }
      }
      if (projects.isEmpty()) {
        throw new IllegalArgumentException("--projects is required");
      }
      final var out = Path.of(map.getOrDefault("out", "build/experiments/dedupe"));
      return new Config(
          projects,
          out,
          Path.of(map.getOrDefault("recordings", out.resolve("recordings").toString())),
          map.getOrDefault("mode", "record"),
          Integer.parseInt(map.getOrDefault("concurrency", "4")),
          Integer.parseInt(map.getOrDefault("top-jaccard", Integer.toString(TOP_JACCARD))),
          map.getOrDefault("named", NAMED_WORKFLOW),
          map.containsKey("labels") ? Path.of(map.get("labels")) : null
      );
    }
  }

  public static void main(final String[] args) {
    run(Config.parse(args), null);
  }

  /// The runner `config.mode` asks for: replay-only over the recordings, or recording over
  /// the live API (which needs `TYPESAFE_API_KEY`).
  static JevRunner runnerFor(final Config config) {
    return new JevRunner(
        "replay".equals(config.mode()) ? JevRunner.replayOnly(config.recordings()) : JevRunner.recording(config.recordings()),
        config.concurrency());
  }

  /// `runner` overrides the mode's runner (tests); null builds one with [#runnerFor(Config)].
  public static Summary run(final Config config, final JevRunner runner) {
    final var journals = JournalCorpus.journals(config.projects());
    final var findings = JournalCorpus.read(journals);
    final var pairs = FindingPair.block(findings);
    final var selected = select(pairs, config.topJaccard());
    try {
      Files.createDirectories(config.out());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create " + config.out(), e);
    }
    writeFindings(findings, config.out().resolve("findings.tsv"));
    writeLabelingSheet(selected, config.out().resolve("labeling-sheet.tsv"));

    final var namedFindings = findings.stream().filter(f -> f.workflow().equals(config.namedWorkflow())).toList();
    final var named = pairs.stream().filter(pair -> pair.a().workflow().equals(config.namedWorkflow())).toList();
    final var toScore = new LinkedHashMap<String, FindingPair>();
    for (final var pair : selected) {
      toScore.put(pair.id(), pair);
    }
    for (final var pair : named) {
      toScore.put(pair.id(), pair);
    }

    final var summary = new Summary(journals.size(), findings.size(), pairs.size(),
        (int) pairs.stream().filter(FindingPair::exactLine).count(), selected.size(), named.size());
    if ("corpus".equals(config.mode())) {
      summary.write(config.out().resolve("report.md"), null, null, null, null, null);
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var proseRun = score(jev, toScore, false);
    final var ablationRun = score(jev, toScore, true);
    final var prose = proseRun.scores();
    final var ablation = ablationRun.scores();
    writeScores(toScore, prose, config.out().resolve("jev-prose.tsv"));
    writeScores(toScore, ablation, config.out().resolve("jev-ablation.tsv"));
    final var spent = summary.withSpend(new JevRunner.Totals(
        proseRun.totals().requests() + ablationRun.totals().requests(),
        proseRun.totals().succeeded() + ablationRun.totals().succeeded(),
        proseRun.totals().inputTokens() + ablationRun.totals().inputTokens(),
        proseRun.totals().outputTokens() + ablationRun.totals().outputTokens(),
        jev.client().hits(), jev.client().misses()));

    final var strict = cluster(namedFindings, named, prose, s -> s.merges(DedupeBars.MERGE_CONFIDENCE));
    final var grouped = cluster(namedFindings, named, prose, s -> s.sameDefect(DedupeBars.MAX_DIFFERENT));
    final var labels = readLabels(config.labels());
    final var rows = labels == null ? null : rows(toScore, prose, labels);
    final var ablationRows = labels == null ? null : rows(toScore, ablation, labels);
    spent.write(config.out().resolve("report.md"), jev.client().defaultModel(), List.of(strict, grouped), namedFindings, rows, ablationRows);
    return spent;
  }

  /// The labels file when it was named and exists; a named-but-missing file means "not yet".
  static Labels readLabels(final Path labels) {
    return labels != null && Files.isRegularFile(labels) ? Labels.read(labels) : null;
  }

  /// All exact-line pairs plus the `topJaccard` highest-Jaccard pairs that are not exact-line.
  static List<FindingPair> select(final List<FindingPair> pairs, final int topJaccard) {
    final var selected = new ArrayList<FindingPair>();
    for (final var pair : pairs) {
      if (pair.exactLine()) {
        selected.add(pair);
      }
    }
    pairs.stream()
        .filter(pair -> !pair.exactLine())
        .sorted(Comparator.comparingDouble(FindingPair::jaccard).reversed().thenComparing(FindingPair::id))
        .limit(topJaccard)
        .forEach(selected::add);
    return selected;
  }

  /// The request for one pair in one arm.
  static SystemOneRequest request(final FindingPair pair, final boolean ablation) {
    final var context = ablation
        ? new DedupeQuestions.Context(true, pair.lineDelta(), pair.jaccard())
        : DedupeQuestions.Context.NONE;
    return DedupeQuestions.request(
        new DedupeQuestions.Finding(pair.a().text(), pair.a().scenario()),
        new DedupeQuestions.Finding(pair.b().text(), pair.b().scenario()),
        context);
  }

  /// One arm's scores keyed by pair id (a pair whose request failed is absent) and what
  /// the arm cost.
  record Scored(Map<String, PairScore> scores, JevRunner.Totals totals) {
  }

  static Scored score(final JevRunner jev, final Map<String, FindingPair> pairs, final boolean ablation) {
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var entry : pairs.entrySet()) {
      requests.put(entry.getKey(), request(entry.getValue(), ablation));
    }
    final var scores = new LinkedHashMap<String, PairScore>();
    final var outcomes = jev.run(requests);
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
        scores.put(outcome.id(), PairScore.of(outcome.response()));
      }
    }
    return new Scored(scores, jev.totals(outcomes));
  }

  /// Every finding is a node; a pair joins its two findings when `merge` accepts its score.
  /// A pair without a score never joins.
  static Clusters cluster(final List<CorpusFinding> findings,
                          final List<FindingPair> pairs,
                          final Map<String, PairScore> scores,
                          final java.util.function.Predicate<PairScore> merge) {
    final var clusters = new Clusters();
    for (final var finding : findings) {
      clusters.add(finding.id());
    }
    for (final var pair : pairs) {
      final var score = scores.get(pair.id());
      if (score != null && merge.test(score)) {
        clusters.union(pair.a().id(), pair.b().id());
      }
    }
    return clusters;
  }

  /// Labeled pairs that also have a score, in pair order.
  static List<DedupeBars.Row> rows(final Map<String, FindingPair> pairs, final Map<String, PairScore> scores, final Labels labels) {
    final var rows = new ArrayList<DedupeBars.Row>();
    for (final var entry : pairs.entrySet()) {
      final var gold = labels.get(entry.getKey());
      final var score = scores.get(entry.getKey());
      if (gold != null && score != null) {
        rows.add(new DedupeBars.Row(entry.getKey(), gold, entry.getValue().exactLine(), entry.getValue().jaccard(), score));
      }
    }
    return rows;
  }

  static void writeFindings(final List<CorpusFinding> findings, final Path file) {
    final var tsv = new Tsv("id", "workflow", "list_key", "file", "line", "severity", "category", "text", "scenario");
    for (final var f : findings) {
      tsv.row(f.id(), f.workflow(), f.listKey(), f.file(), f.line(), f.severity(), f.category(), f.text(), f.scenario());
    }
    tsv.write(file);
  }

  /// Blind: no Jev output, no Jaccard, just the two findings side by side and empty label columns.
  static void writeLabelingSheet(final List<FindingPair> pairs, final Path file) {
    final var tsv = new Tsv("pair_id", "label", "needed_source", "notes", "workflow", "basename",
        "line_a", "line_b", "text_a", "scenario_a", "text_b", "scenario_b");
    for (final var pair : pairs) {
      tsv.row(pair.id(), "", "", "", pair.a().workflow(), pair.a().basename(),
          pair.a().line(), pair.b().line(), pair.a().text(), pair.a().scenario(), pair.b().text(), pair.b().scenario());
    }
    tsv.write(file);
  }

  /// One row per pair; a pair without a score keeps its facts and leaves the score cells empty.
  static void writeScores(final Map<String, FindingPair> pairs, final Map<String, PairScore> scores, final Path file) {
    final var tsv = new Tsv("pair_id", "level", "p_same", "p_narrowed", "score", "confidence", "new_evidence", "exact_line", "jaccard");
    for (final var entry : pairs.entrySet()) {
      final var score = scores.get(entry.getKey());
      final var pair = entry.getValue();
      if (score == null) {
        tsv.row(entry.getKey(), "", "", "", "", "", "", pair.exactLine(), fmt(pair.jaccard()));
      } else {
        tsv.row(entry.getKey(), score.level(), fmt(score.pSame()), fmt(score.pNarrowed()), fmt(score.score()),
            fmt(score.confidence()), fmt(score.newEvidence()), pair.exactLine(), fmt(pair.jaccard()));
      }
    }
    tsv.write(file);
  }

  static String fmt(final double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  /// Corpus counts plus the report writer. `spend` is what scoring cost; null in corpus mode.
  public record Summary(int journals,
                        int findings,
                        int pairs,
                        int exactLinePairs,
                        int selected,
                        int namedPairs,
                        JevRunner.Totals spend) {

    public Summary(final int journals, final int findings, final int pairs, final int exactLinePairs, final int selected, final int namedPairs) {
      this(journals, findings, pairs, exactLinePairs, selected, namedPairs, null);
    }

    public Summary withSpend(final JevRunner.Totals spend) {
      return new Summary(journals, findings, pairs, exactLinePairs, selected, namedPairs, spend);
    }

    /// `namedClusters` holds the strict (pre-registered) clustering first and the post-hoc
    /// same-defect clustering second, or is null in corpus mode.
    void write(final Path file,
               final String model,
               final List<Clusters> namedClusters,
               final List<CorpusFinding> namedFindings,
               final List<DedupeBars.Row> rows,
               final List<DedupeBars.Row> ablationRows) {
      final var out = new StringBuilder();
      out.append("# Experiment B: finding dedupe between finder and refuter phases\n\n");
      out.append("## Corpus\n\n");
      out.append("| journals | findings | same-basename pairs | exact-line pairs | selected for labeling | named-workflow pairs |\n");
      out.append("| --- | --- | --- | --- | --- | --- |\n");
      out.append("| ").append(journals).append(" | ").append(findings).append(" | ").append(pairs).append(" | ")
          .append(exactLinePairs).append(" | ").append(selected).append(" | ").append(namedPairs).append(" |\n\n");
      if (spend != null) {
        out.append("## Jev\n\n");
        out.append("Recording hits ").append(spend.hits()).append(", misses ").append(spend.misses())
            .append("; model ").append(model).append(". Requests ").append(spend.requests()).append(" (")
            .append(spend.succeeded()).append(" answered), input tokens ").append(spend.inputTokens())
            .append(", cost $").append(String.format(Locale.ROOT, "%.4f", spend.dollars())).append(".\n\n");
      }
      if (namedClusters != null) {
        out.append(namedCase("pre-registered rule: level 2 at confidence >= " + DedupeBars.MERGE_CONFIDENCE,
            namedClusters.get(0), namedFindings));
        out.append(namedCase("post-hoc rule: same underlying defect, P(different) <= " + DedupeBars.MAX_DIFFERENT,
            namedClusters.get(1), namedFindings));
      }
      if (rows != null) {
        out.append("## Bars (prose arm, ").append(rows.size()).append(" labeled pairs, gold histogram ")
            .append(DedupeBars.goldHistogram(rows)).append(")\n\n");
        out.append(verdictTable(DedupeBars.verdict(rows)));
        out.append("\n```\n").append(DedupeBars.confusion(rows).render()).append("```\n\n");
        out.append(sameDefectTable(rows));
        out.append("## Bars (ablation arm, ").append(ablationRows.size()).append(" labeled pairs)\n\n");
        out.append(verdictTable(DedupeBars.verdict(ablationRows)));
      } else {
        out.append("## Bars\n\nNo labels yet: fill `labeling-sheet.tsv` (label 0/1/2 per pair, needed_source y/n) and rerun with `--labels`.\n");
      }
      try {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
      } catch (final IOException e) {
        throw new UncheckedIOException("failed to write " + file, e);
      }
    }

    /// The named workflow's groups under one rule: every multi-member cluster listed,
    /// singletons counted.
    static String namedCase(final String rule, final Clusters clusters, final List<CorpusFinding> findings) {
      final var out = new StringBuilder();
      out.append("## Named case: ").append(NAMED_WORKFLOW).append(" (prose arm, ").append(rule).append(")\n\n");
      final var byId = new LinkedHashMap<String, CorpusFinding>();
      for (final var finding : findings) {
        byId.put(finding.id(), finding);
      }
      int singletons = 0;
      for (final var cluster : clusters.clusters()) {
        if (cluster.size() == 1) {
          ++singletons;
          continue;
        }
        out.append("- cluster of ").append(cluster.size()).append(":\n");
        for (final var id : cluster) {
          final var f = byId.get(id);
          out.append("  - ").append(f.basename()).append(':').append(f.line()).append(' ').append(f.text()).append('\n');
        }
      }
      out.append("- singletons: ").append(singletons).append('\n');
      out.append("- refuter budget: ").append(byId.size()).append(" findings -> ")
          .append(clusters.clusters().size()).append(" groups\n\n");
      return out.toString();
    }

    /// The post-hoc rule's numbers, reported beside the pre-registered bars, never in them.
    static String sameDefectTable(final List<DedupeBars.Row> rows) {
      final var point = DedupeBars.sameDefect(rows);
      final var violations = DedupeBars.sameDefectViolations(rows);
      return "### Post-hoc same-defect rule (P(different) <= " + DedupeBars.MAX_DIFFERENT + ", gold 1 or 2 counts as same)\n\n"
          + "| grouped | precision | recall | gold-0 pairs grouped |\n| --- | --- | --- | --- |\n"
          + "| " + point.merged() + " | " + fmt(point.precision()) + " | " + fmt(point.recall()) + " | " + violations.size() + " |\n\n";
    }

    static String verdictTable(final DedupeBars.Verdict verdict) {
      final var out = new StringBuilder("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
      for (final var check : verdict.checks()) {
        out.append("| ").append(check.bar()).append(" | ").append(check.value()).append(" | ")
            .append(check.required()).append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
      }
      out.append("| **keep** | **").append(verdict.keep()).append("** | all | |\n");
      return out.toString();
    }
  }

  private DedupeExperiment() {
  }
}
