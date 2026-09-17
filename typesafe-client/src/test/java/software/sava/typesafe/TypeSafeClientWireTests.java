package software.sava.typesafe;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.sava.typesafe.exceptions.TypeSafeParseException;
import software.sava.typesafe.exceptions.TypeSafeRequestException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Pins `TypeSafeClientImpl` against real responses on a loopback server: the path each call
/// takes, the bearer header, the status gate, the parse-failure path, and the request-id
/// header. None of that is observable any other way.
final class TypeSafeClientWireTests {

  private HttpServer server;

  private record Wire(String endpoint,
                      AtomicReference<String> path,
                      AtomicReference<String> method,
                      AtomicReference<Map<String, List<String>>> headers,
                      AtomicReference<String> body) {
  }

  private Wire serve(final int status, final String responseBody, final String requestId) throws IOException {
    final var path = new AtomicReference<String>();
    final var method = new AtomicReference<String>();
    final var headers = new AtomicReference<Map<String, List<String>>>();
    final var body = new AtomicReference<String>();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        path.set(exchange.getRequestURI().getPath());
        method.set(exchange.getRequestMethod());
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        headers.set(Map.copyOf(exchange.getRequestHeaders()));
        if (requestId != null) {
          exchange.getResponseHeaders().add(TypeSafeRequestException.REQUEST_ID_HEADER, requestId);
        }
        final var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.setExecutor(ForkJoinPool.commonPool());
    server.start();
    final var address = server.getAddress();
    return new Wire("http://" + address.getHostString() + ':' + address.getPort(), path, method, headers, body);
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private static TypeSafeClient client(final Wire wire) {
    return TypeSafeClient.clientBuilder()
        .endpoint(wire.endpoint())
        .apiKey("secret-key")
        .model("jev-latest")
        .createClient();
  }

  private static SystemOneRequest request() {
    return SystemOneRequest.builder()
        .state("The export button crashes the settings page in Safari.")
        .question("still_holds", Question.choice("q", "still_holds", "no_longer_holds", "cannot_tell"))
        .question("names_escape", Question.noul("n"))
        .question("severity", Question.score("s", "a", "b", "c"))
        .build();
  }

  @Test
  void aSuccessfulSystemOneCallIsParsedWithItsRequestId() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, TestBodies.SMOKE_REQUEST_ID);
    final var response = client(wire).systemOne(request()).join();

    assertEquals("jev-1.13.0", response.model());
    assertEquals(TestBodies.SMOKE_REQUEST_ID, response.requestId());
    assertEquals("still_holds", response.choice("still_holds").choice());
    assertEquals(new Usage(619, 81), response.usage());

    assertEquals(TypeSafeClientImpl.SYSTEM_ONE_PATH, wire.path().get());
    assertEquals("POST", wire.method().get());
    final var headers = wire.headers().get();
    assertEquals(List.of("Bearer secret-key"), headers.get("Authorization"));
    assertEquals(List.of("application/json"), headers.get("Content-type"));
    // the client default model was written into the body the server saw
    assertEquals(request().withDefaultModel("jev-latest").body(), wire.body().get());
  }

  @Test
  void anExplicitRequestModelWinsOverTheClientDefault() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var request = SystemOneRequest.builder()
        .state("s").model("jev-preview").question("q", Question.noul("n")).build();
    final var response = client(wire).systemOne(request).join();
    assertNull(response.requestId());
    assertTrue(wire.body().get().contains("\"model\":\"jev-preview\""));
  }

  @Test
  void theRequestExtenderRunsAfterTheBearerToken() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var client = TypeSafeClient.clientBuilder()
        .endpoint(wire.endpoint())
        .apiKey("k")
        .extendRequest(builder -> builder.setHeader("X-Trace", "t1"))
        .createClient();
    assertNotNull(client.systemOne(request()).join());
    final var headers = wire.headers().get();
    assertEquals(List.of("Bearer k"), headers.get("Authorization"));
    assertEquals(List.of("t1"), headers.get("X-trace"));
  }

  @Test
  void aValidationFailureIsARequestExceptionThatIsNotRetried() throws IOException {
    final var wire = serve(422, "{\"detail\":\"criteria must not be empty\"}", "req_422");
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    final var exception = assertInstanceOf(TypeSafeRequestException.class, failure.getCause());
    assertEquals(422, exception.statusCode());
    assertEquals("req_422", exception.requestId());
    assertEquals("{\"detail\":\"criteria must not be empty\"}", exception.body());
    assertFalse(exception.canBeRetried());
    assertEquals("TypeSafe request failed: HTTP 422 [req_422]: {\"detail\":\"criteria must not be empty\"}",
        exception.getMessage());
    assertEquals(422, exception.httpResponse().statusCode());
  }

  @Test
  void rateLimitAndOverloadCanBeRetried() throws IOException {
    for (final int status : new int[]{429, 529, 503}) {
      final var wire = serve(status, "", null);
      final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
      final var exception = assertInstanceOf(TypeSafeRequestException.class, failure.getCause());
      assertEquals(status, exception.statusCode());
      assertTrue(exception.canBeRetried(), "status " + status);
      assertNull(exception.requestId());
      assertEquals("", exception.body());
      assertEquals("TypeSafe request failed: HTTP " + status, exception.getMessage());
      stop();
    }
    final var wire = serve(401, "{\"error\":\"unauthorized\"}", null);
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    assertFalse(assertInstanceOf(TypeSafeRequestException.class, failure.getCause()).canBeRetried());
  }

  @Test
  void aLongErrorBodyIsTruncatedInTheMessageButKeptWhole() throws IOException {
    final var longBody = "x".repeat(TypeSafeRequestException.MESSAGE_BODY_LIMIT + 5);
    final var wire = serve(400, longBody, null);
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    final var exception = assertInstanceOf(TypeSafeRequestException.class, failure.getCause());
    assertEquals(longBody, exception.body());
    assertTrue(exception.getMessage().endsWith("x".repeat(TypeSafeRequestException.MESSAGE_BODY_LIMIT) + "..."));
    assertEquals("TypeSafe request failed: HTTP 400: ".length()
        + TypeSafeRequestException.MESSAGE_BODY_LIMIT + 3, exception.getMessage().length());
  }

  @Test
  void aTwoHundredThatDoesNotParseIsAParseException() throws IOException {
    final var wire = serve(200, "<html>not json</html>", "req_bad");
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    final var exception = assertInstanceOf(TypeSafeParseException.class, failure.getCause());
    assertTrue(exception.getMessage().contains("Failed to adapt 200 response: '<html>not json</html>'"));
    assertNotNull(exception.getCause());
    assertEquals(200, exception.httpResponse().statusCode());
  }

  @Test
  void theStatusGateIsExactlyTheTwoHundreds() throws IOException {
    for (final int status : new int[]{300, 404}) {
      final var wire = serve(status, "", null);
      final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
      assertEquals(status, assertInstanceOf(TypeSafeRequestException.class, failure.getCause()).statusCode());
      stop();
    }
    final var wire = serve(299, TestBodies.SMOKE, null);
    assertEquals("jev-1.13.0", client(wire).systemOne(request()).join().model());
  }

  @Test
  void theTestResponseSeamCanSwallowAResponse() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var client = TypeSafeClient.clientBuilder()
        .endpoint(wire.endpoint())
        .apiKey("k")
        .testResponse((response, body) -> body.length == 0)
        .createClient();
    assertNull(client.systemOne(request()).join());
  }

  @Test
  void theConvenienceOverloadsBuildTheSameRequest() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var client = client(wire);
    final var questions = new java.util.LinkedHashMap<String, Question>();
    questions.put("q", Question.noul("n"));
    assertNotNull(client.systemOne("plain", questions).join());
    assertEquals("""
        {"state":"plain","model":"jev-latest","questions":{"q":{"type":"noul","instructions":"n"}}}""",
        wire.body().get());
    assertNotNull(client.systemOne(JsonContent.array("a", "b"), questions).join());
    assertEquals("""
        {"state":["a","b"],"model":"jev-latest","questions":{"q":{"type":"noul","instructions":"n"}}}""",
        wire.body().get());
  }

  @Test
  void modelsIsAGetOnItsOwnPath() throws IOException {
    final var wire = serve(200, TestBodies.MODELS, null);
    final var models = client(wire).models().join();
    assertEquals(2, models.size());
    assertEquals("jev-latest", models.getFirst().name());
    assertEquals(TypeSafeClientImpl.MODELS_PATH, wire.path().get());
    assertEquals("GET", wire.method().get());
    assertEquals(List.of("Bearer secret-key"), wire.headers().get().get("Authorization"));
  }

  @Test
  void modelsFailuresGoThroughTheSameGate() throws IOException {
    final var wire = serve(500, "boom", null);
    final var failure = assertThrows(CompletionException.class, () -> client(wire).models().join());
    assertTrue(assertInstanceOf(TypeSafeRequestException.class, failure.getCause()).canBeRetried());
    stop();
    final var garbage = serve(200, "[", null);
    final var parse = assertThrows(CompletionException.class, () -> client(garbage).models().join());
    assertInstanceOf(TypeSafeParseException.class, parse.getCause());
  }
}
