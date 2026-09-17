package software.sava.typesafe;

import software.sava.rpc.json.http.client.JsonHttpClient;
import software.sava.typesafe.exceptions.TypeSafeParseException;
import software.sava.typesafe.exceptions.TypeSafeRequestException;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNullElse;

final class TypeSafeClientImpl extends JsonHttpClient implements TypeSafeClient {

  static final String SYSTEM_ONE_PATH = "/v1/systemone";
  static final String MODELS_PATH = "/v1/models";

  private static final Function<HttpResponse<?>, SystemOneResponse> SYSTEM_ONE_PARSER = httpResponse -> {
    final byte[] body = gate(httpResponse);
    try {
      return SystemOneResponse.parse(httpResponse, body);
    } catch (final RuntimeException parseCause) {
      throw parseFailure(httpResponse, body, parseCause);
    }
  };

  private static final Function<HttpResponse<?>, List<ModelCard>> MODELS_PARSER = httpResponse -> {
    final byte[] body = gate(httpResponse);
    try {
      return ModelCard.parseList(JsonIterator.parse(body));
    } catch (final RuntimeException parseCause) {
      throw parseFailure(httpResponse, body, parseCause);
    }
  };

  /// Reads the body and rejects anything outside 2xx.
  // package-private for tests: the loopback server cannot emit a 1xx final response
  static byte[] gate(final HttpResponse<?> httpResponse) {
    final byte[] body = readBody(httpResponse);
    final int statusCode = httpResponse.statusCode();
    if (statusCode < 200 || statusCode >= 300) {
      throw TypeSafeRequestException.create(httpResponse, body);
    }
    return body;
  }

  private static TypeSafeParseException parseFailure(final HttpResponse<?> httpResponse,
                                                     final byte[] body,
                                                     final RuntimeException parseCause) {
    return new TypeSafeParseException(httpResponse,
        String.format("Failed to adapt %d response: '%s'",
            httpResponse.statusCode(), new String(body, StandardCharsets.UTF_8)),
        parseCause
    );
  }

  private final URI modelsEndpoint;
  private final String defaultModel;

  TypeSafeClientImpl(final URI baseEndpoint,
                     final String defaultModel,
                     final HttpClient httpClient,
                     final Duration requestTimeout,
                     final UnaryOperator<HttpRequest.Builder> extendRequest,
                     final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    super(baseEndpoint.resolve(SYSTEM_ONE_PATH), httpClient, requestTimeout, extendRequest, testResponse);
    this.modelsEndpoint = baseEndpoint.resolve(MODELS_PATH);
    this.defaultModel = defaultModel;
  }

  @Override
  public String defaultModel() {
    return defaultModel;
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
    final var resolved = request.withDefaultModel(defaultModel);
    return sendPostRequest(
        SYSTEM_ONE_PARSER,
        requireNonNullElse(resolved.timeout(), this.requestTimeout),
        resolved.body()
    );
  }

  @Override
  public CompletableFuture<List<ModelCard>> models() {
    return sendGetRequest(modelsEndpoint, MODELS_PARSER);
  }
}
