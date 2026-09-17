package software.sava.typesafe.evals.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MetricsTests {

  private static final List<Boolean> RANKED = List.of(true, true, false, true, false, false, false, true, false, false);

  @Test
  void precisionAtK() {
    assertEquals(1.0, Metrics.precisionAtK(RANKED, 2));
    assertEquals(2.0 / 3, Metrics.precisionAtK(RANKED, 3));
    assertEquals(0.4, Metrics.precisionAtK(RANKED, 10));
    assertEquals(0.4, Metrics.precisionAtK(RANKED, 50), "k beyond the list uses the whole list");
    assertEquals(0.0, Metrics.precisionAtK(List.of(), 5));
    assertEquals(1.0, Metrics.precisionAtK(RANKED, 1), "k = 1 is the smallest legal k");
    assertEquals(0.0, Metrics.precisionAtK(List.of(false), 1));
    assertThrows(IllegalArgumentException.class, () -> Metrics.precisionAtK(RANKED, 0));
  }

  @Test
  void recallWithinTopFraction() {
    // 4 positives; top 30% of 10 items is 3 items holding 2 positives
    assertEquals(0.5, Metrics.recallWithinTop(RANKED, 0.3));
    assertEquals(0.75, Metrics.recallWithinTop(RANKED, 0.5));
    assertEquals(1.0, Metrics.recallWithinTop(RANKED, 1.0));
    assertEquals(0.25, Metrics.recallWithinTop(RANKED, 0.01), "fraction rounds up to one item");
    assertEquals(1.0, Metrics.recallWithinTop(List.of(false, false), 0.5), "no positives: nothing missed");
    assertThrows(IllegalArgumentException.class, () -> Metrics.recallWithinTop(RANKED, 0.0));
    assertThrows(IllegalArgumentException.class, () -> Metrics.recallWithinTop(RANKED, 1.5));
  }

  @Test
  void confusionMatrix() {
    final var labels = List.of("present", "absent", "unsure");
    final var gold = List.of("present", "present", "absent", "absent", "unsure", "present");
    final var predicted = List.of("present", "absent", "absent", "present", "unsure", "present");
    final var confusion = Metrics.confusion(labels, gold, predicted);
    assertEquals(6, confusion.total());
    assertEquals(2, confusion.count("present", "present"));
    assertEquals(1, confusion.count("present", "absent"));
    assertEquals(1, confusion.count("absent", "present"));
    assertEquals(0, confusion.count("unsure", "present"));
    assertEquals(4.0 / 6, confusion.accuracy(), 1e-12);
    assertEquals(2.0 / 3, confusion.precision("present"), 1e-12);
    assertEquals(2.0 / 3, confusion.recall("present"), 1e-12);
    assertEquals(0.5, confusion.precision("absent"));
    assertEquals(0.5, confusion.recall("absent"));
    assertEquals(1.0, confusion.recall("unsure"));
    assertEquals("""
        gold \\ predicted\tpresent\tabsent\tunsure
        present\t2\t1\t0
        absent\t1\t1\t0
        unsure\t0\t0\t1
        """, confusion.render());
    final var empty = Metrics.confusion(labels, List.of(), List.of());
    assertEquals(0.0, empty.accuracy());
    assertEquals(0.0, empty.precision("present"));
    assertEquals(0.0, empty.recall("present"));
    assertThrows(IllegalArgumentException.class, () -> Metrics.confusion(labels, List.of("present"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> Metrics.confusion(labels, List.of("nope"), List.of("present")));
    assertThrows(IllegalArgumentException.class, () -> Metrics.confusion(labels, List.of("present"), List.of("nope")));
  }

  @Test
  void calibrationBuckets() {
    final var confidence = List.of(0.05, 0.15, 0.55, 0.95, 1.0, 0.5);
    final var correct = List.of(false, true, true, true, true, false);
    final var buckets = Metrics.calibration(confidence, correct, 2);
    assertEquals(2, buckets.size());
    final var low = buckets.getFirst();
    assertEquals(0.0, low.low());
    assertEquals(0.5, low.high());
    assertEquals(2, low.items());
    assertEquals(1, low.correct());
    assertEquals(0.1, low.meanConfidence(), 1e-12);
    assertEquals(0.5, low.accuracy());
    final var high = buckets.getLast();
    assertEquals(0.5, high.low());
    assertEquals(1.0, high.high());
    assertEquals(4, high.items());
    assertEquals(3, high.correct());
    assertEquals(0.75, high.accuracy());
    assertEquals((0.55 + 0.95 + 1.0 + 0.5) / 4, high.meanConfidence(), 1e-12);
    final var ten = Metrics.calibration(List.of(1.0), List.of(true), 10);
    assertEquals(1, ten.get(9).items(), "a confidence of exactly 1 lands in the last bucket");
    assertEquals(0.0, ten.get(0).accuracy());
    assertEquals(0.0, ten.get(0).meanConfidence());
    final var one = Metrics.calibration(List.of(0.0, 0.4, 1.0), List.of(true, false, true), 1);
    assertEquals(1, one.size(), "one bucket is the smallest legal count");
    assertEquals(3, one.getFirst().items());
    assertEquals(2, one.getFirst().correct());
    assertEquals(0.0, one.getFirst().low());
    assertEquals(1.0, one.getFirst().high());
    assertEquals(0.0, Metrics.calibration(List.of(0.0), List.of(false), 4).getFirst().meanConfidence(), "a confidence of exactly 0 is legal");
    assertEquals(1, Metrics.calibration(List.of(0.0), List.of(false), 4).getFirst().items());
    assertThrows(IllegalArgumentException.class, () -> Metrics.calibration(confidence, correct, 0));
    assertThrows(IllegalArgumentException.class, () -> Metrics.calibration(List.of(0.5), List.of(), 2));
    assertThrows(IllegalArgumentException.class, () -> Metrics.calibration(List.of(1.5), List.of(true), 2));
    assertThrows(IllegalArgumentException.class, () -> Metrics.calibration(List.of(-0.1), List.of(true), 2));
  }

  @Test
  void pearson() {
    assertEquals(1.0, Metrics.pearson(new double[]{1, 2, 3}, new double[]{2, 4, 6}), 1e-12);
    assertEquals(-1.0, Metrics.pearson(new double[]{1, 2, 3}, new double[]{6, 4, 2}), 1e-12);
    assertEquals(0.0, Metrics.pearson(new double[]{1, 1, 1}, new double[]{1, 2, 3}));
    assertEquals(0.0, Metrics.pearson(new double[]{1, 2, 3}, new double[]{2, 2, 2}));
    assertEquals(0.0, Metrics.pearson(new double[]{}, new double[]{}));
    // r for (1,2,3,4) vs (1,3,2,4) is 0.8
    assertEquals(0.8, Metrics.pearson(new double[]{1, 2, 3, 4}, new double[]{1, 3, 2, 4}), 1e-12);
    assertThrows(IllegalArgumentException.class, () -> Metrics.pearson(new double[]{1}, new double[]{}));
  }

  @Test
  void aurocIsTheMannWhitneyStatistic() {
    assertEquals(1.0, Metrics.auroc(List.of(0.9, 0.8), List.of(0.1, 0.2)));
    assertEquals(0.0, Metrics.auroc(List.of(0.1, 0.2), List.of(0.9, 0.8)));
    assertEquals(0.5, Metrics.auroc(List.of(0.5), List.of(0.5)), "a tie counts one half");
    // wins: 0.9>0.1, 0.9>0.5, 0.5>0.1, 0.5==0.5 -> 3.5 of 4
    assertEquals(0.875, Metrics.auroc(List.of(0.9, 0.5), List.of(0.1, 0.5)));
    assertTrue(Double.isNaN(Metrics.auroc(List.of(), List.of(0.1))));
    assertTrue(Double.isNaN(Metrics.auroc(List.of(0.1), List.of())));
  }

  @Test
  void aurocIntervalIsDeterministicAndBracketsThePointEstimate() {
    final var positives = List.of(0.9, 0.8, 0.7, 0.4, 0.6, 0.95);
    final var negatives = List.of(0.1, 0.3, 0.5, 0.45, 0.2, 0.35);
    final var point = Metrics.auroc(positives, negatives);
    final var interval = Metrics.aurocInterval(positives, negatives, 200, 7L);
    assertEquals(2, interval.length);
    assertTrue(interval[0] <= point && point <= interval[1], java.util.Arrays.toString(interval) + " around " + point);
    assertTrue(interval[0] < interval[1], "the resamples are not all identical");
    assertArrayEquals(interval, Metrics.aurocInterval(positives, negatives, 200, 7L), "same seed, same interval");
    final var other = Metrics.aurocInterval(positives, negatives, 200, 8L);
    assertTrue(other[0] <= point && point <= other[1], "another seed still brackets the point estimate");
    final var perfect = Metrics.aurocInterval(List.of(0.9, 0.8), List.of(0.1, 0.2), 50, 1L);
    assertArrayEquals(new double[]{1.0, 1.0}, perfect, "every resample of a perfect ranker is perfect");
    // the percentile indexes: with 200 resamples the lower is floor(0.025*199)=4, the upper ceil(0.975*199)=195
    final var narrow = Metrics.aurocInterval(List.of(0.9, 0.1), List.of(0.5, 0.5), 200, 3L);
    assertTrue(narrow[0] >= 0.0 && narrow[1] <= 1.0);
    assertTrue(Double.isNaN(Metrics.aurocInterval(List.of(), List.of(), 10, 1L)[0]));
    assertTrue(Double.isNaN(Metrics.aurocInterval(List.of(0.5), List.of(0.5), 0, 1L)[1]), "no resamples, no interval");
    assertThrows(IllegalArgumentException.class, () -> Metrics.aurocInterval(List.of(0.5), List.of(), 10, 1L));
  }

  @Test
  void histogramKeepsFirstSeenOrder() {
    assertEquals(Map.of("b", 2, "a", 1), Metrics.histogram(List.of("b", "a", "b")));
    assertEquals(List.of("b", "a"), List.copyOf(Metrics.histogram(List.of("b", "a", "b")).keySet()));
    assertEquals(Map.of(), Metrics.histogram(List.of()));
  }
}
