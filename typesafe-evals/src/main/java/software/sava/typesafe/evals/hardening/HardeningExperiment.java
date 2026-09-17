package software.sava.typesafe.evals.hardening;

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

/// Experiment C2 end to end: rows from every public checkout named, the REAL and SWAPPED
/// arms scored in separate requests, the pre-registered bars, and a blind sheet of the top
/// rows for the human value bar.
///
/// Arguments: `--checkouts <dir>` `--repos <name,name,...>` `--out <dir>` `--recordings <dir>`
/// `--mode record|replay|corpus` `--concurrency <n>` `--labels <sheet.tsv>` `--visibility-cache <file>`
public final class HardeningExperiment {

  public record Config(Path checkouts,
                       List<String> repos,
                       Path out,
                       Path recordings,
                       String mode,
                       int concurrency,
                       Path labels,
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
      final var out = Path.of(map.getOrDefault("out", "build/experiments/hardening"));
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
  public record Summary(int repos, int skipped, int modules, int rows, int scorable, Map<String, Integer> byStatus,
                        JevRunner.Totals spend, HardeningBars.Verdict verdict) {
  }

  private HardeningExperiment() {
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
    final var rows = new ArrayList<HardeningRow>();
    int skipped = 0;
    int modules = 0;
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
      final var corpus = new HardeningCorpus(name, checkout, git);
      final var found = corpus.modules();
      modules += found.size();
      for (final var module : found) {
        rows.addAll(corpus.rows(module));
      }
    }
    rows.sort(java.util.Comparator.comparing(HardeningRow::id));
    try {
      Files.createDirectories(config.out());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create " + config.out(), e);
    }
    writeRows(rows, config.out().resolve("rows.tsv"));
    final var byStatus = new TreeMap<String, Integer>();
    for (final var row : rows) {
      byStatus.merge(row.memberStatus(), 1, Integer::sum);
    }
    final var scorable = rows.stream().filter(HardeningRow::scorable).toList();
    if ("corpus".equals(config.mode())) {
      final var summary = new Summary(config.repos().size(), skipped, modules, rows.size(), scorable.size(), byStatus, null, null);
      writeReport(config.out().resolve("report.md"), summary, rows, List.of());
      return summary;
    }

    final var jev = runner != null ? runner : runnerFor(config);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (final var row : scorable) {
      requests.put(row.id() + "#real", HardeningQuestions.request(row.state()));
      requests.put(row.id() + "#swapped", HardeningQuestions.request(row.swappedState()));
    }
    final var outcomes = jev.run(requests);
    final var scores = new LinkedHashMap<String, HardeningScore>();
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
        scores.put(outcome.id(), HardeningScore.of(outcome.response()));
      }
    }
    final var pairs = new ArrayList<HardeningBars.Pair>();
    for (final var row : scorable) {
      final var real = scores.get(row.id() + "#real");
      final var swapped = scores.get(row.id() + "#swapped");
      if (real != null && swapped != null) {
        pairs.add(new HardeningBars.Pair(row, real, swapped));
      }
    }
    writeScores(pairs, config.out().resolve("jev.tsv"));
    writeLabelingSheet(HardeningBars.top(pairs), config.out().resolve("labeling-sheet.tsv"));
    final var labels = config.labels() != null && Files.isRegularFile(config.labels())
        ? HardeningLabels.read(config.labels()).byKey()
        : Map.<String, String>of();
    final var verdict = HardeningBars.verdict(pairs, labels);
    final var summary = new Summary(config.repos().size(), skipped, modules, rows.size(), scorable.size(), byStatus, jev.totals(outcomes), verdict);
    writeReport(config.out().resolve("report.md"), summary, rows, pairs);
    return summary;
  }

  static void writeRows(final List<HardeningRow> rows, final Path file) {
    final var tsv = new Tsv("row_id", "module", "suite", "class", "method", "mutator", "status", "label", "line", "member_status",
        "declarations", "bodies_shown", "paragraph_chars", "swapped_mutator", "word_real", "word_swapped", "identifiers_missing");
    for (final var row : rows) {
      tsv.row(row.id(), row.module(), row.row().suite(), row.row().className(), row.row().method(), row.row().mutator(),
          row.row().status(), row.label(), row.row().lineHint(), row.memberStatus(), row.declarations(), row.bodiesShown(),
          row.paragraphChars(), row.swappedMutator(), row.wordReal(), row.wordSwapped(), String.join(" ", row.identifiersMissing()));
    }
    tsv.write(file);
  }

  static void writeScores(final List<HardeningBars.Pair> pairs, final Path file) {
    final var tsv = new Tsv("row_id", "arm", "choice", "p_applies", "p_does_not_apply", "p_cannot", "confidence", "construct_absent",
        "word_baseline");
    for (final var pair : pairs) {
      tsv.row(pair.row().id(), "real", pair.real().choice(), fmt(pair.real().pApplies()), fmt(pair.real().pDoesNotApply()),
          fmt(pair.real().pCannot()), fmt(pair.real().confidence()), fmt(pair.real().constructAbsent()), pair.row().wordReal());
      tsv.row(pair.row().id(), "swapped", pair.swapped().choice(), fmt(pair.swapped().pApplies()), fmt(pair.swapped().pDoesNotApply()),
          fmt(pair.swapped().pCannot()), fmt(pair.swapped().confidence()), fmt(pair.swapped().constructAbsent()), pair.row().wordSwapped());
    }
    tsv.write(file);
  }

  /// Blind: the top rows in id order, without their scores.
  static void writeLabelingSheet(final List<HardeningBars.Pair> top, final Path file) {
    final var tsv = new Tsv("row_id", "label", "notes", "module", "row", "mutator_description", "paragraph_head", "member_head");
    final var ordered = new ArrayList<>(top);
    ordered.sort(java.util.Comparator.comparing(p -> p.row().id()));
    for (final var pair : ordered) {
      final var row = pair.row();
      tsv.row(row.id(), "", "", row.module(), row.row().raw(), row.state().mutatorDescription(),
          head(row.state().paragraph(), 600), head(row.state().memberSource(), 400));
    }
    tsv.write(file);
  }

  static String head(final String text, final int chars) {
    if (text == null) {
      return "";
    }
    final var flat = text.replace('\n', ' ');
    return flat.length() <= chars ? flat : flat.substring(0, chars) + " …";
  }

  static String fmt(final double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  static void writeReport(final Path file, final Summary summary, final List<HardeningRow> rows, final List<HardeningBars.Pair> pairs) {
    final var out = new StringBuilder();
    out.append("# Experiment C2: hardening evidence versus the mutant it explains\n\n## Corpus\n\n");
    out.append("| repositories | skipped (private or absent) | modules | rows | scorable |\n| --- | --- | --- | --- | --- |\n");
    out.append("| ").append(summary.repos()).append(" | ").append(summary.skipped()).append(" | ").append(summary.modules())
        .append(" | ").append(summary.rows()).append(" | ").append(summary.scorable()).append(" |\n\n");
    out.append("Member status: ").append(summary.byStatus()).append("\n\n");
    final var perModule = new TreeMap<String, int[]>();
    for (final var row : rows) {
      final var counts = perModule.computeIfAbsent(row.module(), k -> new int[3]);
      counts[0]++;
      if (row.scorable()) {
        counts[1]++;
      }
      if (row.wordReal()) {
        counts[2]++;
      }
    }
    out.append("| module | rows | scorable | paragraph mentions the row's family |\n| --- | --- | --- | --- |\n");
    for (final var entry : perModule.entrySet()) {
      out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()[0]).append(" | ").append(entry.getValue()[1])
          .append(" | ").append(entry.getValue()[2]).append(" |\n");
    }
    out.append('\n');
    if (summary.spend() == null) {
      out.append("## Jev\n\nNot scored (corpus mode).\n");
    } else {
      final var spend = summary.spend();
      out.append("## Jev\n\nRequests ").append(spend.requests()).append(" (").append(spend.succeeded()).append(" answered), input tokens ")
          .append(spend.inputTokens()).append(", cost $").append(String.format(Locale.ROOT, "%.4f", spend.dollars()))
          .append("; recording hits ").append(spend.hits()).append(", misses ").append(spend.misses()).append("; ")
          .append(pairs.size()).append(" rows with both arms scored.\n\n");
      appendBars(out, summary.verdict());
      appendChoices(out, pairs);
      appendTop(out, pairs);
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }

  static void appendBars(final StringBuilder out, final HardeningBars.Verdict verdict) {
    out.append("## Bars (pre-registered decision table, first match wins)\n\n");
    out.append("AUROC ").append(fmt(verdict.auroc())).append(" (bootstrap 95% ").append(fmt(verdict.interval()[0])).append(" to ")
        .append(fmt(verdict.interval()[1])).append("), mutator-word baseline ").append(fmt(verdict.baselineAuroc())).append(".\n\n");
    out.append("| bar | value | required | pass |\n| --- | --- | --- | --- |\n");
    for (final var check : verdict.checks()) {
      out.append("| ").append(check.name()).append(" | ").append(fmt(check.value())).append(" | ").append(check.required())
          .append(" | ").append(check.pass() ? "yes" : "NO").append(" |\n");
    }
    out.append("| **decision** | **").append(verdict.decision()).append("** | | |\n\n");
  }

  static void appendChoices(final StringBuilder out, final List<HardeningBars.Pair> pairs) {
    final var real = new TreeMap<String, Integer>();
    final var swapped = new TreeMap<String, Integer>();
    for (final var pair : pairs) {
      real.merge(pair.real().choice(), 1, Integer::sum);
      swapped.merge(pair.swapped().choice(), 1, Integer::sum);
    }
    out.append("Choices, REAL arm: ").append(real).append("; SWAPPED arm: ").append(swapped).append(".\n\n");
  }

  static void appendTop(final StringBuilder out, final List<HardeningBars.Pair> pairs) {
    out.append("## Top REAL rows by P(does_not_apply)\n\n| row | P(does_not_apply) | confidence | construct_absent | swapped arm P |\n| --- | --- | --- | --- | --- |\n");
    for (final var pair : HardeningBars.top(pairs)) {
      out.append("| ").append(pair.row().id()).append(" | ").append(fmt(pair.real().pDoesNotApply())).append(" | ")
          .append(fmt(pair.real().confidence())).append(" | ").append(fmt(pair.real().constructAbsent())).append(" | ")
          .append(fmt(pair.swapped().pDoesNotApply())).append(" |\n");
    }
  }
}
