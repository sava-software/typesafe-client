package software.sava.typesafe.evals.hardening;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one arm of one row, reduced to what the bars use.
public record HardeningScore(String choice, double pApplies, double pDoesNotApply, double pCannot, double confidence,
                             double constructAbsent) {

  public static HardeningScore of(final SystemOneResponse response) {
    final var applies = response.choice(HardeningQuestions.APPLIES);
    return new HardeningScore(
        applies.choice(),
        applies.probability(HardeningQuestions.APPLIES_YES),
        applies.probability(HardeningQuestions.DOES_NOT_APPLY),
        applies.probability(HardeningQuestions.CANNOT_TELL),
        applies.confidence(),
        response.noul(HardeningQuestions.CONSTRUCT_ABSENT).noul()
    );
  }
}
