package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.SystemOneResponse;

/// Jev's answer for one pair, reduced to what the bars use.
///
/// @param level       the highest-probability level (0 different, 1 narrowed, 2 restated)
/// @param pDifferent  probability of level 0, as answered (never derived, so no rounding drift)
/// @param pNarrowed   probability of level 1
/// @param pSame       probability of level 2
/// @param score       the probability-weighted mean level
/// @param confidence  concentration of the level distribution
/// @param newEvidence probability that `b` cites evidence `a` does not
public record PairScore(int level,
                        double pDifferent,
                        double pNarrowed,
                        double pSame,
                        double score,
                        double confidence,
                        double newEvidence) {

  public static PairScore of(final SystemOneResponse response) {
    final var relation = response.score(DedupeQuestions.RELATION);
    int level = 0;
    double best = -1;
    for (final var entry : relation.probabilities().entrySet()) {
      if (entry.getValue() > best) {
        best = entry.getValue();
        level = entry.getKey();
      }
    }
    return new PairScore(
        level,
        relation.probability(DedupeQuestions.DIFFERENT_DEFECTS),
        relation.probability(DedupeQuestions.SAME_DEFECT_NARROWED),
        relation.probability(DedupeQuestions.SAME_DEFECT_RESTATED),
        relation.score(),
        relation.confidence(),
        response.noul(DedupeQuestions.CLAIMS_NEW_EVIDENCE).noul()
    );
  }

  /// The pre-registered merge decision: the top level is "restated" and the distribution
  /// is at least `minConfidence` concentrated.
  public boolean merges(final double minConfidence) {
    return level == DedupeQuestions.SAME_DEFECT_RESTATED && confidence >= minConfidence;
  }

  /// The post-hoc grouping decision, added after the first live run showed same-defect
  /// findings splitting their mass between "narrowed" and "restated": the two findings
  /// describe the same underlying defect when "different" holds at most `maxDifferent`.
  public boolean sameDefect(final double maxDifferent) {
    return pDifferent() <= maxDifferent;
  }
}
