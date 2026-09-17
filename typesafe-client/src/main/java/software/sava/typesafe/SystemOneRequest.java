package software.sava.typesafe;

import systems.comodal.jsoniter.JIUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

import static java.util.Objects.requireNonNull;

/// One `POST /v1/systemone` request: the state to evaluate, the model, and the questions
/// keyed by the ids their answers come back under. Independent questions over the same
/// state belong in one request; they are evaluated in parallel.
///
/// @param state   text, a JSON object, or an array of text; null evaluates `null`
/// @param model   model id or alias; null defers to the client default
/// @param timeout per-request override of the client timeout; null uses the client default
public record SystemOneRequest(JsonContent state,
                               String model,
                               SequencedMap<String, Question> questions,
                               Duration timeout) {

  public SystemOneRequest {
    requireNonNull(questions, "questions");
    if (questions.isEmpty()) {
      throw new IllegalArgumentException("a request needs at least one question");
    }
    final var copy = new LinkedHashMap<String, Question>();
    for (final var entry : questions.entrySet()) {
      final var id = entry.getKey();
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("question ids must not be blank");
      }
      copy.put(id, requireNonNull(entry.getValue(), () -> "question " + id));
    }
    questions = Collections.unmodifiableSequencedMap(copy);
    if (model != null && model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /// This request with `model` filled in when it was left null.
  public SystemOneRequest withDefaultModel(final String defaultModel) {
    return model == null ? new SystemOneRequest(state, defaultModel, questions, timeout) : this;
  }

  /// The wire body. The model must be resolved first; see [#withDefaultModel(String)].
  public String body() {
    if (model == null) {
      throw new IllegalStateException("model is unresolved; call withDefaultModel first");
    }
    final var out = new StringBuilder(256);
    out.append("{\"state\":");
    JsonContent.write(out, state);
    out.append(",\"model\":\"").append(JIUtil.escapeJson(model)).append("\",\"questions\":{");
    var first = true;
    for (final var entry : questions.entrySet()) {
      if (first) {
        first = false;
      } else {
        out.append(',');
      }
      out.append('"').append(JIUtil.escapeJson(entry.getKey())).append("\":");
      entry.getValue().writeTo(out);
    }
    out.append("}}");
    return out.toString();
  }

  public static final class Builder {

    private JsonContent state;
    private String model;
    private final LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
    private Duration timeout;

    private Builder() {
    }

    public Builder state(final JsonContent state) {
      this.state = state;
      return this;
    }

    public Builder state(final String text) {
      this.state = text == null ? null : JsonContent.text(text);
      return this;
    }

    public Builder model(final String model) {
      this.model = model;
      return this;
    }

    public Builder question(final String id, final Question question) {
      this.questions.put(id, question);
      return this;
    }

    public Builder questions(final Map<String, ? extends Question> questions) {
      this.questions.putAll(questions);
      return this;
    }

    public Builder timeout(final Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public SystemOneRequest build() {
      return new SystemOneRequest(state, model, questions, timeout);
    }
  }
}
