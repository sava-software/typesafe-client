package software.sava.typesafe.exceptions;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.FakeHttpResponse;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypeSafeRequestExceptionTests {

  private static HttpResponse<Object> response(final int status, final String requestId) {
    return new FakeHttpResponse(status, requestId, null);
  }

  @Test
  void aNullBodyIsAnEmptyBody() {
    final var exception = TypeSafeRequestException.create(response(503, null), null);
    assertEquals("", exception.body());
    assertNull(exception.requestId());
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

  @Test
  void retryabilityFollowsTheDocumentedStatuses() {
    for (final int retryable : new int[]{429, 500, 503, 529}) {
      assertTrue(TypeSafeRequestException.create(response(retryable, null), new byte[0]).canBeRetried(), "status " + retryable);
    }
    for (final int terminal : new int[]{400, 401, 404, 422, 499, 199}) {
      assertFalse(TypeSafeRequestException.create(response(terminal, null), new byte[0]).canBeRetried(), "status " + terminal);
    }
  }
}
