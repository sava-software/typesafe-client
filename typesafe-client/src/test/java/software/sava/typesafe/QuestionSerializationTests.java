package software.sava.typesafe;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the wire bodies against the documented request examples, byte for byte.
final class QuestionSerializationTests {

  private static LinkedHashMap<String, String> departments() {
    final var criteria = new LinkedHashMap<String, String>();
    criteria.put("returns", "Exchanges, refunds, wrong or damaged items");
    criteria.put("shipping", "Delivery status, delays, lost packages");
    criteria.put("billing", "Charges, invoices, payment problems");
    return criteria;
  }

  @Test
  void choiceRequestMatchesTheDocumentedExample() {
    final var request = SystemOneRequest.builder()
        .state("My running shoes arrived in the wrong size.")
        .model("jev-latest")
        .question("department", Question.choice("Which team should handle this?", departments()))
        .build();
    assertEquals("""
        {"state":"My running shoes arrived in the wrong size.","model":"jev-latest","questions":{"department":{"type":"choice","instructions":"Which team should handle this?","criteria":{"returns":"Exchanges, refunds, wrong or damaged items","shipping":"Delivery status, delays, lost packages","billing":"Charges, invoices, payment problems"}}}}""",
        request.body());
  }

  @Test
  void scoreMatchesTheDocumentedExample() {
    final var score = Question.score("How severe is the reported issue?",
        "Cosmetic; no impact to functionality",
        "Broken or degraded feature, but workaround exists",
        "Blocking issue; no workaround exists");
    assertEquals(2, score.topLevel());
    assertEquals("""
        {"type":"score","instructions":"How severe is the reported issue?","criteria":["Cosmetic; no impact to functionality","Broken or degraded feature, but workaround exists","Blocking issue; no workaround exists"]}""",
        json(score));
  }

  @Test
  void noulWithAndWithoutCriteria() {
    assertEquals("""
        {"type":"noul","instructions":"Does this convey urgency?"}""",
        json(Question.noul("Does this convey urgency?")));
    assertEquals("""
        {"type":"noul","instructions":"Does this convey urgency?","criteria":{"true":"Asks for action now.","false":"No time pressure."}}""",
        json(Question.noul("Does this convey urgency?", "Asks for action now.", "No time pressure.")));
    assertEquals("""
        {"type":"noul","instructions":"q","criteria":{"true":null,"false":"no"}}""",
        json(new Noul(JsonContent.text("q"), NoulCriteria.of(null, "no"))));
  }

  @Test
  void bareOptionsSerializeAsNullDescriptions() {
    assertEquals("""
        {"type":"choice","instructions":"tone?","criteria":{"calm":null,"angry":null}}""",
        json(Question.choice("tone?", "calm", "angry")));
  }

  @Test
  void nullInstructionsAreOmitted() {
    assertEquals("""
        {"type":"noul"}""", json(new Noul(null, null)));
  }

  @Test
  void structuredInstructionsAndCriteriaWriteAsObjects() {
    final var instructions = JsonContent.object()
        .put("question", "Which topic?")
        .put("focus", "Classify the information the user wants.")
        .build();
    final var criteria = new LinkedHashMap<String, JsonContent>();
    criteria.put("limits", JsonContent.object()
        .put("what", "Quantity restrictions")
        .put("examples", JsonContent.array("How many cards?", "Max per day?"))
        .build());
    criteria.put("other", null);
    final var choice = new Choice(instructions, criteria);
    assertEquals("""
        {"type":"choice","instructions":{"question":"Which topic?","focus":"Classify the information the user wants."},"criteria":{"limits":{"what":"Quantity restrictions","examples":["How many cards?","Max per day?"]},"other":null}}""",
        json(choice));
  }

  @Test
  void stateObjectWritesEveryValueKind() {
    final var state = JsonContent.object()
        .put("text", "a \"quoted\" line\nnext")
        .put("count", 3L)
        .put("ratio", 0.25)
        .put("flag", true)
        .put("items", JsonContent.array("p", "q"))
        .put("raw", JsonContent.raw("{\"pre\":[1,2]}"))
        .put("missing", (JsonContent) null)
        .build();
    assertEquals("""
        {"text":"a \\"quoted\\" line\\nnext","count":3,"ratio":0.25,"flag":true,"items":["p","q"],"raw":{"pre":[1,2]},"missing":null}""",
        state.toJson());
  }

  @Test
  void arrayStateAndNullState() {
    final var request = SystemOneRequest.builder()
        .state(JsonContent.array("Hi", "My card was charged twice."))
        .model("m")
        .question("q", Question.noul("Is this about billing?"))
        .build();
    assertTrue(request.body().startsWith("{\"state\":[\"Hi\",\"My card was charged twice.\"],\"model\":\"m\","));
    final var nullState = SystemOneRequest.builder()
        .model("m")
        .question("q", Question.noul("x"))
        .build();
    assertTrue(nullState.body().startsWith("{\"state\":null,"));
  }

  @Test
  void questionIdsAndModelAreEscaped() {
    final var request = SystemOneRequest.builder()
        .state("s")
        .model("jev\"1")
        .question("id \"x\"", Question.noul("q"))
        .build();
    assertEquals("""
        {"state":"s","model":"jev\\"1","questions":{"id \\"x\\"":{"type":"noul","instructions":"q"}}}""",
        request.body());
  }

  @Test
  void questionsKeepInsertionOrder() {
    final var request = SystemOneRequest.builder()
        .state("s")
        .model("jev-x")
        .question("z", Question.noul("1"))
        .question("a", Question.noul("2"))
        .question("m", Question.noul("3"))
        .build();
    assertEquals(List.of("z", "a", "m"), List.copyOf(request.questions().keySet()));
    final var body = request.body();
    assertTrue(body.indexOf("\"z\"") < body.indexOf("\"a\"") && body.indexOf("\"a\"") < body.indexOf("\"m\""));
  }

  @Test
  void defaultModelFillsOnlyANullModel() {
    final var unresolved = SystemOneRequest.builder().state("s").question("q", Question.noul("x")).build();
    assertNull(unresolved.model());
    assertThrows(IllegalStateException.class, unresolved::body);
    final var resolved = unresolved.withDefaultModel("jev-latest");
    assertEquals("jev-latest", resolved.model());
    assertEquals(resolved.questions(), unresolved.questions());
    final var explicit = unresolved.withDefaultModel("a").withDefaultModel("b");
    assertEquals("a", explicit.model());
    assertSame(explicit, explicit.withDefaultModel("c"));
  }

  @Test
  void requestValidation() {
    assertThrows(IllegalArgumentException.class, () -> SystemOneRequest.builder().state("s").build());
    assertThrows(IllegalArgumentException.class, () -> SystemOneRequest.builder()
        .state("s").question(" ", Question.noul("x")).build());
    assertThrows(NullPointerException.class, () -> SystemOneRequest.builder()
        .state("s").question("q", null).build());
    assertThrows(IllegalArgumentException.class, () -> SystemOneRequest.builder()
        .state("s").model(" ").question("q", Question.noul("x")).build());
  }

  @Test
  void choiceValidation() {
    assertThrows(IllegalArgumentException.class, () -> Question.choice("q"));
    assertThrows(IllegalArgumentException.class, () -> Question.choice("q", " "));
    final var tooMany = new LinkedHashMap<String, JsonContent>();
    for (int i = 0; i <= Choice.MAX_OPTIONS; i++) {
      tooMany.put("o" + i, null);
    }
    assertThrows(IllegalArgumentException.class, () -> new Choice(null, tooMany));
    tooMany.remove("o0");
    assertEquals(Choice.MAX_OPTIONS, new Choice(null, tooMany).criteria().size());
    final var choice = Question.choice("q", departments());
    assertThrows(UnsupportedOperationException.class, () -> choice.criteria().put("x", null));
  }

  @Test
  void scoreValidation() {
    assertThrows(IllegalArgumentException.class, () -> Question.score("q", List.of()));
    final var score = Question.score("q", "only");
    assertEquals(0, score.topLevel());
    assertThrows(UnsupportedOperationException.class, () -> score.levels().add(null));
  }

  @Test
  void jsonContentValidation() {
    assertThrows(NullPointerException.class, () -> JsonContent.text(null));
    assertThrows(IllegalArgumentException.class, () -> JsonContent.raw(" "));
    assertThrows(IllegalArgumentException.class, () -> JsonContent.number(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> JsonContent.number(Double.POSITIVE_INFINITY));
    assertEquals("null", JsonContent.raw("null").toJson());
    assertEquals("1.0", JsonContent.number(1.0).toJson());
    assertEquals("-7", JsonContent.number(-7L).toJson());
    assertEquals("false", JsonContent.bool(false).toJson());
    assertSame(JsonContent.bool(true), JsonContent.bool(true));
    assertEquals("{}", JsonContent.object().build().toJson());
    assertEquals("[]", JsonContent.array(List.of()).toJson());
    assertEquals("[null]", JsonContent.array(java.util.Arrays.asList((JsonContent) null)).toJson());
    final var obj = JsonContent.object(new LinkedHashMap<>(Map.of("k", JsonContent.text("v"))));
    assertThrows(UnsupportedOperationException.class, () -> obj.fields().clear());
    final var builder = JsonContent.object().putAll(Map.of("a", JsonContent.text("1")));
    assertEquals("{\"a\":\"1\"}", builder.build().toJson());
  }

  @Test
  void aPerRequestTimeoutIsCarriedNotSerialized() {
    final var request = SystemOneRequest.builder()
        .state("s")
        .model("m")
        .timeout(java.time.Duration.ofSeconds(7))
        .question("q", Question.noul("x"))
        .build();
    assertEquals(java.time.Duration.ofSeconds(7), request.timeout());
    assertEquals(java.time.Duration.ofSeconds(7), request.withDefaultModel("z").timeout());
    assertFalse(request.body().contains("timeout"));
    assertNull(SystemOneRequest.builder().state("s").question("q", Question.noul("x")).build().timeout());
  }

  @Test
  void twoQuestionsAreCommaSeparated() {
    final var request = SystemOneRequest.builder()
        .state("s")
        .model("m")
        .question("a", Question.noul("1"))
        .question("b", Question.noul("2"))
        .build();
    assertEquals("""
        {"state":"s","model":"m","questions":{"a":{"type":"noul","instructions":"1"},"b":{"type":"noul","instructions":"2"}}}""",
        request.body());
  }

  @Test
  void nullsInTheConvenienceFactoriesStayNull() {
    assertEquals("""
        {"type":"noul"}""", json(Question.noul(null)));
    final var withNullValue = new LinkedHashMap<String, String>();
    withNullValue.put("a", "described");
    withNullValue.put("b", null);
    assertEquals("""
        {"type":"choice","instructions":"q","criteria":{"a":"described","b":null}}""",
        json(Question.choice("q", withNullValue)));
    assertEquals("""
        {"type":"noul","instructions":"q","criteria":{"true":"yes","false":null}}""",
        json(new Noul(JsonContent.text("q"), NoulCriteria.of("yes", null))));
    assertEquals("{\"k\":null}", JsonContent.object().put("k", (String) null).build().toJson());
    final var nullState = SystemOneRequest.builder().state((String) null).model("m")
        .question("q", Question.noul("x")).build();
    assertTrue(nullState.body().startsWith("{\"state\":null,"));
  }

  @Test
  void nullKeysAreRejectedAsBlankNotAsNullPointers() {
    assertThrows(IllegalArgumentException.class, () -> Question.choice("q", (String) null));
    final var nullId = new LinkedHashMap<String, Question>();
    nullId.put(null, Question.noul("x"));
    assertThrows(IllegalArgumentException.class, () -> new SystemOneRequest(null, "m", nullId, null));
    final var nullQuestion = assertThrows(NullPointerException.class, () -> SystemOneRequest.builder()
        .state("s").question("named", null).build());
    assertEquals("question named", nullQuestion.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new JsonContent.Num(" "));
  }

  private static String json(final Question question) {
    final var out = new StringBuilder();
    question.writeTo(out);
    return out.toString();
  }
}
