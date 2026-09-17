package software.sava.typesafe.evals.dedupe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.evals.text.Jaccard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class JournalCorpusTests {

  /// One journal exercising every branch: a finder with two shapes, a merge re-emission, a
  /// verdict, a bare-string result, a string-valued findings key, an all-clear, concerns that
  /// borrow the carrier's file, breakages with camelCase fixes, and a prose `where`.
  private static final String JOURNAL = """
      {"type":"launched"}
      {"type":"started","key":"v2:1","agentId":"a1"}
      {"type":"result","key":"v2:1","agentId":"a1","result":{"dimensionSummary":"x","findings":[{"file":"/Users/jim/src/sava/sava-core/src/main/java/software/sava/core/tx/Transaction.java","line":266,"category":"doc-inaccuracy","severity":"high","summary":"The single-table overload is undocumented","failure_scenario":"A caller reads the javadoc of one overload only","evidence":"Transaction.java:266","suggested_fix":"Document it"},{"file":"CONVENTIONS.md","line":64,"category":"process","severity":"low","summary":"Not added to CONVENTIONS.md","failure_scenario":null,"evidence":"CONVENTIONS.md:64","suggested_fix":"Add it"}]}}
      {"type":"result","key":"v2:2","agentId":"a2","result":{"findings":[{"id":"F01","sources":["fuzz"],"file":"X.java","line":1,"summary":"merged restatement"}],"dropped":[]}}
      {"type":"result","key":"v2:3","agentId":"a3","result":{"refuted":false,"confidence":"high","reason":"verified","correction":"","findings":"[]"}}
      {"type":"result","key":"v2:4","agentId":"a4","result":"a bare string result"}
      {"type":"result","key":"v2:5","agentId":"a5","result":{"checked":[],"findings":[{"file":"Dockerfile","line":92,"severity":"info","claim":"Checked: the loop is fine","evidence":"e"},{"file":"Dockerfile","line":93,"severity":"minor","claim":"Loop misses the last entry","evidence":"e"}]}}
      {"type":"result","key":"v2:6","agentId":"a6","result":{"file":"sava-core/src/main/java/software/sava/core/tx/V1Transaction.java","nonConflictConcerns":[{"location":"`static Transaction createTx(...)` return statement","problem":"HARD COMPILE ERROR once hunk 1 is resolved","fix":"replace the call"}],"breakages":[{"file":"sava-core/config/pitest/tx-accepted.csv","symbol":"TransactionSkeletonRecord","problem":"The merge deletes the record","severity":"suspicious","suggestedFix":"regenerate","line":24}]}}
      {"type":"result","key":"v2:7","agentId":"a7","result":{"lens":"fuzz","findings":[{"severity":"should-fix","where":"/private/tmp/claude-501/-Users-jim-src-sava/0dc415f0-48f0-4712-953b-611118c193fe/scratchpad/evidence/SHA256SUMS.txt line 1 (and elsewhere)","claim_or_defect":"The manifest fails its own verification","evidence":"line 1 is the empty hash","suggested_fix":"regenerate"}]}}
      {"type":"result","key":"v2:8","agentId":"a8","result":{"results":[{"case":"a","outcome":"pass","detail":"d"}]}}
      {"type":"failed","key":"v2:9","agentId":"a9"}
      {"type":"result","key":"v2:10","agentId":"a10","result":{"findings":[{"file":"B.java","line":1,"severity":"nit"}]}}

      {"type":"started","key":"v2:11","agentId":"a11","result":{"findings":[{"file":"C.java","line":1,"summary":"not a result record"}]}}
      {"type":"result","key":"v2:12","agentId":"a12","result":{"findings":[{"id":"F02","file":"D.java","line":2,"summary":"has an id but no sources"},{"sources":["x"],"file":"D.java","line":3,"summary":"has sources but no id"},{"file":"D.java","line":4,"severity":"info","claim":"Not an all-clear despite info"},{"file":"D.java","line":5,"severity":"minor","claim":"Checked: but minor severity keeps it"}]}}
      """;

  @Test
  void findsJournalsUnderTheProjectLayout(@TempDir final Path dir) throws Exception {
    final var project = dir.resolve("-Users-jim-src-sava");
    final var run = project.resolve("session-1/subagents/workflows/wf_ab12cd34-e5f");
    Files.createDirectories(run);
    Files.writeString(run.resolve("journal.jsonl"), JOURNAL);
    Files.createDirectories(project.resolve("session-1/subagents/workflows/not-a-run"));
    Files.writeString(project.resolve("session-1/subagents/workflows/not-a-run/journal.jsonl"), "{}");
    Files.createDirectories(project.resolve("session-2/subagents/workflows/wf_00000000-000"));
    Files.createDirectories(project.resolve("session-3"));
    final var journals = JournalCorpus.journals(List.of(project, dir.resolve("absent")));
    assertEquals(List.of(run.resolve("journal.jsonl")), journals);

    final var findings = JournalCorpus.read(journals);
    assertEquals(10, findings.size(), findings.stream().map(CorpusFinding::id).toList().toString());
    assertEquals(List.of("has an id but no sources", "has sources but no id", "Not an all-clear despite info", "Checked: but minor severity keeps it"),
        findings.subList(6, 10).stream().map(CorpusFinding::text).toList(), "only id+sources and info+Checked: are excluded");
    assertTrue(findings.stream().allMatch(f -> "wf_ab12cd34-e5f".equals(f.workflow())));

    final var first = findings.getFirst();
    assertEquals("wf_ab12cd34-e5f#a1#findings#0", first.id());
    assertEquals("sava/sava-core/src/main/java/software/sava/core/tx/Transaction.java", first.file(), "checkout paths are scrubbed");
    assertEquals("Transaction.java", first.basename());
    assertEquals(266, first.line());
    assertEquals("The single-table overload is undocumented", first.text());
    assertEquals("A caller reads the javadoc of one overload only", first.scenario());
    assertEquals("The single-table overload is undocumented A caller reads the javadoc of one overload only", first.prose());
    assertEquals("high", first.severity());
    assertEquals("doc-inaccuracy", first.category());

    final var second = findings.get(1);
    assertEquals("CONVENTIONS.md", second.basename());
    assertNull(second.scenario());
    assertEquals("Not added to CONVENTIONS.md", second.prose());

    final var loop = findings.get(2);
    assertEquals("Loop misses the last entry", loop.text(), "the all-clear before it is dropped");
    assertEquals("Dockerfile", loop.basename());
    assertEquals(93, loop.line());

    final var concern = findings.get(3);
    assertEquals("nonConflictConcerns", concern.listKey());
    assertEquals("HARD COMPILE ERROR once hunk 1 is resolved", concern.text());
    assertEquals("sava-core/src/main/java/software/sava/core/tx/V1Transaction.java", concern.file(), "the carrier's file is lifted onto the concern");
    assertEquals("V1Transaction.java", concern.basename());
    assertNull(concern.line());

    final var breakage = findings.get(4);
    assertEquals("breakages", breakage.listKey());
    assertEquals("tx-accepted.csv", breakage.basename());
    assertEquals(24, breakage.line());
    assertEquals("suspicious", breakage.severity());

    final var prose = findings.get(5);
    assertEquals("The manifest fails its own verification", prose.text());
    assertEquals("<scratchpad>", prose.file(), "a scratchpad path inside prose is scrubbed whole");
    assertEquals("<scratchpad>", prose.basename());
  }

  @Test
  void pathTokensAndBasenames() {
    assertEquals("sava-core/src/main/java/A.java", JournalCorpus.firstPathToken("see sava-core/src/main/java/A.java:12 and B.md"));
    assertEquals("HARDENING.md", JournalCorpus.firstPathToken("HARDENING.md line 5"));
    assertNull(JournalCorpus.firstPathToken("no path here"));
    assertNull(JournalCorpus.firstPathToken(null));
    assertEquals("A.java", JournalCorpus.basename("x/y/A.java"));
    assertEquals("A.java", JournalCorpus.basename("A.java"));
    assertEquals("A.java", JournalCorpus.basename("/A.java"));
    assertEquals("y", JournalCorpus.basename("x/y/"));
    assertNull(JournalCorpus.basename("/"));
    assertNull(JournalCorpus.basename(null));
  }

  @Test
  void pairsAreBlockedByWorkflowAndBasename() {
    final var a = finding("w1", "a", "Transaction.java", 266, "the single table overload is undocumented", "one overload only");
    final var b = finding("w1", "b", "Transaction.java", 266, "the sibling single table overload has no javadoc", null);
    final var c = finding("w1", "c", "Transaction.java", 435, "comment placement drops the block", "uncommenting hides it");
    final var d = finding("w1", "d", "CONVENTIONS.md", 64, "not indexed in CONVENTIONS", null);
    final var e = finding("w2", "e", "Transaction.java", 266, "the single table overload is undocumented", "one overload only");
    final var noFile = new CorpusFinding("w1#f", "w1", "f", "findings", "t", null, null, null, null, null, null, null);
    final var pairs = FindingPair.block(List.of(a, b, c, d, e, noFile));
    assertEquals(List.of("w1#a | w1#b", "w1#a | w1#c", "w1#b | w1#c"), pairs.stream().map(FindingPair::id).toList());
    final var ab = pairs.getFirst();
    assertTrue(ab.exactLine());
    assertEquals(0, ab.lineDelta());
    assertEquals(Jaccard.similarity(a.prose(), b.prose()), ab.jaccard());
    assertTrue(ab.jaccard() > 0.2 && ab.jaccard() < 1.0, "jaccard " + ab.jaccard());
    final var ac = pairs.get(1);
    assertFalse(ac.exactLine());
    assertEquals(169, ac.lineDelta());
    final var g = new CorpusFinding("w1#g", "w1", "g", "findings", "t", null, null, "Transaction.java", "Transaction.java", null, null, null);
    final var noLineB = new FindingPair("x", a, g, 0.0);
    assertFalse(noLineB.exactLine());
    assertNull(noLineB.lineDelta());
    final var noLineA = new FindingPair("y", g, a, 0.0);
    assertFalse(noLineA.exactLine());
    assertNull(noLineA.lineDelta());
    final var noLines = new FindingPair("z", g, g, 1.0);
    assertFalse(noLines.exactLine());
    assertNull(noLines.lineDelta());
    final var otherNoFile = new CorpusFinding("w1#h", "w1", "h", "findings", "t", null, null, null, null, null, null, null);
    assertEquals(List.of(), FindingPair.block(List.of(noFile, otherNoFile)), "findings without a basename never pair, even with each other");
  }

  private static CorpusFinding finding(final String workflow, final String agent, final String file, final Integer line, final String text, final String scenario) {
    return new CorpusFinding(workflow + '#' + agent, workflow, agent, "findings", text, scenario, null, file, JournalCorpus.basename(file), line, "medium", "process");
  }
}
