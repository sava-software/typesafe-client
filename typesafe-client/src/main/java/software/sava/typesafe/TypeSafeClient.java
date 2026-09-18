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
/// The client does not retry. A 408, 429 or 529 surfaces as a
/// [software.sava.typesafe.exceptions.TypeSafeRequestException] whose `canBeRetried()` is
/// true; callers own the backoff.
///
/// Every request carries `Authorization`, `Accept: application/json`, a `User-Agent` and
/// `X-TypeSafe-SDK` of [#CLIENT_ID], and an `X-TypeSafe-Runtime` of [#RUNTIME_ID]. Those are
/// set after [Builder#extendRequest(UnaryOperator)] so a caller's decorator cannot replace
/// them, matching both reference SDKs.
public interface TypeSafeClient {

  String DEFAULT_ENDPOINT = "https://api.typesafe.ai";
  String DEFAULT_MODEL = "jev-latest";

  /// The whole-call budget: one attempt, no retry inside it. Comparable to the SDKs' default
  /// of 10s per attempt with two retries, which spends about the same end to end.
  Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /// This client's version, from the packaged implementation version, `dev` when it is not
  /// packaged (a classes directory carries no manifest).
  String VERSION = implementationVersion();

  /// Sent as both `User-Agent` and `X-TypeSafe-SDK`.
  String CLIENT_ID = "sava-typesafe-client/" + VERSION;

  /// Sent as `X-TypeSafe-Runtime`.
  String RUNTIME_ID = "java/" + Runtime.version().feature();

  String API_KEY_ENV = "TYPESAFE_API_KEY";
  String BASE_URL_ENV = "TYPESAFE_BASE_URL";
  String DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

  private static String implementationVersion() {
    final var version = TypeSafeClient.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

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

  /// `GET /v1/models`: the models this key can use. Sent without a `Content-Type`, since it
  /// has no body, as both SDKs do.
  ///
  /// The response's `x-typesafe-request-id` is dropped: both SDKs expose it on every call,
  /// and matching that here needs a carrier record beside the list, which is deferred.
  /// A failed models call still carries the id, on the exception.
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

    /// The API base URL, `https://api.typesafe.ai` by default (or `TYPESAFE_BASE_URL`). The
    /// call paths are appended to it after trailing slashes are stripped, so a gateway prefix
    /// is kept: `https://gw.corp/typesafe/` reaches `https://gw.corp/typesafe/v1/systemone`.
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

    /// The whole-call budget, [#DEFAULT_REQUEST_TIMEOUT] by default.
    ///
    /// @throws IllegalArgumentException when the duration is null, zero or negative, which
    /// the JDK would otherwise reject from inside `systemOne` with a message naming neither
    /// this setter nor the request.
    public Builder requestTimeout(final Duration requestTimeout) {
      if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "Builder.requestTimeout must be a positive Duration, not " + requestTimeout);
      }
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

    /// Decorates every request before the client's own headers are set, so an extender cannot
    /// replace `Authorization`, `Accept`, `User-Agent`, `X-TypeSafe-SDK` or
    /// `X-TypeSafe-Runtime`: whatever it sets for those is overwritten. Both SDKs order it
    /// the same way. Everything else it sets goes out as written.
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
      final UnaryOperator<HttpRequest.Builder> clientHeaders = builder -> builder
          .setHeader("Authorization", "Bearer " + apiKey)
          .setHeader("Accept", "application/json")
          .setHeader("User-Agent", CLIENT_ID)
          .setHeader("X-TypeSafe-SDK", CLIENT_ID)
          .setHeader("X-TypeSafe-Runtime", RUNTIME_ID);
      final var extend = this.extendRequest;
      final UnaryOperator<HttpRequest.Builder> extendRequest = extend == null
          ? clientHeaders
          : builder -> clientHeaders.apply(extend.apply(builder));
      return new TypeSafeClientImpl(endpoint, model, httpClient, requestTimeout, extendRequest, testResponse);
    }

    /// The explicit value, else the environment's, each stripped of surrounding whitespace;
    /// a value blank once stripped counts as absent. Both SDKs trim, and an untrimmed value
    /// here breaks differently per variable: a padded base URL throws from `URI.create`, a
    /// padded model is sent verbatim and rejected as unknown, and a key with a newline throws
    /// `invalid header value` on every call.
    private String orEnv(final String explicit, final String variable) {
      if (explicit != null && !explicit.isBlank()) {
        return explicit.strip();
      }
      final var fromEnv = environment.apply(variable);
      return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
    }

    private static String orDefault(final String value, final String fallback) {
      return value == null ? fallback : value;
    }
  }
}
