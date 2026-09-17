package software.sava.typesafe.exceptions;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/// A non-2xx TypeSafe response. The documented statuses are 401 (bad key), 422 (request
/// validation), 429 (rate limit), and 529 (overloaded); [#canBeRetried()] is true for the
/// last two and for any other 5xx. The body is kept verbatim: the error envelope is not
/// documented as a stable contract, so nothing here parses it.
public final class TypeSafeRequestException extends RuntimeException {

  /// Longest body prefix quoted in the exception message.
  public static final int MESSAGE_BODY_LIMIT = 512;

  private final int statusCode;
  private final String requestId;
  private final String body;
  private final HttpResponse<?> httpResponse;

  private TypeSafeRequestException(final String message,
                                   final int statusCode,
                                   final String requestId,
                                   final String body,
                                   final HttpResponse<?> httpResponse) {
    super(message);
    this.statusCode = statusCode;
    this.requestId = requestId;
    this.body = body;
    this.httpResponse = httpResponse;
  }

  public static TypeSafeRequestException create(final HttpResponse<?> httpResponse, final byte[] body) {
    final int statusCode = httpResponse.statusCode();
    final var requestId = httpResponse.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
    final var text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
    final var quoted = text.length() > MESSAGE_BODY_LIMIT ? text.substring(0, MESSAGE_BODY_LIMIT) + "..." : text;
    final var message = "TypeSafe request failed: HTTP " + statusCode
        + (requestId == null ? "" : " [" + requestId + ']')
        + (quoted.isEmpty() ? "" : ": " + quoted);
    return new TypeSafeRequestException(message, statusCode, requestId, text, httpResponse);
  }

  /// The response header carrying TypeSafe's request id.
  public static final String REQUEST_ID_HEADER = "x-typesafe-request-id";

  /// Rate limits (429) and overload (529, or any 5xx) are worth a backoff and retry; a bad
  /// key or an invalid request is not.
  public boolean canBeRetried() {
    return statusCode == 429 || statusCode >= 500;
  }

  public int statusCode() {
    return statusCode;
  }

  /// The `x-typesafe-request-id` header, or null when absent.
  public String requestId() {
    return requestId;
  }

  /// The full response body as UTF-8 text, possibly empty.
  public String body() {
    return body;
  }

  public HttpResponse<?> httpResponse() {
    return httpResponse;
  }
}
