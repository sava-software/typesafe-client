package software.sava.typesafe.evals.drift;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.evals.docs.FileMembers;
import software.sava.typesafe.evals.docs.HistoryMiner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class DriftCorpusAndBarsTests {

  private static final FileMembers.Key KEY = new FileMembers.Key("T", "compute", "int");
  private static final String LONG = "Computes the cached total from the entries, skipping the empty ones entirely.";

  static HistoryMiner.Event event(final String commit, final String oldComment, final String newComment, final String oldBody, final String newBody) {
    final boolean commentChanged = !java.util.Objects.equals(oldComment, newComment);
    final boolean bodyChanged = !oldBody.equals(newBody);
    return new HistoryMiner.Event(commit, 0, "p/T.java", KEY, "method", commentChanged, bodyChanged, oldComment, newComment,
        "int compute(int x)", "int compute(int x)", oldBody, newBody);
  }

  @Test
  void eventsAreClassifiedAndFiltered() {
    final var events = List.of(
        event("c1", LONG, LONG, "int compute(int x) {\n  return cache[x];\n}", "int compute(int x) {\n  return total(x);\n}"),
        event("c2", LONG, "Recomputes the total from the entries every time it is asked, cache gone.", "int compute(int x) {\n  return cache[x];\n}", "int compute(int x) {\n  return total(x);\n}"),
        event("c3", LONG, LONG + " Also.", "{a}", "{b}"),
        event("c4", LONG, null, "{a}", "{b}"),
        event("c5", null, LONG, "{a}", "{b}"),
        event("c6", LONG, LONG, "{ a; }", "{  a;  }"),
        event("c7", "short one", "short one", "{a}", "{b}"),
        event("c8", LONG, "x", "{a}", "{a}")
    );
    final var result = DriftCorpus.rows("repo", events);
    assertEquals(List.of("BODY_ONLY", "CO_EDIT"), result.rows().stream().map(DriftCorpus.Row::klass).toList());
    final var reasons = result.excluded().stream().map(e -> e.reason() + "=" + e.count()).toList();
    assertEquals(List.of("comment-only event=1", "no comment before the change=1", "whitespace-only body change=1",
        "comment shorter than 40 as shown=1", "comment retouched (jaccard >= 0.9)=1", "comment removed=1"), reasons);
    final var bodyOnly = result.rows().get(0);
    assertEquals("repo#c1#p/T.java#T.compute(int)", bodyOnly.id());
    assertEquals("c1", bodyOnly.commit());
    assertEquals("method", bodyOnly.kind());
    assertEquals(LONG, bodyOnly.oldComment());
    assertEquals(LONG, bodyOnly.newComment());
    assertEquals(LONG.length(), bodyOnly.commentChars(), "no tag, no name in the prose: shown as is");
    assertEquals(2, bodyOnly.diffSize());
    assertEquals(" int compute(int x) {\n-  return cache[x];\n+  return total(x);\n }", bodyOnly.state().change());
    assertEquals("int compute(int x) {\n  return total(x);\n}", bodyOnly.state().newSource());
    assertEquals("{\"member_kind\":\"method\",\"old_lines\":3,\"new_lines\":3,\"new_lines_shown\":3,\"diff_lines_shown\":4,\"diff_lines_total\":4}",
        bodyOnly.state().sourceExtent().toJson());
    assertEquals("p/T.java", bodyOnly.state().filePath());
    assertEquals(0.0, bodyOnly.overlap(), "the comment names no identifier-like token");
    final var coEdit = result.rows().get(1);
    assertEquals("c2", coEdit.commit());
    assertEquals("Recomputes the total from the entries every time it is asked, cache gone.", coEdit.newComment());
  }

  @Test
  void overlapCountsIdentifiersThatOccurInChangedLines() {
    final var diff = " int m() {\n-  return cacheSize + MAX_ITEMS;\n+  return total;\n }";
    assertEquals(2.0 / 3.0, DriftCorpus.overlap("Uses `cacheSize` and the MAX_ITEMS bound; see `unrelatedThing`.", diff), 1e-12, "two of three identifiers sit in changed lines");
    assertEquals(0.0, DriftCorpus.overlap("plain words", diff));
    assertEquals(1.0, DriftCorpus.overlap("Reads `total`.", diff));
    assertEquals(0.0, DriftCorpus.overlap("Reads `total`.", " int m() {\n   return total;\n }"), "context lines do not count");
    assertEquals(Set.of("cacheSize", "MAX_ITEMS", "unrelatedThing"), DriftCorpus.identifiers("`cacheSize` and MAX_ITEMS; `Outer.unrelatedThing(x)` <METHOD>"));
    assertEquals("Widget", DriftCorpus.memberName(new FileMembers.Key("Outer$Widget", "<init>", "")));
    assertEquals("compute", DriftCorpus.memberName(KEY));
    assertEquals(0.0, DriftCorpus.overlap("`x`", "-a\n+b"), "a single-letter backticked span still counts as an identifier, and misses");
  }

  static DriftBars.Scored scored(final String id, final String klass, final double pAffected, final int diffSize, final double overlap) {
    final var state = new DriftQuestions.State("c", "-a\n+b", "n", software.sava.typesafe.JsonContent.object().build(), "p");
    final var row = new DriftCorpus.Row(id, "repo", "c", "p", KEY, "method", klass, "old", "new", 50, diffSize, overlap, state);
    final double rest = 1 - pAffected;
    return new DriftBars.Scored(row, new DriftScore(pAffected >= 0.5 ? "affected" : "unaffected", pAffected, rest * 0.8, rest * 0.2, 0.9));
  }

  @Test
  void barsSeparateRankAndDecide() {
    final var rows = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 10, 0.5),
        scored("b", DriftCorpus.CO_EDIT, 0.8, 4, 0.0),
        scored("c", DriftCorpus.CO_EDIT, 0.3, 2, 0.0),
        scored("d", DriftCorpus.BODY_ONLY, 0.7, 20, 1.0),
        scored("e", DriftCorpus.BODY_ONLY, 0.2, 2, 0.0),
        scored("f", DriftCorpus.BODY_ONLY, 0.1, 2, 0.0)
    );
    // CO_EDIT {0.9, 0.8, 0.3} over BODY_ONLY {0.7, 0.2, 0.1}: 0.9 wins 3, 0.8 wins 3, 0.3 wins 2 -> 8 of 9
    assertEquals(8.0 / 9.0, DriftBars.auroc(rows), 1e-12);
    final var ranks = DriftBars.rank01(rows, s -> (double) s.row().diffSize());
    assertEquals(1.0, ranks.get("d"), "the largest diff ranks last, scaled to 1");
    assertEquals(0.8, ranks.get("a"), 1e-12);
    assertEquals(0.2, ranks.get("c"), 1e-12, "c, e, f tie at 2 lines: mean rank of 0,1,2 is 1, over n-1 = 5");
    assertEquals(ranks.get("c"), ranks.get("e"));
    final var baseline = DriftBars.baselineScores(rows);
    assertEquals(1.0, baseline.get("d"), "largest diff and full overlap");
    assertTrue(baseline.get("a") >= 0.8);
    assertTrue(DriftBars.baselineAuroc(rows) < DriftBars.auroc(rows));
    assertEquals(List.of("d", "e", "f"), DriftBars.topBodyOnly(rows).stream().map(s -> s.row().id()).toList());
    final double r = DriftBars.sizeCorrelation(rows);
    assertTrue(r > 0 && r < 0.8, "" + r);
    var verdict = DriftBars.verdict(rows, Map.of(), Map.of());
    assertEquals(8.0 / 9.0, verdict.auroc(), 1e-12);
    assertEquals("value bar pending", verdict.decision());
    assertEquals(-1, verdict.noiseRelated());
    assertTrue(Double.isNaN(verdict.ceiling()));
    assertEquals(4, verdict.checks().size());
    assertTrue(verdict.interval()[0] <= verdict.auroc() && verdict.auroc() <= verdict.interval()[1]);
    verdict = DriftBars.verdict(rows, Map.of("d", "needed_update", "e", "no_update_needed"), Map.of("a", "related", "b", "unrelated"));
    assertEquals("nothing missed", verdict.decision(), "one missed update of the five required");
    assertEquals(1.0, verdict.checks().get(3).value());
    assertEquals(1, verdict.noiseRelated());
    assertEquals(2, verdict.noiseRead());
    assertEquals(0.75, verdict.ceiling(), 1e-12, "half the positives unrelated: a perfect judge tops out at 1 - 0.5 * 0.5");
    final var many = new java.util.ArrayList<DriftBars.Scored>();
    final var labels = new java.util.HashMap<String, String>();
    for (int i = 0; i < 6; i++) {
      many.add(scored("co" + i, DriftCorpus.CO_EDIT, 0.95, 3, 0.0));
      many.add(scored("bo" + i, DriftCorpus.BODY_ONLY, 0.9 - i * 0.1, 3, 0.0));
      labels.put("bo" + i, i < 5 ? "needed_update" : "cannot_tell");
    }
    verdict = DriftBars.verdict(many, labels, Map.of());
    assertEquals("keep", verdict.decision());
    assertEquals(1.0, verdict.auroc());
    assertEquals(0.5, verdict.baselineAuroc(), "identical sizes and overlaps: the baseline is a coin");
    assertEquals(0.0, verdict.sizeCorrelation(), "constant size has no variance");
  }

  @Test
  void decisionTablePrecedence() {
    final var flat = List.of(scored("a", DriftCorpus.CO_EDIT, 0.5, 1, 0.0), scored("b", DriftCorpus.BODY_ONLY, 0.5, 1, 0.0));
    assertEquals("kill: separation", DriftBars.verdict(flat, Map.of(), Map.of()).decision());
    final var wordy = List.of(scored("a", DriftCorpus.CO_EDIT, 0.9, 3, 1.0), scored("b", DriftCorpus.CO_EDIT, 0.8, 3, 1.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.1, 3, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.2, 3, 0.0));
    final var w = DriftBars.verdict(wordy, Map.of(), Map.of());
    assertEquals(0.0, w.sizeCorrelation(), "equal diff sizes: no variance, so the proxy rule stays quiet");
    assertEquals(1.0, w.baselineAuroc(), "overlap alone separates the classes");
    assertEquals("no lift", w.decision(), "the overlap baseline separates as well as Jev");
    final var proxy = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 90, 0.0), scored("b", DriftCorpus.CO_EDIT, 0.5, 50, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.1, 10, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.3, 30, 0.0));
    final var v = DriftBars.verdict(proxy, Map.of(), Map.of());
    assertTrue(v.sizeCorrelation() > 0.99);
    assertEquals("kill: proxy", v.decision(), "the proxy rule overrides separation");
    assertEquals(Double.NaN, DriftBars.verdict(List.of(), Map.of(), Map.of()).auroc());
    assertEquals("kill: separation", DriftBars.verdict(List.of(), Map.of(), Map.of()).decision());
  }

  @Test
  void noiseSampleIsSeededAndDrawsOnlyCoEdits() {
    final var rows = new java.util.ArrayList<DriftCorpus.Row>();
    for (int i = 0; i < 40; i++) {
      rows.add(scored(String.format("r%02d", i), i % 2 == 0 ? DriftCorpus.CO_EDIT : DriftCorpus.BODY_ONLY, 0.5, 1, 0.0).row());
    }
    final var sample = DriftBars.noiseSample(rows);
    assertEquals(20, sample.size(), "only twenty co-edits exist, fewer than the sample size");
    assertTrue(sample.stream().allMatch(r -> r.klass().equals(DriftCorpus.CO_EDIT)));
    assertEquals(sample, DriftBars.noiseSample(rows), "seeded");
    assertEquals(sample.stream().map(DriftCorpus.Row::id).sorted().toList(), sample.stream().map(DriftCorpus.Row::id).toList(), "id order");
    final var big = new java.util.ArrayList<DriftCorpus.Row>();
    for (int i = 0; i < 80; i++) {
      big.add(scored(String.format("r%02d", i), DriftCorpus.CO_EDIT, 0.5, 1, 0.0).row());
    }
    assertEquals(30, DriftBars.noiseSample(big).size());
    assertEquals(List.of(), DriftBars.noiseSample(List.of()));
  }

  @Test
  void constants() {
    assertEquals(0.75, DriftBars.SEPARATION_BAR);
    assertEquals(0.10, DriftBars.LIFT_BAR);
    assertEquals(0.8, DriftBars.CORRELATION_CEILING);
    assertEquals(30, DriftBars.TOP_N);
    assertEquals(5, DriftBars.VALUE_BAR);
    assertEquals(30, DriftBars.NOISE_SAMPLE);
    assertEquals(0.9, DriftCorpus.RETOUCH_JACCARD);
    assertEquals(40, DriftCorpus.MIN_COMMENT_CHARS);
    assertEquals(0.8499, DriftBars.round(0.84994));
  }
}
