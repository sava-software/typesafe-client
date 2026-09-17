package software.sava.typesafe.evals.hardening;

import java.util.List;

/// One accepted-baseline row with everything the experiment needs: its README family, the
/// member at HEAD, the REAL state, and the operator the SWAPPED arm substitutes.
///
/// @param id            `module#suite#class.method#mutator#status#line`, unique per corpus
/// @param module        `repo/module-path`
/// @param memberStatus  `RESOLVED`, `MISSING_MEMBER` (type found, no such member), or `MISSING_TYPE`
/// @param state         the REAL state; null when the member is not resolved (not scorable)
/// @param swappedMutator the operator (of another family) whose description the SWAPPED arm shows
/// @param wordReal      the mutator-word baseline on the REAL description: paragraph mentions its family
/// @param wordSwapped   the same baseline on the SWAPPED description
public record HardeningRow(String id,
                           String module,
                           BaselineRow row,
                           ReadmeFamilies.Family family,
                           String memberStatus,
                           int declarations,
                           int bodiesShown,
                           int paragraphChars,
                           HardeningQuestions.State state,
                           String swappedMutator,
                           boolean wordReal,
                           boolean wordSwapped,
                           List<String> identifiersMissing) {

  public boolean scorable() {
    return state != null;
  }

  public HardeningQuestions.State swappedState() {
    return state.withDescription(MutatorDescriptions.describe(swappedMutator).description());
  }

  public String label() {
    return row.label();
  }
}
