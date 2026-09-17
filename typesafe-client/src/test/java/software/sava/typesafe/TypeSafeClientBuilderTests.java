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

  @Test
  void settersReturnTheBuilder() {
    final var builder = TypeSafeClient.clientBuilder();
    assertSame(builder, builder.testResponse((response, body) -> true));
    assertSame(builder, builder.extendRequest(b -> b));
    assertSame(builder, builder.environment(Map.<String, String>of()::get));
  }
}
