package software.sava.typesafe;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonException;
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

  private static SystemOneResponse response(final String json) {
    return SystemOneResponse.parse(json.getBytes(StandardCharsets.UTF_8), null);
  }

  /// One row of a body-to-outcome table: `thrown` is null when the body must parse.
  private record Row(String name, String body, Class<? extends RuntimeException> thrown, String detail) {
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
    assertEquals(List.of("Cosmetic", "Degraded", "Blocking"),
        List.of(score.legendText(0), score.legendText(1), score.legendText(2)));
    assertEquals(JsonContent.text("Cosmetic"), score.legend().get(0));
    assertEquals(2, score.topLevel());
    // 1.3 of a top level of 2
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
    assertNull(score.legendText(0));
    final var single = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.0,"confidence":1,"legend":{"0":"only"},"probabilities":{"0":1}}"""));
    assertEquals(0, single.topLevel());
    assertEquals(0.0, single.normalized());
  }

  /// response-1, fixtures-1: a legend value is the criterion that was sent, so it is any JSON
  /// value. The rich-legend body is the one `test_rich_descriptions` serves.
  @Test
  void richLegendValuesAreKeptAsJson() {
    final var risk = response(TestBodies.SDK_RICH_LEGEND).score("risk");
    final var criteria = """
        {"summary":"duplicated","examples":["charged twice"]}""";
    assertEquals(JsonContent.raw(criteria), risk.legend().get(0));
    assertEquals(criteria, risk.legendText(0));
    assertEquals(0.0, risk.score());
    assertEquals(1.0, risk.confidence());
    assertEquals(1.0, risk.probability(0));
    assertEquals(0, risk.topLevel());

    final var nested = assertInstanceOf(ScoreAnswer.class, answer(TestBodies.SDK_NESTED_LEGEND));
    assertEquals("""
        {"examples":["a",{"note":null}]}""", nested.legendText(0));
  }

  /// Every JSON shape a legend level can carry, including the `null` level the old
  /// string-valued parser already accepted.
  @Test
  void legendLevelsCoverEveryJsonShape() {
    final var score = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.0,"confidence":1.0,"legend":{"0":{"examples":["a",{"note":null}]},"1":"ok","2":[1,true,null],"3":null,"4":7.5,"5":false},"probabilities":{"0":0.5,"1":0.5}}"""));
    assertEquals("""
        {"examples":["a",{"note":null}]}""", score.legendText(0));
    assertEquals("ok", score.legendText(1));
    assertEquals(JsonContent.text("ok"), score.legend().get(1));
    assertEquals("[1,true,null]", score.legendText(2));
    assertTrue(score.legend().containsKey(3), "a null level is a key with a null value");
    assertNull(score.legend().get(3));
    assertNull(score.legendText(3));
    assertEquals("7.5", score.legendText(4));
    assertEquals("false", score.legendText(5));
    assertEquals(5, score.topLevel());
    assertNull(score.legendText(6));
    // 0.0 of a top level of 5
    assertEquals(0.0, score.normalized());
    assertThrows(UnsupportedOperationException.class, () -> score.legend().put(9, null));
  }

  /// An escaped legend value is re-escaped the way [JsonContent] writes it, so the JSON text a
  /// caller reads back parses to the same value.
  @Test
  void legendJsonTextIsEscaped() {
    final var score = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.0,"confidence":1.0,"legend":{"0":{"a\\"b":"c\\nd"}}}"""));
    assertEquals("""
        {"a\\"b":"c\\nd"}""", score.legendText(0));
  }

  @Test
  void unknownTypesAreKeptNotRejected() {
    final var unknown = assertInstanceOf(UnknownAnswer.class, answer("{\"type\":\"rank\",\"items\":[1,2]}"));
    assertEquals("rank", unknown.type());
  }

  /// response-7: an answer type this client does not model keeps its payload, so a caller can
  /// read it without re-parsing [SystemOneResponse#raw()].
  @Test
  void unknownAnswersKeepTheirPayload() {
    final var response = response(TestBodies.SDK_UNKNOWN_ANSWER_TYPE);
    assertEquals(List.of("spam", "mystery"), List.copyOf(response.answers().keySet()));
    assertEquals(0.9, response.noul("spam").noul());
    final var mystery = assertInstanceOf(UnknownAnswer.class, response.answer("mystery"));
    assertEquals("aurora", mystery.type());
    assertEquals("{\"type\":\"aurora\",\"value\":3}", mystery.json());

    // fields the client does model are kept too when the type is one it does not
    final var rank = assertInstanceOf(UnknownAnswer.class, answer("""
        {"type":"rank","score":1.5,"legend":{"0":"x"},"items":[1,{"a":null},true,null],"noul":0.5}"""));
    assertEquals("""
        {"type":"rank","score":1.5,"legend":{"0":"x"},"items":[1,{"a":null},true,null],"noul":0.5}""", rank.json());
  }

  /// The payload walk is recursive; past [Answer.Parser#MAX_DEPTH] it fails as a
  /// [RuntimeException] rather than exhausting the stack, which the fuzz contract requires.
  @Test
  void deeplyNestedPayloadsFailInsteadOfOverflowing() {
    final int depth = Answer.Parser.MAX_DEPTH;
    // the answer object is depth 0 and the value of "v" is depth 1, so `depth` arrays reach it
    final var atTheLimit = assertInstanceOf(UnknownAnswer.class,
        answer("{\"type\":\"rank\",\"v\":" + "[".repeat(depth) + "]".repeat(depth) + "}"));
    assertEquals("{\"type\":\"rank\",\"v\":" + "[".repeat(depth) + "]".repeat(depth) + "}", atTheLimit.json());
    final var tooDeep = assertThrows(IllegalStateException.class,
        () -> answer("{\"type\":\"rank\",\"v\":" + "[".repeat(depth + 1) + "]".repeat(depth + 1) + "}"));
    assertEquals("JSON value nested deeper than " + depth + " levels", tooDeep.getMessage());
    assertThrows(IllegalStateException.class, () -> answer("""
        {"type":"score","score":0.0,"confidence":1.0,"legend":{"0":\
        """ + "[".repeat(depth + 2) + "]".repeat(depth + 2) + "}}"));
  }

  @Test
  void missingTypeIsAnError() {
    assertEquals("answer without a type",
        assertThrows(IllegalStateException.class, () -> answer("{\"noul\":0.5}")).getMessage());
    assertEquals("answer without a type",
        assertThrows(IllegalStateException.class, () -> answer("{\"type\":null,\"noul\":0.5}")).getMessage());
  }

  /// response-2, fixtures-2: the malformed-answer table the Python SDK pins at
  /// tests/test_responses.py:29-40, row for row. Every row is a 200 body there, and every row
  /// fails the parse here too; the field paths Python reports are the fields named below.
  @Test
  void theSdkMalformedAnswerTableFailsTheParse() {
    final var rows = List.of(
        new Row("model", "{\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"answers\":{}}",
            IllegalStateException.class, "System One response without a model"),
        new Row("answers.n.noul", "{\"n\":{\"type\":\"noul\"}}",
            IllegalStateException.class, "noul answer without a 'noul' field"),
        new Row("answers.n.noul quoted", "{\"n\":{\"type\":\"noul\",\"noul\":\"0.5\"}}",
            IllegalStateException.class, "answer field 'noul' must be a JSON number, not STRING"),
        new Row("answers.c.confidence", "{\"c\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{}}}",
            IllegalStateException.class, "choice answer without a 'confidence' field"),
        new Row("answers.c.choice", "{\"c\":{\"type\":\"choice\",\"confidence\":0.5,\"probabilities\":{}}}",
            IllegalStateException.class, "choice answer without a 'choice' field"),
        new Row("answers.s.legend array",
            "{\"s\":{\"type\":\"score\",\"score\":1.0,\"confidence\":1.0,\"legend\":[],\"probabilities\":{}}}",
            JsonException.class, "testObject"),
        new Row("answers.s.legend.x",
            "{\"s\":{\"type\":\"score\",\"score\":1.0,\"confidence\":1.0,\"legend\":{\"x\":\"bad\"},\"probabilities\":{}}}",
            IllegalStateException.class, "score answer 'legend' key 'x' is not a level number"),
        new Row("answers.c.type", "{\"c\":\"not-a-mapping\"}",
            JsonException.class, "testObject"),
        // rows the Python table leaves implicit: score carries the same two required fields,
        // and a null value is as absent as a missing key (models.py:110, :115)
        new Row("answers.s.score", "{\"s\":{\"type\":\"score\",\"confidence\":1.0,\"probabilities\":{}}}",
            IllegalStateException.class, "score answer without a 'score' field"),
        new Row("answers.s.confidence", "{\"s\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":{}}}",
            IllegalStateException.class, "score answer without a 'confidence' field"),
        new Row("answers.c.choice null", "{\"c\":{\"type\":\"choice\",\"choice\":null,\"confidence\":0.5}}",
            IllegalStateException.class, "choice answer without a 'choice' field")
    );
    for (final var row : rows) {
      final var body = row.name().equals("model")
          ? row.body()
          : "{\"model\":\"test\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"answers\":" + row.body() + '}';
      final var thrown = assertThrows(row.thrown(), () -> response(body), row.name());
      if (thrown instanceof final JsonException jsonException) {
        assertEquals(row.detail(), jsonException.op(), row.name());
      } else {
        assertEquals(row.detail(), thrown.getMessage(), row.name());
      }
    }
    // the same answers, complete, parse
    final var ok = response("""
        {"model":"test","usage":{"input_tokens":1,"output_tokens":1},"answers":{"n":{"type":"noul","noul":0.5},\
        "c":{"type":"choice","choice":"a","confidence":0.5,"probabilities":{}},\
        "s":{"type":"score","score":1.0,"confidence":1.0,"legend":{"1":"x"},"probabilities":{}}}}""");
    assertEquals(0.5, ok.noul("n").noul());
    assertEquals("a", ok.choice("c").choice());
    assertEquals(1.0, ok.score("s").score());
  }

  /// A non-integer key is rejected wherever a score map is keyed by level.
  @Test
  void scoreProbabilityKeysMustBeLevelNumbers() {
    assertEquals("score answer 'probabilities' key '1.5' is not a level number",
        assertThrows(IllegalStateException.class, () -> answer("""
            {"type":"score","score":1.0,"confidence":1.0,"probabilities":{"1.5":1.0}}""")).getMessage());
    assertEquals("score answer 'legend' key '' is not a level number",
        assertThrows(IllegalStateException.class, () -> answer("""
            {"type":"score","score":1.0,"confidence":1.0,"legend":{"":"bad"}}""")).getMessage());
    // a negative level is a number, so it is accepted and sorts first
    final var negative = assertInstanceOf(ScoreAnswer.class, answer("""
        {"type":"score","score":0.0,"confidence":1.0,"legend":{"0":"zero","-1":"below"}}"""));
    assertEquals(List.of(-1, 0), List.copyOf(negative.legend().keySet()));
    assertEquals(0, negative.topLevel());
  }

  /// response-8: json-iterator would unwrap a quoted number; both this client and the Python
  /// SDK's strict models reject one.
  @Test
  void quotedNumbersAreRejected() {
    assertEquals("answer field 'noul' must be a JSON number, not STRING",
        assertThrows(IllegalStateException.class, () -> answer("{\"type\":\"noul\",\"noul\":\"0.5\"}")).getMessage());
    assertEquals("answer field 'confidence' must be a JSON number, not STRING",
        assertThrows(IllegalStateException.class, () -> answer("""
            {"type":"choice","choice":"a","confidence":"0.5"}""")).getMessage());
    assertEquals("answer field 'score' must be a JSON number, not STRING",
        assertThrows(IllegalStateException.class, () -> answer("""
            {"type":"score","score":"1","confidence":1.0}""")).getMessage());
    assertEquals("answer field 'probabilities' must be a JSON number, not STRING",
        assertThrows(IllegalStateException.class, () -> answer("""
            {"type":"choice","choice":"a","confidence":1.0,"probabilities":{"a":"1.0"}}""")).getMessage());
    assertEquals("usage field 'input_tokens' must be a JSON number, not STRING",
        assertThrows(IllegalStateException.class,
            () -> Usage.parse(JsonIterator.parse("{\"input_tokens\":\"12\"}"))).getMessage());
    assertEquals("usage field 'output_tokens' must be a JSON number, not BOOLEAN",
        assertThrows(IllegalStateException.class,
            () -> Usage.parse(JsonIterator.parse("{\"output_tokens\":true}"))).getMessage());
    // a null number is rejected too: only usage counts tolerate null
    assertEquals("answer field 'noul' must be a JSON number, not NULL",
        assertThrows(IllegalStateException.class, () -> answer("{\"type\":\"noul\",\"noul\":null}")).getMessage());
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
    assertEquals("The note names a specific branch or guard.", severity.legendText(1));
    assertEquals(JsonContent.text("The note names a specific branch or guard."), severity.legend().get(1));
    // 1.82 of a top level of 2
    assertEquals(0.91, severity.normalized(), 1e-12);

    assertThrows(IllegalStateException.class, () -> response.choice("names_escape"));
    assertThrows(IllegalStateException.class, () -> response.noul("severity"));
    assertThrows(IllegalStateException.class, () -> response.score("still_holds"));
    final var missing = assertThrows(NoSuchElementException.class, () -> response.answer("nope"));
    assertTrue(missing.getMessage().contains("still_holds"));
    assertThrows(UnsupportedOperationException.class, () -> response.answers().clear());
  }

  /// response-6: the SDKs' own canonical response bodies, asserted for the same values their
  /// tests assert.
  @Test
  void theSdkResponseFixturesParse() {
    final var result = response(TestBodies.SDK_RESULT);
    assertEquals("jev-latest", result.model());
    assertEquals(new Usage(12, 3), result.usage());
    assertEquals(0.98, result.noul("spam").noul());
    assertEquals("friendly", result.choice("tone").choice());
    assertEquals(0.9, result.choice("tone").probability("friendly"));
    assertEquals(0.1, result.choice("tone").probability("hostile"));
    final var quality = result.score("quality");
    assertEquals(1.7, quality.score());
    assertEquals(0.8, quality.confidence());
    assertEquals(List.of("bad", "ok", "great"),
        List.of(quality.legendText(0), quality.legendText(1), quality.legendText(2)));
    assertEquals(0.8, quality.probability(2));
    // 1.7 of a top level of 2
    assertEquals(0.85, quality.normalized(), 1e-12);

    final var js = response(TestBodies.SDK_JS_RESULT);
    assertEquals("m", js.model());
    assertEquals(0.5, js.noul("q1").noul());
    assertEquals(new Usage(1, 1), js.usage());
  }

  /// fixtures-10: `default -> ji.skip()` holds at the response top level, inside an answer and
  /// inside usage, and `raw()` still carries what the parser dropped.
  @Test
  void unknownFieldsAreSkippedEverywhere() {
    final var response = response(TestBodies.SDK_EXTRA_FIELDS_AND_TOP_LEVEL);
    assertEquals("test", response.model());
    assertEquals(new Usage(1, 1), response.usage());
    assertEquals(0.9, response.noul("spam").noul());
    assertEquals(TestBodies.SDK_EXTRA_FIELDS_AND_TOP_LEVEL, response.raw());
    assertTrue(response.raw().contains("\"reasoning_tokens\":9"), "raw keeps the dropped usage field");
    assertTrue(response.raw().contains("\"trace_id\":\"t-1\""), "raw keeps the dropped top-level field");
    assertTrue(response.raw().contains("\"explanation\":\"spammy\""), "raw keeps the dropped answer field");
    assertEquals(new Usage(1, 1), response(TestBodies.SDK_EXTRA_FIELDS).usage());
  }

  /// response-3: a 200 that is not a System One response fails at the boundary.
  @Test
  void aResponseWithoutAModelFails() {
    for (final var body : List.of("{}", "null", "{\"detail\":\"Not Found\"}", "{\"model\":null,\"answers\":{}}",
        "{\"answers\":{},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")) {
      assertEquals("System One response without a model",
          assertThrows(IllegalStateException.class, () -> response(body), body).getMessage());
    }
  }

  @Test
  void emptyAnswersAndMissingUsage() {
    final var response = response("{\"model\":\"m\"}");
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

  /// response-5, fixtures-9: a present-but-empty or partial usage object reads as 0, and a
  /// count sent as `null` does not take the whole response down with it.
  @Test
  void usageToleratesAnEmptyOrPartialObject() {
    final var empty = response(TestBodies.SDK_EMPTY_USAGE);
    assertEquals("jev-latest", empty.model());
    assertEquals(Map.of(), empty.answers());
    assertEquals(new Usage(0, 0), empty.usage(), "an empty usage object is 0/0, not null");
    assertEquals(new Usage(0, 0), response("{\"model\":\"m\",\"usage\":{},\"answers\":{}}").usage());
    assertEquals(new Usage(619, 0), Usage.parse(JsonIterator.parse("{\"input_tokens\":619}")));
    assertEquals(new Usage(0, 3), Usage.parse(JsonIterator.parse("{\"output_tokens\":3}")));
    assertEquals(new Usage(0, 3), Usage.parse(JsonIterator.parse("{\"input_tokens\":null,\"output_tokens\":3}")));
    assertEquals(new Usage(1, 0), Usage.parse(JsonIterator.parse("{\"input_tokens\":1,\"output_tokens\":null}")));
  }

  @Test
  void modelCardEnvelopeSkipsFieldsAroundTheList() {
    final var models = ModelCard.parseList(JsonIterator.parse("""
        {"object":"list","models":[{"name":"a","description":"d","release_date":"r","extra":{"x":[1]}}],"next":null}"""));
    assertEquals(List.of(new ModelCard("a", "d", "r")), models);
    assertEquals(List.of(), ModelCard.parseList(JsonIterator.parse("{\"models\":[]}")));
  }

  /// response-4, fixtures-5: the union of the two SDKs' envelope tables --
  /// test/client.test.ts:176-185 and tests/test_clients.py:266-269.
  @Test
  void modelsEnvelopeShapes() {
    final var rows = List.of(
        new Row("bare null", "null", IllegalStateException.class,
            "models response must be a JSON object, not NULL"),
        new Row("array body", "[]", IllegalStateException.class,
            "models response must be a JSON object, not ARRAY"),
        new Row("string body", "\"models\"", IllegalStateException.class,
            "models response must be a JSON object, not STRING"),
        new Row("models absent", "{}", IllegalStateException.class,
            "models response without a 'models' array"),
        new Row("other key only", "{\"ok\":true}", IllegalStateException.class,
            "models response without a 'models' array"),
        new Row("models null", "{\"models\":null}", IllegalStateException.class,
            "models response 'models' must be a JSON array, not NULL"),
        new Row("models object", "{\"models\":{\"models\":[]}}", IllegalStateException.class,
            "models response 'models' must be a JSON array, not OBJECT"),
        new Row("models string", "{\"models\":\"bad\"}", IllegalStateException.class,
            "models response 'models' must be a JSON array, not STRING"),
        new Row("empty list", "{\"models\":[]}", null, null),
        // the one row the Python SDK rejects and this client keeps: a card is not required to
        // carry every field (tests/test_clients.py:269 -> field_path models[0].description)
        new Row("partial card", "{\"models\":[{\"name\":\"x\"}]}", null, null)
    );
    for (final var row : rows) {
      if (row.thrown() == null) {
        continue;
      }
      assertEquals(row.detail(),
          assertThrows(row.thrown(), () -> ModelCard.parseList(JsonIterator.parse(row.body())), row.name()).getMessage(),
          row.name());
    }
    assertEquals(List.of(), ModelCard.parseList(JsonIterator.parse("{\"models\":[]}")));
    assertEquals(List.of(new ModelCard("x", null, null)),
        ModelCard.parseList(JsonIterator.parse("{\"models\":[{\"name\":\"x\"}]}")));
  }

  /// fixtures-5: unmodelled card fields are skipped, in both SDKs' fixtures.
  @Test
  void modelCardsSkipUnmodelledFields() {
    final var card = new ModelCard("jev-latest", "Fast model", "2026-08-01");
    assertEquals(List.of(card), ModelCard.parseList(JsonIterator.parse(TestBodies.SDK_MODELS_CARD)));
    assertEquals(List.of(card), ModelCard.parseList(JsonIterator.parse(TestBodies.SDK_MODELS_CARD_EXTRA_FIELDS)));
    assertEquals(List.of(new ModelCard("m", "d", "2026")),
        ModelCard.parseList(JsonIterator.parse(TestBodies.SDK_JS_MODELS_TAGS)));
  }

  @Test
  void usageAndModelCards() {
    assertEquals(new Usage(1, 2), Usage.parse(JsonIterator.parse("{\"output_tokens\":2,\"x\":null,\"input_tokens\":1}")));
    final var models = ModelCard.parseList(JsonIterator.parse(TestBodies.MODELS));
    assertEquals(2, models.size());
    assertEquals(new ModelCard("jev-latest", "The latest iteration of TypeSafe's System One Model: Jev",
        "2026-09-10T18:38:01.391457+00:00"), models.getFirst());
    assertEquals("jev-preview", models.getLast().name());
  }
}
