package software.sava.typesafe;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A response with a status, optional request-id header, and a byte[] body, for pinning
/// code that reads responses without a socket.
public record FakeHttpResponse(int statusCode, String requestId, byte[] body) implements HttpResponse<Object> {

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
    return requestId == null
        ? HttpHeaders.of(Map.of(), (a, b) -> true)
        : HttpHeaders.of(Map.of("x-typesafe-request-id", List.of(requestId)), (a, b) -> true);
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
