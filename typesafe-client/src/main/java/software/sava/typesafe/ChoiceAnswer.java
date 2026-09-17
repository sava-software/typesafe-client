package software.sava.typesafe;

import java.util.Map;

/// The answer to a [Choice]: the option with the highest probability, the full distribution
/// over options (summing to 1), and a confidence in 0..1 summarizing how concentrated that
/// distribution is. Confidence is a summary of the distribution's shape, not the probability
/// of the winning option, and not a statement about overall correctness.
public record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence) implements Answer {

  public static final String TYPE = "choice";

  @Override
  public String type() {
    return TYPE;
  }

  /// The probability of `option`, or 0 when the option was not in the question.
  public double probability(final String option) {
    final var probability = probabilities.get(option);
    return probability == null ? 0.0 : probability;
  }
}
