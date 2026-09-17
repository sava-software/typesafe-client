package software.sava.typesafe.exceptions;

import java.net.http.HttpResponse;

/// A 2xx response whose body did not parse as the documented shape.
public final class TypeSafeParseException extends RuntimeException {

  private final HttpResponse<?> httpResponse;

  public TypeSafeParseException(final HttpResponse<?> httpResponse, final String message, final Throwable cause) {
    super(message, cause);
    this.httpResponse = httpResponse;
  }

  public HttpResponse<?> httpResponse() {
    return httpResponse;
  }
}
