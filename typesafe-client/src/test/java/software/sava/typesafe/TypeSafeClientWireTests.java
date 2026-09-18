package software.sava.typesafe;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.sava.typesafe.exceptions.TypeSafeParseException;
import software.sava.typesafe.exceptions.TypeSafeRequestException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
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

  private String start(final HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", handler);
    server.setExecutor(ForkJoinPool.commonPool());
    server.start();
    final var address = server.getAddress();
    return "http://" + address.getHostString() + ':' + address.getPort();
  }

  private Wire serve(final int status, final String responseBody, final String requestId) throws IOException {
    final var path = new AtomicReference<String>();
    final var method = new AtomicReference<String>();
    final var headers = new AtomicReference<Map<String, List<String>>>();
    final var body = new AtomicReference<String>();
    final var endpoint = start(exchange -> {
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
    return new Wire(endpoint, path, method, headers, body);
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
  void theRequestExtenderRunsBeforeTheClientOwnedHeaders() throws IOException {
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

  /// Both SDKs apply the caller's headers first so they cannot clobber credentials or the
  /// client's identity (js/src/client.ts:352-361, python/_core/transport.py:108-119, pinned
  /// with hostile input at js/test/release-regressions.test.ts:35-49).
  @Test
  void anExtenderCannotReplaceTheClientOwnedHeaders() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var client = TypeSafeClient.clientBuilder()
        .endpoint(wire.endpoint())
        .apiKey("secret")
        .extendRequest(builder -> builder
            .setHeader("Authorization", "Bearer HIJACKED")
            .setHeader("Accept", "text/html")
            .setHeader("User-Agent", "curl/8")
            .setHeader("X-TypeSafe-SDK", "not-this-client")
            .setHeader("X-TypeSafe-Runtime", "cobol/1")
            .setHeader("X-Trace", "kept"))
        .createClient();
    assertNotNull(client.systemOne(request()).join());
    final var headers = wire.headers().get();
    assertEquals(List.of("Bearer secret"), headers.get("Authorization"));
    assertEquals(List.of("application/json"), headers.get("Accept"));
    assertEquals(List.of(TypeSafeClient.CLIENT_ID), headers.get("User-agent"));
    assertEquals(List.of(TypeSafeClient.CLIENT_ID), headers.get("X-typesafe-sdk"));
    assertEquals(List.of(TypeSafeClient.RUNTIME_ID), headers.get("X-typesafe-runtime"));
    // anything the client does not own is left as the extender set it
    assertEquals(List.of("kept"), headers.get("X-trace"));
  }

  /// The whole outgoing header set, as both SDKs pin theirs (js/test/client.test.ts:132-147,
  /// python/tests/test_clients.py:379-440): the identity set on both verbs, `Content-Type` on
  /// the POST only, and no retry-count header on a first attempt.
  @Test
  void theOutgoingHeaderSetIsPinnedForBothVerbs() throws IOException {
    final var expectedAgent = "sava-typesafe-client/" + TypeSafeClient.VERSION;
    final var expectedRuntime = "java/" + Runtime.version().feature();
    final var post = serve(200, TestBodies.SMOKE, null);
    assertNotNull(client(post).systemOne(request()).join());
    final var postHeaders = post.headers().get();
    assertEquals(List.of("Bearer secret-key"), postHeaders.get("Authorization"));
    assertEquals(List.of("application/json"), postHeaders.get("Accept"));
    assertEquals(List.of(expectedAgent), postHeaders.get("User-agent"));
    assertEquals(List.of(expectedAgent), postHeaders.get("X-typesafe-sdk"));
    assertEquals(List.of(expectedRuntime), postHeaders.get("X-typesafe-runtime"));
    assertEquals(List.of("application/json"), postHeaders.get("Content-type"));
    assertNull(postHeaders.get("X-typesafe-retry-count"));
    stop();

    final var get = serve(200, TestBodies.MODELS, null);
    assertNotNull(client(get).models().join());
    final var getHeaders = get.headers().get();
    assertEquals(List.of("Bearer secret-key"), getHeaders.get("Authorization"));
    assertEquals(List.of("application/json"), getHeaders.get("Accept"));
    assertEquals(List.of(expectedAgent), getHeaders.get("User-agent"));
    assertEquals(List.of(expectedAgent), getHeaders.get("X-typesafe-sdk"));
    assertEquals(List.of(expectedRuntime), getHeaders.get("X-typesafe-runtime"));
    // a bodyless GET declares no entity media type, as in both SDKs
    assertNull(getHeaders.get("Content-type"));
    assertNull(getHeaders.get("X-typesafe-retry-count"));
  }

  /// A base URL with a gateway prefix keeps it: both SDKs concatenate rather than resolve
  /// (python/tests/test_errors.py:88-92, js/src/client.ts:351).
  @Test
  void aBaseUrlWithAPathPrefixReachesThePrefixedPaths() throws IOException {
    final var wire = serve(200, TestBodies.SMOKE, null);
    final var client = TypeSafeClient.clientBuilder()
        .endpoint(wire.endpoint() + "/proxy/")
        .apiKey("k")
        .createClient();
    assertNotNull(client.systemOne(request()).join());
    assertEquals("/proxy" + TypeSafeClientImpl.SYSTEM_ONE_PATH, wire.path().get());
    stop();

    final var models = serve(200, TestBodies.MODELS, null);
    final var modelsClient = TypeSafeClient.clientBuilder()
        .endpoint(models.endpoint() + "/proxy/")
        .apiKey("k")
        .createClient();
    assertEquals(2, modelsClient.models().join().size());
    assertEquals("/proxy" + TypeSafeClientImpl.MODELS_PATH, models.path().get());
  }

  /// The FastAPI-shaped 422 both SDKs record (js/test/errors.test.ts:67-84): the body is kept
  /// byte for byte and the message names the rejected field paths.
  @Test
  void aFastApiValidationBodyIsExtractedAndKeptVerbatim() throws IOException {
    final var body = """
        {"detail":[\
        {"type":"list_type","loc":["body","questions","q","score","criteria"],"msg":"Input should be a valid list"},\
        {"type":"too_short","loc":["body","questions"],"msg":"Dictionary should have at least 1 item"}]}""";
    final var wire = serve(422, body, "req_v");
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    final var exception = assertInstanceOf(TypeSafeRequestException.class, failure.getCause());
    assertEquals(body, exception.body());
    assertEquals("""
        questions.q.score.criteria: Input should be a valid list
        questions: Dictionary should have at least 1 item""", exception.serverMessage());
    assertEquals("TypeSafe request failed: HTTP 422 [req_v]: "
        + "questions.q.score.criteria: Input should be a valid list\n"
        + "questions: Dictionary should have at least 1 item", exception.getMessage());
    assertFalse(exception.canBeRetried());
  }

  /// The transport half of the SDKs' retry policy, which they classify as
  /// `api_connection_error` (python/_core/retry.py:70): nothing answers on a closed port.
  @Test
  void aClosedPortFailsWithAConnectException() throws IOException {
    final int closedPort;
    try (final var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    final var client = TypeSafeClient.clientBuilder()
        .endpoint("http://127.0.0.1:" + closedPort)
        .apiKey("k")
        .createClient();
    final var failure = assertThrows(CompletionException.class, () -> client.systemOne(request()).join());
    assertInstanceOf(ConnectException.class, failure.getCause());
  }

  /// The other transport half, `api_timeout_error` (python/_core/retry.py:73): the handler
  /// holds the exchange open past the client's budget.
  @Test
  void aResponseSlowerThanTheRequestTimeoutFailsWithATimeout() throws IOException {
    final var released = new CountDownLatch(1);
    final var endpoint = start(exchange -> {
      try (exchange) {
        // outlast the 100 ms budget below, then unblock so the server thread never leaks
        assertTrue(released.await(30, TimeUnit.SECONDS));
        exchange.sendResponseHeaders(200, 0);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    final var client = TypeSafeClient.clientBuilder()
        .endpoint(endpoint)
        .apiKey("k")
        .requestTimeout(Duration.ofMillis(100))
        .createClient();
    try {
      final var failure = assertThrows(CompletionException.class, () -> client.systemOne(request()).join());
      assertInstanceOf(HttpTimeoutException.class, failure.getCause());
    } finally {
      released.countDown();
    }
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
    // a string-valued detail is a real API shape both SDKs record, and it is what the
    // message quotes instead of the envelope around it
    assertEquals("criteria must not be empty", exception.serverMessage());
    assertEquals("TypeSafe request failed: HTTP 422 [req_422]: criteria must not be empty",
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
  void anEmptyBodiedSuccessIsAParseException() throws IOException {
    // the status gate admits any 2xx; an empty body then fails in the parser on both paths
    var wire = serve(200, "", null);
    var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    var exception = assertInstanceOf(TypeSafeParseException.class, failure.getCause());
    assertTrue(exception.getMessage().contains("Failed to adapt 200 response: ''"), exception.getMessage());
    stop();
    final var modelsWire = serve(200, "", null);
    failure = assertThrows(CompletionException.class, () -> client(modelsWire).models().join());
    exception = assertInstanceOf(TypeSafeParseException.class, failure.getCause());
    assertTrue(exception.getMessage().contains("Failed to adapt 200 response: ''"), exception.getMessage());
  }

  @Test
  void aTwoHundredThatIsJsonButNotAResponseIsAParseException() throws IOException {
    // a proxy's JSON error page served with a 200: valid JSON, no model, so not a success
    final var wire = serve(200, "{\"detail\":\"Not Found\"}", "req_proxy");
    final var failure = assertThrows(CompletionException.class, () -> client(wire).systemOne(request()).join());
    final var exception = assertInstanceOf(TypeSafeParseException.class, failure.getCause());
    assertEquals("System One response without a model", exception.getCause().getMessage());
    assertTrue(exception.getMessage().contains("Failed to adapt 200 response: '{\"detail\":\"Not Found\"}'"), exception.getMessage());
    stop();
    final var modelsWire = serve(200, "{\"models\":null}", null);
    final var modelsFailure = assertThrows(CompletionException.class, () -> client(modelsWire).models().join());
    assertInstanceOf(IllegalStateException.class, assertInstanceOf(TypeSafeParseException.class, modelsFailure.getCause()).getCause());
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
    // a bodyless GET carries no entity media type; JsonHttpClient's JSON GET helper would
    assertNull(wire.headers().get().get("Content-type"));
    assertEquals("", wire.body().get());
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
