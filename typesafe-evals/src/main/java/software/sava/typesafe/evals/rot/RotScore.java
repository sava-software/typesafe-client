package software.sava.typesafe.evals.rot;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one row, reduced to what the bars use.
public record RotScore(String choice, double pAbsent, double pPresent, double pCannot, double confidence,
                       double contradicted, double dependsOnUnseen) {

  public static RotScore of(final SystemOneResponse response) {
    final var construct = response.choice(RotQuestions.CONSTRUCT);
    return new RotScore(
        construct.choice(),
        construct.probability(RotQuestions.CONSTRUCT_ABSENT),
        construct.probability(RotQuestions.CONSTRUCT_PRESENT),
        construct.probability(RotQuestions.CANNOT_RESOLVE),
        construct.confidence(),
        response.noul(RotQuestions.CONTRADICTED).noul(),
        response.noul(RotQuestions.DEPENDS_ON_UNSEEN).noul()
    );
  }

  public boolean saysAbsent() {
    return RotQuestions.CONSTRUCT_ABSENT.equals(choice);
  }

  /// A confident "present": the answer that silently retains a rotted acceptance.
  public boolean confidentlyPresent(final double minConfidence) {
    return RotQuestions.CONSTRUCT_PRESENT.equals(choice) && confidence >= minConfidence;
  }
}
