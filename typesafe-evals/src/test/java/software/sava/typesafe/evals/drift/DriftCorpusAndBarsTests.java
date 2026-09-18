package software.sava.typesafe.evals.drift;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.evals.docs.FileMembers;
import software.sava.typesafe.evals.docs.HistoryMiner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class DriftCorpusAndBarsTests {

  private static final FileMembers.Key KEY = new FileMembers.Key("T", "compute", "int");
  private static final String LONG = "Computes the cached total from the entries, skipping the empty ones entirely.";

  static HistoryMiner.Event event(final String commit, final FileMembers.Key key, final String oldComment, final String newComment,
                                  final String oldBody, final String newBody) {
    final boolean commentChanged = !java.util.Objects.equals(oldComment, newComment);
    final boolean bodyChanged = !oldBody.equals(newBody);
    return new HistoryMiner.Event(commit, 0, "p/T.java", key, "method", commentChanged, bodyChanged, oldComment, newComment,
        "int compute(int x)", "int compute(int x)", oldBody, newBody);
  }

  static HistoryMiner.Event event(final String commit, final String oldComment, final String newComment, final String oldBody, final String newBody) {
    return event(commit, KEY, oldComment, newComment, oldBody, newBody);
  }

  @Test
  void eventsAreClassifiedFilteredAndDedupedPerMember() {
    final var other = new FileMembers.Key("T", "other", "");
    final var events = List.of(
        event("c1", LONG, LONG, "int compute(int x) {\n  return cache[x];\n}", "int compute(int x) {\n  return total(x);\n}"),
        event("c2", other, LONG, "Recomputes the total from the entries every time it is asked, cache gone.", "int other() {\n  return cache;\n}", "int other() {\n  return total();\n}"),
        event("c3", LONG, LONG + " Also.", "{a}", "{b}"),
        event("c4", LONG, null, "{a}", "{b}"),
        event("c5", null, LONG, "{a}", "{b}"),
        event("c6", LONG, LONG, "{ a; }", "{\n  a;\n}"),
        event("c7", "short one", "short one", "{a}", "{b}"),
        event("c8", LONG, "x", "{a}", "{a}"),
        event("c9", LONG, LONG + "\n@return the total", "{a}", "{b}"),
        event("c10", LONG, LONG, "int compute(int x) {\n  return total(x);\n}", "int compute(int x) {\n  return total(x) + 1;\n}")
    );
    final var result = DriftCorpus.rows("repo", events);
    assertEquals(List.of("repo#c10#p/T.java#T.compute(int)", "repo#c2#p/T.java#T.other()"),
        result.rows().stream().map(DriftCorpus.Row::id).toList(), "compute keeps its latest change (c10 over c1); other keeps c2");
    assertEquals(List.of("BODY_ONLY", "CO_EDIT"), result.rows().stream().map(DriftCorpus.Row::klass).toList());
    final var reasons = result.excluded().stream().map(e -> e.reason() + "=" + e.count()).toList();
    assertEquals(List.of("comment-only event=1", "no comment before the change=1", "whitespace-only body change=1",
        "comment shorter than 40 as shown=1", "comment retouched (shown text equal or jaccard >= 0.9)=2", "comment removed=1",
        "earlier change of the same member=1"), reasons, "c3 is a retouch by jaccard; c9 adds only a tag line, so the shown text is equal");
    assertEquals(3, result.beforeDedupe());
    final var bodyOnly = result.rows().get(0);
    assertEquals("c10", bodyOnly.commit());
    assertEquals("method", bodyOnly.kind());
    assertEquals(LONG, bodyOnly.oldComment());
    assertEquals(LONG.length(), bodyOnly.commentChars());
    assertEquals(2, bodyOnly.diffSize());
    assertEquals(List.of("  return total(x);"), bodyOnly.state().removedLines());
    assertEquals(List.of("  return total(x) + 1;"), bodyOnly.state().addedLines());
    assertEquals("int compute(int x) {\n  return total(x) + 1;\n}", bodyOnly.state().newSource());
    assertEquals("{\"member_kind\":\"method\"}", bodyOnly.state().sourceExtent().toJson(), "no line counts in the state");
    assertEquals("p/T.java", bodyOnly.state().filePath());
    assertEquals(3, bodyOnly.linesBefore());
    assertEquals(3, bodyOnly.linesAfter());
    assertFalse(bodyOnly.abstractToggle());
    assertEquals(" int compute(int x) {\n-  return total(x);\n+  return total(x) + 1;\n }", bodyOnly.diffText());
    assertEquals(0.0, bodyOnly.overlap(), "the comment names no identifier-like token");
    final var coEdit = result.rows().get(1);
    assertEquals("Recomputes the total from the entries every time it is asked, cache gone.", coEdit.newComment());
    assertEquals("T.other()", coEdit.key().toString());
  }

  @Test
  void abstractTogglesAndCaps() {
    assertTrue(DriftCorpus.abstractToggle("int m();", "int m() {\n  return 1;\n}"));
    assertTrue(DriftCorpus.abstractToggle("default int m() {\n  return 1;\n}", "int m();"));
    assertFalse(DriftCorpus.abstractToggle("int m() {\n  return 1;\n}", "int m() {\n  return 2;\n}"));
    assertFalse(DriftCorpus.abstractToggle("int m();", "int m() throws X;"));
    assertTrue(DriftCorpus.bodiless("  int m();  "));
    assertFalse(DriftCorpus.bodiless("int m() { }"));
    assertFalse(DriftCorpus.bodiless("int m()"), "no semicolon, no braces: not a bodiless declaration");
    assertFalse(DriftCorpus.bodiless("int[] a = new int[]{1};"), "a braced initializer is a body however the declaration ends");
    final var many = new java.util.ArrayList<String>();
    for (int i = 0; i < 205; i++) {
      many.add("l" + i);
    }
    final var capped = DriftCorpus.cap(many);
    assertEquals(201, capped.size());
    assertEquals("// … 5 more lines not shown", capped.getLast());
    assertEquals(List.of("a"), DriftCorpus.cap(List.of("a")));
    assertEquals(many.subList(0, 200), DriftCorpus.cap(many.subList(0, 200)), "the cap itself is shown whole, with nothing left to state");
  }

  @Test
  void aNewBodyLongerThanTheLineCapIsShownWithTheCapStated() {
    final var body = new StringBuilder("int compute(int x) {");
    for (int i = 0; i < 205; i++) {
      body.append("\n  step").append(i).append("();");
    }
    body.append("\n}");
    final var rows = DriftCorpus.rows("repo", List.of(event("c1", LONG, LONG, "int compute(int x) {\n  return 0;\n}", body.toString())));
    final var row = rows.rows().getFirst();
    // the head, 205 steps, and the closing brace are 207 lines; 200 are shown and 7 stated
    assertEquals(207, row.linesAfter());
    assertTrue(row.state().newSource().endsWith("\n  step198();\n// … 7 more lines not shown"), row.state().newSource());
  }

  @Test
  void theCommentLengthAndRetouchBarsAreDecidedAtTheirOwnBoundary() {
    final var atTheMinimum = "Returns the running totals for this key.";
    final var oneShort = "Returns the running total for this key.";
    assertEquals(DriftCorpus.MIN_COMMENT_CHARS, atTheMinimum.length());
    assertEquals(DriftCorpus.MIN_COMMENT_CHARS - 1, oneShort.length());
    assertEquals(1, DriftCorpus.rows("repo", List.of(event("c1", atTheMinimum, atTheMinimum, "{a}", "{b}"))).rows().size(),
        "a comment of exactly the minimum length as shown is long enough");
    final var dropped = DriftCorpus.rows("repo", List.of(event("c2", oneShort, oneShort, "{a}", "{b}")));
    assertEquals(List.of(), dropped.rows());
    assertEquals(1, count(dropped, "comment shorter"));
    // nine tokens shared of ten united is exactly the retouch bar
    final var nine = "alpha beta gamma delta epsilon zeta eta theta iota";
    final var ten = nine + " kappa";
    final var retouched = DriftCorpus.rows("repo", List.of(event("c3", nine, ten, "{a}", "{b}")));
    assertEquals(List.of(), retouched.rows(), "a jaccard of exactly 0.9 is a retouch, not a co-edit");
    assertEquals(1, count(retouched, "comment retouched"));
    // one more token on the new side alone drops the similarity to nine of eleven
    final var coEdit = DriftCorpus.rows("repo", List.of(event("c4", nine, ten + " lambda", "{a}", "{b}")));
    assertEquals(List.of("repo#c4#p/T.java#T.compute(int)"), coEdit.rows().stream().map(DriftCorpus.Row::id).toList());
    assertEquals(DriftCorpus.CO_EDIT, coEdit.rows().getFirst().klass());
    assertEquals(0, count(coEdit, "comment retouched"));
    // a comment with no letters or digits shares no token with itself, so only the shown-text
    // comparison can see that respacing it changed nothing
    final var separator = "--- --- --- --- --- --- --- --- --- --- ---";
    final var respaced = separator.replaceFirst("--- ---", "---  ---");
    assertNotEquals(separator, respaced);
    assertEquals(0.0, software.sava.typesafe.evals.text.Jaccard.similarity(separator, respaced));
    final var spacingOnly = DriftCorpus.rows("repo", List.of(event("c5", separator, respaced, "{a}", "{b}")));
    assertEquals(List.of(), spacingOnly.rows(), "a comment that only changed its spacing is a retouch, not a co-edit");
    assertEquals(1, count(spacingOnly, "comment retouched"));
  }

  static int count(final DriftCorpus.Result result, final String reasonPrefix) {
    return result.excluded().stream().filter(e -> e.reason().startsWith(reasonPrefix)).mapToInt(DriftCorpus.Excluded::count).sum();
  }

  @Test
  void overlapCountsIdentifiersThatOccurInChangedLines() {
    final var removed = List.of("  return cacheSize + MAX_ITEMS;");
    final var added = List.of("  return total;");
    assertEquals(2.0 / 3.0, DriftCorpus.overlap("Uses `cacheSize` and the MAX_ITEMS bound; see `unrelatedThing`.", removed, added), 1e-12);
    assertEquals(0.0, DriftCorpus.overlap("plain words", removed, added));
    assertEquals(1.0, DriftCorpus.overlap("Reads `total`.", removed, added));
    assertEquals(0.0, DriftCorpus.overlap("Reads `total`.", List.of(), List.of()), "no changed lines, nothing overlaps");
    assertEquals(Set.of("cacheSize", "MAX_ITEMS", "unrelatedThing"), DriftCorpus.identifiers("`cacheSize` and MAX_ITEMS; `Outer.unrelatedThing(x)` <METHOD>"));
    assertEquals(Set.of("cacheSize"), DriftCorpus.identifiers("`cacheSize` and `.` and `()` and `2ndTry`"),
        "a backticked span with nothing left after the last dot or the parameter list, or one that cannot start an identifier, names nothing");
    assertEquals("Widget", DriftCorpus.memberName(new FileMembers.Key("Outer$Widget", "<init>", "")));
    assertEquals("compute", DriftCorpus.memberName(KEY));
  }

  static DriftBars.Scored scored(final String id, final String commit, final String klass, final double affected, final int diffSize,
                                 final double overlap, final int linesBefore, final int linesAfter, final int commentChars, final boolean toggle) {
    final var state = new DriftQuestions.State("c", List.of("a"), List.of("b"), "n", software.sava.typesafe.JsonContent.object().build(), "p");
    final var row = new DriftCorpus.Row(id, "repo", commit, "p", KEY, "method", klass, "old", "new", commentChars, diffSize, overlap,
        linesBefore, linesAfter, toggle, "-a\n+b", state);
    final double rest = 1 - affected;
    return new DriftBars.Scored(row, new DriftScore(affected >= 0.5 ? "contradicted_by_change" : "unaffected", affected * 0.6, affected * 0.4,
        rest * 0.8, rest * 0.2, 0.9));
  }

  static DriftBars.Scored scored(final String id, final String klass, final double affected, final int diffSize, final double overlap) {
    return scored(id, "commit-" + id, klass, affected, diffSize, overlap, 3, 3, 50, false);
  }

  @Test
  void separationBaselinesAndRanking() {
    final var rows = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 10, 0.5),
        scored("b", DriftCorpus.CO_EDIT, 0.8, 4, 0.0),
        scored("c", DriftCorpus.CO_EDIT, 0.3, 2, 0.0),
        scored("d", DriftCorpus.BODY_ONLY, 0.7, 20, 1.0),
        scored("e", DriftCorpus.BODY_ONLY, 0.2, 2, 0.0),
        scored("f", DriftCorpus.BODY_ONLY, 0.1, 2, 0.0)
    );
    assertEquals(8.0 / 9.0, DriftBars.auroc(rows), 1e-12, "CO_EDIT {0.9, 0.8, 0.3} over BODY_ONLY {0.7, 0.2, 0.1}: 8 wins of 9");
    final var ranks = DriftBars.rank01(rows, s -> (double) s.row().diffSize());
    assertEquals(1.0, ranks.get("d"));
    assertEquals(0.8, ranks.get("a"), 1e-12);
    assertEquals(0.2, ranks.get("c"), 1e-12, "c, e, f tie at 2 lines");
    final var baselines = DriftBars.baselines(rows);
    assertEquals(List.of("diff size", "comment-to-diff overlap", "rank-max of size and overlap", "member lines before", "member lines after",
        "shown comment length", "abstract member gained or lost a body"), baselines.stream().map(DriftBars.Baseline::name).toList());
    final var size = baselines.get(0);
    // sizes CO_EDIT {10, 4, 2} over BODY_ONLY {20, 2, 2}: 10>2,2 (2), 4>2,2 (2), 2 ties 2,2 (1) -> 5 of 9
    assertEquals(5.0 / 9.0, size.auroc(), 1e-12);
    assertEquals(5.0 / 9.0, size.twoSided(), 1e-12);
    final var overlap = baselines.get(1);
    assertEquals((0.5 + 0.5 + 1 + 0.5 + 1 + 0.5) / 9.0, overlap.auroc(), 1e-12);
    assertTrue(overlap.twoSided() >= 0.5, "two-sided is never below a coin");
    assertEquals(0.5, baselines.get(5).auroc(), "equal comment lengths: a coin");
    assertEquals(0.5, baselines.get(6).auroc(), "no toggles: a coin");
    final var best = DriftBars.best(baselines);
    assertTrue(best.twoSided() >= size.twoSided());
    assertEquals(List.of("d", "e", "f"), DriftBars.rankedBodyOnly(rows).stream().map(s -> s.row().id()).toList());
    final double r = DriftBars.pearson(rows);
    assertTrue(r > 0 && r < 0.8, "" + r);
    final double rho = DriftBars.spearman(rows);
    assertTrue(Math.abs(rho) < 0.8, "" + rho);
    final var interval = DriftBars.clusterBootstrap(rows);
    assertTrue(interval[0] <= 8.0 / 9.0 + 1e-9 && 8.0 / 9.0 <= interval[1] + 1e-9, java.util.Arrays.toString(interval));
    assertArrayEquals(new double[]{Double.NaN, Double.NaN}, DriftBars.clusterBootstrap(List.of()));
    assertEquals("none", DriftBars.best(List.of()).name());
  }

  @Test
  void theDecisionTableWithLabels() {
    final var rows = new java.util.ArrayList<DriftBars.Scored>();
    final var labels = new HashMap<String, String>();
    // 8 co-edits high, 100 body-only spread below them; sizes constant so no proxy
    for (int i = 0; i < 8; i++) {
      rows.add(scored("co" + i, DriftCorpus.CO_EDIT, 0.95 - i * 0.01, 3, 0.0));
      labels.put("co" + i, i < 6 ? "needs_addition" : "unaffected");
    }
    for (int i = 0; i < 100; i++) {
      final var id = String.format("bo%03d", i);
      rows.add(scored(id, DriftCorpus.BODY_ONLY, 0.8 - i * 0.005, 3, 0.0));
      labels.put(id, i < 6 ? "contradicted_by_change" : "unaffected");
    }
    var v = DriftBars.verdict(rows, Map.of());
    assertEquals(1.0, v.auroc());
    assertEquals("pass", v.separation());
    assertEquals("value bar pending", v.decision());
    assertTrue(Double.isNaN(v.ceiling()));
    assertEquals(0, v.topRate().of());
    v = DriftBars.verdict(rows, labels);
    assertEquals(0.75, v.oracleRho(), 1e-12, "6 of 8 co-edits marked affected");
    assertEquals(0.06, v.oracleBeta(), 1e-12, "6 of 100 body-only marked affected");
    assertEquals(0.5 + (0.75 - 0.06) / 2, v.ceiling(), 1e-12);
    assertEquals(6, v.topRate().count(), "the six affected body-only rows are the six highest scored");
    assertEquals(30, v.topRate().of());
    assertEquals(0, v.restRate().count());
    assertEquals(70, v.restRate().of());
    assertEquals("keep", v.decision(), "6 of 30 (Wilson lower 0.094) against 0 of 70 (Wilson upper 0.052)");
    assertTrue(v.checks().get(3).pass());
    // the same labels but the affected rows scored at the bottom: nothing missed
    final var inverted = new java.util.ArrayList<DriftBars.Scored>();
    for (final var s : rows) {
      inverted.add(s.row().klass().equals(DriftCorpus.BODY_ONLY) ? scored(s.row().id(), DriftCorpus.BODY_ONLY, 1 - s.affected(), 3, 0.0) : s);
    }
    v = DriftBars.verdict(inverted, labels);
    assertEquals("nothing missed", v.decision());
    // enough in the top but no separation from the rest: ranking not shown
    final var flat = new java.util.ArrayList<DriftBars.Scored>();
    final var flatLabels = new HashMap<String, String>();
    for (int i = 0; i < 8; i++) {
      flat.add(scored("co" + i, DriftCorpus.CO_EDIT, 0.95, 3, 0.0));
    }
    for (int i = 0; i < 60; i++) {
      final var id = String.format("bo%02d", i);
      flat.add(scored(id, DriftCorpus.BODY_ONLY, 0.5, 3, 0.0));
      flatLabels.put(id, i % 4 == 0 ? "contradicted_by_change" : "unaffected");
    }
    v = DriftBars.verdict(flat, flatLabels);
    assertEquals("ranking not shown", v.decision(), "ties rank by id: 8 of the top 30 and 7 of the rest are affected, the same rate");
  }

  @Test
  void decisionTablePrecedence() {
    final var flat = List.of(scored("a", DriftCorpus.CO_EDIT, 0.5, 1, 0.0), scored("b", DriftCorpus.BODY_ONLY, 0.5, 1, 0.0));
    final var flatVerdict = DriftBars.verdict(flat, Map.of());
    assertEquals("kill: separation", flatVerdict.decision(), "a single tie: interval entirely below the bar");
    assertEquals("kill", flatVerdict.separation());
    final var undetermined = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 3, 0.0), scored("b", DriftCorpus.CO_EDIT, 0.4, 3, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.5, 3, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.1, 3, 0.0));
    final var u = DriftBars.verdict(undetermined, Map.of());
    assertEquals(0.75, u.auroc(), 1e-12);
    assertEquals("undetermined", u.separation(), "four clusters: the bootstrap straddles the bar");
    final var wordy = List.of(scored("a", DriftCorpus.CO_EDIT, 0.9, 3, 1.0), scored("b", DriftCorpus.CO_EDIT, 0.8, 3, 1.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.1, 3, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.2, 3, 0.0));
    final var w = DriftBars.verdict(wordy, Map.of());
    assertEquals(1.0, w.best().twoSided(), "overlap alone separates the classes");
    assertEquals("comment-to-diff overlap", w.best().name());
    assertNotEquals("kill: proxy", w.decision());
    assertTrue(List.of("no lift", "kill: separation").contains(w.decision()), "either the interval kills at n=4 or the lift bar fails: " + w.decision());
    final var proxy = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 90, 0.0), scored("b", DriftCorpus.CO_EDIT, 0.5, 50, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.1, 10, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.3, 30, 0.0));
    final var p = DriftBars.verdict(proxy, Map.of());
    assertTrue(p.pearson() > 0.99 && p.spearman() > 0.99);
    assertEquals("kill: proxy", p.decision(), "the proxy rule overrides everything");
    final var rankProxy = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 1000, 0.0), scored("b", DriftCorpus.CO_EDIT, 0.5, 30, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.1, 10, 0.0), scored("d", DriftCorpus.BODY_ONLY, 0.3, 20, 0.0));
    final var rp = DriftBars.verdict(rankProxy, Map.of());
    assertEquals(1.0, rp.spearman(), 1e-12, "a rank-shaped proxy scores Spearman 1 whatever Pearson says");
    assertEquals("kill: proxy", rp.decision());
    assertEquals("kill: separation", DriftBars.verdict(List.of(), Map.of()).decision(), "NaN never clears the bar");
    final var toggled = List.of(scored("a", "c1", DriftCorpus.CO_EDIT, 0.9, 3, 0.0, 1, 3, 50, true), scored("b", "c2", DriftCorpus.BODY_ONLY, 0.1, 3, 0.0, 3, 3, 50, false));
    final var t = DriftBars.verdict(toggled, Map.of());
    assertTrue(Double.isNaN(t.aurocNoToggle()), "removing the toggle row leaves no positive");
    assertEquals(1.0, t.baselines().get(6).twoSided(), "the toggle bit separates these two perfectly");
  }

  @Test
  void rank01AndBestHandleSingleRowsAndUnrankableBaselines() {
    final var one = List.of(scored("only", DriftCorpus.BODY_ONLY, 0.4, 7, 0.0));
    assertEquals(Map.of("only", 0.5), DriftBars.rank01(one, s -> (double) s.row().diffSize()),
        "a single row has nothing to rank against and sits in the middle");
    final var baselines = List.of(new DriftBars.Baseline("unrankable", Double.NaN, Double.NaN),
        new DriftBars.Baseline("strong", 0.9, 0.9), new DriftBars.Baseline("weak", 0.6, 0.6));
    assertEquals("strong", DriftBars.best(baselines).name(), "a NaN baseline never wins and the strongest two-sided score does");
    assertEquals(List.of("a", "b"), DriftBars.rankedBodyOnly(List.of(
            scored("b", DriftCorpus.BODY_ONLY, 0.5, 1, 0.0), scored("a", DriftCorpus.BODY_ONLY, 0.5, 1, 0.0)))
        .stream().map(s -> s.row().id()).toList(), "rows tied on the score rank by id, not by arrival");
    assertEquals(0, DriftBars.Rate.of(2, 0).count(), "with no denominator the rate is empty, whatever count is claimed");
  }

  @Test
  void everyDeterministicBaselineReadsItsOwnFeature() {
    // the CO_EDIT row leads on size, member lines, and comment length, and trails on overlap
    final var rows = List.of(
        scored("a", "c1", DriftCorpus.CO_EDIT, 0.9, 40, 0.1, 9, 11, 90, true),
        scored("b", "c2", DriftCorpus.BODY_ONLY, 0.2, 10, 0.9, 5, 7, 60, false),
        scored("c", "c3", DriftCorpus.BODY_ONLY, 0.1, 20, 0.2, 3, 5, 30, false));
    final var baselines = DriftBars.baselines(rows);
    assertEquals(1.0, baselines.get(0).auroc(), "40 changed lines over 10 and 20");
    assertEquals(0.0, baselines.get(1).auroc(), "overlap 0.1 under both 0.9 and 0.2");
    // size ranks a=1, b=0, c=0.5 against overlap ranks a=0, b=1, c=0.5: the rank-max is
    // a=1, b=1, c=0.5, so the one pair a shares with b is a tie and the pair with c is a win
    assertEquals(0.75, baselines.get(2).auroc(), 1e-12);
    assertEquals(1.0, baselines.get(3).auroc(), "9 lines before over 5 and 3");
    assertEquals(1.0, baselines.get(4).auroc(), "11 lines after over 7 and 5");
    assertEquals(1.0, baselines.get(5).auroc(), "90 comment characters over 60 and 30");
    assertEquals(1.0, baselines.get(6).auroc(), "the only member that toggled is the CO_EDIT row");
  }

  @Test
  void theProxyRuleReadsBothCorrelationsAndTheCeilingItselfPasses() {
    // scores and sizes are both evenly spaced with two adjacent pairs swapped: sum d^2 = 4
    // over n = 5 is Spearman 1 - 6*4/(5*24) = 0.8, and evenly spaced values make Pearson agree
    final var atTheCeiling = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.9, 50, 0.0),
        scored("b", DriftCorpus.CO_EDIT, 0.7, 30, 0.0),
        scored("c", DriftCorpus.CO_EDIT, 0.5, 40, 0.0),
        scored("d", DriftCorpus.BODY_ONLY, 0.3, 10, 0.0),
        scored("e", DriftCorpus.BODY_ONLY, 0.1, 20, 0.0));
    final var ceiling = DriftBars.verdict(atTheCeiling, Map.of());
    assertEquals(DriftBars.CORRELATION_CEILING, DriftBars.round(ceiling.pearson()));
    assertEquals(DriftBars.CORRELATION_CEILING, DriftBars.round(ceiling.spearman()));
    assertTrue(ceiling.checks().getFirst().pass(), "the ceiling itself is not above it");
    assertNotEquals("kill: proxy", ceiling.decision());
    // Pearson alone: one huge diff drags the line through the scores while the ranks disagree
    final var linearOnly = List.of(
        scored("a", DriftCorpus.CO_EDIT, 0.99, 1000, 0.0),
        scored("b", DriftCorpus.CO_EDIT, 0.3, 1, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.2, 5, 0.0),
        scored("d", DriftCorpus.BODY_ONLY, 0.1, 10, 0.0));
    final var linear = DriftBars.verdict(linearOnly, Map.of());
    assertTrue(DriftBars.round(linear.pearson()) > DriftBars.CORRELATION_CEILING, "" + linear.pearson());
    assertTrue(Math.abs(DriftBars.round(linear.spearman())) <= DriftBars.CORRELATION_CEILING, "" + linear.spearman());
    assertFalse(linear.checks().getFirst().pass());
    assertEquals("kill: proxy", linear.decision(), "either correlation over the ceiling is enough");
    // Spearman alone: the sizes track the score order exactly, but one score dwarfs the rest
    final var rankOnly = List.of(
        scored("a", DriftCorpus.CO_EDIT, 1.0, 4, 0.0),
        scored("b", DriftCorpus.CO_EDIT, 0.01, 3, 0.0),
        scored("c", DriftCorpus.BODY_ONLY, 0.005, 2, 0.0),
        scored("d", DriftCorpus.BODY_ONLY, 0.0, 1, 0.0));
    final var rank = DriftBars.verdict(rankOnly, Map.of());
    assertTrue(Math.abs(DriftBars.round(rank.pearson())) <= DriftBars.CORRELATION_CEILING, "" + rank.pearson());
    assertEquals(1.0, DriftBars.round(rank.spearman()));
    assertEquals("kill: proxy", rank.decision());
  }

  @Test
  void theLiftBarMeasuresTheScoreAgainstTheBestBaseline() {
    // the score separates perfectly; diff size wins 9 of its 10 pairs, so the lift is
    // exactly 1.0 - 0.9 = 0.10, the bar itself
    final var rows = List.of(
        scored("p1", "c1", DriftCorpus.CO_EDIT, 0.9, 100, 0.0, 3, 3, 50, false),
        scored("p2", "c2", DriftCorpus.CO_EDIT, 0.8, 90, 0.0, 3, 3, 50, false),
        scored("n1", "c3", DriftCorpus.BODY_ONLY, 0.7, 80, 0.0, 3, 3, 50, false),
        scored("n2", "c4", DriftCorpus.BODY_ONLY, 0.6, 70, 0.0, 3, 3, 50, false),
        scored("n3", "c5", DriftCorpus.BODY_ONLY, 0.5, 60, 0.0, 3, 3, 50, false),
        scored("n4", "c6", DriftCorpus.BODY_ONLY, 0.4, 50, 0.0, 3, 3, 50, false),
        scored("n5", "c7", DriftCorpus.BODY_ONLY, 0.3, 95, 0.0, 3, 3, 50, false));
    final var v = DriftBars.verdict(rows, Map.of());
    assertEquals(1.0, v.auroc());
    assertEquals(1.0, v.aurocNoToggle(), "no member toggled, so dropping the toggle rows drops nothing");
    assertEquals("diff size", v.best().name());
    assertEquals(0.9, v.best().twoSided(), 1e-12);
    assertEquals(DriftBars.LIFT_BAR, v.checks().get(2).value(), 1e-12, "AUROC 1.0 less the best baseline's 0.9");
    assertTrue(v.checks().get(2).pass(), "the bar is a floor the lift may sit on");
    assertEquals("value bar pending", v.decision(), "no label has been read yet");
  }

  @Test
  void separationReadsTheCommitBootstrapAgainstTheBar() {
    // one commit, so every resample is the same four rows and the interval collapses onto
    // the AUROC itself: 3 of the 4 pairs won, exactly the bar
    final var oneCommit = List.of(
        scored("a", "c1", DriftCorpus.CO_EDIT, 0.9, 3, 0.0, 3, 3, 50, false),
        scored("b", "c1", DriftCorpus.CO_EDIT, 0.5, 3, 0.0, 3, 3, 50, false),
        scored("c", "c1", DriftCorpus.BODY_ONLY, 0.7, 3, 0.0, 3, 3, 50, false),
        scored("d", "c1", DriftCorpus.BODY_ONLY, 0.4, 3, 0.0, 3, 3, 50, false));
    assertEquals(DriftBars.SEPARATION_BAR, DriftBars.auroc(oneCommit), 1e-12);
    assertArrayEquals(new double[]{0.75, 0.75}, DriftBars.clusterBootstrap(oneCommit));
    assertEquals("pass", DriftBars.verdict(oneCommit, Map.of()).separation(), "a lower bound on the bar clears it");
    // add a second commit holding one high-scoring BODY_ONLY row: drawing both commits scores
    // 0.5, drawing the first twice scores 0.75, and drawing the second twice holds no CO_EDIT
    // row at all and is not counted, so half the resamples sit at each value
    final var twoCommits = new java.util.ArrayList<>(oneCommit);
    twoCommits.add(scored("e", "c2", DriftCorpus.BODY_ONLY, 0.99, 3, 0.0, 3, 3, 50, false));
    assertArrayEquals(new double[]{0.5, 0.75}, DriftBars.clusterBootstrap(twoCommits));
    assertEquals("undetermined", DriftBars.verdict(twoCommits, Map.of()).separation(), "an upper bound on the bar does not kill");
    // one class only: no resample has an AUROC, so there is nothing to take a percentile of
    assertArrayEquals(new double[]{Double.NaN, Double.NaN},
        DriftBars.clusterBootstrap(List.of(scored("a", "c1", DriftCorpus.CO_EDIT, 0.9, 3, 0.0, 3, 3, 50, false))));
  }

  @Test
  void theBootstrapTakesThePercentilesOfAThousandCommitResamples() {
    // four commits, each holding rows of both classes, so every one of the 1000 resamples has
    // an AUROC. The interval is the 25th smallest sample (index floor(0.025 * 999) = 24) and
    // the 976th (index ceil(0.975 * 999) = 975); under this seed the neighbouring samples are
    // 0.0 below the first and 0.9464 above the second, so one resample more, one draw more
    // per resample, or one place either way on either index moves an end of the interval
    final var rows = List.of(
        scored("a", "c1", DriftCorpus.CO_EDIT, 0.8, 3, 0.0, 3, 3, 50, false),
        scored("b", "c1", DriftCorpus.BODY_ONLY, 0.6, 3, 0.0, 3, 3, 50, false),
        scored("c", "c2", DriftCorpus.CO_EDIT, 0.9, 3, 0.0, 3, 3, 50, false),
        scored("d", "c2", DriftCorpus.CO_EDIT, 0.5, 3, 0.0, 3, 3, 50, false),
        scored("e", "c2", DriftCorpus.BODY_ONLY, 0.8, 3, 0.0, 3, 3, 50, false),
        scored("f", "c3", DriftCorpus.CO_EDIT, 0.6, 3, 0.0, 3, 3, 50, false),
        scored("g", "c3", DriftCorpus.BODY_ONLY, 0.5, 3, 0.0, 3, 3, 50, false),
        scored("h", "c3", DriftCorpus.BODY_ONLY, 0.1, 3, 0.0, 3, 3, 50, false),
        scored("i", "c4", DriftCorpus.CO_EDIT, 0.4, 3, 0.0, 3, 3, 50, false),
        scored("j", "c4", DriftCorpus.BODY_ONLY, 0.6, 3, 0.0, 3, 3, 50, false));
    assertEquals(1000, DriftBars.RESAMPLES);
    assertEquals(7L, DriftBars.SEED);
    assertArrayEquals(new double[]{0.2, 0.925}, DriftBars.clusterBootstrap(rows));
  }

  /// Ten CO_EDIT rows, seven of them scored above every BODY_ONLY row and three below, in two
  /// commits: the first holds the seven and all 130 BODY_ONLY rows, the second holds the three.
  static List<DriftBars.Scored> valueBarRows() {
    final var rows = new java.util.ArrayList<DriftBars.Scored>();
    for (int i = 0; i < 7; i++) {
      rows.add(scored("co" + i, "c1", DriftCorpus.CO_EDIT, 0.95 - i * 0.001, 3, 0.0, 3, 3, 50, false));
    }
    for (int i = 0; i < 3; i++) {
      rows.add(scored("lo" + i, "c2", DriftCorpus.CO_EDIT, 0.01, 3, 0.0, 3, 3, 50, false));
    }
    for (int i = 0; i < 130; i++) {
      rows.add(scored(String.format("bo%03d", i), "c1", DriftCorpus.BODY_ONLY, 0.8 - i * 0.005, 3, 0.0, 3, 3, 50, false));
    }
    return List.copyOf(rows);
  }

  /// Blind labels over every BODY_ONLY row of `valueBarRows`: `top` of the 30 highest scored
  /// and `rest` of the other 100 marked affected, the remainder marked unaffected.
  static Map<String, String> valueBarLabels(final int top, final int rest) {
    final var labels = new HashMap<String, String>();
    for (int i = 0; i < 130; i++) {
      labels.put(String.format("bo%03d", i), i < top || (i >= DriftBars.TOP_N && i < DriftBars.TOP_N + rest)
          ? "contradicted_by_change" : "unaffected");
    }
    return labels;
  }

  @Test
  void theValueBarNeedsEnoughAffectedRowsAndSeparationFromTheRest() {
    final var rows = valueBarRows();
    // 7 of the 10 CO_EDIT rows beat all 130 BODY_ONLY rows and 3 beat none of them
    assertEquals(0.7, DriftBars.auroc(rows), 1e-12);
    final var v = DriftBars.verdict(rows, valueBarLabels(DriftBars.VALUE_BAR, 0));
    assertEquals("undetermined", v.separation(), "a resample of the first commit alone separates perfectly, a mixed one does not");
    assertEquals(30, v.topRate().of());
    assertEquals(DriftBars.VALUE_BAR, v.topRate().count());
    assertEquals(100, v.restRate().of());
    assertEquals(0, v.restRate().count());
    assertEquals("keep (separation undetermined)", v.decision(), "5 of 30 against 0 of 100, with the bar inside the interval");
    assertEquals("nothing missed", DriftBars.verdict(rows, valueBarLabels(DriftBars.VALUE_BAR - 1, 0)).decision(),
        "one row short of the value bar, however well the top separates from the rest");
    final var mixed = DriftBars.verdict(rows, valueBarLabels(DriftBars.VALUE_BAR, 20));
    assertEquals(20, mixed.restRate().count());
    assertEquals("ranking not shown", mixed.decision(), "enough affected rows in the top, but the rest holds as many");
  }

  @Test
  void constants() {
    assertEquals(0.75, DriftBars.SEPARATION_BAR);
    assertEquals(0.10, DriftBars.LIFT_BAR);
    assertEquals(0.8, DriftBars.CORRELATION_CEILING);
    assertEquals(30, DriftBars.TOP_N);
    assertEquals(5, DriftBars.VALUE_BAR);
    assertEquals(0.9, DriftCorpus.RETOUCH_JACCARD);
    assertEquals(40, DriftCorpus.MIN_COMMENT_CHARS);
    assertEquals(0.8499, DriftBars.round(0.84994));
    assertTrue(Double.isNaN(DriftBars.round(Double.NaN)), "NaN never rounds to 0");
    assertTrue(DriftBars.affectedLabel("needs_addition"));
    assertTrue(DriftBars.affectedLabel("contradicted_by_change"));
    assertFalse(DriftBars.affectedLabel("unaffected"));
    assertFalse(DriftBars.affectedLabel(null));
    final var five = DriftBars.Rate.of(5, 150);
    assertEquals(0.0143, five.lower(), 5e-4);
    assertEquals(0.0757, five.upper(), 5e-4);
    assertTrue(Double.isNaN(DriftBars.Rate.of(0, 0).rate()));
  }
}
