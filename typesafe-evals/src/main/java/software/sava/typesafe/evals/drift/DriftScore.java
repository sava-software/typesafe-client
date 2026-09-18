package software.sava.typesafe.evals.drift;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one change, reduced to what the bars use.
public record DriftScore(String choice, double pAffected, double pUnaffected, double pNotCheckable, double confidence) {

  public static DriftScore of(final SystemOneResponse response) {
    final var affected = response.choice(DriftQuestions.AFFECTED);
    return new DriftScore(
        affected.choice(),
        affected.probability(DriftQuestions.AFFECTED_YES),
        affected.probability(DriftQuestions.UNAFFECTED),
        affected.probability(DriftQuestions.NOT_CHECKABLE),
        affected.confidence()
    );
  }
}
