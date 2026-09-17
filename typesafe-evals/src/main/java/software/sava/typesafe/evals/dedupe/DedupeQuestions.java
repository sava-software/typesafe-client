package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.Noul;
import software.sava.typesafe.NoulCriteria;
import software.sava.typesafe.Score;
import software.sava.typesafe.SystemOneRequest;

import java.util.List;

/// Experiment B, the questions. One request per pair of findings from parallel finders:
/// are they the same defect? The Score's three described levels are the entity-alignment
/// recipe, so no threshold has to be fitted: level 0 keeps both, level 2 merges, level 1
/// goes to the human. Wording is pinned by the tests.
public final class DedupeQuestions {

  public static final String RELATION = "relation";
  public static final int DIFFERENT_DEFECTS = 0;
  public static final int SAME_DEFECT_NARROWED = 1;
  public static final int SAME_DEFECT_RESTATED = 2;
  public static final String CLAIMS_NEW_EVIDENCE = "claims_new_evidence";

  /// One side of a pair: prose only. File, line, severity, and category are deliberately
  /// withheld: irrelevant state distracts, and a line number invites number comparison.
  public record Finding(String summary, String failureScenario) {

    public JsonContent toJson() {
      return JsonContent.object()
          .put("summary", summary)
          .put("failure_scenario", failureScenario)
          .build();
    }
  }

  /// Code-computed facts for the ablation arm; null fields are omitted.
  public record Context(Boolean sameFile, Integer lineDelta, Double cosine) {

    public static final Context NONE = new Context(null, null, null);
  }

  public static JsonContent state(final Finding a, final Finding b, final Context context) {
    final var state = JsonContent.object()
        .put("a", a.toJson())
        .put("b", b.toJson());
    if (context.sameFile() != null) {
      state.put("same_file", context.sameFile());
    }
    if (context.lineDelta() != null) {
      state.put("line_delta", (long) context.lineDelta());
    }
    if (context.cosine() != null) {
      state.put("cosine", context.cosine());
    }
    return state.build();
  }

  public static SystemOneRequest request(final Finding a, final Finding b, final Context context) {
    return SystemOneRequest.builder()
        .state(state(a, b, context))
        .question(RELATION, relation())
        .question(CLAIMS_NEW_EVIDENCE, claimsNewEvidence())
        .build();
  }

  static Score relation() {
    return new Score(JsonContent.object()
        .put("question", "How does the defect described by `b` relate to the defect described by `a`?")
        .put("focus", "Compare the defects, not the wording. Two findings about the same code can still be different defects.")
        .build(), List.of(
        JsonContent.text("Different defects: fixing the defect `a` describes would leave the defect `b` describes unfixed, even when both sit in the same code."),
        JsonContent.text("The same underlying defect, but `b` narrows it, re-scopes which instances or inputs it covers, or reports it at a different place. Not a different defect in adjacent code."),
        JsonContent.text("The same defect restated in different words: fixing one fixes the other.")));
  }

  static Noul claimsNewEvidence() {
    return new Noul(JsonContent.text("Does `b` cite evidence that `a` does not?"),
        NoulCriteria.of(
            "`b` names a file, test, command, observation, or reproduction that `a` does not mention.",
            "`b` cites nothing beyond what `a` already cites, or cites nothing at all."));
  }

  private DedupeQuestions() {
  }
}
