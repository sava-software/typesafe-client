package software.sava.typesafe.evals.drift;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.ModelCard;
import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;
import software.sava.typesafe.evals.jev.JevRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// End to end over a real temporary checkout with three commits: one body-only change and
/// one co-edit. The stub answers "contradicted" when the removed lines name `cache`, which
/// is what the co-edit's comment was about.
final class DriftExperimentTests {

  private static final String V1 = """
      package p;

      public final class Widget {

        /// Returns the cached sum for the key, computing it once per key on demand.
        public int total(final int key) {
          return cache.computeIfAbsent(key, this::compute);
        }

        /// Returns the allocation for the count, never under-allocating for anyone.
        static int size(final int count) {
          return count + 1;
        }
      }
      """;

  private static final String V2 = V1.replace("return count + 1;", "return count + 2;");

  private static final String V3 = V2
      .replace("/// Returns the cached sum for the key, computing it once per key on demand.", "/// Recomputes the total for the key on every call; nothing is cached any more.")
      .replace("return cache.computeIfAbsent(key, this::compute);", "return compute(key);");

  private static CommandRunner commands() {
    return (command, directory) -> command.getFirst().equals("gh") ? "public\n" : ProcessCommandRunner.INSTANCE.run(command, directory);
  }

  private static final class StubClient implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      final boolean affected = state.contains("\"removed_lines\":[\"    return cache.");
      if (state.startsWith("{\"changes\":[")) {
        // the batched arm: one noul per candidate; the total() comment is the one the cache change affects
        final var answers = new StringBuilder();
        for (final var id : request.questions().keySet()) {
          final int index = Integer.parseInt(id.substring(1));
          final boolean isTotal = state.contains("{\"id\":" + index + ",\"member\":\"Widget.total(int)\"");
          if (!answers.isEmpty()) {
            answers.append(',');
          }
          answers.append('"').append(id).append("\":{\"type\":\"noul\",\"noul\":").append(affected && isTotal ? "0.9" : "0.1").append('}');
        }
        final var batch = "{\"model\":\"jev-stub\",\"answers\":{" + answers + "},\"usage\":{\"input_tokens\":500,\"output_tokens\":5}}";
        return CompletableFuture.completedFuture(SystemOneResponse.parse(batch.getBytes(StandardCharsets.UTF_8), "req_batch"));
      }
      final var body = affected
          ? "{\"model\":\"jev-stub\",\"answers\":{\"affected\":{\"type\":\"choice\",\"choice\":\"contradicted_by_change\",\"confidence\":0.9,\"probabilities\":{\"contradicted_by_change\":0.8,\"needs_addition\":0.1,\"unaffected\":0.05,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"affected\":{\"type\":\"choice\",\"choice\":\"unaffected\",\"confidence\":0.85,\"probabilities\":{\"contradicted_by_change\":0.05,\"needs_addition\":0.05,\"unaffected\":0.85,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), "req_stub"));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static String git(final Path repo, final String... args) {
    final var command = new java.util.ArrayList<String>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@x"));
    command.addAll(List.of(args));
    return ProcessCommandRunner.INSTANCE.run(command, null);
  }

  private static Path checkout(final Path checkouts) throws Exception {
    final var repo = checkouts.resolve("repo");
    final var src = repo.resolve("mod/src/main/java/p");
    Files.createDirectories(src);
    Files.writeString(src.resolve("Widget.java"), V1);
    git(repo, "init", "-q", "-b", "main");
    git(repo, "remote", "add", "origin", "git@github.com:test-org/repo.git");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "one");
    Files.writeString(src.resolve("Widget.java"), V2);
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "two: size body only");
    Files.writeString(src.resolve("Widget.java"), V3);
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "three: total co-edited");
    return repo;
  }

  @Test
  void corpusModeCountsClassesExclusionsAndBatches(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var config = DriftExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo,absent",
        "--out", out.toString(), "--mode", "corpus"});
    final var summary = DriftExperiment.run(config, null, commands());
    assertEquals(2, summary.repos());
    assertEquals(1, summary.skipped());
    assertEquals(2, summary.rows());
    assertEquals(1, summary.coEdits());
    assertEquals(1, summary.bodyOnly());
    assertEquals(2, summary.commits());
    assertNull(summary.spend());
    assertEquals(2, summary.batch().batches());
    assertEquals(4, summary.batch().candidates());
    assertEquals(1, summary.batch().positives());
    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals("row_id\trepo\tcommit\tpath\tmember\tkind\tclass\tcomment_chars\tdiff_size\toverlap\tlines_before\tlines_after\tabstract_toggle", rows.getFirst());
    assertEquals(3, rows.size());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.size(int)\tmethod\tBODY_ONLY\t") && l.endsWith("\t2\t0.000\t3\t3\tfalse")), rows.toString());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.total(int)\tmethod\tCO_EDIT\t")), rows.toString());
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals("row_id\tlabel\tnotes\trepo\tmember\tcomment\tchange\tnew_source", sheet.getFirst());
    assertEquals(3, sheet.size(), "both classes in one sheet");
    assertTrue(sheet.stream().noneMatch(l -> l.contains("CO_EDIT") || l.contains("BODY_ONLY")), "the class is not shown");
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 2 | 1 | 2 | 1 | 1 | 2 |"), report);
    assertTrue(report.contains("Excluded events: {"), report);
    assertTrue(report.contains("Abstract-toggle rows (a member gained or lost a body): 0."), report);
    assertTrue(report.contains("Not scored (corpus mode)"), report);
  }

  @Test
  void scoredRowsFeedTheBarsTheSheetAndTheBatchedArm(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new StubClient(), dir.resolve("rec")), 2);
    Files.createDirectories(dir.resolve("rec"));
    Files.writeString(dir.resolve("rec/0000stale.response.json"), "{}");
    var config = DriftExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString()});
    final var summary = DriftExperiment.run(config, runner, commands());
    assertEquals(new JevRunner.Totals(4, 4, 1600, 20, 0, 4), summary.spend(), "two pair requests and two batched requests");
    assertFalse(Files.exists(dir.resolve("rec/0000stale.response.json")), "record mode prunes stale recordings");
    final var verdict = summary.verdict();
    assertEquals(1.0, verdict.auroc(), "the co-edit at 0.9 outranks the body-only change at 0.1");
    assertEquals("value bar pending", verdict.decision());
    assertEquals(7, verdict.baselines().size());
    final var batch = summary.batch();
    assertEquals(2, batch.batches());
    assertEquals(2, batch.requests());
    assertEquals(4, batch.candidates());
    assertEquals(1, batch.positives());
    assertEquals(1000, batch.inputTokens());
    assertEquals(1, batch.requestsWithBoth());
    assertEquals(1.0, batch.pooledAuroc());
    assertEquals(1.0, batch.changedOnlyAuroc());
    assertEquals(1.0, batch.meanRequestAuroc());
    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals("row_id\tclass\tchoice\tscore\tp_contradicted\tp_needs_addition\tp_unaffected\tp_not_checkable\tconfidence", jev.getFirst());
    assertEquals(3, jev.size());
    assertTrue(jev.stream().anyMatch(l -> l.contains("\tCO_EDIT\tcontradicted_by_change\t0.900\t0.800\t0.100\t")), jev.toString());
    final var batched = Files.readAllLines(out.resolve("batched.tsv"));
    assertEquals("batch_id\tcandidate\tmember\tpositive\tmember_changed\tp_affected", batched.getFirst());
    assertEquals(5, batched.size());
    assertTrue(batched.stream().anyMatch(l -> l.contains("\tWidget.total(int)\ttrue\ttrue\t0.900")), batched.toString());
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals(3, sheet.size());
    assertTrue(sheet.stream().noneMatch(l -> l.contains("0.900") || l.contains("0.100")), "blind");
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Requests 4 (4 answered), input tokens 1600, cost $0.0001; recording hits 0, misses 4; 2 rows scored."), report);
    assertTrue(report.contains("| deterministic baseline | AUROC | two-sided |"), report);
    assertTrue(report.contains("Choices, CO_EDIT: {contradicted_by_change=1}; BODY_ONLY: {unaffected=1}."), report);
    assertTrue(report.contains("Oracle ceiling: the blind sheet has not been read yet"), report);
    assertTrue(report.contains("## Top BODY_ONLY rows by score"), report);
    assertTrue(report.contains("| 2 | 2 | 4 | 1 | 1 | 1000 |"), report);
    assertTrue(report.contains("Cost per judged comment: batched 250 input tokens in 0.50 requests; pair arm 300 input tokens in 1 request."), report);

    // labels: the co-edit is affected, the body-only change is not; replay needs no key
    final var labeled = new StringBuilder(sheet.getFirst()).append('\n');
    for (final var line : sheet.subList(1, sheet.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = cells[0].contains("Widget.total(") ? "needs addition" : "unaffected";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(dir.resolve("labels.tsv"), labeled.toString());
    config = DriftExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString(), "--mode", "replay", "--labels", dir.resolve("labels.tsv").toString()});
    final var replayed = DriftExperiment.run(config, null, commands());
    assertEquals(4, replayed.spend().hits());
    final var v = replayed.verdict();
    assertEquals(1.0, v.oracleRho(), "the one co-edit was marked affected");
    assertEquals(0.0, v.oracleBeta());
    assertEquals(1.0, v.ceiling(), 1e-12);
    assertEquals(1, v.topRate().of());
    assertEquals(0, v.topRate().count());
    assertEquals("nothing missed", v.decision(), "no body-only row was marked affected");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Oracle ceiling: readers marked 1.000 of CO_EDIT rows and 0.000 of BODY_ONLY rows affected"), report);
    assertTrue(report.contains("Value bar: top 30 BODY_ONLY rows 0 of 1 = 0.000"), report);
  }

  @Test
  void configAndLabels(@TempDir final Path dir) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> DriftExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> DriftExperiment.Config.parse(new String[]{"checkouts", "c", "--repos", "r"}));
    final var config = DriftExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a,b", "--mode", "replay", "--concurrency", "3"});
    assertEquals(Path.of("build/experiments/drift"), config.out());
    assertEquals(Path.of("build/experiments/drift/recordings"), config.recordings());
    assertEquals(3, config.concurrency());
    assertNull(config.labels());
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, DriftExperiment.runnerFor(config).client().mode());
    assertEquals("", DriftExperiment.fmt(Double.NaN));
    assertEquals("0.500", DriftExperiment.fmt(0.5));
    assertEquals("n/a", DriftExperiment.rate(DriftBars.Rate.of(0, 0)));
    assertEquals("1 of 4 = 0.250 (0.046 to 0.699)", DriftExperiment.rate(DriftBars.Rate.of(1, 4)));
    assertEquals("a\\\\b\\nc", DriftExperiment.escape("a\\b\nc"), "backslashes double before line breaks become backslash-n");
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "row_id\tlabel\na\tContradicted by change\nb\tneeds-addition\nc\t\nd\tunaffected\ne\tnot checkable\n");
    assertEquals(Map.of("a", "contradicted_by_change", "b", "needs_addition", "d", "unaffected", "e", "not_checkable"), DriftLabels.read(file).byKey());
    Files.writeString(file, "row_id\tlabel\nx\trelated\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file)).getMessage().contains("line 2"));
    Files.writeString(file, "id\tlabel\n");
    assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file));
    Files.writeString(file, "");
    assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file));
    assertThrows(java.io.UncheckedIOException.class, () -> DriftLabels.read(dir.resolve("absent")));
  }
}
