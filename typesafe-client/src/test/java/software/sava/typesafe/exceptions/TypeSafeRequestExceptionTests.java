package software.sava.typesafe.exceptions;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.FakeHttpResponse;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

final class TypeSafeRequestExceptionTests {

  private static HttpResponse<Object> response(final int status, final String requestId) {
    return new FakeHttpResponse(status, requestId, null);
  }

  private static TypeSafeRequestException failure(final String body) {
    return TypeSafeRequestException.create(response(400, null), body.getBytes(StandardCharsets.UTF_8));
  }

  private static TypeSafeRequestException withHeader(final String name, final String value) {
    return TypeSafeRequestException.create(FakeHttpResponse.withHeader(429, name, value), null);
  }

  /// A fixed origin, so the HTTP-date arithmetic below is exact.
  private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

  @Test
  void aNullBodyIsAnEmptyBody() {
    final var exception = TypeSafeRequestException.create(response(503, null), null);
    assertEquals("", exception.body());
    assertNull(exception.requestId());
    assertNull(exception.serverMessage());
    assertEquals("TypeSafe request failed: HTTP 503", exception.getMessage());
    assertEquals(503, exception.statusCode());
    assertEquals(503, exception.httpResponse().statusCode());
  }

  @Test
  void aBodyAtTheLimitIsQuotedWholeAndOneOverIsTruncated() {
    final var atLimit = "y".repeat(TypeSafeRequestException.MESSAGE_BODY_LIMIT);
    final var exact = TypeSafeRequestException.create(response(400, "req_x"), atLimit.getBytes(StandardCharsets.UTF_8));
    assertEquals("TypeSafe request failed: HTTP 400 [req_x]: " + atLimit, exact.getMessage());
    final var over = TypeSafeRequestException.create(response(400, "req_x"), (atLimit + "z").getBytes(StandardCharsets.UTF_8));
    assertEquals("TypeSafe request failed: HTTP 400 [req_x]: " + atLimit + "...", over.getMessage());
    assertEquals(atLimit + "z", over.body());
    assertEquals("req_x", over.requestId());
  }

  /// The default set both SDKs retry: `{408, 429} ∪ [500, 600)`
  /// (typesafe-sdk-js/src/retry.ts:16-17, pinned there at test/retry.test.ts:39 and in
  /// typesafe-sdk-python/tests/test_retry.py:162 against 409, 422 and 302).
  @Test
  void retryabilityFollowsTheDocumentedStatuses() {
    for (final int retryable : new int[]{408, 429, 500, 502, 503, 504, 529, 599}) {
      assertTrue(TypeSafeRequestException.create(response(retryable, null), new byte[0]).canBeRetried(), "status " + retryable);
    }
    for (final int terminal : new int[]{199, 302, 400, 401, 404, 409, 422, 428, 430, 499, 600, 700}) {
      assertFalse(TypeSafeRequestException.create(response(terminal, null), new byte[0]).canBeRetried(), "status " + terminal);
    }
  }

  @Test
  void retryAfterMillisReadsTheMillisecondHeader() {
    // the header is already in milliseconds, so it is the delay verbatim
    assertEquals(OptionalLong.of(125L), withHeader("retry-after-ms", "125").retryAfterMillis(NOW));
    assertEquals(OptionalLong.of(0L), withHeader("retry-after-ms", "0").retryAfterMillis(NOW));
    assertEquals(OptionalLong.of(125L), withHeader("Retry-After-MS", " 125 ").retryAfterMillis(NOW));
    // a negative delay is no guidance at all
    assertEquals(OptionalLong.empty(), withHeader("retry-after-ms", "-1").retryAfterMillis(NOW));
  }

  @Test
  void retryAfterMillisReadsSecondsFromTheRetryAfterHeader() {
    // 2 seconds -> 2 * 1000 = 2000 ms
    assertEquals(OptionalLong.of(2_000L), withHeader("retry-after", "2").retryAfterMillis(NOW));
    // 120 seconds -> 120_000 ms, well past the SDKs' 60_000 ms policy cap, which is not applied here
    assertEquals(OptionalLong.of(120_000L), withHeader("retry-after", "120").retryAfterMillis(NOW));
    assertEquals(OptionalLong.of(0L), withHeader("retry-after", "0").retryAfterMillis(NOW));
    assertEquals(OptionalLong.empty(), withHeader("retry-after", "-3").retryAfterMillis(NOW));
  }

  @Test
  void retryAfterMillisReadsAnHttpDateAgainstTheGivenClock() {
    // 12:00:30Z is 30 seconds after NOW -> 30 * 1000 = 30000 ms
    assertEquals(OptionalLong.of(30_000L),
        withHeader("retry-after", "Fri, 18 Sep 2026 12:00:30 GMT").retryAfterMillis(NOW));
    // the same instant as NOW -> no wait
    assertEquals(OptionalLong.of(0L),
        withHeader("retry-after", "Fri, 18 Sep 2026 12:00:00 GMT").retryAfterMillis(NOW));
    // a date already past is negative, so empty (both SDKs clamp it to 0 instead)
    assertEquals(OptionalLong.empty(),
        withHeader("retry-after", "Fri, 18 Sep 2026 11:59:59 GMT").retryAfterMillis(NOW));
  }

  @Test
  void retryAfterMillisIsEmptyWithoutAUsableHeader() {
    assertEquals(OptionalLong.empty(), TypeSafeRequestException.create(response(429, "req_1"), null).retryAfterMillis(NOW));
    assertEquals(OptionalLong.empty(), withHeader("retry-after", "soon").retryAfterMillis(NOW));
    assertEquals(OptionalLong.empty(), withHeader("retry-after", "").retryAfterMillis(NOW));
    // an unparseable millisecond header falls through to retry-after, which is absent here
    assertEquals(OptionalLong.empty(), withHeader("retry-after-ms", "later").retryAfterMillis(NOW));
    // the no-argument overload reads the same headers against the system clock
    assertEquals(OptionalLong.of(1_500L), withHeader("retry-after-ms", "1500").retryAfterMillis());
    assertEquals(OptionalLong.empty(), TypeSafeRequestException.create(response(429, null), null).retryAfterMillis());
  }

  @Test
  void theMillisecondHeaderWinsOverRetryAfter() {
    final var both = TypeSafeRequestException.create(
        new FakeHttpResponse(429, null, null, java.util.Map.of(
            "retry-after-ms", java.util.List.of("250"),
            "retry-after", java.util.List.of("30"))),
        null);
    // 250 ms from the millisecond header, not 30 * 1000 = 30000 ms from retry-after
    assertEquals(OptionalLong.of(250L), both.retryAfterMillis(NOW));
  }

  /// The six envelope shapes pinned at typesafe-sdk-js/test/errors.test.ts:59-89. The
  /// validation array renders one entry per line here, where the SDKs join with "; ".
  @Test
  void serverMessageWalksTheSdkLadder() {
    assertEquals("plain string", failure("{\"error\":\"plain string\"}").serverMessage());
    assertEquals("invalid api key", failure("{\"error\":{\"message\":\"invalid api key\"}}").serverMessage());
    assertEquals("top-level message", failure("{\"message\":\"top-level message\"}").serverMessage());
    assertEquals("fastapi style", failure("{\"detail\":\"fastapi style\"}").serverMessage());
    assertEquals("Unknown model: x",
        failure("{\"detail\":{\"error_type\":\"api_usage_error\",\"message\":\"Unknown model: x\"}}").serverMessage());
    final var validation = failure("""
        {"detail":[\
        {"type":"list_type","loc":["body","questions","q","score","criteria"],"msg":"Input should be a valid list"},\
        {"type":"too_short","loc":["body","questions"],"msg":"Dictionary should have at least 1 item"}]}""");
    assertEquals("""
        questions.q.score.criteria: Input should be a valid list
        questions: Dictionary should have at least 1 item""", validation.serverMessage());
  }

  @Test
  void theLadderTakesTheFirstRungPresent() {
    // error wins over message and detail, whatever order they arrive in
    assertEquals("from error",
        failure("{\"detail\":\"from detail\",\"message\":\"from message\",\"error\":\"from error\"}").serverMessage());
    // message wins over detail
    assertEquals("from message",
        failure("{\"detail\":\"from detail\",\"message\":\"from message\"}").serverMessage());
    // a rung of the wrong type is not a rung: error is a number, so message is used
    assertEquals("from message", failure("{\"error\":7,\"message\":\"from message\"}").serverMessage());
    // an object error without a string message likewise falls through
    assertEquals("from detail", failure("{\"error\":{\"code\":7},\"detail\":\"from detail\"}").serverMessage());
    // unknown envelope fields are skipped rather than confusing the ladder
    assertEquals("kept", failure("{\"trace\":{\"a\":[1,2]},\"message\":\"kept\",\"code\":7}").serverMessage());
    // only "message" is read out of an error object: another string field is not a message
    assertEquals("from message", failure("{\"error\":{\"code\":\"E7\"},\"message\":\"from message\"}").serverMessage());
    // a non-string message inside an error object is not a message either
    assertEquals("from detail", failure("{\"error\":{\"message\":7},\"detail\":\"from detail\"}").serverMessage());
    // and a non-string top-level message falls through to detail
    assertEquals("from detail", failure("{\"message\":7,\"detail\":\"from detail\"}").serverMessage());
  }

  @Test
  void validationEntriesWithoutAPathOrAMessageAreHandled() {
    // no loc: the message stands alone
    assertEquals("Input should be a valid list",
        failure("{\"detail\":[{\"msg\":\"Input should be a valid list\"}]}").serverMessage());
    // a numeric loc segment is part of the path, and only a leading "body" is dropped
    assertEquals("questions.0.body: too short",
        failure("{\"detail\":[{\"loc\":[\"body\",\"questions\",0,\"body\"],\"msg\":\"too short\"}]}").serverMessage());
    // a loc of nothing but "body" leaves an empty path, so the message stands alone
    assertEquals("too short", failure("{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"too short\"}]}").serverMessage());
    // entries that are not objects, or carry no string msg, are dropped
    assertEquals("kept", failure("{\"detail\":[null,42,{\"msg\":4},{\"msg\":\"kept\"}]}").serverMessage());
    // including one whose loc would otherwise have rendered a path with no message after it
    assertEquals("kept",
        failure("{\"detail\":[{\"loc\":[\"body\",\"questions\"],\"msg\":4},{\"msg\":\"kept\"}]}").serverMessage());
    // an empty loc is an empty path, so the message stands alone
    assertEquals("m", failure("{\"detail\":[{\"loc\":[],\"msg\":\"m\"}]}").serverMessage());
    // a loc that is not an array contributes no path
    assertEquals("m", failure("{\"detail\":[{\"loc\":\"body.questions\",\"msg\":\"m\"}]}").serverMessage());
    // only a leading "body" is dropped, so a path that does not start with one is kept whole
    assertEquals("questions.q: m",
        failure("{\"detail\":[{\"loc\":[\"questions\",\"q\"],\"msg\":\"m\"}]}").serverMessage());
    // an array with no usable entry leaves no message at all
    assertNull(failure("{\"detail\":[]}").serverMessage());
  }

  @Test
  void getMessageUsesTheServerMessageWhenThereIsOne() {
    final var exception = TypeSafeRequestException.create(response(422, "req_422"),
        "{\"detail\":{\"message\":\"Unknown model: x\"}}".getBytes(StandardCharsets.UTF_8));
    assertEquals("TypeSafe request failed: HTTP 422 [req_422]: Unknown model: x", exception.getMessage());
    // the body stays verbatim whatever the message says
    assertEquals("{\"detail\":{\"message\":\"Unknown model: x\"}}", exception.body());
  }

  /// Ported from typesafe-sdk-python/tests/test_errors.py:140-152. Java keeps the raw bytes
  /// rather than the SDKs' parsed body, so a bare `null` reads as the text `null` where
  /// Python reports "no body"; every other row matches.
  @Test
  void errorBodyEdgeCases() {
    record Row(byte[] body, String text, String message) {
    }
    final var rows = new Row[]{
        new Row(new byte[0], "", "TypeSafe request failed: HTTP 400"),
        new Row("null".getBytes(StandardCharsets.UTF_8), "null", "TypeSafe request failed: HTTP 400: null"),
        new Row("[]".getBytes(StandardCharsets.UTF_8), "[]", "TypeSafe request failed: HTTP 400: []"),
        new Row("42".getBytes(StandardCharsets.UTF_8), "42", "TypeSafe request failed: HTTP 400: 42"),
        // 0xFF is not valid UTF-8, so it decodes to the U+FFFD replacement character
        new Row(new byte[]{'n', 'o', 't', ' ', 'J', 'S', 'O', 'N', ':', ' ', (byte) 0xFF},
            "not JSON: �", "TypeSafe request failed: HTTP 400: not JSON: �"),
        new Row("<h1>bad gateway</h1>".getBytes(StandardCharsets.UTF_8),
            "<h1>bad gateway</h1>", "TypeSafe request failed: HTTP 400: <h1>bad gateway</h1>"),
        // an empty first rung ends the ladder, so the raw body is quoted, ignoring "message"
        new Row("{\"error\":\"\",\"message\":\"ignored\"}".getBytes(StandardCharsets.UTF_8),
            "{\"error\":\"\",\"message\":\"ignored\"}",
            "TypeSafe request failed: HTTP 400: {\"error\":\"\",\"message\":\"ignored\"}"),
        new Row("{\"detail\":[null,42,{\"msg\":4}]}".getBytes(StandardCharsets.UTF_8),
            "{\"detail\":[null,42,{\"msg\":4}]}",
            "TypeSafe request failed: HTTP 400: {\"detail\":[null,42,{\"msg\":4}]}"),
        // a truncated JSON object is not an envelope, so it falls back to the raw body
        new Row("{\"message\":".getBytes(StandardCharsets.UTF_8),
            "{\"message\":", "TypeSafe request failed: HTTP 400: {\"message\":")
    };
    for (final var row : rows) {
      final var exception = TypeSafeRequestException.create(response(400, null), row.body());
      assertEquals(row.text(), exception.body(), row.text());
      assertEquals(row.message(), exception.getMessage(), row.text());
      assertNull(exception.serverMessage(), row.text());
    }
  }

  @Test
  void aLongBodyWithAServerMessageQuotesTheMessageNotTheBody() {
    // 600 characters of padding puts the raw body past the 512-character quote limit
    final var padding = "p".repeat(600);
    final var exception = TypeSafeRequestException.create(response(413, null),
        ("{\"pad\":\"" + padding + "\",\"message\":\"too large\"}").getBytes(StandardCharsets.UTF_8));
    assertEquals("too large", exception.serverMessage());
    assertEquals("TypeSafe request failed: HTTP 413: too large", exception.getMessage());
    // 8 chars of {"pad":" + 600 of padding + 24 of ","message":"too large"} = 632
    assertEquals(632, exception.body().length());
  }
}
