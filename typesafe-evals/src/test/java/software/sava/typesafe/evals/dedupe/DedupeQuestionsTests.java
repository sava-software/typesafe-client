package software.sava.typesafe.evals.dedupe;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.Noul;
import software.sava.typesafe.Score;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DedupeQuestionsTests {

  private static final DedupeQuestions.Finding A = new DedupeQuestions.Finding(
      "Signature slot count is not validated before indexing",
      "A transaction with fewer signatures than signers indexes past the array");
  private static final DedupeQuestions.Finding B = new DedupeQuestions.Finding(
      "Unchecked signer count in Transaction.sign",
      "sign() reads signatures[i] for i up to numSigners without a bounds check");

  @Test
  void proseOnlyStateByDefault() {
    assertEquals("""
        {"a":{"summary":"Signature slot count is not validated before indexing","failure_scenario":"A transaction with fewer signatures than signers indexes past the array"},"b":{"summary":"Unchecked signer count in Transaction.sign","failure_scenario":"sign() reads signatures[i] for i up to numSigners without a bounds check"}}""",
        DedupeQuestions.state(A, B, DedupeQuestions.Context.NONE).toJson());
  }

  @Test
  void theAblationArmAddsOnlyTheFactsItHas() {
    assertEquals("""
        {"a":{"summary":"s","failure_scenario":null},"b":{"summary":"t","failure_scenario":"f"},"same_file":true,"line_delta":12,"cosine":0.25}""",
        DedupeQuestions.state(new DedupeQuestions.Finding("s", null), new DedupeQuestions.Finding("t", "f"),
            new DedupeQuestions.Context(true, 12, 0.25)).toJson());
    assertEquals("""
        {"a":{"summary":"s","failure_scenario":null},"b":{"summary":"t","failure_scenario":"f"},"same_file":false}""",
        DedupeQuestions.state(new DedupeQuestions.Finding("s", null), new DedupeQuestions.Finding("t", "f"),
            new DedupeQuestions.Context(false, null, null)).toJson());
  }

  @Test
  void theRequestCarriesAScoreAndANoul() {
    final var request = DedupeQuestions.request(A, B, DedupeQuestions.Context.NONE);
    assertEquals(List.of(DedupeQuestions.RELATION, DedupeQuestions.CLAIMS_NEW_EVIDENCE), List.copyOf(request.questions().keySet()));
    final var relation = assertInstanceOf(Score.class, request.questions().get(DedupeQuestions.RELATION));
    assertEquals(DedupeQuestions.SAME_DEFECT_RESTATED, relation.topLevel());
    assertEquals(0, DedupeQuestions.DIFFERENT_DEFECTS);
    assertEquals(1, DedupeQuestions.SAME_DEFECT_NARROWED);
    assertInstanceOf(Noul.class, request.questions().get(DedupeQuestions.CLAIMS_NEW_EVIDENCE));
  }

  @Test
  void theWordingIsPinned() {
    final var score = new StringBuilder();
    DedupeQuestions.relation().writeTo(score);
    assertEquals("""
        {"type":"score","instructions":{"question":"How does the defect described by `b` relate to the defect described by `a`?","focus":"Compare the defects, not the wording. Two findings about the same code can still be different defects."},"criteria":["Different defects: fixing the defect `a` describes would leave the defect `b` describes unfixed, even when both sit in the same code.","The same underlying defect, but `b` narrows it, re-scopes which instances or inputs it covers, or reports it at a different place. Not a different defect in adjacent code.","The same defect restated in different words: fixing one fixes the other."]}""",
        score.toString());
    final var noul = new StringBuilder();
    DedupeQuestions.claimsNewEvidence().writeTo(noul);
    assertEquals("""
        {"type":"noul","instructions":"Does `b` cite evidence that `a` does not?","criteria":{"true":"`b` names a file, test, command, observation, or reproduction that `a` does not mention.","false":"`b` cites nothing beyond what `a` already cites, or cites nothing at all."}}""",
        noul.toString());
  }
}
