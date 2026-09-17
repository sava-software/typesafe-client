package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static software.sava.typesafe.exceptions.TypeSafeRequestException.REQUEST_ID_HEADER;

/// One `POST /v1/systemone` response.
///
/// @param model     the model that answered, as reported (an alias resolves to its version)
/// @param answers   answers keyed by question id, in wire order
/// @param usage     token accounting; may be null when the API omits it
/// @param requestId the `x-typesafe-request-id` header, or null when absent
/// @param raw       the response body verbatim, for recording and provenance
public record SystemOneResponse(String model,
                               Map<String, Answer> answers,
                               Usage usage,
                               String requestId,
                               String raw) {

  /// Parses a body, taking the request id from the response headers.
  public static SystemOneResponse parse(final HttpResponse<?> httpResponse, final byte[] body) {
    final var requestId = httpResponse.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
    return parse(body, requestId);
  }

  public static SystemOneResponse parse(final byte[] body, final String requestId) {
    return parse(JsonIterator.parse(body), requestId, new String(body, StandardCharsets.UTF_8));
  }

  public static SystemOneResponse parse(final JsonIterator ji, final String requestId, final String raw) {
    final var parser = new Parser();
    ji.testObject(Parser.FIELDS, parser);
    return new SystemOneResponse(
        parser.model,
        parser.answers == null ? Map.of() : Collections.unmodifiableMap(parser.answers),
        parser.usage,
        requestId,
        raw
    );
  }

  /// The answer to question `id`.
  ///
  /// @throws NoSuchElementException when no answer came back under that id
  public Answer answer(final String id) {
    final var answer = answers.get(id);
    if (answer == null) {
      throw new NoSuchElementException("no answer for question '" + id + "'; have " + answers.keySet());
    }
    return answer;
  }

  /// @throws IllegalStateException when the answer is not a choice
  public ChoiceAnswer choice(final String id) {
    return expect(id, ChoiceAnswer.class);
  }

  /// @throws IllegalStateException when the answer is not a noul
  public NoulAnswer noul(final String id) {
    return expect(id, NoulAnswer.class);
  }

  /// @throws IllegalStateException when the answer is not a score
  public ScoreAnswer score(final String id) {
    return expect(id, ScoreAnswer.class);
  }

  private <A extends Answer> A expect(final String id, final Class<A> type) {
    final var answer = answer(id);
    if (type.isInstance(answer)) {
      return type.cast(answer);
    }
    throw new IllegalStateException("question '" + id + "' answered as " + answer.type()
        + ", not " + type.getSimpleName());
  }

  private static final class Parser implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of("model", "answers", "usage");

    private String model;
    private LinkedHashMap<String, Answer> answers;
    private Usage usage;

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> model = ji.readString();
        case 1 -> {
          final var map = new LinkedHashMap<String, Answer>();
          ji.testObject((buf, offset, len, ji1) -> {
            map.put(new String(buf, offset, len), Answer.parse(ji1));
            return true;
          });
          answers = map;
        }
        case 2 -> usage = Usage.parse(ji);
        default -> ji.skip();
      }
      return true;
    }
  }
}
