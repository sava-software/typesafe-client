package software.sava.typesafe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/// Client for the TypeSafe System One API. Build one with [#clientBuilder()]; the API key
/// comes from the builder or the `TYPESAFE_API_KEY` environment variable.
///
/// The client does not retry. A 429 or 529 surfaces as a
/// [software.sava.typesafe.exceptions.TypeSafeRequestException] whose `canBeRetried()` is
/// true; callers own the backoff.
public interface TypeSafeClient {

  String DEFAULT_ENDPOINT = "https://api.typesafe.ai";
  String DEFAULT_MODEL = "jev-latest";
  Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  String API_KEY_ENV = "TYPESAFE_API_KEY";
  String BASE_URL_ENV = "TYPESAFE_BASE_URL";
  String DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

  static Builder clientBuilder() {
    return new Builder();
  }

  /// The model used when a request leaves its model null.
  String defaultModel();

  CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request);

  default CompletableFuture<SystemOneResponse> systemOne(final JsonContent state,
                                                        final SequencedMap<String, Question> questions) {
    return systemOne(SystemOneRequest.builder().state(state).questions(questions).build());
  }

  default CompletableFuture<SystemOneResponse> systemOne(final String state,
                                                        final SequencedMap<String, Question> questions) {
    return systemOne(SystemOneRequest.builder().state(state).questions(questions).build());
  }

  /// `GET /v1/models`: the models this key can use.
  CompletableFuture<List<ModelCard>> models();

  final class Builder {

    private URI endpoint;
    private HttpClient httpClient;
    private Duration requestTimeout;
    private String apiKey;
    private String model;
    private UnaryOperator<HttpRequest.Builder> extendRequest;
    private BiPredicate<HttpResponse<?>, byte[]> testResponse;
    private Function<String, String> environment = System::getenv;

    private Builder() {
    }

    /// Test seam: where `TYPESAFE_*` variables are read from. Production reads the process
    /// environment.
    // package-private for tests
    Builder environment(final Function<String, String> environment) {
      this.environment = environment;
      return this;
    }

    /// The API origin, `https://api.typesafe.ai` by default (or `TYPESAFE_BASE_URL`). Paths
    /// are resolved against it, so a value ending in a path segment is not what you want.
    public Builder endpoint(final URI endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder endpoint(final String endpoint) {
      return endpoint(URI.create(endpoint));
    }

    public Builder httpClient(final HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public Builder requestTimeout(final Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    /// Overrides `TYPESAFE_API_KEY`.
    public Builder apiKey(final String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /// Overrides `TYPESAFE_DEFAULT_MODEL`, itself defaulting to [#DEFAULT_MODEL].
    public Builder model(final String model) {
      this.model = model;
      return this;
    }

    /// Decorates every request after the bearer token is set.
    public Builder extendRequest(final UnaryOperator<HttpRequest.Builder> extendRequest) {
      this.extendRequest = extendRequest;
      return this;
    }

    /// A test seam from `JsonHttpClient`: a predicate that may swallow a response (returning
    /// null from the future) before it is parsed.
    public Builder testResponse(final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
      this.testResponse = testResponse;
      return this;
    }

    public TypeSafeClient createClient() {
      final var apiKey = orEnv(this.apiKey, API_KEY_ENV);
      if (apiKey == null) {
        throw new IllegalStateException("TypeSafe API key missing: set " + API_KEY_ENV + " or Builder.apiKey");
      }
      final var endpoint = this.endpoint != null
          ? this.endpoint
          : URI.create(orDefault(orEnv(null, BASE_URL_ENV), DEFAULT_ENDPOINT));
      final var model = orDefault(orEnv(this.model, DEFAULT_MODEL_ENV), DEFAULT_MODEL);
      final var httpClient = this.httpClient != null ? this.httpClient : HttpClient.newHttpClient();
      final var requestTimeout = this.requestTimeout != null ? this.requestTimeout : DEFAULT_REQUEST_TIMEOUT;
      final UnaryOperator<HttpRequest.Builder> bearer = builder -> builder.setHeader("Authorization", "Bearer " + apiKey);
      final var extend = this.extendRequest;
      final UnaryOperator<HttpRequest.Builder> extendRequest = extend == null
          ? bearer
          : builder -> extend.apply(bearer.apply(builder));
      return new TypeSafeClientImpl(endpoint, model, httpClient, requestTimeout, extendRequest, testResponse);
    }

    private String orEnv(final String explicit, final String variable) {
      if (explicit != null && !explicit.isBlank()) {
        return explicit;
      }
      final var fromEnv = environment.apply(variable);
      return fromEnv == null || fromEnv.isBlank() ? null : fromEnv;
    }

    private static String orDefault(final String value, final String fallback) {
      return value == null ? fallback : value;
    }
  }
}
