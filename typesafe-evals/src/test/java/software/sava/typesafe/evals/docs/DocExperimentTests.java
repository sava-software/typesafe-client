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
          ? "{\"model\":\"jev-stub\",\"answers\":{\"agreement\":{\"type\":\"choice\",\"choice\":\"contradicted\",\"confidence\":0.9,\"probabilities\":{\"consistent\":0.05,\"contradicted\":0.9,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"agreement\":{\"type\":\"choice\",\"choice\":\"consistent\",\"confidence\":0.85,\"probabilities\":{\"consistent\":0.85,\"contradicted\":0.1,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
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
    assertEquals("row_id\trepo\tpath\tmember\tkind\tcomment_chars\tbody_lines\tswapped_from\tmismatch_real\tbaseline_real\tbaseline_swapped\tidentifiers_missing\tsample", rows.getFirst());
    assertEquals(4, rows.size());
    final var size = rows.stream().filter(l -> l.contains("#Widget.size(int)\t")).findFirst().orElseThrow();
    assertTrue(size.endsWith("\tNEXT\tstale-candidate"), "size's comment names NEXT, which its new body lacks: " + size);
    assertTrue(size.contains("\tWidget.step()\t1.000\t1.000\t"), "size's swap is step's comment; its own mismatch and baseline are 1.0: " + size);
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
    assertEquals("row_id\tarm\tchoice\tp_consistent\tp_contradicted\tp_not_checkable\tconfidence\tbaseline", jev.getFirst());
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
    assertTrue(report.contains("## Design 2: the real population (blind-labeled sample, a prevalence study)"), report);
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
    Files.writeString(dir.resolve("rec/0000keep.response.json"), "{}");
    config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo", "--out", out.toString(),
        "--recordings", dir.resolve("rec").toString(), "--sample", "3", "--mode", "replay",
        "--labels-sample", dir.resolve("sample-labels.tsv").toString(), "--labels-top", dir.resolve("top-labels.tsv").toString()});
    final var replayed = DocExperiment.run(config, null, commands());
    assertEquals(6, replayed.spend().hits());
    assertTrue(Files.exists(dir.resolve("rec/0000keep.response.json")), "replay mode prunes nothing: it may not own the directory");
    final var sv = replayed.sampleVerdict();
    assertNotNull(sv);
    assertEquals(3, sv.pooled().labeled());
    assertEquals(1, sv.pooled().contradicted());
    assertEquals(2, sv.pooled().consistent());
    assertEquals(1.0 / 3.0, sv.pooled().prevalence().rate(), 1e-12, "one contradicted of three decided");
    assertEquals(List.of("stale-candidate", "random"), sv.strata().stream().map(DocBars.Stratum::name).toList());
    assertEquals(1, sv.strata().get(0).contradicted(), "size is the stale candidate and is contradicted");
    assertEquals(0, sv.strata().get(1).contradicted());
    assertTrue(Double.isNaN(sv.auroc()), "one contradicted row is far below the twenty a ranking statistic needs");
    assertEquals(1, sv.topPrecision().count());
    assertEquals(3, sv.topPrecision().of());
    assertEquals(0, sv.confidentWrong().count());
    assertEquals(2, sv.confidentWrong().of());
    assertEquals(1.0, sv.binomialP(), 1e-12, "zero confident-wrong rows: at least zero is certain");
    assertEquals("kill: separation", replayed.verdict().decision(), "the table stops at separation before it reads the value bar");
    assertEquals(0.0, replayed.verdict().checks().get(3).value(), "three top rows read, none contradicted");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| pooled | 3 | 1 | 2 | 0 | 1 of 3 = 0.333 (0.061 to 0.792) |"), report);
    assertTrue(report.contains("| stale-candidate | 1 | 1 | 0 | 0 | 1 of 1 = 1.000 ("), report);
    assertTrue(report.contains("Precision of the top 20 REAL rows by P(contradicted): 1 of 3 = 0.333"), report);
    assertTrue(report.contains("Consistent rows at P(contradicted) >= 0.9: 0 of 2 = 0.000 (0.000 to 0.658); exact one-sided p against a 0.02 rate: 1.000."), report);
    assertTrue(report.contains("AUROC not reported: fewer than 20 rows are labeled contradicted"), report);
    assertTrue(report.contains("contradicted comments confirmed among the top 30 REAL rows (3 read) | 0.000 | >= 5 | NO |"), report);
    assertTrue(report.contains("| |r| <= 0.8 | yes |"), "a bar that is met reads yes: " + report);
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
    final var quiet = new HistoryMiner.Event("c1", 0, "a/T.java", key, "method", false, false, "doc", "doc", "s", "s", "{a}", "{a}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(quiet)), "a commit that changed neither side decides nothing");
    assertEquals(Set.of("repo#a/T.java#T.m()"), DocExperiment.staleCandidates("repo", rows, List.of(bodyOnly, quiet)),
        "and it does not clear what an earlier body-only commit decided");
    final var edited = new HistoryMiner.Event("c1", 0, "a/T.java", key, "method", true, true, "old", "doc", "s", "s", "{a}", "{b}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(edited)),
        "a commit that rewrote the comment with the body is not a body-only edit");
    final var dropped = new HistoryMiner.Event("c1", 0, "a/T.java", key, "method", false, true, "doc", null, "s", "s", "{a}", "{b}");
    assertEquals(Set.of(), DocExperiment.staleCandidates("repo", rows, List.of(dropped)),
        "a body edit that took the comment away leaves nothing that could be stale");
    assertEquals(List.of(), DocExperiment.sample(rows, Set.of(), 0));
    assertEquals(List.of(row), DocExperiment.sample(rows, Set.of("repo#a/T.java#T.m()"), 1), "candidates first");
    assertEquals(2, DocExperiment.sample(rows, Set.of(), 5).size(), "random fill stops when rows run out");
    assertEquals(DocExperiment.sample(rows, Set.of(), 1), DocExperiment.sample(rows, Set.of(), 1), "seeded, so reproducible");
    assertEquals(List.of(other), DocExperiment.sample(rows, Set.of(), 1), "with no candidate the fill is drawn, not taken in order");
    final var bothStale = Set.of("repo#a/T.java#T.m()", "repo#b/U.java#U.n()");
    assertEquals(1, DocExperiment.sample(rows, bothStale, 1).size(), "the candidate quota is the sample size, not the candidate count");
    assertEquals(List.of(row, other), DocExperiment.sample(List.of(other, row), bothStale, 2), "the sample is handed back in id order");
  }

  private static DocCorpus.Row row(final String id, final String path, final FileMembers.Key key, final String comment) {
    final var state = new DocQuestions.State(comment, "{}", software.sava.typesafe.JsonContent.object().build(), path);
    return new DocCorpus.Row(id, "repo", path, key, "method", comment, comment.length(), 1, state, null, null, 0.0, 0.0, Double.NaN, List.of());
  }

  @Test
  void configAndLabels(@TempDir final Path dir) throws Exception {
    assertThrows(IllegalArgumentException.class, () -> DocExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> DocExperiment.Config.parse(new String[]{"checkouts", "c", "--repos", "r"}));
    assertThrows(IllegalArgumentException.class, () -> DocExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "r", "mode", "replay"}),
        "every option is named with --, wherever it sits");
    assertEquals("record", DocExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "r", "--mode"}).mode(),
        "a trailing option with no value is not read");
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a,b", "--mode", "replay", "--sample", "9"});
    assertEquals(Path.of("build/experiments/docs"), config.out());
    assertEquals(Path.of("build/experiments/docs/recordings"), config.recordings());
    assertEquals(9, config.sample());
    assertNull(config.labelsTop());
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, DocExperiment.runnerFor(config).client().mode());
    assertEquals("", DocExperiment.fmt(Double.NaN));
    assertEquals("0.500", DocExperiment.fmt(0.5));
    assertEquals("n/a", DocExperiment.rate(DocBars.Rate.of(0, 0)));
    assertEquals("1 of 4 = 0.250 (0.046 to 0.699)", DocExperiment.rate(DocBars.Rate.of(1, 4)));
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "row_id\tlabel\na\tContradicted\nb\tnot checkable\nc\t\nd\tnot-checkable\n");
    assertEquals(Map.of("a", "contradicted", "b", "not_checkable", "d", "not_checkable"), DocLabels.read(file).byKey());
    assertEquals(3, DocLabels.read(file).size());
    Files.writeString(file, "row_id\tlabel\nx\tmaybe\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file)).getMessage().contains("line 2"));
    Files.writeString(file, "label\trow_id\nnot_checkable\tz\n");
    assertEquals(Map.of("z", "not_checkable"), DocLabels.read(file).byKey(), "the two columns may come in either order");
    Files.writeString(file, "row_id\tlabel\nshort\n");
    assertEquals(Map.of(), DocLabels.read(file).byKey(), "a line with no label cell at all is skipped, not read past its end");
    Files.writeString(file, "row_id\tnotes\na\tb\n");
    assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file), "a label column is required too");
    Files.writeString(file, "id\tlabel\n");
    assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file));
    Files.writeString(file, "");
    assertThrows(IllegalArgumentException.class, () -> DocLabels.read(file));
    assertThrows(java.io.UncheckedIOException.class, () -> DocLabels.read(dir.resolve("absent")));
  }

  private static final String SOLO = """
      package p;

      public final class Solo {

        /// A documented constant with enough words to count as a comment here.
        static final int SCALE = 2;

        /// Adds the two values given and returns their total, documented here.
        static int add(final int a, final int b) {
          return a + b;
        }

        /// Constructs a Solo from nothing at all, which is documented at length here.
        Solo() {
        }
      }
      """;

  /// One type whose three documented members are a field, a method, and a constructor: no two
  /// share a kind, so not one of them has a sibling to swap with.
  private static void soloCheckout(final Path checkouts) throws Exception {
    final var repo = checkouts.resolve("solo");
    final var src = repo.resolve("mod/src/main/java/p");
    Files.createDirectories(src);
    Files.writeString(src.resolve("Solo.java"), SOLO);
    git(repo, "init", "-q", "-b", "main");
    git(repo, "remote", "add", "origin", "git@github.com:test-org/solo.git");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "one");
  }

  /// Answers every request but the constructor's, whose future fails.
  private static final class PickyClient implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      if (request.state().toJson().contains("Constructs")) {
        return CompletableFuture.failedFuture(new IllegalStateException("the model refused this one"));
      }
      final var body = "{\"model\":\"jev-stub\",\"answers\":{\"agreement\":{\"type\":\"choice\",\"choice\":\"consistent\",\"confidence\":0.85,"
          + "\"probabilities\":{\"consistent\":0.85,\"contradicted\":0.1,\"not_checkable\":0.05}}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), "req_stub"));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static DocCorpus.Row rowWith(final String repo, final String path, final FileMembers.Key key, final boolean swap,
                                       final double baselineSwapped) {
    final var extent = software.sava.typesafe.JsonContent.object().build();
    final var real = new DocQuestions.State("the real comment", "int m() {\n}", extent, path);
    final var swapped = swap ? new DocQuestions.State("a sibling's comment", "int m() {\n}", extent, path) : null;
    return new DocCorpus.Row(repo + '#' + path + '#' + key, repo, path, key, "method", "the real comment", 16, 2,
        real, swapped, swap ? "T.other()" : null, 0.25, 0.5, baselineSwapped, List.of("Missing"));
  }

  @Test
  void theRowsTableStatesTheSwapBaselineAndTheStratum(@TempDir final Path dir) throws Exception {
    final var sampledStale = rowWith("repo", "p/A.java", new FileMembers.Key("A", "a", ""), true, 0.75);
    final var sampledPlain = rowWith("repo", "p/B.java", new FileMembers.Key("B", "b", ""), false, 0.75);
    final var staleOnly = rowWith("repo", "p/C.java", new FileMembers.Key("C", "c", ""), true, 0.75);
    final var neither = rowWith("repo", "p/D.java", new FileMembers.Key("D", "d", ""), true, 0.75);
    final var file = dir.resolve("rows.tsv");
    DocExperiment.writeRows(List.of(sampledStale, sampledPlain, staleOnly, neither), List.of(sampledStale, sampledPlain),
        Set.of(sampledStale.id(), staleOnly.id()), file);
    final var lines = Files.readAllLines(file);
    assertEquals(5, lines.size());
    assertEquals("repo#p/A.java#A.a()\trepo\tp/A.java\tA.a()\tmethod\t16\t2\tT.other()\t0.250\t0.500\t0.750\tMissing\tstale-candidate", lines.get(1));
    assertEquals("repo#p/B.java#B.b()\trepo\tp/B.java\tB.b()\tmethod\t16\t2\t\t0.250\t0.500\t\tMissing\trandom", lines.get(2),
        "a row with no swap has no swapped baseline to state, whatever number it carries");
    assertEquals("repo#p/C.java#C.c()\trepo\tp/C.java\tC.c()\tmethod\t16\t2\tT.other()\t0.250\t0.500\t0.750\tMissing\tstale-candidate-unsampled", lines.get(3));
    assertEquals("repo#p/D.java#D.d()\trepo\tp/D.java\tD.d()\tmethod\t16\t2\tT.other()\t0.250\t0.500\t0.750\tMissing\t", lines.get(4));
  }

  @Test
  void theScoresTableAndTheLabelingSheet(@TempDir final Path dir) throws Exception {
    final var first = rowWith("repo", "p/A.java", new FileMembers.Key("A", "a", ""), true, 0.75);
    final var second = rowWith("repo", "p/C.java", new FileMembers.Key("C", "c", ""), true, 0.75);
    final var real = new DocScore("contradicted", 0.05, 0.9, 0.05, 0.8);
    final var swapped = new DocScore("consistent", 0.9, 0.05, 0.05, 0.7);
    final var scores = dir.resolve("jev.tsv");
    DocExperiment.writeScores(List.of(new DocBars.Scored(first, real)), Map.of(), scores);
    assertEquals(List.of("row_id\tarm\tchoice\tp_consistent\tp_contradicted\tp_not_checkable\tconfidence\tbaseline",
            "repo#p/A.java#A.a()\treal\tcontradicted\t0.050\t0.900\t0.050\t0.800\t0.500"),
        Files.readAllLines(scores), "a row whose swapped arm was never scored writes one line");
    DocExperiment.writeScores(List.of(new DocBars.Scored(first, real)), Map.of(first.id() + "#swapped", swapped), scores);
    assertEquals("repo#p/A.java#A.a()\tswapped\tconsistent\t0.900\t0.050\t0.050\t0.700\t0.750", Files.readAllLines(scores).get(2));
    final var sheet = dir.resolve("sheet.tsv");
    DocExperiment.writeLabelingSheet(List.of(second, first), sheet);
    final var lines = Files.readAllLines(sheet);
    assertEquals(List.of(first.id(), second.id()), List.of(lines.get(1).split("\t")[0], lines.get(2).split("\t")[0]),
        "the sheet is in id order however the rows arrive");
    assertEquals("repo#p/A.java#A.a()\t\t\trepo\tA.a()\tthe real comment\tint m() { }", lines.get(1),
        "blind: the label and notes cells are empty and no score is shown");
  }

  @Test
  void theReportCountsMembersPerRepositoryAndStatesEveryBar(@TempDir final Path dir) throws Exception {
    final var swapped = rowWith("alpha", "p/A.java", new FileMembers.Key("A", "a", ""), true, 0.75);
    final var lone = rowWith("alpha", "p/B.java", new FileMembers.Key("B", "b", ""), false, Double.NaN);
    final var elsewhere = rowWith("beta", "p/C.java", new FileMembers.Key("C", "c", ""), true, 0.75);
    final var real = new DocScore("contradicted", 0.05, 0.9, 0.05, 0.8);
    final var other = new DocScore("consistent", 0.9, 0.05, 0.05, 0.7);
    final var scored = List.of(new DocBars.Scored(swapped, real), new DocBars.Scored(elsewhere, other));
    final var pairs = List.of(new DocBars.Pair(swapped, real, other));
    final var verdict = new DocBars.Verdict(0.9, new double[]{0.8, 1.0}, 0.5, 0.1,
        List.of(new DocBars.Check("a bar that is met", 0.9, ">= 0.85", true),
            new DocBars.Check("a bar that is not", 0.1, ">= 5", false)), "keep");
    final var pooled = new DocBars.Stratum("pooled", 3, 1, 2, 0, DocBars.Rate.of(1, 3));
    final var sampleVerdict = new DocBars.SampleVerdict(List.of(new DocBars.Stratum("stale-candidate", 3, 1, 2, 0, DocBars.Rate.of(1, 3))),
        pooled, DocBars.Rate.of(1, 2), DocBars.Rate.of(0, 2), 0.5, 0.77, new double[]{0.6, 0.9});
    final var summary = new DocExperiment.Summary(2, 0, 3, 2, 1, 1, new JevRunner.Totals(6, 6, 1800, 30, 2, 4), verdict, sampleVerdict);
    final var file = dir.resolve("report.md");
    DocExperiment.writeReport(file, summary, List.of(swapped, lone, elsewhere), pairs, scored, scored);
    final var report = Files.readString(file);
    assertTrue(report.contains("| alpha | 2 | 1 |\n| beta | 1 | 1 |\n"), "per repository: members, and how many of them have a swap: " + report);
    assertTrue(report.contains("| a bar that is met | 0.900 | >= 0.85 | yes |\n"), report);
    assertTrue(report.contains("| a bar that is not | 0.100 | >= 5 | NO |\n"), report);
    assertTrue(report.contains("## Top REAL rows by P(contradicted)\n\n| row | P(contradicted) | confidence | baseline |\n| --- | --- | --- | --- |\n"
        + "| alpha#p/A.java#A.a() | 0.900 | 0.800 | 0.500 |\n"), report);
    assertTrue(report.contains("AUROC (contradicted over consistent, no bar) 0.770 (bootstrap 95% 0.600 to 0.900).\n"), report);
    assertFalse(report.contains("AUROC not reported"), "an AUROC that was computed is reported");
  }

  @Test
  void withoutASampleNoHistoryIsMinedAndNothingIsMarked(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    Files.createDirectories(checkouts.resolve("bare"));
    final var out = dir.resolve("out");
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo,bare",
        "--out", out.toString(), "--mode", "corpus"});
    final var summary = DocExperiment.run(config, null, commands());
    assertEquals(1, summary.skipped(), "a directory that is not a checkout is skipped, not read");
    assertEquals(3, summary.rows());
    assertEquals(0, summary.staleCandidates(), "history is mined only when a sample is asked for");
    assertEquals(0, summary.sampled());
    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals(4, rows.size());
    assertTrue(rows.subList(1, 4).stream().allMatch(line -> line.endsWith("\t")), "every sample cell is empty: " + rows);
  }

  @Test
  void aRepositoryTheGateCannotCallPublicContributesNothing(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final CommandRunner closed = (command, directory) -> command.getFirst().equals("gh")
        ? "private\n"
        : ProcessCommandRunner.INSTANCE.run(command, directory);
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", dir.resolve("out").toString(), "--mode", "corpus", "--sample", "2"});
    final var summary = DocExperiment.run(config, null, closed);
    assertEquals(1, summary.skipped(), "the gate fails closed, so a private checkout is skipped");
    assertEquals(0, summary.rows(), "nothing from it becomes request state");
  }

  @Test
  void aCheckoutThatIsNotOnDiskIsSkippedBeforeGitIsAsked(@TempDir final Path dir) {
    final CommandRunner scripted = (command, directory) -> {
      if (command.getFirst().equals("gh")) {
        return "public\n";
      }
      return switch (command.get(3)) {
        case "remote" -> "git@github.com:test-org/ghost.git\n";
        case "ls-files" -> "mod/src/main/java/p/Ghost.java\n";
        case "show" -> "package p;\nclass Ghost {\n  /// Returns the one value this ghost has, documented at length here.\n"
            + "  int value() {\n    return 1;\n  }\n}\n";
        default -> throw new IllegalStateException(String.join(" ", command));
      };
    };
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", dir.resolve("absent").toString(), "--repos", "ghost",
        "--out", dir.resolve("out").toString(), "--mode", "corpus"});
    final var summary = DocExperiment.run(config, null, scripted);
    assertEquals(1, summary.skipped());
    assertEquals(0, summary.rows(), "a checkout that is not there is never read, however willing the runner is");
  }

  @Test
  void rowsWithoutASwapAndARequestThatFailedStillReport(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    soloCheckout(checkouts);
    final var out = dir.resolve("out");
    final var recordings = dir.resolve("rec");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new PickyClient(), recordings), 2);
    var config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "solo", "--out", out.toString(),
        "--recordings", recordings.toString(), "--sample", "1"});
    final var summary = DocExperiment.run(config, runner, commands());
    assertEquals(3, summary.rows(), "a field, a method, and a constructor");
    assertEquals(0, summary.withSwap(), "no two of them share a kind, so none has a sibling");
    assertEquals(new JevRunner.Totals(3, 2, 600, 10, 0, 3), summary.spend(), "one REAL request per row, and the constructor's failed");
    assertEquals(3, Files.readAllLines(out.resolve("jev.tsv")).size(), "the row whose request failed is not scored");

    final var ids = Files.readAllLines(out.resolve("rows.tsv")).stream().skip(1).map(line -> line.split("\t", -1)[0]).toList();
    assertEquals(3, ids.size());
    final var labels = new StringBuilder("row_id\tlabel\n");
    for (final var id : ids) {
      labels.append(id).append("\tconsistent\n");
    }
    Files.writeString(dir.resolve("sample-labels.tsv"), labels.toString());
    config = DocExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "solo", "--out", out.toString(),
        "--recordings", recordings.toString(), "--sample", "1", "--mode", "replay",
        "--labels-sample", dir.resolve("sample-labels.tsv").toString()});
    final var replayed = DocExperiment.run(config, null, commands());
    assertEquals(2, replayed.spend().hits(), "the two recorded answers replay; the failure was never recorded");
    assertEquals(1, replayed.sampleVerdict().pooled().labeled(),
        "Design 2 reads the sampled row only, though every row is labeled and two are scored");
  }

  @Test
  void recordModeNeverHandsBackAReplayOnlyClient(@TempDir final Path dir) {
    final var config = DocExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a",
        "--recordings", dir.resolve("rec").toString()});
    assertEquals("record", config.mode());
    try {
      assertEquals(RecordingTypeSafeClient.Mode.RECORD, DocExperiment.runnerFor(config).client().mode(),
          "record mode records over a live client");
    } catch (final IllegalStateException noKey) {
      assertTrue(noKey.getMessage().contains("API key"), noKey.getMessage());
    }
  }
}
