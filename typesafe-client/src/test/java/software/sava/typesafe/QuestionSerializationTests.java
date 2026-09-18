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
    // a null side is omitted, not written as null: both SDKs send only the keys given
    assertEquals("""
        {"type":"noul","instructions":"q","criteria":{"false":"no"}}""",
        json(new Noul(JsonContent.text("q"), NoulCriteria.of(null, "no"))));
    assertEquals("""
        {"type":"noul","instructions":"q","criteria":{"true":"yes"}}""",
        json(Question.noul("q", "yes")));
    assertEquals("""
        {"type":"noul","instructions":"q","criteria":{}}""",
        json(new Noul(JsonContent.text("q"), NoulCriteria.of(null, null))));
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
  void arrayStateAndTheRejectedNullState() {
    final var request = SystemOneRequest.builder()
        .state(JsonContent.array("Hi", "My card was charged twice."))
        .model("m")
        .question("q", Question.noul("Is this about billing?"))
        .build();
    assertTrue(request.body().startsWith("{\"state\":[\"Hi\",\"My card was charged twice.\"],\"model\":\"m\","));
    // the generated schema marks state required and non-nullable (_schemas/models.py)
    final var noState = SystemOneRequest.builder().model("m").question("q", Question.noul("x"));
    final var missing = assertThrows(IllegalArgumentException.class, noState::build);
    assertEquals("state is required; the API schema marks it non-nullable", missing.getMessage());
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
    // one level is deliberate: the generated schema says min_length=1 (_schemas/models.py) and
    // the Python SDK rejects only an empty list (tests/test_questions.py); the JS SDK is the
    // stricter outlier, refusing anything under two ('at least two scores are required').
    final var score = Question.score("q", "only");
    assertEquals(0, score.topLevel());
    assertEquals("""
        {"type":"score","instructions":"q","criteria":["only"]}""", json(score));
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
        {"type":"noul","instructions":"q","criteria":{"true":"yes"}}""",
        json(new Noul(JsonContent.text("q"), NoulCriteria.of("yes", null))));
    assertEquals("{\"k\":null}", JsonContent.object().put("k", (String) null).build().toJson());
    final var nullState = SystemOneRequest.builder().state((String) null).model("m")
        .question("q", Question.noul("x"));
    assertThrows(IllegalArgumentException.class, nullState::build);
  }

  @Test
  void nullKeysAreRejectedAsBlankNotAsNullPointers() {
    assertThrows(IllegalArgumentException.class, () -> Question.choice("q", (String) null));
    final var nullId = new LinkedHashMap<String, Question>();
    nullId.put(null, Question.noul("x"));
    assertThrows(IllegalArgumentException.class,
        () -> new SystemOneRequest(JsonContent.text("s"), "m", nullId, null));
    final var nullQuestion = assertThrows(NullPointerException.class, () -> SystemOneRequest.builder()
        .state("s").question("named", null).build());
    assertEquals("question named", nullQuestion.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new JsonContent.Num(" "));
  }

  @Test
  void extraBodyFieldsAreWrittenAfterQuestions() {
    final var request = SystemOneRequest.builder()
        .state("s")
        .model("m")
        .question("q", Question.noul("x"))
        .extraBody("beam_width", JsonContent.number(4L))
        .extraBody("nullable", null)
        .build();
    assertEquals("""
        {"state":"s","model":"m","questions":{"q":{"type":"noul","instructions":"x"}},"beam_width":4,"nullable":null}""",
        request.body());
    assertEquals(List.of("beam_width", "nullable"), List.copyOf(request.extraBody().keySet()));
    assertThrows(UnsupportedOperationException.class, () -> request.extraBody().clear());
    // the extras survive model resolution, which rebuilds the record
    assertEquals(request.body(), request.withDefaultModel("ignored").body());
  }

  @Test
  void noExtraBodyLeavesTheBodyAndSoTheRecordingKeyUnchanged() {
    final var plain = SystemOneRequest.builder().state("s").model("m").question("q", Question.noul("x")).build();
    assertTrue(plain.extraBody().isEmpty());
    assertEquals("""
        {"state":"s","model":"m","questions":{"q":{"type":"noul","instructions":"x"}}}""", plain.body());
    // the recording cache hashes these bytes, so an absent extras map keys exactly as before
    assertEquals(plain.body(), new SystemOneRequest(JsonContent.text("s"), "m", plain.questions(), null).body());
  }

  @Test
  void extraBodyKeysMayNotCollideWithTheFieldsTheClientWrites() {
    for (final var reserved : List.of("state", "model", "questions")) {
      final var builder = SystemOneRequest.builder().state("s").model("m")
          .question("q", Question.noul("x"))
          .extraBody(reserved, JsonContent.text("v"));
      final var collision = assertThrows(IllegalArgumentException.class, builder::build);
      assertEquals("extra body field " + reserved + " collides with a body field this client writes",
          collision.getMessage());
    }
    assertThrows(NullPointerException.class, () -> SystemOneRequest.builder().extraBody(null, null));
  }

  @Test
  void rawQuestionsAreWrittenVerbatim() {
    final var raw = Question.raw("future", """
        {"type":"future","instructions":"q","nested":{"k":null},"weight":3}""");
    assertEquals("future", raw.type());
    assertNull(raw.instructions());
    assertEquals("""
        {"type":"future","instructions":"q","nested":{"k":null},"weight":3}""", json(raw));
    final var built = Question.raw("noul", JsonContent.object()
        .put("type", "noul")
        .put("instructions", "Is this about billing?")
        .put("weight", 3L)
        .build());
    assertEquals("""
        {"type":"noul","instructions":"Is this about billing?","weight":3}""", json(built));
    final var request = SystemOneRequest.builder()
        .state("s").model("m").question("q", built).build();
    assertEquals("""
        {"state":"s","model":"m","questions":{"q":{"type":"noul","instructions":"Is this about billing?","weight":3}}}""",
        request.body());
  }

  @Test
  void rawQuestionValidation() {
    assertEquals("{}", Question.raw("future", "  {}  ").json()); // trimmed, then bounds-checked
    assertThrows(IllegalArgumentException.class, () -> Question.raw("future", "[1]"));
    assertThrows(IllegalArgumentException.class, () -> Question.raw("future", "{\"a\":1"));
    assertThrows(IllegalArgumentException.class, () -> Question.raw("future", "\"a\":1}"));
    assertThrows(IllegalArgumentException.class, () -> Question.raw("future", "   "));
    assertThrows(IllegalArgumentException.class, () -> Question.raw(" ", "{}"));
    assertThrows(NullPointerException.class, () -> Question.raw("future", (String) null));
    assertThrows(NullPointerException.class, () -> Question.raw("future", (JsonContent) null));
    assertThrows(NullPointerException.class, () -> Question.raw(null, "{}"));
  }

  @Test
  void richScoreLevelsSerialize() {
    // mirrors typesafe-sdk-python tests/test_clients.py test_rich_descriptions:
    // Score(instructions="Risk?", criteria=[{"summary": "duplicated", "examples": ["charged twice"]}])
    final var level = JsonContent.object()
        .put("summary", "duplicated")
        .put("examples", JsonContent.array("charged twice"))
        .build();
    final var score = Question.scoreLevels("Risk?", List.of(level));
    assertEquals(0, score.topLevel());
    assertEquals("""
        {"type":"score","instructions":"Risk?","criteria":[{"summary":"duplicated","examples":["charged twice"]}]}""",
        json(score));
  }

  @Test
  void nullScoreLevelsAreRejectedOnEveryPath() {
    final var varargs = assertThrows(IllegalArgumentException.class,
        () -> Question.score("q", "low", null));
    assertEquals("score level 1 must not be null", varargs.getMessage());
    final var list = assertThrows(IllegalArgumentException.class,
        () -> Question.score("q", java.util.Arrays.asList(null, "high")));
    assertEquals("score level 0 must not be null", list.getMessage());
    final var record = assertThrows(IllegalArgumentException.class,
        () -> new Score(null, java.util.Arrays.asList(JsonContent.text("a"), JsonContent.text("b"), null)));
    assertEquals("score level 2 must not be null", record.getMessage());
    assertThrows(NullPointerException.class, () -> new Score(null, null));
  }

  @Test
  void timeoutsMustBePositive() {
    final var builder = SystemOneRequest.builder().state("s").model("m").question("q", Question.noul("x"));
    final var zero = assertThrows(IllegalArgumentException.class, () -> builder.timeout(java.time.Duration.ZERO));
    assertEquals("timeout must be positive, got PT0S", zero.getMessage());
    assertThrows(IllegalArgumentException.class, () -> builder.timeout(java.time.Duration.ofSeconds(-1)));
    final var nullTimeout = assertThrows(IllegalArgumentException.class, () -> builder.timeout(null));
    assertEquals("timeout must not be null; leave it unset to use the client default", nullTimeout.getMessage());
    assertNull(builder.build().timeout()); // none of the rejected values was stored
    final var negative = assertThrows(IllegalArgumentException.class,
        () -> new SystemOneRequest(JsonContent.text("s"), "m", builder.build().questions(),
            java.time.Duration.ofNanos(-1)));
    assertEquals("timeout must be positive, got PT-0.000000001S", negative.getMessage());
    final var recordZero = assertThrows(IllegalArgumentException.class,
        () -> new SystemOneRequest(JsonContent.text("s"), "m", builder.build().questions(),
            java.time.Duration.ZERO));
    assertEquals("timeout must be positive, got PT0S", recordZero.getMessage());
    assertEquals(java.time.Duration.ofNanos(1),
        new SystemOneRequest(JsonContent.text("s"), "m", builder.build().questions(),
            java.time.Duration.ofNanos(1)).timeout());
  }

  @Test
  void nonAsciiControlAndSurrogateText() {
    // non-ASCII and a well-formed pair go out as UTF-8; a control character and a lone
    // surrogate are escaped, the latter as the JS SDK's well-formed JSON.stringify does
    final var request = SystemOneRequest.builder()
        .state("caf\u00e9 \u0007 \ud83d\ude00 lone:\ud83d end")
        .model("m")
        .question("q", Question.noul("x"))
        .build();
    assertEquals("""
        {"state":"caf\u00e9 \\u0007 \ud83d\ude00 lone:\\ud83d end","model":"m","questions":{"q":{"type":"noul","instructions":"x"}}}""",
        request.body());
    assertEquals("\"\\udc00\"", JsonContent.text("\udc00").toJson()); // a lone low surrogate
    assertEquals("\"\\ud800\"", JsonContent.text("\ud800").toJson()); // at the very end of the text
    assertEquals("\"\\ud800a\"", JsonContent.text("\ud800a").toJson()); // followed by a non-surrogate
    assertEquals("\"\\ud800\\ud800\"", JsonContent.text("\ud800\ud800").toJson()); // two in a row
    assertEquals("\"\ud83d\ude00\"", JsonContent.text("\ud83d\ude00").toJson()); // the pair is untouched
    // map keys take the same path as values
    assertEquals("{\"k\\ud83d\":\"v\\udfff\"}",
        JsonContent.object().put("k\ud83d", "v\udfff").build().toJson());
  }

  @Test
  void escapedSurrogatesSurviveUtf8Encoding() {
    // the point of the escape: the JDK's UTF-8 encoder replaces a bare lone surrogate with '?'
    final var escaped = JsonContent.text("x\ud83dy").toJson();
    assertEquals("\"x\\ud83dy\"", escaped);
    assertArrayEquals("\"x\\ud83dy\"".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        escaped.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals("x?y", new String("x\ud83dy".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        java.nio.charset.StandardCharsets.UTF_8)); // what the unescaped text would become
  }

  @Test
  void theCrossSdkTicketRequestSerializes() {
    // the case both SDKs run live, rebuilt here: typesafe-sdk-python tests/test_integration.py
    // test_live_questions -- a one-sided noul with rich criteria, a bare-option choice, and a
    // three-level score. Pinned in this client's key order; the SDKs never pin theirs.
    final var billing = new Noul(JsonContent.text("Is this ticket about billing?"),
        new NoulCriteria(JsonContent.object()
            .put("meaning", "Payments or invoices")
            .put("examples", JsonContent.array("charged twice"))
            .build(), null));
    final var request = SystemOneRequest.builder()
        .state(JsonContent.object()
            .put("subject", "Charged twice this month")
            .put("body", "I see two charges of $49. I only have one account. Please fix this ASAP.")
            .build())
        .model("jev-latest")
        .question("billing", billing)
        .question("tone", Question.choice("What is the customer's tone?", "calm", "frustrated", "angry"))
        .question("urgency", Question.score("How urgent is this ticket?", "can wait", "this week", "today"))
        .build();
    assertEquals("""
        {"state":{"subject":"Charged twice this month","body":"I see two charges of $49. I only have one account. Please fix this ASAP."},"model":"jev-latest","questions":{"billing":{"type":"noul","instructions":"Is this ticket about billing?","criteria":{"true":{"meaning":"Payments or invoices","examples":["charged twice"]}}},"tone":{"type":"choice","instructions":"What is the customer's tone?","criteria":{"calm":null,"frustrated":null,"angry":null}},"urgency":{"type":"score","instructions":"How urgent is this ticket?","criteria":["can wait","this week","today"]}}}""",
        request.body());
  }

  private static String json(final Question question) {
    final var out = new StringBuilder();
    question.writeTo(out);
    return out.toString();
  }
}
