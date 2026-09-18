package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.JsonContent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DocBarsTests {

  static DocScore score(final double pContradicted) {
    final double rest = 1.0 - pContradicted;
    final var choice = pContradicted >= rest * 0.8 ? DocQuestions.CONTRADICTED : DocQuestions.CONSISTENT;
    return new DocScore(choice, rest * 0.8, pContradicted, rest * 0.2, 0.9, 0.1);
  }

  static DocCorpus.Row row(final String id, final String realComment, final String swappedComment, final double mismatchReal,
                           final double mismatchSwapped) {
    final var facts = JsonContent.object().build();
    final var real = new DocQuestions.State(realComment, "int m() {\n}", facts, "p/C.java");
    final var swapped = swappedComment == null ? null : new DocQuestions.State(swappedComment, "int m() {\n}", facts, "p/C.java");
    return new DocCorpus.Row(id, "repo", "p/C.java", new FileMembers.Key("C", "m", ""), "method", realComment, realComment.length(), 2,
        real, swapped, swappedComment == null ? null : "C.n()", mismatchReal, mismatchSwapped, List.of());
  }

  static DocBars.Pair pair(final String id, final double real, final double swapped, final int chars, final double mReal, final double mSwapped) {
    final var row = row(id, "r".repeat(chars), "s".repeat(chars), mReal, mSwapped);
    return new DocBars.Pair(row, score(real), score(swapped));
  }

  @Test
  void design1SeparationBaselineAndRanking() {
    final var pairs = List.of(
        pair("a", 0.1, 0.9, 100, 0.0, 1.0),
        pair("b", 0.2, 0.8, 100, 0.0, 0.5),
        pair("c", 0.3, 0.7, 100, 0.5, 0.5),
        pair("d", 0.6, 0.4, 100, 0.0, 0.0)
    );
    assertEquals(0.9375, DocBars.auroc(pairs), "swapped 0.4 loses to real 0.6 only");
    // baseline: swapped {1, .5, .5, 0} vs real {0, 0, .5, 0}: 1 wins 4; each .5 wins 3 and ties 1; 0 ties 3 -> 12.5 of 16
    assertEquals(12.5 / 16.0, DocBars.baselineAuroc(pairs), 1e-12);
    final var scored = pairs.stream().map(p -> new DocBars.Scored(p.row(), p.real())).toList();
    assertEquals(List.of("d", "c", "b", "a"), DocBars.ranked(scored).stream().map(s -> s.row().id()).toList());
    assertEquals(4, DocBars.top(scored).size());
    assertEquals(0.0, DocBars.lengthCorrelation(pairs), 1e-12, "equal comment lengths have no variance");
    final var interval = DocBars.interval(pairs);
    assertTrue(interval[0] <= 0.9375 && 0.9375 <= interval[1]);
  }

  @Test
  void design1DecisionTable() {
    final var clean = List.of(pair("a", 0.1, 0.9, 100, 0.0, 0.0), pair("b", 0.2, 0.8, 100, 0.0, 0.0), pair("c", 0.3, 0.7, 100, 0.0, 0.0));
    final var scored = clean.stream().map(p -> new DocBars.Scored(p.row(), p.real())).toList();
    var verdict = DocBars.verdict(clean, scored, Map.of());
    assertEquals(1.0, verdict.auroc());
    assertEquals(0.5, verdict.baselineAuroc(), "identical mismatch in both arms: a coin");
    assertEquals("value bar pending", verdict.decision());
    assertEquals(4, verdict.checks().size());
    assertEquals("contradicted comments confirmed among the top 30 REAL rows (0 read)", verdict.checks().get(3).name());
    verdict = DocBars.verdict(clean, scored, Map.of("a", "contradicted", "b", "consistent", "c", "not_checkable"));
    assertEquals("no problem found", verdict.decision(), "one contradicted of the five required");
    assertEquals(1.0, verdict.checks().get(3).value());
    final var five = new java.util.ArrayList<DocBars.Pair>();
    final var labels = new java.util.HashMap<String, String>();
    for (int i = 0; i < 6; i++) {
      five.add(pair("r" + i, 0.9 - i * 0.05, 0.99, 100, 0.0, 0.0));
      labels.put("r" + i, i < 5 ? "contradicted" : "consistent");
    }
    verdict = DocBars.verdict(five, five.stream().map(p -> new DocBars.Scored(p.row(), p.real())).toList(), labels);
    assertEquals("keep", verdict.decision());
    // no lift: the baseline separates as well
    final var wordy = List.of(pair("a", 0.1, 0.9, 100, 0.0, 1.0), pair("b", 0.2, 0.8, 100, 0.0, 1.0));
    verdict = DocBars.verdict(wordy, List.of(), Map.of());
    assertEquals("no lift", verdict.decision());
    // separation fails
    verdict = DocBars.verdict(List.of(pair("a", 0.5, 0.5, 100, 0.0, 0.0), pair("b", 0.6, 0.4, 100, 0.0, 0.0)), List.of(), Map.of());
    assertEquals("kill: separation", verdict.decision());
    // proxy: P tracks comment length in both arms
    final var proxy = List.of(pair("a", 0.1, 0.15, 10, 0.0, 0.0), pair("b", 0.5, 0.55, 50, 0.0, 0.0), pair("c", 0.9, 0.95, 90, 0.0, 0.0));
    verdict = DocBars.verdict(proxy, List.of(), Map.of());
    assertTrue(verdict.lengthCorrelation() > 0.99);
    assertEquals("kill: proxy", verdict.decision());
    assertEquals("kill: separation", DocBars.verdict(List.of(), List.of(), Map.of()).decision(), "NaN never clears the bar");
  }

  @Test
  void design2SampleBars() {
    final var rows = List.of(
        new DocBars.Scored(row("a", "x".repeat(50), null, 1.0, Double.NaN), score(0.9)),
        new DocBars.Scored(row("b", "x".repeat(60), null, 0.5, Double.NaN), score(0.8)),
        new DocBars.Scored(row("c", "x".repeat(40), null, 0.0, Double.NaN), score(0.2)),
        new DocBars.Scored(row("d", "x".repeat(45), null, 0.0, Double.NaN), score(0.1)),
        new DocBars.Scored(row("e", "x".repeat(70), null, 0.0, Double.NaN), score(0.95)),
        new DocBars.Scored(row("f", "x".repeat(30), null, 0.0, Double.NaN), score(0.5)),
        new DocBars.Scored(row("g", "x".repeat(30), null, 0.0, Double.NaN), score(0.5))
    );
    final var labels = Map.of("a", "contradicted", "b", "contradicted", "c", "consistent", "d", "consistent", "e", "consistent", "f", "not_checkable");
    final var v = DocBars.sample(rows, labels);
    assertEquals(6, v.labeled(), "g is unlabeled");
    assertEquals(2, v.contradicted());
    assertEquals(3, v.consistent());
    assertEquals(1, v.notCheckable());
    assertEquals(0.4, v.prevalence(), 1e-12);
    // contradicted {0.9, 0.8} vs consistent {0.2, 0.1, 0.95}: 4 wins of 6
    assertEquals(4.0 / 6.0, v.auroc(), 1e-12);
    assertEquals(1.0, v.mismatchAuroc(), "the mismatch baseline separates these perfectly");
    // lengths: {50, 60} vs {40, 45, 70}: 50>40,45; 60>40,45 -> 4 of 6
    assertEquals(4.0 / 6.0, v.lengthAuroc(), 1e-12);
    assertEquals(1.0 / 3.0, v.confidentWrong(), 1e-12, "e is consistent at 0.95");
    assertEquals(4, v.checks().size());
    assertFalse(v.checks().get(0).pass(), "0.667 < 0.80");
    assertFalse(v.checks().get(1).pass(), "one confident wrong of three consistent");
    assertFalse(v.checks().get(2).pass(), "no lift over the mismatch baseline");
    assertFalse(v.checks().get(3).pass(), "equal to the length predictor");
    assertTrue(v.interval()[0] <= v.auroc() && v.auroc() <= v.interval()[1]);
    final var empty = DocBars.sample(rows, Map.of());
    assertEquals(0, empty.labeled());
    assertTrue(Double.isNaN(empty.prevalence()));
    assertTrue(Double.isNaN(empty.auroc()));
    assertTrue(Double.isNaN(empty.interval()[0]));
    assertEquals(0.0, empty.confidentWrong());
    assertArrayEquals(new double[]{Double.NaN, Double.NaN}, DocBars.bootstrap(List.of(), List.of(0.5)));
    assertArrayEquals(new double[]{1.0, 1.0}, DocBars.bootstrap(List.of(0.9, 0.8), List.of(0.1)));
  }

  @Test
  void constantsAndRounding() {
    assertEquals(0.85, DocBars.SEPARATION_BAR);
    assertEquals(0.10, DocBars.LIFT_BAR);
    assertEquals(0.8, DocBars.CORRELATION_CEILING);
    assertEquals(30, DocBars.TOP_N);
    assertEquals(5, DocBars.VALUE_BAR);
    assertEquals(0.80, DocBars.SAMPLE_AUROC_BAR);
    assertEquals(0.9, DocBars.CONFIDENT);
    assertEquals(0.02, DocBars.CONFIDENT_WRONG_MAX);
    assertEquals(0.8499, DocBars.round(0.84994));
    assertEquals(0.85, DocBars.round(0.84996));
  }
}
