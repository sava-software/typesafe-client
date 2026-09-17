package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/// The pre-registered bars of Experiment B, computed over labeled pairs.
///
/// A "merge" is predicting level 2. Merge safety is the number of gold-0 pairs merged at
/// confidence >= 0.8 (one is a fail). Suppression is recall on gold 2. Middle recall is
/// gold 1 predicted as 1. Baselines: exact file:line equality, and the best Jaccard
/// threshold at the same recall floor. The kill check is Pearson(P(same), Jaccard).
public final class DedupeBars {

  public static final double MERGE_CONFIDENCE = 0.8;
  /// Post-hoc rule: group when P(different) is at most this.
  public static final double MAX_DIFFERENT = 0.2;
  public static final double RECALL_FLOOR = 0.70;
  public static final double PRECISION_BAR = 0.95;
  public static final double EXACT_LINE_MARGIN = 0.20;
  public static final double JACCARD_MARGIN = 0.15;
  public static final double MIDDLE_RECALL_BAR = 0.50;
  public static final double CORRELATION_CEILING = 0.8;

  /// One labeled pair with everything the bars need.
  public record Row(String pairId, int gold, boolean exactLine, double jaccard, PairScore score) {
  }

  /// Precision and recall of "merge" over `rows` under `merge`.
  public record OperatingPoint(String name, double threshold, double precision, double recall, int merged) {
  }

  public static OperatingPoint point(final String name, final double threshold, final List<Row> rows, final Predicate<Row> merge) {
    int predicted = 0;
    int hit = 0;
    int gold2 = 0;
    for (final var row : rows) {
      final boolean isGold2 = row.gold() == DedupeQuestions.SAME_DEFECT_RESTATED;
      if (isGold2) {
        ++gold2;
      }
      if (merge.test(row)) {
        ++predicted;
        if (isGold2) {
          ++hit;
        }
      }
    }
    return new OperatingPoint(name,
        threshold,
        predicted == 0 ? 0.0 : (double) hit / predicted,
        gold2 == 0 ? 0.0 : (double) hit / gold2,
        predicted);
  }

  /// Jev merges at `level == 2 && confidence >= t`.
  public static OperatingPoint jev(final List<Row> rows) {
    return best("jev", rows, t -> row -> row.score().merges(t), thresholds(0.5, 1.0, 0.05));
  }

  /// Post-hoc rule: precision and recall of "same underlying defect" (gold 1 or 2) when
  /// grouping at P(different) <= MAX_DIFFERENT.
  public static OperatingPoint sameDefect(final List<Row> rows) {
    int predicted = 0;
    int hit = 0;
    int goldSame = 0;
    for (final var row : rows) {
      final boolean isSame = row.gold() != DedupeQuestions.DIFFERENT_DEFECTS;
      if (isSame) {
        ++goldSame;
      }
      if (row.score().sameDefect(MAX_DIFFERENT)) {
        ++predicted;
        if (isSame) {
          ++hit;
        }
      }
    }
    return new OperatingPoint("same-defect", MAX_DIFFERENT,
        predicted == 0 ? 0.0 : (double) hit / predicted,
        goldSame == 0 ? 0.0 : (double) hit / goldSame,
        predicted);
  }

  /// Gold-0 pairs the post-hoc rule would group: its merge-safety violations.
  public static List<Row> sameDefectViolations(final List<Row> rows) {
    return rows.stream()
        .filter(row -> row.gold() == DedupeQuestions.DIFFERENT_DEFECTS && row.score().sameDefect(MAX_DIFFERENT))
        .toList();
  }

  /// Jaccard merges at `jaccard >= t`.
  public static OperatingPoint jaccard(final List<Row> rows) {
    return best("jaccard", rows, t -> row -> row.jaccard() >= t, thresholds(0.05, 1.0, 0.05));
  }

  public static OperatingPoint exactLine(final List<Row> rows) {
    return point("exact-line", 0.0, rows, Row::exactLine);
  }

  /// Among thresholds that keep recall at or above the floor, the highest precision (the
  /// higher threshold on a tie); when none does, the highest recall (the higher precision,
  /// then the lower threshold, on ties).
  static OperatingPoint best(final String name,
                             final List<Row> rows,
                             final DoubleFunction<Predicate<Row>> mergeAt,
                             final double[] thresholds) {
    final var points = java.util.Arrays.stream(thresholds)
        .mapToObj(t -> point(name, t, rows, mergeAt.apply(t)))
        .toList();
    final var eligible = points.stream().filter(p -> p.recall() >= RECALL_FLOOR).toList();
    if (!eligible.isEmpty()) {
      return eligible.stream()
          .max(Comparator.comparingDouble(OperatingPoint::precision).thenComparingDouble(OperatingPoint::threshold))
          .orElseThrow();
    }
    return points.stream()
        .max(Comparator.comparingDouble(OperatingPoint::recall)
            .thenComparingDouble(OperatingPoint::precision)
            .thenComparing(Comparator.comparingDouble(OperatingPoint::threshold).reversed()))
        .orElseThrow();
  }

  /// `from`, `from + step`, ... `to`, each rounded to two decimals.
  static double[] thresholds(final double from, final double to, final double step) {
    final int steps = (int) Math.round((to - from) / step);
    return IntStream.rangeClosed(0, steps)
        .mapToDouble(i -> Math.round((from + i * step) * 100.0) / 100.0)
        .toArray();
  }

  /// Gold-0 pairs Jev would merge at the merge confidence: any is a fail.
  public static List<Row> mergeSafetyViolations(final List<Row> rows) {
    return rows.stream()
        .filter(row -> row.gold() == DedupeQuestions.DIFFERENT_DEFECTS && row.score().merges(MERGE_CONFIDENCE))
        .toList();
  }

  /// Recall on gold 2 at the merge confidence.
  public static double suppression(final List<Row> rows) {
    return point("jev", MERGE_CONFIDENCE, rows, row -> row.score().merges(MERGE_CONFIDENCE)).recall();
  }

  /// Gold-1 pairs whose top level is 1, over all gold-1 pairs; 0 when there are none.
  public static double middleRecall(final List<Row> rows) {
    int gold1 = 0;
    int hit = 0;
    for (final var row : rows) {
      if (row.gold() == DedupeQuestions.SAME_DEFECT_NARROWED) {
        ++gold1;
        if (row.score().level() == DedupeQuestions.SAME_DEFECT_NARROWED) {
          ++hit;
        }
      }
    }
    return gold1 == 0 ? 0.0 : (double) hit / gold1;
  }

  /// Pearson correlation of P(same) with Jaccard over every scored pair, labeled or not.
  public static double lexicalCorrelation(final List<Row> rows) {
    final var pSame = rows.stream().mapToDouble(row -> row.score().pSame()).toArray();
    final var jaccard = rows.stream().mapToDouble(Row::jaccard).toArray();
    return Metrics.pearson(pSame, jaccard);
  }

  public static Metrics.Confusion confusion(final List<Row> rows) {
    final var labels = List.of("0", "1", "2");
    return Metrics.confusion(labels,
        rows.stream().map(row -> Integer.toString(row.gold())).toList(),
        rows.stream().map(row -> Integer.toString(row.score().level())).toList());
  }

  /// One bar of the verdict: what was measured, what was required, and whether it passed.
  /// Values are compared at four decimals, so a difference of two ratios that is 0.2 in
  /// arithmetic but 0.19999999999999996 in doubles still meets a 0.20 bar.
  public record Check(String bar, String value, String required, boolean pass) {

    static Check atLeast(final String bar, final double value, final double required) {
      return new Check(bar, fmt(value), ">= " + fmt(required), round(value) >= required);
    }

    static Check atMost(final String bar, final double value, final double required) {
      return new Check(bar, fmt(value), "<= " + fmt(required), round(value) <= required);
    }

    static double round(final double value) {
      return Math.round(value * 10_000.0) / 10_000.0;
    }

    static String fmt(final double value) {
      return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
  }

  /// The pass/fail summary the report prints.
  public record Verdict(List<Row> violations,
                        double suppression,
                        double middleRecall,
                        OperatingPoint jev,
                        OperatingPoint exactLine,
                        OperatingPoint jaccard,
                        double lexicalCorrelation) {

    public List<Check> checks() {
      return List.of(
          new Check("merge safety (gold-0 pairs merged at >= " + MERGE_CONFIDENCE + ")",
              Integer.toString(violations.size()), "0", violations.isEmpty()),
          Check.atLeast("suppression (recall on gold 2 at >= " + MERGE_CONFIDENCE + ")", suppression, RECALL_FLOOR),
          Check.atLeast("jev precision at recall >= " + RECALL_FLOOR + " (t=" + jev.threshold() + ")", jev.precision(), PRECISION_BAR),
          Check.atLeast("jev precision minus exact-line precision (" + Check.fmt(exactLine.precision()) + ")",
              jev.precision() - exactLine.precision(), EXACT_LINE_MARGIN),
          Check.atLeast("jev precision minus jaccard precision (" + Check.fmt(jaccard.precision()) + " at t=" + jaccard.threshold() + ")",
              jev.precision() - jaccard.precision(), JACCARD_MARGIN),
          Check.atLeast("middle recall (gold 1 as 1)", middleRecall, MIDDLE_RECALL_BAR),
          Check.atMost("pearson(P(same), jaccard)", lexicalCorrelation, CORRELATION_CEILING)
      );
    }

    public boolean keep() {
      return checks().stream().allMatch(Check::pass);
    }
  }

  public static Verdict verdict(final List<Row> rows) {
    return new Verdict(
        mergeSafetyViolations(rows),
        suppression(rows),
        middleRecall(rows),
        jev(rows),
        exactLine(rows),
        jaccard(rows),
        lexicalCorrelation(rows)
    );
  }

  public static Map<String, Integer> goldHistogram(final List<Row> rows) {
    return Metrics.histogram(rows.stream().map(row -> Integer.toString(row.gold())).toList());
  }

  private DedupeBars() {
  }
}
