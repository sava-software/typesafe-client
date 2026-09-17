package software.sava.typesafe;

import java.util.Map;

/// The answer to a [Score]: the probability-weighted mean level, the distribution over levels
/// (keyed by level number, ascending), the level descriptions echoed back, and a confidence
/// in 0..1. Different distributions can share a score; read `probabilities` alongside it.
public record ScoreAnswer(double score,
                          Map<Integer, Double> probabilities,
                          Map<Integer, String> legend,
                          double confidence) implements Answer {

  public static final String TYPE = "score";

  @Override
  public String type() {
    return TYPE;
  }

  /// The highest level number in the legend, or -1 when the legend is empty.
  public int topLevel() {
    return legend.isEmpty() ? -1 : legend.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
  }

  /// The score divided by the top level, placing every scale on 0..1 for weighting in code.
  /// A one-level legend has nothing to normalize against and returns the score unchanged.
  public double normalized() {
    final int topLevel = topLevel();
    return topLevel <= 0 ? score : score / topLevel;
  }

  /// The probability of `level`, or 0 when the level is not in the answer.
  public double probability(final int level) {
    final var probability = probabilities.get(level);
    return probability == null ? 0.0 : probability;
  }
}
