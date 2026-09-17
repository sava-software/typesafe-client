package software.sava.typesafe;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

final class AnswerParseTests {

  private static Answer answer(final String json) {
    return Answer.parse(JsonIterator.parse(json));
  }

  @Test
  void documentedChoiceAnswer() {
    final var answer = answer("""
        {"type":"choice","choice":"returns","confidence":1.0,"probabilities":{"shipping":0.0,"returns":1.0,"billing":0.0}}""");
    final var choice = assertInstanceOf(ChoiceAnswer.class, answer);
    assertEquals("choice", choice.type());
    assertEquals("returns", choice.choice());
    assertEquals(1.0, choice.confidence());
    assertEquals(List.of("shipping", "returns", "billing"), List.copyOf(choice.probabilities().keySet()));
    assertEquals(1.0, choice.probability("returns"));
    assertEquals(0.0, choice.probability("shipping"));
    assertEquals(0.0, choice.probability("not an option"));
    assertThrows(UnsupportedOperationException.class, () -> choice.probabilities().put("x", 1.0));
  }

  @Test
  void typeMayFollowTheFieldsItInterprets() {
    final var answer = answer("""
        {"choice":"x","probabilities":{"x":0.7,"y":0.3},"confidence":0.5,"extra":[1,{"a":2}],"type":"choice"}""");
    final var choice = assertInstanceOf(ChoiceAnswer.class, answer);
    assertEquals("x", choice.choice());
    assertEquals(0.3, choice.probability("y"));
    assertEquals(0.5, choice.confidence());
  }

  @Test
  void noulAnswer() {
    final var noul = assertInstanceOf(NoulAnswer.class, answer("{\"type\":\"noul\",\"noul\":0.93}"));
    assertEquals("noul", noul.type());
    assertEquals(0.93, noul.noul());
  }

  @Test
  void scoreAnswerReKeysLevelsAscending() {
    final var score = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":1.3,"confidence":0.54,"probabilities":{"2":0.3,"0":0.0,"1":0.7},"legend":{"2":"Blocking","0":"Cosmetic","1":"Degraded"}}"""));
    assertEquals("score", score.type());
    assertEquals(1.3, score.score());
    assertEquals(0.54, score.confidence());
    assertEquals(List.of(0, 1, 2), List.copyOf(score.probabilities().keySet()));
    assertEquals(List.of("Cosmetic", "Degraded", "Blocking"), List.copyOf(score.legend().values()));
    assertEquals(2, score.topLevel());
    assertEquals(0.65, score.normalized(), 1e-12);
    assertEquals(0.7, score.probability(1));
    assertEquals(0.0, score.probability(9));
    assertThrows(UnsupportedOperationException.class, () -> score.legend().clear());
  }

  @Test
  void scoreWithoutLegendNormalizesToItself() {
    final var score = assertInstanceOf(ScoreAnswer.class, answer("{\"type\":\"score\",\"score\":0.4,\"confidence\":1}"));
    assertEquals(-1, score.topLevel());
    assertEquals(0.4, score.normalized());
    assertTrue(score.probabilities().isEmpty());
    final var single = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.0,"confidence":1,"legend":{"0":"only"},"probabilities":{"0":1}}"""));
    assertEquals(0, single.topLevel());
    assertEquals(0.0, single.normalized());
  }

  @Test
  void unknownTypesAreKeptNotRejected() {
    final var unknown = assertInstanceOf(UnknownAnswer.class, answer("{\"type\":\"rank\",\"items\":[1,2]}"));
    assertEquals("rank", unknown.type());
  }

  @Test
  void missingTypeIsAnError() {
    assertThrows(IllegalStateException.class, () -> answer("{\"noul\":0.5}"));
  }

  @Test
  void malformedInputFailsWithARuntimeException() {
    assertThrows(RuntimeException.class, () -> answer("{\"type\":\"noul\",\"noul\":\"x\"}"));
    assertThrows(RuntimeException.class, () -> answer("{\"type\":\"score\",\"probabilities\":{\"a\":1}}"));
    assertThrows(RuntimeException.class, () -> SystemOneResponse.parse("not json".getBytes(StandardCharsets.UTF_8), null));
  }

  @Test
  void smokeResponseRoundTrip() {
    final var response = SystemOneResponse.parse(TestBodies.SMOKE.getBytes(StandardCharsets.UTF_8), TestBodies.SMOKE_REQUEST_ID);
    assertEquals("jev-1.13.0", response.model());
    assertEquals(TestBodies.SMOKE_REQUEST_ID, response.requestId());
    assertEquals(TestBodies.SMOKE, response.raw());
    assertEquals(List.of("still_holds", "names_escape", "severity"), List.copyOf(response.answers().keySet()));
    assertEquals(new Usage(619, 81), response.usage());

    final var stillHolds = response.choice("still_holds");
    assertEquals("still_holds", stillHolds.choice());
    assertEquals(0.85, stillHolds.confidence());
    assertEquals(0.1, stillHolds.probability("no_longer_holds"));

    assertEquals(0.04, response.noul("names_escape").noul());

    final var severity = response.score("severity");
    assertEquals(1.82, severity.score());
    assertEquals(0.73, severity.confidence());
    assertEquals(0.82, severity.probability(2));
    assertEquals("The note names a specific branch or guard.", severity.legend().get(1));
    assertEquals(0.91, severity.normalized(), 1e-12);

    assertThrows(IllegalStateException.class, () -> response.choice("names_escape"));
    assertThrows(IllegalStateException.class, () -> response.noul("severity"));
    assertThrows(IllegalStateException.class, () -> response.score("still_holds"));
    final var missing = assertThrows(NoSuchElementException.class, () -> response.answer("nope"));
    assertTrue(missing.getMessage().contains("still_holds"));
    assertThrows(UnsupportedOperationException.class, () -> response.answers().clear());
  }

  @Test
  void emptyAnswersAndMissingUsage() {
    final var response = SystemOneResponse.parse("{\"model\":\"m\"}".getBytes(StandardCharsets.UTF_8), null);
    assertEquals("m", response.model());
    assertEquals(Map.of(), response.answers());
    assertNull(response.usage());
    assertNull(response.requestId());
  }

  @Test
  void absentMapsParseAsEmpty() {
    final var choice = assertInstanceOf(ChoiceAnswer.class, answer("{\"type\":\"choice\",\"choice\":\"x\",\"confidence\":1}"));
    assertEquals(Map.of(), choice.probabilities());
    assertEquals(0.0, choice.probability("x"));
    final var score = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.5,"confidence":0.5,"legend":{},"probabilities":{}}"""));
    assertEquals(Map.of(), score.legend());
    assertEquals(Map.of(), score.probabilities());
    assertEquals(-1, score.topLevel());
  }

  @Test
  void modelCardEnvelopeSkipsFieldsAroundTheList() {
    final var models = ModelCard.parseList(JsonIterator.parse("""
        {"object":"list","models":[{"name":"a","description":"d","release_date":"r","extra":{"x":[1]}}],"next":null}"""));
    assertEquals(List.of(new ModelCard("a", "d", "r")), models);
    assertEquals(List.of(), ModelCard.parseList(JsonIterator.parse("{\"models\":[]}")));
  }

  @Test
  void usageAndModelCards() {
    assertEquals(new Usage(1, 2), Usage.parse(JsonIterator.parse("{\"output_tokens\":2,\"x\":null,\"input_tokens\":1}")));
    final var models = ModelCard.parseList(JsonIterator.parse(TestBodies.MODELS));
    assertEquals(2, models.size());
    assertEquals(new ModelCard("jev-latest", "The latest iteration of TypeSafe's System One Model: Jev",
        "2026-09-10T18:38:01.391457+00:00"), models.getFirst());
    assertEquals("jev-preview", models.getLast().name());
    assertEquals(List.of(), ModelCard.parseList(JsonIterator.parse("{}")));
  }
}
