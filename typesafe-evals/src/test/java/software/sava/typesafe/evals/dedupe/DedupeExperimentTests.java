package software.sava.typesafe.evals.dedupe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.ModelCard;
import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;
import software.sava.typesafe.evals.jev.JevRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

final class DedupeExperimentTests {

  /// The named workflow in miniature: three findings at one line that say the same thing,
  /// two distinct defects at another line, two CONVENTIONS findings that agree, and one
  /// README finding whose request the stub fails.
  private static final String JOURNAL = """
      {"type":"result","key":"v2:1","agentId":"a1","result":{"findings":[{"file":"sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":266,"severity":"high","summary":"sibling overload is undocumented (same defect)","failure_scenario":"reader sees one overload"},{"file":"sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":435,"severity":"medium","summary":"javadoc block sits below commented-out lines","failure_scenario":"uncommenting drops it"},{"file":"CONVENTIONS.md","line":64,"severity":"low","summary":"not indexed in CONVENTIONS (same defect)","failure_scenario":null}]}}
      {"type":"result","key":"v2:2","agentId":"a2","result":{"findings":[{"file":"sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":266,"severity":"medium","summary":"the single-table overload lacks javadoc (same defect)","failure_scenario":null},{"file":"sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":435,"severity":"medium","summary":"javadoc omits the mutation of lookup table metas","failure_scenario":"caller is surprised"},{"file":"CONVENTIONS.md","line":64,"severity":"low","summary":"array ownership family not extended (same defect)","failure_scenario":null}]}}
      {"type":"result","key":"v2:3","agentId":"a3","result":{"findings":[{"file":"sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":266,"severity":"medium","summary":"only one createTx overload documents the mutation (same defect)","failure_scenario":null},{"file":"README.md","line":9,"severity":"nit","summary":"stale link in the intro"},{"file":"README.md","line":9,"severity":"nit","summary":"broken anchor in the intro FAIL"}]}}
      """;

  private static final String OTHER = """
      {"type":"result","key":"v2:9","agentId":"b1","result":{"findings":[{"file":"README.md","line":1,"severity":"nit","summary":"typo in title"},{"file":"README.md","line":40,"severity":"nit","summary":"stale link"}]}}
      """;

  /// Merges any pair whose two summaries both carry "(same defect)"; fails any pair whose
  /// state carries "FAIL"; everything else is level 0. Keeps every state it was sent.
  private static final class StubClient implements TypeSafeClient {

    final List<String> states = new ArrayList<>();

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      states.add(state);
      if (state.contains("FAIL")) {
        return CompletableFuture.failedFuture(new IllegalStateException("stub failure"));
      }
      final int marks = state.split("\\(same defect\\)", -1).length - 1;
      final var body = marks == 2
          ? "{\"model\":\"jev-stub\",\"answers\":{\"relation\":{\"type\":\"score\",\"score\":1.9,\"confidence\":0.9,\"probabilities\":{\"0\":0.0,\"1\":0.1,\"2\":0.9},\"legend\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"c\"}},\"claims_new_evidence\":{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{\"input_tokens\":50,\"output_tokens\":2}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"relation\":{\"type\":\"score\",\"score\":0.1,\"confidence\":0.9,\"probabilities\":{\"0\":0.9,\"1\":0.1,\"2\":0.0},\"legend\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"c\"}},\"claims_new_evidence\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{\"input_tokens\":50,\"output_tokens\":2}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), "req_stub"));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static Path project(final Path dir) throws Exception {
    final var project = dir.resolve("-Users-jim-src-sava");
    final var named = project.resolve("s1/subagents/workflows/" + DedupeExperiment.NAMED_WORKFLOW);
    Files.createDirectories(named);
    Files.writeString(named.resolve("journal.jsonl"), JOURNAL);
    final var other = project.resolve("s1/subagents/workflows/wf_00000000-001");
    Files.createDirectories(other);
    Files.writeString(other.resolve("journal.jsonl"), OTHER);
    return project;
  }

  // named workflow: Transaction.java 5 findings -> 10 pairs (4 exact-line: 3 at 266, 1 at 435),
  // CONVENTIONS 2 -> 1 pair (exact-line), README 2 -> 1 pair (exact-line); other: README 2 -> 1 pair
  private static final DedupeExperiment.Summary EXPECTED = new DedupeExperiment.Summary(2, 11, 13, 6, 7, 12);

  @Test
  void corpusModeWritesTheSheetsAndNoScores(@TempDir final Path dir) throws Exception {
    final var project = project(dir);
    final var out = dir.resolve("out");
    final var config = DedupeExperiment.Config.parse(new String[]{
        "--projects", project + "," + dir.resolve("absent"), "--out", out.toString(), "--mode", "corpus", "--top-jaccard", "1"});
    assertEquals(EXPECTED, DedupeExperiment.run(config, null));
    assertTrue(Files.isRegularFile(out.resolve("findings.tsv")));
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals("pair_id\tlabel\tneeded_source\tnotes\tworkflow\tbasename\tline_a\tline_b\ttext_a\tscenario_a\ttext_b\tscenario_b", sheet.getFirst());
    assertEquals(8, sheet.size(), "6 exact-line pairs plus the single top-jaccard pair");
    assertFalse(Files.exists(out.resolve("jev-prose.tsv")));
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 2 | 11 | 13 | 6 | 7 | 12 |"), report);
    assertTrue(report.contains("No labels yet"));
    assertFalse(report.contains("## Jev"));
    assertFalse(report.contains("## Named case"));
  }

  @Test
  void recordModeScoresClustersAndComputesBarsWhenLabeled(@TempDir final Path dir) throws Exception {
    final var project = project(dir);
    final var out = dir.resolve("out");
    final var labels = dir.resolve("labels.tsv");
    final var stub = new StubClient();
    final var runner = new JevRunner(RecordingTypeSafeClient.record(stub, dir.resolve("rec")), 2);
    var config = DedupeExperiment.Config.parse(new String[]{"--projects", project.toString(), "--out", out.toString(),
        "--top-jaccard", "2", "--labels", labels.toString()});
    assertEquals("record", config.mode());
    assertEquals(4, config.concurrency());
    assertEquals(out.resolve("recordings"), config.recordings());
    assertEquals(labels, config.labels());

    final var first = DedupeExperiment.run(config, runner);
    assertEquals(8, first.selected());
    final var prose = Files.readAllLines(out.resolve("jev-prose.tsv"));
    assertEquals("pair_id\tlevel\tp_same\tp_narrowed\tscore\tconfidence\tnew_evidence\texact_line\tjaccard", prose.getFirst());
    assertEquals(13, prose.size(), "the 12 named-workflow pairs (which include every selected pair here), once each, plus the header");
    final var failedRow = prose.stream().filter(line -> line.contains("FAIL") || line.split("\t", -1)[1].isEmpty()).toList();
    assertEquals(1, failedRow.size(), "the failed pair keeps its row with empty score cells: " + prose);
    // {stale, link, in, the, intro} vs {broken, anchor, in, the, intro, fail}: 3 shared of 8
    assertTrue(failedRow.getFirst().endsWith("\ttrue\t" + DedupeExperiment.fmt(3.0 / 8)), failedRow.getFirst());
    assertTrue(prose.stream().anyMatch(line -> line.contains("\t2\t0.900\t0.100\t1.900\t0.900\t0.100\ttrue\t")), "scores are written at three decimals: " + prose);
    assertTrue(Files.isRegularFile(out.resolve("jev-ablation.tsv")));
    // both arms were sent: the ablation arm carries the code-computed facts, the prose arm does not
    assertTrue(stub.states.stream().anyMatch(s -> s.contains("\"line_delta\":") && s.contains("\"same_file\":true") && s.contains("\"cosine\":")));
    assertTrue(stub.states.stream().anyMatch(s -> !s.contains("line_delta")));
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("## Jev"), report);
    assertTrue(report.contains("Requests 24 (22 answered), input tokens 1100, cost $0.0000."), report);
    assertEquals(new JevRunner.Totals(24, 22, 1100, 44, 0, 24), first.spend());
    assertTrue(report.contains("pre-registered rule: level 2 at confidence >= 0.8"), report);
    assertTrue(report.contains("post-hoc rule: same underlying defect, P(different) <= 0.2"), report);
    assertEquals(2, report.split("- cluster of 3:", -1).length - 1, "both rules group the three same-defect findings: " + report);
    assertTrue(report.contains("- cluster of 3:"), report);
    assertTrue(report.contains("- cluster of 2:"), report);
    assertTrue(report.contains("Transaction.java:266"), report);
    assertTrue(report.contains("CONVENTIONS.md:64"), report);
    assertTrue(report.contains("- singletons: 4"), report);
    assertTrue(report.contains("9 findings -> 6 groups"), report);
    assertTrue(report.contains("No labels yet"), "a labels path that does not exist yet means not yet");

    // label from the sheet: the same-defect pairs are 2, everything else 0
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    final var labeled = new StringBuilder(sheet.getFirst()).append('\n');
    for (final var line : sheet.subList(1, sheet.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = cells[8].contains("(same defect)") && cells[10].contains("(same defect)") ? "2" : "0";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(labels, labeled.toString());
    config = DedupeExperiment.Config.parse(new String[]{"--projects", project.toString(), "--out", out.toString(),
        "--mode", "replay", "--recordings", dir.resolve("rec").toString(), "--labels", labels.toString(), "--top-jaccard", "2"});
    // no runner passed: the mode builds the replay-only runner, which needs no key
    DedupeExperiment.run(config, null);
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Recording hits 22, misses 2;"), "11 successes per arm replay; the failed pair misses again: " + report);
    assertTrue(report.contains("## Bars (prose arm, 7 labeled pairs"), report);
    assertTrue(report.contains("| merge safety (gold-0 pairs merged at >= 0.8) | 0 | 0 | yes |"), report);
    assertTrue(report.contains("| suppression (recall on gold 2 at >= 0.8) | 1.000 | >= 0.700 | yes |"), report);
    assertTrue(report.contains("gold \\ predicted"), report);
    assertTrue(report.contains("### Post-hoc same-defect rule"), report);
    assertTrue(report.contains("| 4 | 1.000 | 1.000 | 0 |"), "the three 266 pairs and the CONVENTIONS pair are grouped, none wrong: " + report);
    assertTrue(report.contains("| yes |"), report);
    assertTrue(report.contains("| middle recall (gold 1 as 1) | 0.000 | >= 0.500 | NO |"), "no gold-1 pairs were labeled, so that bar fails: " + report);
    assertTrue(report.contains("| **keep** | **false** | all | |"), report);
    assertTrue(report.contains("## Bars (ablation arm, 7 labeled pairs)"), report);
    assertEquals(2, report.split("\\| \\*\\*keep\\*\\* \\|", -1).length - 1, "one keep line per arm");
    assertTrue(report.indexOf("| **keep** |") < report.indexOf("## Bars (ablation arm"), "the prose table precedes the ablation heading");
  }

  @Test
  void runnerForFollowsTheMode(@TempDir final Path dir) {
    final var replay = DedupeExperiment.runnerFor(new DedupeExperiment.Config(List.of(dir), dir, dir, "replay", 3, 1, "w", null));
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, replay.client().mode());
    final var record = new DedupeExperiment.Config(List.of(dir), dir, dir, "record", 3, 1, "w", null);
    if (System.getenv(TypeSafeClient.API_KEY_ENV) == null) {
      assertThrows(IllegalStateException.class, () -> DedupeExperiment.runnerFor(record));
    } else {
      assertEquals(RecordingTypeSafeClient.Mode.RECORD, DedupeExperiment.runnerFor(record).client().mode());
    }
  }

  @Test
  void helpersHandleMissingScoresAndLabels(@TempDir final Path dir) throws Exception {
    final var a = new CorpusFinding("w#a", "w", "a", "findings", "t1 (same defect)", "s", null, "F.java", "F.java", 1, null, null);
    final var b = new CorpusFinding("w#b", "w", "b", "findings", "t2 (same defect)", null, null, "F.java", "F.java", 1, null, null);
    final var c = new CorpusFinding("w#c", "w", "c", "findings", "t3", null, null, "F.java", "F.java", 5, null, null);
    final var pairs = FindingPair.block(List.of(a, b, c));
    final var byId = new LinkedHashMap<String, FindingPair>();
    for (final var pair : pairs) {
      byId.put(pair.id(), pair);
    }
    final var merged = DedupeBarsTests.score(0.0, 0.1, 0.9, 0.9, 0.1);
    final var lowConfidence = DedupeBarsTests.score(0.0, 0.1, 0.9, 0.5, 0.1);
    final var scores = Map.of("w#a | w#b", merged, "w#a | w#c", lowConfidence);
    // w#b | w#c has no score: never merged; w#a | w#c is level 2 below the confidence: never merged
    final var clusters = DedupeExperiment.cluster(List.of(a, b, c), pairs, scores, s -> s.merges(DedupeBars.MERGE_CONFIDENCE));
    assertEquals(List.of(List.of("w#a", "w#b"), List.of("w#c")), clusters.clusters());
    final var grouped = DedupeExperiment.cluster(List.of(a, b, c), pairs, scores, s -> s.sameDefect(DedupeBars.MAX_DIFFERENT));
    assertEquals(List.of(List.of("w#a", "w#b", "w#c")), grouped.clusters(), "the low-confidence level-2 pair still has P(different) = 0");

    final var labels = new Labels(Map.of("w#a | w#b", 2, "w#b | w#c", 0, "unknown", 1));
    final var rows = DedupeExperiment.rows(byId, scores, labels);
    assertEquals(List.of("w#a | w#b"), rows.stream().map(DedupeBars.Row::pairId).toList(), "labeled and scored only");
    assertEquals(2, rows.getFirst().gold());
    assertTrue(rows.getFirst().exactLine());

    assertNull(DedupeExperiment.readLabels(null));
    assertNull(DedupeExperiment.readLabels(dir.resolve("missing.tsv")));
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "pair_id\tlabel\nx\t1\n");
    assertEquals(1, DedupeExperiment.readLabels(file).get("x"));

    assertTrue(DedupeExperiment.request(pairs.getFirst(), false).state().toJson().startsWith("{\"a\":{\"summary\":\"t1 (same defect)\",\"failure_scenario\":\"s\"},\"b\":"));
    assertFalse(DedupeExperiment.request(pairs.getFirst(), false).state().toJson().contains("line_delta"));
    assertTrue(DedupeExperiment.request(pairs.getFirst(), true).state().toJson().endsWith("\"same_file\":true,\"line_delta\":0,\"cosine\":" + pairs.getFirst().jaccard() + "}"));
  }

  @Test
  void configValidation() {
    assertThrows(IllegalArgumentException.class, () -> DedupeExperiment.Config.parse(new String[]{}));
    final var notAnOption = assertThrows(IllegalArgumentException.class, () -> DedupeExperiment.Config.parse(new String[]{"projects", "x"}));
    assertEquals("expected --option value, got projects", notAnOption.getMessage());
    final var config = DedupeExperiment.Config.parse(new String[]{"--projects", " a , ,b ", "--recordings", "r", "--concurrency", "2",
        "--named", "wf_x", "--labels", "l.tsv", "--mode", "replay", "--dangling"});
    assertEquals(List.of(Path.of("a"), Path.of("b")), config.projects());
    assertEquals(Path.of("build/experiments/dedupe"), config.out());
    assertEquals(Path.of("r"), config.recordings());
    assertEquals(2, config.concurrency());
    assertEquals(DedupeExperiment.TOP_JACCARD, config.topJaccard());
    assertEquals("wf_x", config.namedWorkflow());
    assertEquals(Path.of("l.tsv"), config.labels());
    assertEquals("replay", config.mode());
  }
}
