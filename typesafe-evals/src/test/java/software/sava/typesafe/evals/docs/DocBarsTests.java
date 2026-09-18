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
    return new DocScore(choice, rest * 0.8, pContradicted, rest * 0.2, 0.9);
  }

  static DocCorpus.Row row(final String id, final String realComment, final String swappedComment, final double baselineReal,
                           final double baselineSwapped) {
    final var extent = JsonContent.object().build();
    final var real = new DocQuestions.State(realComment, "int m() {\n}", extent, "p/C.java");
    final var swapped = swappedComment == null ? null : new DocQuestions.State(swappedComment, "int m() {\n}", extent, "p/C.java");
    return new DocCorpus.Row(id, "repo", "p/C.java", new FileMembers.Key("C", "m", ""), "method", realComment, realComment.length(), 2,
        real, swapped, swappedComment == null ? null : "C.n()", baselineReal, baselineReal, baselineSwapped, List.of());
  }

  static DocBars.Pair pair(final String id, final double real, final double swapped, final int chars, final double bReal, final double bSwapped) {
    return new DocBars.Pair(row(id, "r".repeat(chars), "s".repeat(chars), bReal, bSwapped), score(real), score(swapped));
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
    final var tied = List.of(new DocBars.Scored(row("z", "x", null, 0.0, Double.NaN), score(0.5)),
        new DocBars.Scored(row("a", "x", null, 0.0, Double.NaN), score(0.5)));
    assertEquals(List.of("a", "z"), DocBars.ranked(tied).stream().map(s -> s.row().id()).toList(),
        "rows that share a P(contradicted) rank by id, not by the order they arrived in");
    assertEquals(4, DocBars.top(scored, 30).size());
    assertEquals(2, DocBars.top(scored, 2).size());
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
    assertEquals(0.5, verdict.baselineAuroc(), "identical baselines in both arms: a coin");
    assertEquals("value bar pending", verdict.decision());
    assertTrue(verdict.checks().get(0).pass(), "equal comment lengths cannot correlate, so the proxy bar is met");
    assertEquals(0.5, verdict.checks().get(2).value(), "the lift bar reports AUROC minus the baseline");
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
    final var wordy = List.of(pair("a", 0.1, 0.9, 100, 0.0, 1.0), pair("b", 0.2, 0.8, 100, 0.0, 1.0));
    assertEquals("no lift", DocBars.verdict(wordy, List.of(), Map.of()).decision(), "the baseline separates as well");
    assertEquals("kill: separation", DocBars.verdict(List.of(pair("a", 0.5, 0.5, 100, 0.0, 0.0), pair("b", 0.6, 0.4, 100, 0.0, 0.0)), List.of(), Map.of()).decision());
    final var proxy = List.of(pair("a", 0.1, 0.15, 10, 0.0, 0.0), pair("b", 0.5, 0.55, 50, 0.0, 0.0), pair("c", 0.9, 0.95, 90, 0.0, 0.0));
    verdict = DocBars.verdict(proxy, List.of(), Map.of());
    assertTrue(verdict.lengthCorrelation() > 0.99);
    assertFalse(verdict.checks().get(0).pass(), "a correlation past the ceiling fails the proxy bar");
    assertEquals("kill: proxy", verdict.decision());
    assertEquals("kill: separation", DocBars.verdict(List.of(), List.of(), Map.of()).decision(), "NaN never clears the bar");
  }

  @Test
  void ratesCarryWilsonIntervals() {
    final var none = DocBars.Rate.of(0, 0);
    assertEquals(0, none.of());
    assertTrue(Double.isNaN(none.rate()));
    assertEquals(0, DocBars.Rate.of(3, 0).count(), "nothing was observed, so nothing is counted");
    final var five = DocBars.Rate.of(5, 150);
    assertEquals(5.0 / 150, five.rate(), 1e-12);
    assertEquals(0.0143, five.lower(), 5e-4);
    assertEquals(0.0757, five.upper(), 5e-4);
    final var all = DocBars.Rate.of(3, 3);
    assertEquals(1.0, all.rate());
    assertEquals(1.0, all.upper(), "clamped at one");
    assertTrue(all.lower() > 0.4 && all.lower() < 0.5);
    final var zero = DocBars.Rate.of(0, 10);
    assertEquals(0.0, zero.lower(), "clamped at zero");
    assertTrue(zero.upper() > 0.27 && zero.upper() < 0.29);
  }

  @Test
  void binomialTailIsExact() {
    assertEquals(1.0, DocBars.binomialTail(0, 10, 0.02), 1e-12, "at least zero is certain");
    assertEquals(1 - Math.pow(0.98, 10), DocBars.binomialTail(1, 10, 0.02), 1e-12);
    assertEquals(Math.pow(0.02, 3), DocBars.binomialTail(3, 3, 0.02), 1e-15);
    // 6 or more of 120 at 2%: the review's worked example, about 0.034
    assertEquals(0.034, DocBars.binomialTail(6, 120, 0.02), 2e-3);
  }

  @Test
  void design2IsAPrevalenceStudy() {
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
    final var strata = Map.of("a", "stale-candidate", "b", "stale-candidate", "c", "stale-candidate");
    final var v = DocBars.sample(rows, labels, strata);
    assertEquals(List.of("stale-candidate", "random"), v.strata().stream().map(DocBars.Stratum::name).toList(), "unmapped rows are random");
    final var stale = v.strata().get(0);
    assertEquals(3, stale.labeled());
    assertEquals(2, stale.contradicted());
    assertEquals(1, stale.consistent());
    assertEquals(2.0 / 3.0, stale.prevalence().rate(), 1e-12);
    final var random = v.strata().get(1);
    assertEquals(3, random.labeled(), "d, e, f; g is unlabeled");
    assertEquals(0, random.contradicted());
    assertEquals(1, random.notCheckable());
    assertEquals(0.0, random.prevalence().rate());
    assertEquals(6, v.pooled().labeled());
    assertEquals(0.4, v.pooled().prevalence().rate(), 1e-12);
    // top 20 by P(contradicted) covers every row; labeled: a, b contradicted of 6 labeled
    assertEquals(2, v.topPrecision().count());
    assertEquals(6, v.topPrecision().of());
    assertEquals(1, v.confidentWrong().count(), "e is consistent at 0.95");
    assertEquals(3, v.confidentWrong().of());
    assertEquals(DocBars.binomialTail(1, 3, 0.02), v.binomialP(), 1e-12);
    assertTrue(Double.isNaN(v.auroc()), "two contradicted rows are too few for a ranking statistic");
    assertTrue(Double.isNaN(v.interval()[0]));
    final var empty = DocBars.sample(rows, Map.of(), Map.of());
    assertEquals(0, empty.pooled().labeled());
    assertTrue(Double.isNaN(empty.pooled().prevalence().rate()));
    assertEquals(0, empty.confidentWrong().of());
    assertTrue(Double.isNaN(empty.binomialP()));
  }

  @Test
  void design2ReportsAurocOnlyWithEnoughContradictedRows() {
    final var rows = new java.util.ArrayList<DocBars.Scored>();
    final var labels = new java.util.HashMap<String, String>();
    for (int i = 0; i < 20; i++) {
      rows.add(new DocBars.Scored(row("c" + i, "x", null, 0.0, Double.NaN), score(0.6 + i * 0.01)));
      labels.put("c" + i, "contradicted");
    }
    for (int i = 0; i < 9; i++) {
      rows.add(new DocBars.Scored(row("k" + i, "x", null, 0.0, Double.NaN), score(0.1 + i * 0.01)));
      labels.put("k" + i, "consistent");
    }
    rows.add(new DocBars.Scored(row("k9", "x", null, 0.0, Double.NaN), score(0.95)));
    labels.put("k9", "consistent");
    final var v = DocBars.sample(rows, labels, Map.of());
    assertEquals(0.9, v.auroc(), 1e-12, "one consistent row at 0.95 outranks all twenty: 180 of 200 pairs");
    assertArrayEquals(new double[]{0.7, 1.0}, v.interval(), "the seeded percentile bootstrap over those same scores");
    assertEquals(20, v.pooled().contradicted());
    assertArrayEquals(new double[]{Double.NaN, Double.NaN}, DocBars.bootstrap(List.of(), List.of(0.5)));
  }

  /// Both numeric bars read `>=`, so a value that lands exactly on one clears it.
  @Test
  void design1BarsAreMetWhenTheValueEqualsTheBar() {
    // swapped {0.90 eight times, 0.035, 0.025} over real {0.01 .. 0.10} wins 85 of 100 pairs;
    // swapped baselines {0.5 five times, 0.0 five times} over real baselines {0.0} wins 75.
    final var pairs = new java.util.ArrayList<DocBars.Pair>();
    for (int i = 0; i < 10; i++) {
      final double swapped = i < 8 ? 0.90 : (i == 8 ? 0.035 : 0.025);
      pairs.add(pair("r" + i, (i + 1) / 100.0, swapped, 100, 0.0, i < 5 ? 0.5 : 0.0));
    }
    assertEquals(DocBars.SEPARATION_BAR, DocBars.auroc(pairs), "the separation is exactly the bar");
    assertEquals(0.75, DocBars.baselineAuroc(pairs));
    final var verdict = DocBars.verdict(pairs, List.of(), Map.of());
    assertEquals(DocBars.SEPARATION_BAR, verdict.checks().get(1).value());
    assertTrue(verdict.checks().get(1).pass(), "AUROC at the bar separates");
    assertEquals(DocBars.LIFT_BAR, verdict.checks().get(2).value(), "0.85 over a 0.75 baseline is exactly the lift bar");
    assertTrue(verdict.checks().get(2).pass(), "lift at the bar is lift");
    assertEquals("value bar pending", verdict.decision(), "both bars cleared, and nothing is labeled yet");
  }

  /// The proxy bar reads `>`, so a correlation exactly at the ceiling is still allowed.
  @Test
  void aCorrelationExactlyAtTheCeilingDoesNotKill() {
    final var pairs = List.of(
        pair("a", 0.0, 0.2, 20, 0.0, 0.0),
        pair("b", 0.1, 0.9, 40, 0.0, 0.0),
        pair("c", 0.8, 1.0, 60, 0.0, 0.0));
    final var verdict = DocBars.verdict(pairs, List.of(), Map.of());
    assertEquals(DocBars.CORRELATION_CEILING, DocBars.round(verdict.lengthCorrelation()), "r rounds to the ceiling itself");
    assertTrue(verdict.checks().get(0).pass(), "a correlation at the ceiling is not yet a length proxy");
    assertEquals("value bar pending", verdict.decision(), "the table does not kill at the ceiling");
  }

  @Test
  void bootstrapResamplesPositivesAndNegativesIndependently() {
    final var pos = List.of(0.9, 0.8, 0.7, 0.6, 0.5);
    final var neg = List.of(0.75, 0.55, 0.45, 0.35, 0.25);
    assertEquals(0.84, software.sava.typesafe.evals.metrics.Metrics.auroc(pos, neg), 1e-12, "21 of 25 pairs the positive wins");
    final var interval = DocBars.bootstrap(pos, neg);
    assertArrayEquals(new double[]{0.52, 1.0}, interval, "the seeded 2.5% and 97.5% percentiles of 1000 resamples");
    assertTrue(interval[0] <= 0.84 && 0.84 <= interval[1], "the interval brackets the point estimate");
    assertArrayEquals(new double[]{Double.NaN, Double.NaN}, DocBars.bootstrap(pos, List.of()),
        "with nothing to rank against there is no interval");
  }

  @Test
  void aConsistentRowExactlyAtTheConfidenceBarCountsAsConfidentlyWrong() {
    final var rows = List.of(
        new DocBars.Scored(row("a", "x", null, 0.0, Double.NaN), score(DocBars.CONFIDENT)),
        new DocBars.Scored(row("b", "x", null, 0.0, Double.NaN), score(0.8999)));
    final var v = DocBars.sample(rows, Map.of("a", "consistent", "b", "consistent"), Map.of());
    assertEquals(2, v.confidentWrong().of());
    assertEquals(1, v.confidentWrong().count(), "P(contradicted) of exactly 0.9 is confident; 0.8999 is not");
  }

  @Test
  void constantsAndRounding() {
    assertEquals(0.85, DocBars.SEPARATION_BAR);
    assertEquals(0.10, DocBars.LIFT_BAR);
    assertEquals(0.8, DocBars.CORRELATION_CEILING);
    assertEquals(30, DocBars.TOP_N);
    assertEquals(5, DocBars.VALUE_BAR);
    assertEquals(20, DocBars.PRECISION_TOP);
    assertEquals(0.9, DocBars.CONFIDENT);
    assertEquals(0.02, DocBars.CONFIDENT_WRONG_RATE);
    assertEquals(20, DocBars.AUROC_MIN_POSITIVES);
    assertEquals(0.8499, DocBars.round(0.84994));
    assertEquals(0.85, DocBars.round(0.84996));
  }
}
