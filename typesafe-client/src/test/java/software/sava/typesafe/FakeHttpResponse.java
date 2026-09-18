package software.sava.typesafe;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A response with a status, optional request-id header, further headers, and a byte[] body,
/// for pinning code that reads responses without a socket.
public record FakeHttpResponse(int statusCode,
                               String requestId,
                               byte[] body,
                               Map<String, List<String>> extraHeaders) implements HttpResponse<Object> {

  public FakeHttpResponse(final int statusCode, final String requestId, final byte[] body) {
    this(statusCode, requestId, body, Map.of());
  }

  /// A response carrying one header beyond the request id, for the `retry-after` family.
  public static FakeHttpResponse withHeader(final int statusCode, final String name, final String value) {
    return new FakeHttpResponse(statusCode, null, null, Map.of(name, List.of(value)));
  }

  @Override
  public HttpRequest request() {
    return null;
  }

  @Override
  public Optional<HttpResponse<Object>> previousResponse() {
    return Optional.empty();
  }

  @Override
  public HttpHeaders headers() {
    final var values = new LinkedHashMap<>(extraHeaders);
    if (requestId != null) {
      values.put("x-typesafe-request-id", List.of(requestId));
    }
    return HttpHeaders.of(values, (a, b) -> true);
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return Optional.empty();
  }

  @Override
  public URI uri() {
    return URI.create("http://127.0.0.1/v1/systemone");
  }

  @Override
  public HttpClient.Version version() {
    return HttpClient.Version.HTTP_1_1;
  }
}
