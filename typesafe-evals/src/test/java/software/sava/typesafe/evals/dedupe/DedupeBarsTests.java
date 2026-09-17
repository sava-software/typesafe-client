package software.sava.typesafe.evals.dedupe;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.SystemOneResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DedupeBarsTests {

  static PairScore score(final double p0, final double p1, final double p2, final double confidence, final double newEvidence) {
    final var body = String.format(java.util.Locale.ROOT, """
        {"model":"m","answers":{"relation":{"type":"score","score":%.3f,"confidence":%.3f,"probabilities":{"0":%.3f,"1":%.3f,"2":%.3f},"legend":{"0":"a","1":"b","2":"c"}},"claims_new_evidence":{"type":"noul","noul":%.3f}}}""",
        p1 + 2 * p2, confidence, p0, p1, p2, newEvidence);
    return PairScore.of(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), null));
  }

  private static DedupeBars.Row row(final String id, final int gold, final boolean exactLine, final double jaccard, final PairScore score) {
    return new DedupeBars.Row(id, gold, exactLine, jaccard, score);
  }

  @Test
  void pairScoreReadsTheTopLevelAndMerges() {
    final var same = score(0.05, 0.05, 0.9, 0.85, 0.2);
    assertEquals(2, same.level());
    assertEquals(0.9, same.pSame());
    assertEquals(0.05, same.pNarrowed());
    assertEquals(1.85, same.score(), 1e-9);
    assertEquals(0.85, same.confidence());
    assertEquals(0.2, same.newEvidence());
    assertTrue(same.merges(0.8));
    assertTrue(same.merges(0.85));
    assertFalse(same.merges(0.86));
    final var narrowed = score(0.3, 0.5, 0.2, 0.4, 0.9);
    assertEquals(1, narrowed.level());
    assertFalse(narrowed.merges(0.0), "only the restated level merges");
    final var tie = score(0.4, 0.4, 0.2, 0.3, 0.0);
    assertEquals(0, tie.level(), "a tie keeps the lower level");
    final var different = score(0.9, 0.05, 0.05, 0.9, 0.0);
    assertEquals(0, different.level());
    assertFalse(different.merges(0.5));
    assertEquals(0.9, different.pDifferent(), 1e-9);
    assertFalse(different.sameDefect(0.2));
    assertEquals(0.3, narrowed.pDifferent(), 1e-9);
    assertFalse(narrowed.sameDefect(0.2));
    assertTrue(narrowed.sameDefect(0.3), "the bound is inclusive");
    assertEquals(0.05, same.pDifferent(), 1e-9);
    assertTrue(same.sameDefect(0.2));
    assertEquals(0.0, score(0.0, 0.5, 0.5, 0.5, 0).pDifferent());
  }

  @Test
  void theSameDefectRuleCountsNarrowedAsSame() {
    final var rows = List.of(
        row("a", 2, false, 0.5, score(0.05, 0.45, 0.5, 0.3, 0)),
        row("b", 1, false, 0.5, score(0.1, 0.6, 0.3, 0.4, 0)),
        row("c", 1, false, 0.5, score(0.3, 0.5, 0.2, 0.4, 0)),
        row("d", 0, false, 0.5, score(0.15, 0.4, 0.45, 0.3, 0)),
        row("e", 0, false, 0.5, score(0.95, 0.03, 0.02, 0.9, 0))
    );
    final var point = DedupeBars.sameDefect(rows);
    assertEquals("same-defect", point.name());
    assertEquals(DedupeBars.MAX_DIFFERENT, point.threshold());
    assertEquals(3, point.merged(), "a, b, d are grouped; c and e are not");
    assertEquals(2.0 / 3, point.precision(), 1e-9);
    assertEquals(2.0 / 3, point.recall(), 1e-9);
    assertEquals(List.of("d"), DedupeBars.sameDefectViolations(rows).stream().map(DedupeBars.Row::pairId).toList());
    final var none = DedupeBars.sameDefect(List.of(rows.get(4)));
    assertEquals(0.0, none.precision());
    assertEquals(0.0, none.recall());
  }

  @Test
  void operatingPointsAndBaselines() {
    final var rows = List.of(
        row("p1", 2, true, 0.6, score(0.0, 0.1, 0.9, 0.9, 0)),
        row("p2", 2, true, 0.4, score(0.1, 0.1, 0.8, 0.75, 0)),
        row("p3", 2, false, 0.5, score(0.2, 0.2, 0.6, 0.55, 0)),
        row("p4", 0, true, 0.3, score(0.8, 0.1, 0.1, 0.8, 0)),
        row("p5", 0, false, 0.7, score(0.1, 0.1, 0.8, 0.9, 0)),
        row("p6", 1, false, 0.5, score(0.2, 0.6, 0.2, 0.5, 0))
    );
    // exact-line predicts p1, p2, p4 as merges: 2 of 3 are gold 2, 2 of 3 gold-2 rows caught
    final var exact = DedupeBars.exactLine(rows);
    assertEquals(2.0 / 3, exact.precision(), 1e-9);
    assertEquals(2.0 / 3, exact.recall(), 1e-9);
    assertEquals(3, exact.merged());
    assertEquals("exact-line", exact.name());
    // jev at t=0.8 merges p1, p5 -> precision 0.5 recall 1/3
    final var at80 = DedupeBars.point("jev", 0.8, rows, r -> r.score().merges(0.8));
    assertEquals(0.5, at80.precision());
    assertEquals(1.0 / 3, at80.recall(), 1e-9);
    // t=0.5 and t=0.55 both merge p1,p2,p3,p5 (0.75 / 1.0); the higher threshold wins the tie
    final var jev = DedupeBars.jev(rows);
    assertEquals(0.75, jev.precision(), 1e-9);
    assertEquals(1.0, jev.recall());
    assertEquals(0.55, jev.threshold(), 1e-9);
    // jaccard: t=0.4 merges p1,p2,p3,p5,p6 -> 0.6 / 1.0; t=0.45 drops p2 -> recall 2/3, ineligible
    final var jaccard = DedupeBars.jaccard(rows);
    assertEquals(0.6, jaccard.precision(), 1e-9);
    assertEquals(1.0, jaccard.recall());
    assertEquals(0.4, jaccard.threshold(), 1e-9);
    assertEquals("jaccard", jaccard.name());
    final var empty = DedupeBars.point("x", 0, List.of(), r -> true);
    assertEquals(0.0, empty.precision());
    assertEquals(0.0, empty.recall());
    assertEquals(0, empty.merged());
  }

  @Test
  void whenNoThresholdReachesTheFloorTheMostRecallWins() {
    // q1..q3 gold 2; only q3 ever merges, so recall tops out at 1/3 for t <= 1.0
    final var rows = List.of(
        row("q1", 2, false, 0.1, score(0.9, 0.05, 0.05, 0.9, 0)),
        row("q2", 2, false, 0.1, score(0.9, 0.05, 0.05, 0.9, 0)),
        row("q3", 2, false, 0.9, score(0.0, 0.0, 1.0, 1.0, 0)),
        row("q4", 0, false, 0.2, score(0.1, 0.1, 0.8, 0.6, 0))
    );
    final var jev = DedupeBars.jev(rows);
    assertEquals(1.0 / 3, jev.recall(), 1e-9);
    // t <= 0.6 merges q3 and q4 (precision 0.5); t > 0.6 merges only q3 (precision 1.0):
    // same recall, so the higher precision wins, and among those the lowest threshold
    assertEquals(1.0, jev.precision());
    assertEquals(0.65, jev.threshold(), 1e-9);
    final var jaccard = DedupeBars.jaccard(rows);
    // t <= 0.1 merges everything (recall 1.0, eligible): precision 3/4 at t=0.05 and t=0.1; tie -> 0.1
    assertEquals(0.75, jaccard.precision(), 1e-9);
    assertEquals(0.1, jaccard.threshold(), 1e-9);
  }

  @Test
  void aRecallExactlyAtTheFloorIsEligible() {
    final var rows = new java.util.ArrayList<DedupeBars.Row>();
    for (int i = 0; i < 7; i++) {
      rows.add(row("m" + i, 2, false, 0.5, score(0.0, 0.1, 0.9, 0.8, 0)));
    }
    for (int i = 0; i < 3; i++) {
      rows.add(row("u" + i, 2, false, 0.5, score(0.9, 0.05, 0.05, 0.9, 0)));
    }
    rows.add(row("wrong", 0, false, 0.5, score(0.0, 0.1, 0.9, 0.5, 0)));
    // recall is exactly 0.70 for every t in 0.5..0.8; precision is 7/8 at t=0.5 and 1.0 above it,
    // so the eligible set picks 1.0 at its highest threshold, 0.8
    final var jev = DedupeBars.jev(rows);
    assertEquals(0.7, jev.recall(), 1e-12);
    assertEquals(1.0, jev.precision());
    assertEquals(0.8, jev.threshold(), 1e-9);
  }

  @Test
  void safetySuppressionMiddleRecallAndCorrelation() {
    final var rows = List.of(
        row("p1", 2, true, 0.6, score(0.0, 0.1, 0.9, 0.9, 0)),
        row("p2", 2, true, 0.4, score(0.1, 0.1, 0.8, 0.75, 0)),
        row("p4", 0, true, 0.3, score(0.8, 0.1, 0.1, 0.8, 0)),
        row("p5", 0, false, 0.7, score(0.1, 0.1, 0.8, 0.9, 0)),
        row("p6", 1, false, 0.5, score(0.2, 0.6, 0.2, 0.5, 0)),
        row("p7", 1, false, 0.5, score(0.2, 0.2, 0.6, 0.5, 0))
    );
    assertEquals(List.of("p5"), DedupeBars.mergeSafetyViolations(rows).stream().map(DedupeBars.Row::pairId).toList());
    assertEquals(0.5, DedupeBars.suppression(rows), "p1 merges at 0.8, p2 does not");
    assertEquals(0.5, DedupeBars.middleRecall(rows));
    assertEquals(0.0, DedupeBars.middleRecall(List.of(rows.getFirst())), "no gold-1 rows");
    final var confusion = DedupeBars.confusion(rows);
    assertEquals(2, confusion.count("2", "2"));
    assertEquals(1, confusion.count("0", "2"));
    assertEquals(1, confusion.count("1", "1"));
    assertEquals(1, confusion.count("1", "2"));
    assertEquals(Map.of("2", 2, "0", 2, "1", 2), DedupeBars.goldHistogram(rows));
    final var verdict = DedupeBars.verdict(rows);
    assertEquals(1, verdict.violations().size());
    assertFalse(verdict.checks().getFirst().pass());
    assertEquals("1", verdict.checks().getFirst().value());
    assertFalse(verdict.keep());
  }

  @Test
  void lexicalCorrelationIsExactOnAlignedSeries() {
    final var aligned = List.of(
        row("a", 0, false, 0.1, score(0.9, 0.0, 0.1, 0.9, 0)),
        row("b", 0, false, 0.5, score(0.5, 0.0, 0.5, 0.5, 0)),
        row("c", 2, false, 0.9, score(0.1, 0.0, 0.9, 0.9, 0))
    );
    assertEquals(1.0, DedupeBars.lexicalCorrelation(aligned), 1e-9);
    final var opposed = List.of(
        row("a", 0, false, 0.9, score(0.9, 0.0, 0.1, 0.9, 0)),
        row("b", 0, false, 0.5, score(0.5, 0.0, 0.5, 0.5, 0)),
        row("c", 2, false, 0.1, score(0.1, 0.0, 0.9, 0.9, 0))
    );
    assertEquals(-1.0, DedupeBars.lexicalCorrelation(opposed), 1e-9);
  }

  @Test
  void aCleanRunKeeps() {
    final var rows = List.of(
        row("a", 2, true, 0.6, score(0.0, 0.05, 0.95, 0.95, 0)),
        row("b", 2, false, 0.2, score(0.0, 0.1, 0.9, 0.9, 0)),
        row("c", 2, false, 0.1, score(0.05, 0.1, 0.85, 0.85, 0)),
        row("d", 0, true, 0.5, score(0.9, 0.05, 0.05, 0.9, 0)),
        row("e", 0, true, 0.6, score(0.85, 0.1, 0.05, 0.85, 0)),
        row("f", 0, false, 0.05, score(0.9, 0.05, 0.05, 0.9, 0)),
        row("g", 1, false, 0.3, score(0.2, 0.7, 0.1, 0.7, 0)),
        row("h", 1, false, 0.3, score(0.1, 0.6, 0.3, 0.6, 0))
    );
    final var verdict = DedupeBars.verdict(rows);
    assertTrue(verdict.violations().isEmpty());
    assertEquals(1.0, verdict.suppression());
    assertEquals(1.0, verdict.jev().precision());
    assertEquals(1.0 / 3, verdict.exactLine().precision(), 1e-9);
    assertTrue(verdict.jaccard().precision() <= 0.6, "jaccard " + verdict.jaccard());
    assertEquals(1.0, verdict.middleRecall());
    assertTrue(verdict.lexicalCorrelation() < 0.8, "correlation " + verdict.lexicalCorrelation());
    assertEquals(7, verdict.checks().size());
    assertTrue(verdict.checks().stream().allMatch(DedupeBars.Check::pass), verdict.checks().toString());
    assertTrue(verdict.keep());
  }

  @Test
  void everyCheckIsInclusiveAtItsBoundary() {
    assertTrue(DedupeBars.Check.atLeast("x", 0.95, 0.95).pass());
    assertFalse(DedupeBars.Check.atLeast("x", 0.9499, 0.95).pass());
    assertTrue(DedupeBars.Check.atLeast("x", 0.9501, 0.95).pass());
    assertTrue(DedupeBars.Check.atLeast("x", 0.95 - 0.75, 0.20).pass(), "a double difference just under the bar rounds onto it");
    assertTrue(DedupeBars.Check.atMost("x", 0.8, 0.8).pass());
    assertFalse(DedupeBars.Check.atMost("x", 0.8001, 0.8).pass());
    assertTrue(DedupeBars.Check.atMost("x", 0.80004, 0.8).pass(), "below half a unit at four decimals rounds down");
    assertEquals(0.2, DedupeBars.Check.round(0.19999999999999996));
    assertEquals(0.9499, DedupeBars.Check.round(0.94994));
    assertEquals(new DedupeBars.Check("x", "0.950", ">= 0.950", true), DedupeBars.Check.atLeast("x", 0.95, 0.95));
    assertEquals(new DedupeBars.Check("y", "0.800", "<= 0.800", true), DedupeBars.Check.atMost("y", 0.8, 0.8));

    final var point = (java.util.function.Function<Double, DedupeBars.OperatingPoint>) p -> new DedupeBars.OperatingPoint("n", 0.5, p, 1.0, 1);
    final var base = new DedupeBars.Verdict(List.of(), 0.70, 0.50, point.apply(0.95), point.apply(0.75), point.apply(0.80), 0.8);
    assertTrue(base.keep(), "every bar exactly at its boundary passes: " + base.checks());
    assertFalse(new DedupeBars.Verdict(List.of(), 0.6999, 0.50, point.apply(0.95), point.apply(0.75), point.apply(0.80), 0.8).keep());
    assertFalse(new DedupeBars.Verdict(List.of(), 0.70, 0.4999, point.apply(0.95), point.apply(0.75), point.apply(0.80), 0.8).keep());
    assertFalse(new DedupeBars.Verdict(List.of(), 0.70, 0.50, point.apply(0.9499), point.apply(0.70), point.apply(0.75), 0.8).keep());
    assertFalse(new DedupeBars.Verdict(List.of(), 0.70, 0.50, point.apply(0.95), point.apply(0.7501), point.apply(0.80), 0.8).keep(), "exact-line margin");
    assertFalse(new DedupeBars.Verdict(List.of(), 0.70, 0.50, point.apply(0.95), point.apply(0.75), point.apply(0.8001), 0.8).keep(), "jaccard margin");
    assertFalse(new DedupeBars.Verdict(List.of(), 0.70, 0.50, point.apply(0.95), point.apply(0.75), point.apply(0.80), 0.8001).keep());
    final var violation = row("v", 0, false, 0.1, score(0, 0, 1, 1, 0));
    assertFalse(new DedupeBars.Verdict(List.of(violation), 0.70, 0.50, point.apply(0.95), point.apply(0.75), point.apply(0.80), 0.8).keep());
    // the margins are differences: a tiny exact-line precision with a huge jev precision passes, not their sum
    assertTrue(new DedupeBars.Verdict(List.of(), 1.0, 1.0, point.apply(1.0), point.apply(0.0), point.apply(0.0), 0.0).keep());
    assertFalse(new DedupeBars.Verdict(List.of(), 1.0, 1.0, point.apply(0.96), point.apply(0.80), point.apply(0.0), 0.0).keep(), "0.96 - 0.80 < 0.20");
    assertFalse(new DedupeBars.Verdict(List.of(), 1.0, 1.0, point.apply(0.96), point.apply(0.0), point.apply(0.85), 0.0).keep(), "0.96 - 0.85 < 0.15");
  }

  @Test
  void thresholdsAreInclusiveAndRounded() {
    assertArrayEquals(new double[]{0.5, 0.75, 1.0}, DedupeBars.thresholds(0.5, 1.0, 0.25), 1e-12);
    assertArrayEquals(new double[]{0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0}, DedupeBars.thresholds(0.5, 1.0, 0.05), 1e-12);
    final var fine = DedupeBars.thresholds(0.05, 1.0, 0.05);
    assertEquals(20, fine.length);
    assertEquals(0.05, fine[0], 1e-12);
    assertEquals(0.1, fine[1], 1e-12);
    assertEquals(1.0, fine[19], 1e-12);
    assertArrayEquals(new double[]{0.3}, DedupeBars.thresholds(0.3, 0.3, 0.1), 1e-12);
  }
}
