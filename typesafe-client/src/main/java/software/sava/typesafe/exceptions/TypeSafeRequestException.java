package software.sava.typesafe.exceptions;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNullElse;

/// A non-2xx TypeSafe response. The documented statuses are 401 (bad key), 422 (request
/// validation), 429 (rate limit), and 529 (overloaded); [#canBeRetried()] is true for the
/// last two, for 408, and for any other 5xx.
///
/// [#body()] is kept verbatim. [#serverMessage()] is a best-effort read of the error
/// envelope: the envelope is not documented as a stable contract, but the Python and
/// JavaScript SDKs extract the same fields in the same order, so it is treated here as a
/// de-facto one — parity evidence, not a published guarantee.
public final class TypeSafeRequestException extends RuntimeException {

  /// Longest body prefix quoted in the exception message, in characters.
  public static final int MESSAGE_BODY_LIMIT = 512;

  private final int statusCode;
  private final String requestId;
  private final String body;
  private final String serverMessage;
  private final HttpResponse<?> httpResponse;

  private TypeSafeRequestException(final String message,
                                   final int statusCode,
                                   final String requestId,
                                   final String body,
                                   final String serverMessage,
                                   final HttpResponse<?> httpResponse) {
    super(message);
    this.statusCode = statusCode;
    this.requestId = requestId;
    this.body = body;
    this.serverMessage = serverMessage;
    this.httpResponse = httpResponse;
  }

  public static TypeSafeRequestException create(final HttpResponse<?> httpResponse, final byte[] body) {
    final int statusCode = httpResponse.statusCode();
    final var requestId = httpResponse.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
    final var text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
    final var serverMessage = extractServerMessage(body);
    final var described = serverMessage != null
        ? serverMessage
        : text.length() > MESSAGE_BODY_LIMIT ? text.substring(0, MESSAGE_BODY_LIMIT) + "..." : text;
    final var message = "TypeSafe request failed: HTTP " + statusCode
        + (requestId == null ? "" : " [" + requestId + ']')
        + (described.isEmpty() ? "" : ": " + described);
    return new TypeSafeRequestException(message, statusCode, requestId, text, serverMessage, httpResponse);
  }

  /// The response header carrying TypeSafe's request id.
  public static final String REQUEST_ID_HEADER = "x-typesafe-request-id";

  /// Integer milliseconds to wait before retrying; wins over [#RETRY_AFTER_HEADER].
  public static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";

  /// Integer seconds, or an HTTP-date, to wait before retrying.
  public static final String RETRY_AFTER_HEADER = "retry-after";

  /// Rate limits (429), request timeouts (408) and overload (529, or any other 5xx) are
  /// worth a backoff and retry; a bad key or an invalid request is not. The set is
  /// `{408, 429} ∪ [500, 600)`, the default both SDKs retry
  /// (typesafe-sdk-js/src/retry.ts:16-17), so a 6xx from a broken proxy is terminal here as
  /// it is there.
  ///
  /// This client never retries, so the whole retry contract it exports is this flag plus
  /// three conventions the SDKs establish and a caller owning the backoff should follow:
  ///
  ///  - the transport half of the policy is retried too: a `CompletionException` whose cause
  ///    is a [java.io.IOException] (for example [java.net.ConnectException]) or a
  ///    [java.net.http.HttpTimeoutException] is the Java shape of the SDKs'
  ///    `api_connection_error` / `api_timeout_error` categories;
  ///  - [#retryAfterMillis()] carries the server's requested delay. Both SDKs cap it at
  ///    60_000 ms and fall back to their own backoff above that; the cap is policy, so it is
  ///    not applied here;
  ///  - both SDKs send `X-TypeSafe-Retry-Count` on a retried request — absent on the first
  ///    attempt, then the attempt number as a string (`"1"`, `"2"`, …). This client sends no
  ///    such header; a caller reproducing the SDK policy sets it through
  ///    `TypeSafeClient.Builder.extendRequest`.
  public boolean canBeRetried() {
    return statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode < 600);
  }

  /// The server's requested retry delay in milliseconds, as of now.
  public OptionalLong retryAfterMillis() {
    return retryAfterMillis(Instant.now());
  }

  /// The server's requested retry delay in milliseconds, with `now` as the clock the
  /// HTTP-date form is measured against.
  ///
  /// The precedence is the SDKs': [#RETRY_AFTER_MS_HEADER] as integer milliseconds, else
  /// [#RETRY_AFTER_HEADER] as integer seconds, else [#RETRY_AFTER_HEADER] as an RFC 1123
  /// HTTP-date. A missing, unparseable or negative delay is empty — including a date already
  /// in the past, where both SDKs clamp to zero instead. No ceiling is applied; see
  /// [#canBeRetried()].
  public OptionalLong retryAfterMillis(final Instant now) {
    final var headers = httpResponse.headers();
    final var millis = headers.firstValue(RETRY_AFTER_MS_HEADER).orElse(null);
    if (millis != null) {
      final var parsed = parseLong(millis);
      if (parsed != null) {
        return parsed < 0 ? OptionalLong.empty() : OptionalLong.of(parsed);
      }
    }
    final var retryAfter = headers.firstValue(RETRY_AFTER_HEADER).orElse(null);
    if (retryAfter == null) {
      return OptionalLong.empty();
    }
    final var seconds = parseLong(retryAfter);
    if (seconds != null) {
      return seconds < 0 ? OptionalLong.empty() : OptionalLong.of(seconds * 1_000L);
    }
    final Instant date;
    try {
      date = ZonedDateTime.parse(retryAfter.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (final RuntimeException unparseable) {
      return OptionalLong.empty();
    }
    final long delay = date.toEpochMilli() - now.toEpochMilli();
    return delay < 0 ? OptionalLong.empty() : OptionalLong.of(delay);
  }

  private static Long parseLong(final String value) {
    try {
      return Long.valueOf(value.strip());
    } catch (final NumberFormatException notANumber) {
      return null;
    }
  }

  public int statusCode() {
    return statusCode;
  }

  /// The `x-typesafe-request-id` header, or null when absent.
  public String requestId() {
    return requestId;
  }

  /// The full response body as UTF-8 text, possibly empty. Bytes that are not valid UTF-8
  /// come back as U+FFFD replacement characters, as they do in the Python SDK: the decode is
  /// lossy and the original bytes are not kept.
  public String body() {
    return body;
  }

  /// The message the server put in its error envelope, or null when none could be read.
  ///
  /// The ladder is the one both SDKs implement (typesafe-sdk-js/src/errors.ts:16-36,
  /// typesafe-sdk-python/_core/errors.py:39-65): `error` as a string or as an object with a
  /// `message`, else a top-level `message`, else `detail` as a string, as an object with a
  /// `message`, or as an array of `{loc, msg}` validation entries. An array renders one
  /// entry per line as `<path>: <msg>`, the path joined with `.` and a leading `body`
  /// segment dropped. The first rung present wins even when it is empty, and an empty result
  /// is reported as null, so the message falls back to the raw body.
  ///
  /// [#getMessage()] uses this when it is present and the truncated raw body otherwise.
  public String serverMessage() {
    return serverMessage;
  }

  public HttpResponse<?> httpResponse() {
    return httpResponse;
  }

  /// Best effort, so every way of not being the envelope reads as no message through one
  /// path: a null, empty, non-JSON or truncated body raises inside json-iterator, and so does
  /// a JSON value that is not an object — only `null` parses quietly, as an object with no
  /// fields.
  private static String extractServerMessage(final byte[] body) {
    try {
      final var envelope = new Envelope();
      JsonIterator.parse(body).testObject(Envelope.FIELDS, envelope);
      // no rung and an empty rung are the same answer: no message, so the raw body is quoted
      final var extracted = requireNonNullElse(envelope.extracted(), "");
      return extracted.isEmpty() ? null : extracted;
    } catch (final RuntimeException notTheEnvelope) {
      return null;
    }
  }

  private static String skip(final JsonIterator ji) {
    ji.skip();
    return null;
  }

  private static final FieldMatcher MESSAGE_FIELD = FieldMatcher.of("message");

  /// `"text"` or `{"message":"text"}`; any other shape contributes nothing.
  private static String readStringOrMessage(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case STRING -> ji.readString();
      case OBJECT -> {
        final var holder = new String[1];
        ji.testObject(MESSAGE_FIELD, (fieldIndex, field) -> {
          if (fieldIndex == 0 && field.whatIsNext() == ValueType.STRING) {
            holder[0] = field.readString();
          } else {
            field.skip();
          }
          return true;
        });
        yield holder[0];
      }
      default -> skip(ji);
    };
  }

  private static String readDetail(final JsonIterator ji) {
    return ji.whatIsNext() == ValueType.ARRAY ? readValidationErrors(ji) : readStringOrMessage(ji);
  }

  private static String readValidationErrors(final JsonIterator ji) {
    final var lines = new ArrayList<String>();
    while (ji.readArray()) {
      if (ji.whatIsNext() == ValueType.OBJECT) {
        final var entry = new ValidationEntry();
        ji.testObject(ValidationEntry.FIELDS, entry);
        final var line = entry.render();
        if (line != null) {
          lines.add(line);
        }
      } else {
        ji.skip();
      }
    }
    // an array with no usable entry joins to the empty string, which reads as no message
    return String.join("\n", lines);
  }

  private static final class Envelope implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of("error", "message", "detail");

    private String error;
    private String message;
    private String detail;

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> error = readStringOrMessage(ji);
        case 1 -> message = ji.whatIsNext() == ValueType.STRING ? ji.readString() : skip(ji);
        case 2 -> detail = readDetail(ji);
        default -> ji.skip();
      }
      return true;
    }

    String extracted() {
      return error != null ? error : message != null ? message : detail;
    }
  }

  private static final class ValidationEntry implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of("loc", "msg");

    private String path;
    private String msg;

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> path = ji.whatIsNext() == ValueType.ARRAY ? readPath(ji) : skip(ji);
        case 1 -> msg = ji.whatIsNext() == ValueType.STRING ? ji.readString() : skip(ji);
        default -> ji.skip();
      }
      return true;
    }

    String render() {
      return msg == null ? null : path == null || path.isEmpty() ? msg : path + ": " + msg;
    }

    private static String readPath(final JsonIterator ji) {
      final var segments = new ArrayList<String>();
      while (ji.readArray()) {
        switch (ji.whatIsNext()) {
          case STRING -> segments.add(ji.readString());
          case NUMBER -> segments.add(ji.readNumberAsString());
          default -> ji.skip();
        }
      }
      if (!segments.isEmpty() && "body".equals(segments.getFirst())) {
        segments.removeFirst();
      }
      return String.join(".", segments);
    }
  }
}
