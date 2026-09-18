package software.sava.typesafe;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypeSafeClientBuilderTests {

  private static TypeSafeClient.Builder builder(final Map<String, String> environment) {
    return TypeSafeClient.clientBuilder().environment(environment::get);
  }

  @Test
  void defaultsResolveTheDocumentedPaths() {
    final var client = builder(Map.of(TypeSafeClient.API_KEY_ENV, "from-env")).createClient();
    final var impl = assertInstanceOf(TypeSafeClientImpl.class, client);
    assertEquals(URI.create("https://api.typesafe.ai/v1/systemone"), impl.endpoint());
    assertEquals(TypeSafeClient.DEFAULT_MODEL, client.defaultModel());
    assertEquals(TypeSafeClient.DEFAULT_REQUEST_TIMEOUT, impl.defaultRequestTimeout());
    assertNotNull(impl.httpClient());
  }

  @Test
  void environmentSuppliesEndpointAndModel() {
    final var client = builder(Map.of(
        TypeSafeClient.API_KEY_ENV, "k",
        TypeSafeClient.BASE_URL_ENV, "http://127.0.0.1:2/",
        TypeSafeClient.DEFAULT_MODEL_ENV, "jev-env"
    )).createClient();
    final var impl = assertInstanceOf(TypeSafeClientImpl.class, client);
    assertEquals(URI.create("http://127.0.0.1:2/v1/systemone"), impl.endpoint());
    assertEquals("jev-env", client.defaultModel());
  }

  @Test
  void explicitSettingsWinOverTheEnvironment() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = builder(Map.of(
          TypeSafeClient.API_KEY_ENV, "env-key",
          TypeSafeClient.BASE_URL_ENV, "http://127.0.0.1:2/",
          TypeSafeClient.DEFAULT_MODEL_ENV, "jev-env"
      ))
          .endpoint("http://127.0.0.1:1/")
          .apiKey("k")
          .model("jev-preview")
          .httpClient(httpClient)
          .requestTimeout(Duration.ofSeconds(3))
          .createClient();
      final var impl = assertInstanceOf(TypeSafeClientImpl.class, client);
      assertEquals(URI.create("http://127.0.0.1:1/v1/systemone"), impl.endpoint());
      assertEquals("jev-preview", client.defaultModel());
      assertEquals(Duration.ofSeconds(3), impl.defaultRequestTimeout());
      assertSame(httpClient, impl.httpClient());
    }
  }

  @Test
  void aMissingOrBlankKeyIsAnError() {
    final var missing = assertThrows(IllegalStateException.class, () -> builder(Map.of()).createClient());
    assertTrue(missing.getMessage().contains(TypeSafeClient.API_KEY_ENV));
    assertThrows(IllegalStateException.class, () -> builder(Map.of(TypeSafeClient.API_KEY_ENV, " ")).createClient());
    assertThrows(IllegalStateException.class, () -> builder(Map.of()).apiKey(" ").createClient());
  }

  @Test
  void blankValuesFallThroughToTheNextSource() {
    final var client = builder(Map.of(
        TypeSafeClient.API_KEY_ENV, "k",
        TypeSafeClient.DEFAULT_MODEL_ENV, " "
    )).model(" ").createClient();
    assertEquals(TypeSafeClient.DEFAULT_MODEL, client.defaultModel());
    final var explicitOverBlankEnv = builder(Map.of(
        TypeSafeClient.API_KEY_ENV, " ",
        TypeSafeClient.BASE_URL_ENV, " "
    )).apiKey("k").createClient();
    assertEquals(URI.create("https://api.typesafe.ai/v1/systemone"),
        assertInstanceOf(TypeSafeClientImpl.class, explicitOverBlankEnv).endpoint());
  }

  /// Both SDKs concatenate the call path onto the configured base after stripping trailing
  /// slashes, so a gateway prefix survives: pinned in
  /// typesafe-sdk-python/tests/test_errors.py:88-92 (`https://api.example.test/prefix`) and
  /// tests/test_clients.py:408 (`https://example.test/prefix///`).
  @Test
  void aBaseUrlWithAPathPrefixKeepsIt() {
    for (final String base : new String[]{"https://host/prefix", "https://host/prefix/", "https://host/prefix///"}) {
      final var impl = assertInstanceOf(TypeSafeClientImpl.class,
          builder(Map.of(TypeSafeClient.API_KEY_ENV, "k")).endpoint(base).createClient());
      assertEquals(URI.create("https://host/prefix/v1/systemone"), impl.endpoint(), base);
    }
    final var fromEnv = assertInstanceOf(TypeSafeClientImpl.class, builder(Map.of(
        TypeSafeClient.API_KEY_ENV, "k",
        TypeSafeClient.BASE_URL_ENV, "https://gw.corp/typesafe/"
    )).createClient());
    assertEquals(URI.create("https://gw.corp/typesafe/v1/systemone"), fromEnv.endpoint());
    // a base that is nothing but slashes leaves the path alone rather than doubling one
    assertEquals(URI.create("/v1/models"), TypeSafeClientImpl.appendPath(URI.create("///"), "/v1/models"));
    assertEquals(URI.create("https://host/v1/models"),
        TypeSafeClientImpl.appendPath(URI.create("https://host"), "/v1/models"));
  }

  /// Both SDKs strip environment and explicit values before using them
  /// (typesafe-sdk-python/tests/test_config.py:45-55, js/src/env.ts:18).
  @Test
  void paddedEnvironmentValuesAreStripped() {
    final var client = builder(Map.of(
        TypeSafeClient.API_KEY_ENV, "  env-key  ",
        TypeSafeClient.BASE_URL_ENV, "  https://env.test///  ",
        TypeSafeClient.DEFAULT_MODEL_ENV, "  env-model  "
    )).createClient();
    final var impl = assertInstanceOf(TypeSafeClientImpl.class, client);
    assertEquals(URI.create("https://env.test/v1/systemone"), impl.endpoint());
    assertEquals("env-model", client.defaultModel());
    assertEquals("Bearer env-key", bearerOf(impl));
  }

  @Test
  void paddedExplicitValuesAreStripped() {
    final var client = builder(Map.of()).apiKey("  k  ").model("  jev-preview  ").createClient();
    assertEquals("jev-preview", client.defaultModel());
    assertEquals("Bearer k", bearerOf(assertInstanceOf(TypeSafeClientImpl.class, client)));
  }

  /// The `Authorization` the client would send, read back through the operator the builder
  /// composed, without a socket.
  private static String bearerOf(final TypeSafeClientImpl impl) {
    final var request = impl.requestExtender()
        .apply(java.net.http.HttpRequest.newBuilder(URI.create("https://host/v1/models")).GET())
        .build();
    return request.headers().firstValue("Authorization").orElse(null);
  }

  /// Both SDKs reject a non-positive timeout at configuration time
  /// (typesafe-sdk-js/src/client.ts:77-84, typesafe-sdk-python/_core/config.py:25-28); the
  /// JDK would otherwise throw `Invalid duration: PT0S` from inside `systemOne`.
  @Test
  void aNonPositiveRequestTimeoutIsRejectedByTheSetter() {
    for (final Duration bad : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(-1)}) {
      final var rejected = assertThrows(IllegalArgumentException.class,
          () -> TypeSafeClient.clientBuilder().requestTimeout(bad), String.valueOf(bad));
      assertTrue(rejected.getMessage().contains("requestTimeout"), rejected.getMessage());
    }
    // the smallest positive duration is accepted
    final var accepted = builder(Map.of(TypeSafeClient.API_KEY_ENV, "k"))
        .requestTimeout(Duration.ofNanos(1)).createClient();
    assertEquals(Duration.ofNanos(1),
        assertInstanceOf(TypeSafeClientImpl.class, accepted).defaultRequestTimeout());
  }

  @Test
  void theClientVersionIsReadableAndNeverNull() {
    assertNotNull(TypeSafeClient.VERSION);
    assertFalse(TypeSafeClient.VERSION.isBlank());
    assertEquals("sava-typesafe-client/" + TypeSafeClient.VERSION, TypeSafeClient.CLIENT_ID);
    assertEquals("java/" + Runtime.version().feature(), TypeSafeClient.RUNTIME_ID);
  }

  @Test
  void settersReturnTheBuilder() {
    final var builder = TypeSafeClient.clientBuilder();
    assertSame(builder, builder.testResponse((response, body) -> true));
    assertSame(builder, builder.extendRequest(b -> b));
    assertSame(builder, builder.environment(Map.<String, String>of()::get));
  }
}
