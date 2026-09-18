package software.sava.typesafe;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// One `POST /v1/systemone` request: the state to evaluate, the model, and the questions
/// keyed by the ids their answers come back under. Independent questions over the same
/// state belong in one request; they are evaluated in parallel.
///
/// `state` is required and must not be null. The schema generated from the API's own
/// description marks it so -- `state: str | dict[str, Any] | list[Any] = Field(...)`, required
/// with no `None` in the union (typesafe-sdk-python `src/typesafe_sdk/_schemas/models.py`) --
/// and the Python SDK's public signature types it `JSONContent`, which excludes None. The JS
/// SDK does type and send a null state; this client follows the generated schema and rejects
/// it here rather than spending a round trip on it.
///
/// `extraBody` is the forward-compatibility hatch both reference SDKs carry (Python's
/// `extra_body`, the JS SDK's spread of the caller's request object): a top-level field the
/// API adds before this client models it. Its keys are written after `questions` and may not
/// collide with `state`, `model` or `questions`, so no key is ever written twice.
///
/// Per-request headers are not modelled. Both reference SDKs take them per call (Python's
/// `extra_headers`, the JS SDK's `options.headers`); here `timeout` is the only per-request
/// option, and `TypeSafeClient.extendRequest` is client-wide, so a per-request trace or tenant
/// header needs a second client. That gap is deliberate for now, not an oversight.
///
/// @param state     text, a JSON object, or an array of text; required
/// @param model     model id or alias; null defers to the client default
/// @param timeout   per-request override of the client timeout; null uses the client default,
///                  and any non-null value must be positive
/// @param extraBody additional top-level body fields, in insertion order; empty by default
public record SystemOneRequest(JsonContent state,
                               String model,
                               SequencedMap<String, Question> questions,
                               Duration timeout,
                               SequencedMap<String, JsonContent> extraBody) {

  /// The reserved top-level keys [#extraBody()] may not carry.
  private static final Set<String> RESERVED_BODY_KEYS = Set.of("state", "model", "questions");

  public SystemOneRequest {
    if (state == null) {
      throw new IllegalArgumentException("state is required; the API schema marks it non-nullable");
    }
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
    if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }
    requireNonNull(extraBody, "extraBody");
    final var extras = new LinkedHashMap<String, JsonContent>();
    for (final var entry : extraBody.entrySet()) {
      final var key = requireNonNull(entry.getKey(), "extra body field name");
      if (RESERVED_BODY_KEYS.contains(key)) {
        throw new IllegalArgumentException("extra body field " + key + " collides with a body field this client writes");
      }
      extras.put(key, entry.getValue());
    }
    extraBody = Collections.unmodifiableSequencedMap(extras);
  }

  /// A request with no extra top-level body fields.
  public SystemOneRequest(final JsonContent state,
                          final String model,
                          final SequencedMap<String, Question> questions,
                          final Duration timeout) {
    this(state, model, questions, timeout, new LinkedHashMap<>());
  }

  public static Builder builder() {
    return new Builder();
  }

  /// This request with `model` filled in when it was left null.
  public SystemOneRequest withDefaultModel(final String defaultModel) {
    return model == null ? new SystemOneRequest(state, defaultModel, questions, timeout, extraBody) : this;
  }

  /// The wire body. The model must be resolved first; see [#withDefaultModel(String)].
  public String body() {
    if (model == null) {
      throw new IllegalStateException("model is unresolved; call withDefaultModel first");
    }
    final var out = new StringBuilder(256);
    out.append("{\"state\":");
    state.writeTo(out);
    out.append(",\"model\":");
    JsonContent.writeString(out, model);
    out.append(",\"questions\":{");
    var first = true;
    for (final var entry : questions.entrySet()) {
      if (first) {
        first = false;
      } else {
        out.append(',');
      }
      JsonContent.writeString(out, entry.getKey());
      out.append(':');
      entry.getValue().writeTo(out);
    }
    out.append('}');
    for (final var entry : extraBody.entrySet()) {
      out.append(',');
      JsonContent.writeString(out, entry.getKey());
      out.append(':');
      JsonContent.write(out, entry.getValue());
    }
    out.append('}');
    return out.toString();
  }

  public static final class Builder {

    private JsonContent state;
    private String model;
    private final LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
    private Duration timeout;
    private final LinkedHashMap<String, JsonContent> extraBody = new LinkedHashMap<>();

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

    /// The per-request timeout; must be positive. Leave it unset to use the client default.
    public Builder timeout(final Duration timeout) {
      if (timeout == null) {
        throw new IllegalArgumentException("timeout must not be null; leave it unset to use the client default");
      }
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be positive, got " + timeout);
      }
      this.timeout = timeout;
      return this;
    }

    /// A top-level body field this client does not model, written after `questions`.
    public Builder extraBody(final String key, final JsonContent value) {
      this.extraBody.put(requireNonNull(key, "extra body field name"), value);
      return this;
    }

    public SystemOneRequest build() {
      return new SystemOneRequest(state, model, questions, timeout, extraBody);
    }
  }
}
