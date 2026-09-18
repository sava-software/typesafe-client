package software.sava.typesafe;

import java.util.Map;

/// The answer to a [Score]: the probability-weighted mean level, the distribution over levels
/// (keyed by level number, ascending), the level descriptions echoed back, and a confidence
/// in 0..1. Different distributions can share a score; read `probabilities` alongside it.
///
/// A legend value is whatever criterion entry was sent for that level, so it is a JSON value
/// and not always a string: a wire string arrives as [JsonContent.Text], an object, array,
/// number or boolean as [JsonContent.Raw] holding its JSON text, and a wire `null` as a null
/// value in the map. Use [#legendText(int)] for the plain-text reading of a level.
public record ScoreAnswer(double score,
                          Map<Integer, Double> probabilities,
                          Map<Integer, JsonContent> legend,
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

  /// The description of `level` as text: the string itself for a text level, its JSON text for
  /// a structured one, and null when the level is absent or was echoed back as `null`.
  public String legendText(final int level) {
    final var entry = legend.get(level);
    return switch (entry) {
      case null -> null;
      case JsonContent.Text text -> text.value();
      default -> entry.toJson();
    };
  }
}
