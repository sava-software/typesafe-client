package software.sava.typesafe.evals.rot;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/// Experiment A end to end: for every public snapshot in the golden-fleet manifest, pair
/// each acceptance note's members with the code at HEAD, run the control arm, write the
/// blind labeling sheet, score every row with Jev, and compute the bars against labels
/// (or, provisionally, against the survey's gold hints).
///
/// Arguments: `--manifest <file>` `--golden-fleet <dir>` `--checkouts <dir>` `--out <dir>`
/// `--recordings <dir>` `--mode record|replay|corpus` `--concurrency <n>` `--labels <file>`
/// `--gold-hints <file>` `--visibility-cache <file>`.
public final class RotExperiment {

  public record Config(Path manifest,
                       Path goldenFleet,
                       Path checkouts,
                       Path out,
                       Path recordings,
                       String mode,
                       int concurrency,
                       Path labels,
                       Path goldHints,
                       Path visibilityCache) {

    public static Config parse(final String[] args) {
      final var map = new LinkedHashMap<String, String>();
      for (int i = 0; i + 1 < args.length; i += 2) {
        if (!args[i].startsWith("--")) {
          throw new IllegalArgumentException("expected --option value, got " + args[i]);
        }
        map.put(args[i].substring(2), args[i + 1]);
      }
      for (final var required : List.of("manifest", "golden-fleet", "checkouts")) {
        if (!map.containsKey(required)) {
          throw new IllegalArgumentException("--" + required + " is required");
        }
      }
      final var out = Path.of(map.getOrDefault("out", "build/experiments/rot"));
      return new Config(
          Path.of(map.get("manifest")),
          Path.of(map.get("golden-fleet")),
          Path.of(map.get("checkouts")),
          out,
          Path.of(map.getOrDefault("recordings", out.resolve("recordings").toString())),
          map.getOrDefault("mode", "record"),
          Integer.parseInt(map.getOrDefault("concurrency", "4")),
          map.containsKey("labels") ? Path.of(map.get("labels")) : null,
          map.containsKey("gold-hints") ? Path.of(map.get("gold-hints")) : null,
          Path.of(map.getOrDefault("visibility-cache", out.resolve("visibility.tsv").toString()))
      );
    }
  }

  public static void main(final String[] args) {
    run(Config.parse(args), null, ProcessCommandRunner.INSTANCE);
  }

  static JevRunner runnerFor(final Config config) {
    return new JevRunner(
        "replay".equals(config.mode()) ? JevRunner.replayOnly(config.recordings()) : JevRunner.recording(config.recordings()),
        config.concurrency());
  }

  /// Corpus counts; `spend` is null in corpus mode.
  public record Summary(int modules, int skippedPrivate, int rows, Map<String, Integer> byStatus, JevRunner.Totals spend) {
  }

  public static Summary run(final Config config, final JevRunner runner, final CommandRunner commands) {
    final var manifest = Manifest.read(config.manifest());
    final var gate = new PublicRepoGate(commands, config.visibilityCache());
    final var hints = config.goldHints() != null && Files.isRegularFile(config.goldHints())
        ? RotLabels.read(config.goldHints(), "key").byKey()
        : Map.<String, String>of();

    // one index per module, shared as sibling context inside its repo
    final var indexes = new LinkedHashMap<String, TypeIndex>();
    final var publicEntries = new ArrayList<Manifest.Entry>();
    int skipped = 0;
    for (final var entry : manifest.entries()) {
      final var checkout = config.checkouts().resolve(entry.repo());
      if (!Files.isDirectory(checkout)) {
        skipped++;
        continue;
      }
      final String origin;
      try {
        origin = new GitRepo(checkout, commands).originOwnerRepo();
      } catch (final RuntimeException notARepo) {
        // no git checkout or no origin: nothing to verify as public, so nothing to send
        skipped++;
        continue;
      }
      if (!gate.isPublic(origin)) {
        skipped++;
        continue;
      }
      if (!Files.isDirectory(config.goldenFleet().resolve(entry.snapshotDir()))) {
        skipped++;
        continue;
      }
      publicEntries.add(entry);
      indexes.put(entry.id(), TypeIndex.scan(checkout.resolve(entry.sourceRoot())));
    }

    final var rows = new ArrayList<RotRow>();
    for (final var entry : publicEntries) {
      final var siblings = new LinkedHashMap<String, TypeIndex>();
      for (final var other : publicEntries) {
        if (!other.id().equals(entry.id()) && other.repo().equals(entry.repo())) {
          siblings.put(other.id(), indexes.get(other.id()));
        }
      }
      final var corpus = new RotCorpus(entry, config.checkouts().resolve(entry.repo()),
          config.goldenFleet().resolve(entry.snapshotDir()), siblings, commands, hints);
      rows.addAll(corpus.rows());
    }
    rows.sort(java.util.Comparator.comparingInt((RotRow r) -> -r.rung()).thenComparing(RotRow::id));

    try {
      Files.createDirectories(config.out());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create " + config.out(), e);
    }
    writeRows(rows, config.out().resolve("rows.tsv"));
    final var byStatus = new TreeMap<String, Integer>();
    for (final var row : rows) {
      byStatus.merge(row.resolution().status().name(), 1, Integer::sum);
    }
    // a reference to a type that never existed in this checkout has no subject to judge
    final var scorable = rows.stream().filter(r -> r.resolution().status() != MemberResolver.Status.MISSING_TYPE).toList();
    writeLabelingSheet(scorable, config.out().resolve("labeling-sheet.tsv"));
    if ("corpus".equals(config.mode())) {
      final var summary = new Summary(publicEntries.size(), skipped, rows.size(), byStatus, null);
      writeReport(config.out().resolve("report.md"), summary, scorable, Map.of(), null, hints);
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var row : scorable) {
      requests.put(row.id(), RotQuestions.request(row.state()));
    }
    final var outcomes = jev.run(requests);
    final var scores = new LinkedHashMap<String, RotScore>();
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
        scores.put(outcome.id(), RotScore.of(outcome.response()));
      }
    }
    writeScores(scorable, scores, config.out().resolve("jev.tsv"));
    final var labels = config.labels() != null && Files.isRegularFile(config.labels()) ? RotLabels.read(config.labels(), "row_id") : null;
    final var summary = new Summary(publicEntries.size(), skipped, rows.size(), byStatus, jev.totals(outcomes));
    writeReport(config.out().resolve("report.md"), summary, scorable, scores, labels, hints);
    return summary;
  }

  static List<RotBars.Row> barRows(final List<RotRow> rows, final Map<String, RotScore> scores, final java.util.function.Function<RotRow, String> gold) {
    final var out = new ArrayList<RotBars.Row>();
    for (final var row : rows) {
      final var label = gold.apply(row);
      final var score = scores.get(row.id());
      if (label != null && score != null) {
        out.add(new RotBars.Row(row.id(), label, row.rung(), row.controlFlag(), score));
      }
    }
    return out;
  }

  static void writeRows(final List<RotRow> rows, final Path file) {
    final var tsv = new Tsv("row_id", "module", "rung", "section", "member", "status", "detail", "control_flags", "file", "note_chars", "source_chars");
    for (final var row : rows) {
      final var state = row.state();
      tsv.row(row.id(), row.module(), row.rung(), row.ref().note().section(), row.ref().display(), row.resolution().status(),
          row.resolution().detail(), String.join(" ", row.controlFlags()), state.filePath(), state.note().length(),
          state.methodSource() == null ? 0 : state.methodSource().length());
    }
    tsv.write(file);
  }

  /// Blind: the note, the member, what code found, and empty label columns. No Jev output.
  static void writeLabelingSheet(final List<RotRow> rows, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "module", "rung", "member", "status", "control_flags", "bullet", "method_source_head");
    for (final var row : rows) {
      final var source = row.state().methodSource();
      tsv.row(row.id(), "", "", row.module(), row.rung(), row.ref().display(), row.resolution().status(),
          String.join(" ", row.controlFlags()), row.ref().note().bullet(),
          source == null ? "" : source.substring(0, Math.min(300, source.length())));
    }
    tsv.write(file);
  }

  static void writeScores(final List<RotRow> rows, final Map<String, RotScore> scores, final Path file) {
    final var tsv = new Tsv("row_id", "choice", "p_absent", "p_present", "p_cannot", "confidence", "contradicted", "depends_on_unseen", "control_flags", "gold_hint");
    for (final var row : rows) {
      final var score = scores.get(row.id());
      if (score == null) {
        tsv.row(row.id(), "", "", "", "", "", "", "", String.join(" ", row.controlFlags()), row.goldHint());
      } else {
        tsv.row(row.id(), score.choice(), fmt(score.pAbsent()), fmt(score.pPresent()), fmt(score.pCannot()), fmt(score.confidence()),
            fmt(score.contradicted()), fmt(score.dependsOnUnseen()), String.join(" ", row.controlFlags()), row.goldHint());
      }
    }
    tsv.write(file);
  }

  static String fmt(final double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  static void writeReport(final Path file,
                          final Summary summary,
                          final List<RotRow> rows,
                          final Map<String, RotScore> scores,
                          final RotLabels labels,
                          final Map<String, String> hints) {
    final var out = new StringBuilder();
    out.append("# Experiment A: acceptance-note rot detector\n\n## Corpus\n\n");
    out.append("| modules | skipped (private or absent) | rows |\n| --- | --- | --- |\n| ")
        .append(summary.modules()).append(" | ").append(summary.skippedPrivate()).append(" | ").append(summary.rows()).append(" |\n\n");
    out.append("Member status: ").append(summary.byStatus()).append("\n\n");
    final var byModule = new TreeMap<String, int[]>();
    for (final var row : rows) {
      final var counts = byModule.computeIfAbsent(row.module() + " (rung " + row.rung() + ")", _ -> new int[3]);
      counts[0]++;
      if (row.resolution().resolved()) {
        counts[1]++;
      }
      if (row.controlFlag()) {
        counts[2]++;
      }
    }
    out.append("| module | rows | resolved | control-flagged |\n| --- | --- | --- | --- |\n");
    for (final var entry : byModule.entrySet()) {
      out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()[0]).append(" | ")
          .append(entry.getValue()[1]).append(" | ").append(entry.getValue()[2]).append(" |\n");
    }
    out.append('\n');
    if (summary.spend() != null) {
      final var spend = summary.spend();
      out.append("## Jev\n\nRequests ").append(spend.requests()).append(" (").append(spend.succeeded()).append(" answered), input tokens ")
          .append(spend.inputTokens()).append(", cost $").append(String.format(Locale.ROOT, "%.4f", spend.dollars()))
          .append("; recording hits ").append(spend.hits()).append(", misses ").append(spend.misses()).append(".\n\n");
      appendBars(out, "hand labels", barRows(rows, scores, r -> labels == null ? null : labels.get(r.id())));
      appendBars(out, "PROVISIONAL survey gold hints (" + hints.size() + " hints; not a substitute for labels)",
          barRows(rows, scores, RotRow::goldHint));
      appendRanking(out, rows, scores);
    } else {
      out.append("## Jev\n\nNot scored (corpus mode).\n");
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }

  /// A basis with no labeled and scored rows (no labels file, no hints, or nothing scored yet) says so.
  static void appendBars(final StringBuilder out, final String basis, final List<RotBars.Row> barRows) {
    out.append("## Bars against ").append(basis).append("\n\n");
    if (barRows.isEmpty()) {
      out.append("No labeled and scored rows yet.\n\n");
      return;
    }
    out.append(barRows.size()).append(" labeled rows. ");
    final var control = RotBars.controlArm(barRows);
    out.append("Control arm: flagged ").append(control.flagged()).append(", true positives ").append(control.truePositives())
        .append(" of ").append(control.absent()).append(" rot rows (recall ").append(fmt(control.recall()))
        .append(", precision ").append(fmt(control.precision())).append(").\n\n");
    out.append("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : RotBars.checks(barRows)) {
      out.append("| ").append(check.bar()).append(" | ").append(check.value()).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append("| **keep** | **").append(RotBars.keep(barRows)).append("** | all | |\n\n```\n")
        .append(RotBars.confusion(barRows).render()).append("```\n\n");
  }

  /// The top 30 rows by P(absent): the list a human would read first.
  static void appendRanking(final StringBuilder out, final List<RotRow> rows, final Map<String, RotScore> scores) {
    out.append("## Top rows by P(construct_absent)\n\n| row | P(absent) | confidence | control flags | gold hint |\n| --- | --- | --- | --- | --- |\n");
    rows.stream()
        .filter(r -> scores.containsKey(r.id()))
        .sorted(java.util.Comparator.comparingDouble((RotRow r) -> -scores.get(r.id()).pAbsent()).thenComparing(RotRow::id))
        .limit(30)
        .forEach(r -> out.append("| ").append(r.id()).append(" | ").append(fmt(scores.get(r.id()).pAbsent())).append(" | ")
            .append(fmt(scores.get(r.id()).confidence())).append(" | ").append(String.join(" ", r.controlFlags())).append(" | ")
            .append(r.goldHint() == null ? "" : r.goldHint()).append(" |\n"));
    out.append('\n');
  }

  private RotExperiment() {
  }
}
