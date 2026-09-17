package software.sava.typesafe.evals.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The arithmetic every experiment bar is stated in. Inputs are already ranked or paired;
/// nothing here reads a file or calls a model.
public final class Metrics {

  /// Precision of the first `k` items of a ranking, where `positive.get(i)` says whether
  /// the item ranked `i` is a true positive. Fewer than `k` items uses what is there.
  public static double precisionAtK(final List<Boolean> positive, final int k) {
    if (k < 1) {
      throw new IllegalArgumentException("k must be at least 1");
    }
    final int n = Math.min(k, positive.size());
    if (n == 0) {
      return 0.0;
    }
    int hits = 0;
    for (int i = 0; i < n; i++) {
      if (positive.get(i)) {
        ++hits;
      }
    }
    return (double) hits / n;
  }

  /// The share of all positives that appear within the top `fraction` of the ranking
  /// (rounded up to whole items). No positives at all scores 1: nothing was missed.
  public static double recallWithinTop(final List<Boolean> positive, final double fraction) {
    if (fraction <= 0 || fraction > 1) {
      throw new IllegalArgumentException("fraction must be in (0, 1]");
    }
    int total = 0;
    for (final var p : positive) {
      if (p) {
        ++total;
      }
    }
    if (total == 0) {
      return 1.0;
    }
    final int window = (int) Math.ceil(fraction * positive.size());
    int found = 0;
    for (int i = 0; i < window; i++) {
      if (positive.get(i)) {
        ++found;
      }
    }
    return (double) found / total;
  }

  /// Counts of (gold, predicted) pairs over a fixed label order.
  public record Confusion(List<String> labels, int[][] counts) {

    public int count(final String gold, final String predicted) {
      return counts[labels.indexOf(gold)][labels.indexOf(predicted)];
    }

    public int total() {
      int total = 0;
      for (final var row : counts) {
        for (final int c : row) {
          total += c;
        }
      }
      return total;
    }

    public double accuracy() {
      final int total = total();
      if (total == 0) {
        return 0.0;
      }
      int agree = 0;
      for (int i = 0; i < labels.size(); i++) {
        agree += counts[i][i];
      }
      return (double) agree / total;
    }

    /// Precision of `label`: of everything predicted as it, how much was it.
    public double precision(final String label) {
      final int column = labels.indexOf(label);
      int predicted = 0;
      for (final var row : counts) {
        predicted += row[column];
      }
      return predicted == 0 ? 0.0 : (double) counts[column][column] / predicted;
    }

    /// Recall of `label`: of everything that was it, how much was predicted as it.
    public double recall(final String label) {
      final int row = labels.indexOf(label);
      int gold = 0;
      for (final int c : counts[row]) {
        gold += c;
      }
      return gold == 0 ? 0.0 : (double) counts[row][row] / gold;
    }

    public String render() {
      final var out = new StringBuilder();
      out.append("gold \\ predicted");
      for (final var label : labels) {
        out.append('\t').append(label);
      }
      out.append('\n');
      for (int i = 0; i < labels.size(); i++) {
        out.append(labels.get(i));
        for (int j = 0; j < labels.size(); j++) {
          out.append('\t').append(counts[i][j]);
        }
        out.append('\n');
      }
      return out.toString();
    }
  }

  /// @throws IllegalArgumentException when a label is not in `labels` or the lists differ in size
  public static Confusion confusion(final List<String> labels, final List<String> gold, final List<String> predicted) {
    if (gold.size() != predicted.size()) {
      throw new IllegalArgumentException("gold has " + gold.size() + " rows, predicted has " + predicted.size());
    }
    final var counts = new int[labels.size()][labels.size()];
    for (int i = 0; i < gold.size(); i++) {
      final int g = labels.indexOf(gold.get(i));
      final int p = labels.indexOf(predicted.get(i));
      if (g < 0 || p < 0) {
        throw new IllegalArgumentException("row " + i + " uses a label outside " + labels + ": " + gold.get(i) + " / " + predicted.get(i));
      }
      counts[g][p]++;
    }
    return new Confusion(List.copyOf(labels), counts);
  }

  /// One calibration bucket: items whose confidence fell in [low, high), how many were
  /// correct, and the mean confidence, so a report can show whether 0.9 means 90%.
  public record Bucket(double low, double high, int items, int correct, double meanConfidence) {

    public double accuracy() {
      return items == 0 ? 0.0 : (double) correct / items;
    }
  }

  /// Equal-width buckets over [0, 1]; a confidence of exactly 1 lands in the last bucket.
  public static List<Bucket> calibration(final List<Double> confidence, final List<Boolean> correct, final int buckets) {
    if (buckets < 1) {
      throw new IllegalArgumentException("buckets must be at least 1");
    }
    if (confidence.size() != correct.size()) {
      throw new IllegalArgumentException("confidence has " + confidence.size() + " rows, correct has " + correct.size());
    }
    final var items = new int[buckets];
    final var right = new int[buckets];
    final var sum = new double[buckets];
    for (int i = 0; i < confidence.size(); i++) {
      final double c = confidence.get(i);
      if (c < 0 || c > 1) {
        throw new IllegalArgumentException("confidence out of range: " + c);
      }
      final int bucket = Math.min(buckets - 1, (int) Math.floor(c * buckets));
      items[bucket]++;
      sum[bucket] += c;
      if (correct.get(i)) {
        right[bucket]++;
      }
    }
    final var out = new ArrayList<Bucket>(buckets);
    for (int b = 0; b < buckets; b++) {
      out.add(new Bucket((double) b / buckets, (double) (b + 1) / buckets, items[b], right[b],
          items[b] == 0 ? 0.0 : sum[b] / items[b]));
    }
    return out;
  }

  /// Pearson correlation of two equal-length series; 0 when either has no variance.
  public static double pearson(final double[] a, final double[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("series differ in length: " + a.length + " vs " + b.length);
    }
    final int n = a.length;
    // an empty series has no variance, so the variance check below already answers 0
    double meanA = 0;
    double meanB = 0;
    for (int i = 0; i < n; i++) {
      meanA += a[i];
      meanB += b[i];
    }
    meanA /= n;
    meanB /= n;
    double cov = 0;
    double varA = 0;
    double varB = 0;
    for (int i = 0; i < n; i++) {
      final double da = a[i] - meanA;
      final double db = b[i] - meanB;
      cov += da * db;
      varA += da * da;
      varB += db * db;
    }
    return varA == 0 || varB == 0 ? 0.0 : cov / Math.sqrt(varA * varB);
  }

  /// Counts per label, in first-seen order; a small helper for report tables.
  public static Map<String, Integer> histogram(final List<String> labels) {
    final var out = new LinkedHashMap<String, Integer>();
    for (final var label : labels) {
      out.merge(label, 1, Integer::sum);
    }
    return out;
  }

  private Metrics() {
  }
}
