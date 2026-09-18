package software.sava.typesafe.evals.docs;

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

/// End to end over a real temporary checkout with two commits: the second changes one
/// member's body under an untouched comment (a stale candidate). The stub answers
/// "contradicted" whenever the comment shown names `NEXT`, which only the swapped comments
/// and the stale candidate's comment do.
final class DocExperimentTests {

  private static final String V1 = """
      package p;

      public final class Widget {

        /// Sums the data with an empty fast path and returns the total of all entries.
        public int sum(final int[] data) {
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }

        /// Sizes the allocation from count using the NEXT step, never under-allocating.
        static int size(final int count) {
          return count + NEXT;
        }

        /// Reports the NEXT step used by size, documented long enough to count as a comment.
        static int step() {
          return NEXT;
        }

        static final int NEXT = 1;
      }
      """;

  private static final String V2 = V1.replace("return count + NEXT;", "return count * 2;");

  private static CommandRunner commands() {
    return (command, directory) -> command.getFirst().equals("gh")
        ? "public\n"
        : ProcessCommandRunner.INSTANCE.run(command, directory);
  }

  private static final class StubClient implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      final int at = state.indexOf("\"comment\":\"");
      final var comment = state.substring(at, state.indexOf("\",\"member_source\""));
      final boolean contradicted = comment.contains("NEXT") && !state.contains("NEXT;\\n");
      final var body = contradicted
          ? "{\"model\":\"jev-stub\",\"answers\":{\"agreement\":{\"type\":\"choice\",\"choice\":\"contradicted\",\"confidence\":0.9,\"probabilities\":{\"consistent\":0.05,\"contradicted\":0.9,\"not_checkable\":0.05}},\"names_missing\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"agreement\":{\"type\":\"choice\",\"choice\":\"consistent\",\"confidence\":0.85,\"probabilities\":{\"consistent\":0.85,\"contradicted\":0.1,\"not_checkable\":0.05}},\"names_missing\":{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
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
    git(repo, "commit", "-q", "-m", "two: size loses NEXT, comment untouched");
    return repo;
  }

  @Test
  void corpusModeCountsMembersSwapsAndStaleCandidates(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo,absent",
        "--out", out.toString(), "--mode", "corpus", "--sample", "2"});
    final var summary = DocExperiment.run(config, null, commands());
    assertEquals(2, summary.repos());
    assertEquals(1, summary.skipped());
    assertEquals(3, summary.rows(), "sum, size, step; NEXT's comment is absent");
    assertEquals(3, summary.withSwap(), "three documented methods in one type: every one has a sibling");
    assertEquals(1, summary.staleCandidates(), "size's body changed under an untouched comment");
    assertEquals(2, summary.sampled());
    assertNull(summary.spend());
    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals("row_id\trepo\tpath\tmember\tkind\tcomment_chars\tbody_lines\tswapped_from\tmismatch_real\tmismatch_swapped\tidentifiers_missing\tsample", rows.getFirst());
    assertEquals(4, rows.size());
    final var size = rows.stream().filter(l -> l.contains("#Widget.size(int)\t")).findFirst().orElseThrow();
    assertTrue(size.endsWith("\tNEXT\tstale-candidate"), "size's comment names NEXT, which its new body lacks: " + size);
    assertTrue(size.contains("\tWidget.step()\t1.000\t"), "size's swap is step's comment; its own mismatch is 1.0: " + size);
    assertEquals(1, rows.stream().filter(l -> l.endsWith("\trandom")).count(), "the sample is filled with one random member");
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 2 | 1 | 3 | 3 | 1 | 2 |"), report);
    assertTrue(report.contains("Not scored (corpus mode)"), report);
  }

  @Test
  void scoredArmsFeedBothDesigns(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new StubClient(), dir.resolve("rec")), 2);
    Files.createDirectories(dir.resolve("rec"));
    Files.writeString(dir.resolve("rec/0000stale.response.json"), "{}");
    var config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString(), "--sample", "3", "--labels-sample", dir.resolve("sample-labels.tsv").toString()});
    assertEquals("record", config.mode());
    final var summary = DocExperiment.run(config, runner, commands());
    assertEquals(new JevRunner.Totals(6, 6, 1800, 30, 0, 6), summary.spend(), "three members, two arms each");
    assertFalse(Files.exists(dir.resolve("rec/0000stale.response.json")), "record mode prunes stale recordings");
    final var verdict = summary.verdict();
    // REAL: sum consistent (0.1), size contradicted (0.9, names NEXT which is gone), step consistent (0.1: its body has NEXT)
    // SWAPPED: sum gets size's comment (NEXT, absent from sum) -> 0.9; size gets step's -> 0.9; step gets sum's -> 0.1
    // swapped {0.9, 0.9, 0.1} over real {0.1, 0.9, 0.1}: four wins, two ties -> 6 of 9
    assertEquals(6.0 / 9.0, verdict.auroc(), 1e-9);
    assertEquals("kill: separation", verdict.decision());
    assertNull(summary.sampleVerdict(), "no sample labels yet");
    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals("row_id\tarm\tchoice\tp_consistent\tp_contradicted\tp_not_checkable\tconfidence\tnames_missing\tmismatch_baseline", jev.getFirst());
    assertEquals(7, jev.size());
    final var top = Files.readAllLines(out.resolve("labeling-sheet-top.tsv"));
    assertEquals("row_id\tlabel\tnotes\trepo\tmember\tcomment\tmember_source", top.getFirst());
    assertEquals(4, top.size());
    assertTrue(top.stream().noneMatch(l -> l.contains("0.900")), "blind");
    final var sample = Files.readAllLines(out.resolve("labeling-sheet-sample.tsv"));
    assertEquals(4, sample.size());
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Requests 6 (6 answered), input tokens 1800, cost $0.0001; recording hits 0, misses 6; 3 rows with both arms scored, 3 REAL rows scored."), report);
    assertTrue(report.contains("## Design 1: swapped comments"), report);
    assertTrue(report.contains("3 sampled rows scored; no labels yet"), report);
    assertTrue(report.contains("Choices, REAL arm: {consistent=2, contradicted=1}; SWAPPED arm: {consistent=1, contradicted=2}."), report);

    // labels for the sample: size is contradicted, the others consistent; replay needs no key
    final var labeled = new StringBuilder(sample.getFirst()).append('\n');
    for (final var line : sample.subList(1, sample.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = cells[0].contains("Widget.size(") ? "contradicted" : "consistent";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(dir.resolve("sample-labels.tsv"), labeled.toString());
    final var topLabeled = new StringBuilder(top.getFirst()).append('\n');
    for (final var line : top.subList(1, top.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = "not checkable";
      topLabeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(dir.resolve("top-labels.tsv"), topLabeled.toString());
    config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString(), "--sample", "3", "--mode", "replay",
        "--labels-sample", dir.resolve("sample-labels.tsv").toString(), "--labels-top", dir.resolve("top-labels.tsv").toString()});
    final var replayed = DocExperiment.run(config, null, commands());
    assertEquals(6, replayed.spend().hits());
    final var sv = replayed.sampleVerdict();
    assertNotNull(sv);
    assertEquals(3, sv.labeled());
    assertEquals(1, sv.contradicted());
    assertEquals(2, sv.consistent());
    assertEquals(1.0 / 3.0, sv.prevalence(), 1e-12, "one contradicted of three decided");
    assertEquals(1.0, sv.auroc(), "size at 0.9 above both consistent rows at 0.1");
    assertEquals(1.0, sv.mismatchAuroc(), "size's mismatch 1.0 above the others");
    assertEquals(0.0, sv.confidentWrong());
    assertEquals("kill: separation", replayed.verdict().decision(), "the table stops at separation before it reads the value bar");
    assertEquals(0.0, replayed.verdict().checks().get(3).value(), "three top rows read, none contradicted");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("3 labeled rows: 1 contradicted, 2 consistent, 0 not checkable; prevalence of contradicted among decided rows 0.333."), report);
    assertTrue(report.contains("contradicted comments confirmed among the top 30 REAL rows (3 read) | 0.000 | >= 5 | NO |"), report);
  }

  @Test
  void staleCandidatesAndSampling() {
    final var key = new FileMembers.Key("T", "m", "");
    final var row = row("repo#a/T.java#T.m()", "a/T.java", key, "doc");
    final var other = row("repo#b/U.java#U.n()", "b/U.java", new FileMembers.Key("U", "n", ""), "x");
    final var rows = List.of(row, other);
    final var bodyOnly = new HistoryMiner.Event("c1", 0, "a/T.java", key, "method", false, true, "doc", "doc", "s", "s", "{a}", "{b}");
    assertEquals(Set.of("repo#a/T.java#T.m()"), DocExperiment.staleCandidates("repo", rows, List.of(bodyOnly)));
    final var later = new HistoryMiner.Event("c2", 1, "a/T.java", key, "method", true, false, "doc", "doc2", "s", "s", "{b}", "{b}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(bodyOnly, later)), "a later comment edit clears the candidate");
    final var drifted = new HistoryMiner.Event("c1", 0, "a/T.java", key, "method", false, true, "old", "old", "s", "s", "{a}", "{b}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(drifted)), "the comment at HEAD differs from the event's");
    final var unknown = new HistoryMiner.Event("c1", 0, "z/Z.java", key, "method", false, true, "doc", "doc", "s", "s", "{a}", "{b}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(unknown)), "not a documented member at HEAD");
    assertEquals(List.of(), DocExperiment.sample(rows, Set.of(), 0));
    assertEquals(List.of(row), DocExperiment.sample(rows, Set.of("repo#a/T.java#T.m()"), 1), "candidates first");
    assertEquals(2, DocExperiment.sample(rows, Set.of(), 5).size(), "random fill stops when rows run out");
    assertEquals(DocExperiment.sample(rows, Set.of(), 1), DocExperiment.sample(rows, Set.of(), 1), "seeded, so reproducible");
  }

  private static DocCorpus.Row row(final String id, final String path, final FileMembers.Key key, final String comment) {
    final var state = new DocQuestions.State(comment, "{}", software.sava.typesafe.JsonContent.object().build(), path);
    return new DocCorpus.Row(id, "repo", path, key, "method", comment, comment.length(), 1, state, null, null, 0.0, Double.NaN, List.of());
  }

  @Test
  void configAndLabels(@TempDir final Path dir) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> DocExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> DocExperiment.Config.parse(new String[]{"checkouts", "c", "--repos", "r"}));
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a,b", "--mode", "replay", "--per-repo", "7", "--sample", "9"});
    assertEquals(Path.of("build/experiments/docs"), config.out());
    assertEquals(Path.of("build/experiments/docs/recordings"), config.recordings());
    assertEquals(7, config.perRepo());
    assertEquals(9, config.sample());
    assertNull(config.labelsTop());
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, DocExperiment.runnerFor(config).client().mode());
    assertEquals("", DocExperiment.fmt(Double.NaN));
    assertEquals("0.500", DocExperiment.fmt(0.5));
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "row_id\tlabel\na\tContradicted\nb\tnot checkable\nc\t\nd\tnot-checkable\n");
    assertEquals(Map.of("a", "contradicted", "b", "not_checkable", "d", "not_checkable"), DocLabels.read(file).byKey());
    assertEquals(3, DocLabels.read(file).size());
    Files.writeString(file, "row_id\tlabel\nx\tmaybe\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file)).getMessage().contains("line 2"));
    Files.writeString(file, "id\tlabel\n");
    assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file));
    Files.writeString(file, "");
    assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file));
    assertThrows(java.io.UncheckedIOException.class, () -> DocLabels.read(dir.resolve("absent")));
  }
}
