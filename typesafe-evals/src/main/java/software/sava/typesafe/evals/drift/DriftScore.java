package software.sava.typesafe.evals.drift;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one change, reduced to what the bars use; `affected` is the pre-registered
/// score, P(contradicted_by_change) + P(needs_addition).
public record DriftScore(String choice, double pContradicted, double pNeedsAddition, double pUnaffected, double pNotCheckable,
                         double confidence) {

  public static DriftScore of(final SystemOneResponse response) {
    final var choice = response.choice(DriftQuestions.AFFECTED);
    return new DriftScore(
        choice.choice(),
        choice.probability(DriftQuestions.CONTRADICTED),
        choice.probability(DriftQuestions.NEEDS_ADDITION),
        choice.probability(DriftQuestions.UNAFFECTED),
        choice.probability(DriftQuestions.NOT_CHECKABLE),
        choice.confidence()
    );
  }

  public double affected() {
    return pContradicted + pNeedsAddition;
  }
}
