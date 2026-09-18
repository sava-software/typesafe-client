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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// End to end over a real temporary checkout with three commits: one body-only change and
/// one co-edit. The stub answers "affected" when the diff removes a line naming `cache`,
/// which is what the co-edit's comment was about.
final class DriftExperimentTests {

  private static final String V1 = """
      package p;

      public final class Widget {

        /// Returns the cached total for the key, computing it once per key on demand.
        public int total(final int key) {
          return cache.computeIfAbsent(key, this::compute);
        }

        /// Sizes the allocation from the count, never under-allocating for the caller.
        static int size(final int count) {
          return count + 1;
        }
      }
      """;

  private static final String V2 = V1.replace("return count + 1;", "return count + 2;");

  private static final String V3 = V2
      .replace("/// Returns the cached total for the key, computing it once per key on demand.", "/// Recomputes the total for the key on every call; nothing is cached any more.")
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
      final boolean affected = state.contains("-    return cache.");
      if (state.contains("\"comments\":[")) {
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
          ? "{\"model\":\"jev-stub\",\"answers\":{\"affected\":{\"type\":\"choice\",\"choice\":\"affected\",\"confidence\":0.9,\"probabilities\":{\"affected\":0.9,\"unaffected\":0.05,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"affected\":{\"type\":\"choice\",\"choice\":\"unaffected\",\"confidence\":0.85,\"probabilities\":{\"affected\":0.1,\"unaffected\":0.85,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
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
  void corpusModeCountsClassesAndExclusions(@TempDir final Path dir) throws Exception {
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
    assertNull(summary.spend());
    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals("row_id\trepo\tcommit\tpath\tmember\tkind\tclass\tcomment_chars\tdiff_size\toverlap", rows.getFirst());
    assertEquals(3, rows.size());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.size(int)\tmethod\tBODY_ONLY\t") && l.endsWith("\t2\t0.000")), rows.toString());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.total(int)\tmethod\tCO_EDIT\t")), rows.toString());
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 2 | 1 | 2 | 1 | 1 |"), report);
    assertTrue(report.contains("Excluded events: {"), report);
    assertTrue(report.contains("Not scored (corpus mode)"), report);
  }

  @Test
  void scoredRowsFeedTheBarsAndTheSheets(@TempDir final Path dir) throws Exception {
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
    final var batch = summary.batch();
    assertEquals(2, batch.batches(), "commit two and commit three each changed Widget.java");
    assertEquals(2, batch.requests());
    assertEquals(4, batch.candidates(), "both documented members in each of the two batches");
    assertEquals(1, batch.positives(), "total's comment in commit three");
    assertEquals(1000, batch.inputTokens());
    assertEquals(1, batch.requestsWithBoth());
    assertEquals(1.0, batch.pooledAuroc(), "the positive at 0.9 over three negatives at 0.1");
    assertEquals(1.0, batch.changedOnlyAuroc(), "the one changed-member negative is size in commit two, at 0.1");
    assertEquals(1.0, batch.meanRequestAuroc());
    final var batched = Files.readAllLines(out.resolve("batched.tsv"));
    assertEquals("batch_id\tcandidate\tmember\tpositive\tmember_changed\tp_affected", batched.getFirst());
    assertEquals(5, batched.size());
    assertTrue(batched.stream().anyMatch(l -> l.contains("\tWidget.total(int)\ttrue\ttrue\t0.900")), batched.toString());
    assertTrue(batched.stream().anyMatch(l -> l.contains("\tWidget.size(int)\tfalse\ttrue\t0.100")), batched.toString());
    final var batches = Files.readAllLines(out.resolve("batches.tsv"));
    assertEquals("batch_id\trepo\tcommit\tpath\tcandidates_shown\tcandidates_total\tpositives\tchanged_members\tdiff_chars", batches.getFirst());
    assertEquals(3, batches.size());
    assertFalse(Files.exists(dir.resolve("rec/0000stale.response.json")), "record mode prunes stale recordings");
    final var verdict = summary.verdict();
    assertEquals(1.0, verdict.auroc(), "the co-edit at 0.9 outranks the body-only change at 0.1");
    assertEquals("value bar pending", verdict.decision());
    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals("row_id\tclass\tchoice\tp_affected\tp_unaffected\tp_not_checkable\tconfidence\tbaseline", jev.getFirst());
    assertEquals(3, jev.size());
    final var top = Files.readAllLines(out.resolve("labeling-sheet-top.tsv"));
    assertEquals("row_id\tlabel\tnotes\trepo\tcommit\tmember\tcomment\tchange\tnew_source", top.getFirst());
    assertEquals(2, top.size(), "one body-only row");
    assertTrue(top.get(1).contains("#Widget.size(int)\t"), top.get(1));
    assertFalse(top.get(1).contains("0.100"), "blind");
    final var noise = Files.readAllLines(out.resolve("labeling-sheet-noise.tsv"));
    assertEquals("row_id\tlabel\tnotes\trepo\tcommit\tmember\told_comment\tnew_comment\tchange", noise.getFirst());
    assertEquals(2, noise.size(), "one co-edit row");
    assertTrue(noise.get(1).contains("Recomputes the total for the key on every call"), noise.get(1));
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Requests 4 (4 answered), input tokens 1600, cost $0.0001; recording hits 0, misses 4; 2 rows scored."), report);
    assertTrue(report.contains("## Batched arm: one request per changed file, one Noul per candidate comment (reported, no bar)"), report);
    assertTrue(report.contains("| 2 | 2 | 4 | 1 | 1 | 1000 |"), report);
    assertTrue(report.contains("AUROC pooled, positives over all negatives: 1.000; over changed-member negatives only (like the pair arm): 1.000; mean within-request AUROC: 1.000."), report);
    assertTrue(report.contains("Cost per judged comment: batched 250 input tokens in 0.50 requests; pair arm 300 input tokens in 1 request."), report);
    assertTrue(report.contains("Choices, CO_EDIT: {affected=1}; BODY_ONLY: {unaffected=1}."), report);
    assertTrue(report.contains("Noise estimate: the CO_EDIT sample has not been read yet"), report);
    assertTrue(report.contains("## Top BODY_ONLY rows by P(affected)"), report);

    // labels turn the table; replay needs no key
    final var topLabeled = top.getFirst() + "\n" + top.get(1).replaceFirst("\t\t", "\tneeded update\t") + "\n";
    Files.writeString(dir.resolve("top.tsv"), topLabeled);
    final var noiseLabeled = noise.getFirst() + "\n" + noise.get(1).replaceFirst("\t\t", "\trelated\t") + "\n";
    Files.writeString(dir.resolve("noise.tsv"), noiseLabeled);
    config = DriftExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString(), "--mode", "replay", "--labels-top", dir.resolve("top.tsv").toString(),
        "--labels-noise", dir.resolve("noise.tsv").toString()});
    final var replayed = DriftExperiment.run(config, null, commands());
    assertEquals(4, replayed.spend().hits());
    assertEquals("nothing missed", replayed.verdict().decision(), "one confirmed miss of the five required");
    assertEquals(1, replayed.verdict().noiseRelated());
    assertEquals(1.0, replayed.verdict().ceiling(), "every sampled co-edit was related");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("confirmed as missed updates (1 read) | 1.000 | >= 5 | NO |"), report);
    assertTrue(report.contains("Noise estimate: 1 of 1 sampled CO_EDIT comment edits were about the body change; a perfect judge's separation ceiling at that rate is about 1.000."), report);
  }

  @Test
  void configAndLabels(@TempDir final Path dir) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> DriftExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> DriftExperiment.Config.parse(new String[]{"checkouts", "c", "--repos", "r"}));
    final var config = DriftExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a,b", "--mode", "replay", "--concurrency", "3"});
    assertEquals(Path.of("build/experiments/drift"), config.out());
    assertEquals(Path.of("build/experiments/drift/recordings"), config.recordings());
    assertEquals(3, config.concurrency());
    assertNull(config.labelsTop());
    assertNull(config.labelsNoise());
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, DriftExperiment.runnerFor(config).client().mode());
    assertEquals("", DriftExperiment.fmt(Double.NaN));
    assertEquals("0.500", DriftExperiment.fmt(0.5));
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "row_id\tlabel\na\tNeeded update\nb\tno-update-needed\nc\t\nd\tcannot_tell\n");
    assertEquals(Map.of("a", "needed_update", "b", "no_update_needed", "d", "cannot_tell"), DriftLabels.read(file, DriftLabels.TOP_LABELS).byKey());
    assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file, DriftLabels.NOISE_LABELS), "the top labels are not noise labels");
    Files.writeString(file, "row_id\tlabel\nx\trelated\ny\tunrelated\n");
    assertEquals(Map.of("x", "related", "y", "unrelated"), DriftLabels.read(file, DriftLabels.NOISE_LABELS).byKey());
    Files.writeString(file, "id\tlabel\n");
    assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file, DriftLabels.TOP_LABELS));
    Files.writeString(file, "");
    assertThrows(IllegalArgumentException.class, () -> DriftLabels.read(file, DriftLabels.TOP_LABELS));
    assertThrows(java.io.UncheckedIOException.class, () -> DriftLabels.read(dir.resolve("absent"), DriftLabels.TOP_LABELS));
    assertEquals(Set.of("needed_update", "no_update_needed", "cannot_tell"), DriftLabels.TOP_LABELS);
  }
}
