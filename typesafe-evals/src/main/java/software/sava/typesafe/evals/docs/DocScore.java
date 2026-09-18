package software.sava.typesafe.evals.docs;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one arm of one documented member, reduced to what the bars use.
public record DocScore(String choice, double pConsistent, double pContradicted, double pNotCheckable, double confidence) {

  public static DocScore of(final SystemOneResponse response) {
    final var agreement = response.choice(DocQuestions.AGREEMENT);
    return new DocScore(
        agreement.choice(),
        agreement.probability(DocQuestions.CONSISTENT),
        agreement.probability(DocQuestions.CONTRADICTED),
        agreement.probability(DocQuestions.NOT_CHECKABLE),
        agreement.confidence()
    );
  }
}
