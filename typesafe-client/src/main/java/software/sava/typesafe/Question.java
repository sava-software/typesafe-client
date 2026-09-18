package software.sava.typesafe;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static java.util.Objects.requireNonNull;

/// One System One question: a `type`, optional `instructions`, and type-specific `criteria`.
/// The question id is the key the caller stores it under in the request; it is not sent to
/// the model, so the instructions must carry the whole meaning.
///
/// [RawQuestion] is the escape hatch for a shape this client does not model -- a question
/// `type` the API adds later, or a new per-question field. Both reference SDKs forward a raw
/// question object untouched (typesafe-sdk-python `_core/questions.py` requires only a
/// non-empty string `type`; the JS SDK spreads the caller's object), so the sealed hierarchy
/// here would otherwise be the one place a new wire shape is unreachable.
public sealed interface Question permits Choice, Noul, Score, Question.RawQuestion {

  /// The wire `type` value.
  String type();

  /// The judgment to evaluate; may be null, in which case the field is omitted.
  JsonContent instructions();

  /// Writes the complete question object, `{"type":...,"instructions":...,"criteria":...}`.
  void writeTo(final StringBuilder out);

  /// A [Choice] over `criteria` keys, each described by its string value (null keeps the
  /// option name as its only description).
  static Choice choice(final String instructions, final SequencedMap<String, String> criteria) {
    final var described = new LinkedHashMap<String, JsonContent>();
    for (final var entry : criteria.entrySet()) {
      described.put(entry.getKey(), entry.getValue() == null ? null : JsonContent.text(entry.getValue()));
    }
    return new Choice(text(instructions), described);
  }

  /// A [Choice] over bare option names with no descriptions.
  static Choice choice(final String instructions, final String... options) {
    final var described = new LinkedHashMap<String, JsonContent>();
    for (final var option : options) {
      described.put(option, null);
    }
    return new Choice(text(instructions), described);
  }

  static Noul noul(final String instructions) {
    return new Noul(text(instructions), null);
  }

  /// A [Noul] describing only the yes side; the `false` key is omitted, as both reference
  /// SDKs do for a one-sided noul.
  static Noul noul(final String instructions, final String trueDescription) {
    return new Noul(text(instructions), new NoulCriteria(text(trueDescription), null));
  }

  static Noul noul(final String instructions, final String trueDescription, final String falseDescription) {
    return new Noul(text(instructions), new NoulCriteria(text(trueDescription), text(falseDescription)));
  }

  /// A [Score] whose levels are the given descriptions, level 0 first.
  static Score score(final String instructions, final List<String> levels) {
    return new Score(text(instructions), levels.stream().map(Question::text).toList());
  }

  static Score score(final String instructions, final String... levels) {
    return score(instructions, Arrays.asList(levels));
  }

  /// A [Score] whose levels are arbitrary JSON -- an object or array per level, the shape the
  /// schema's `list[str | dict | list]` allows and the Python SDK sends at
  /// tests/test_clients.py:209. Named apart from [#score(String,List)], whose erasure it
  /// would otherwise share.
  static Score scoreLevels(final String instructions, final List<JsonContent> levels) {
    return new Score(text(instructions), levels);
  }

  /// A question this client does not model, written verbatim. `json` must be a JSON object.
  static RawQuestion raw(final String type, final String json) {
    return new RawQuestion(type, json);
  }

  /// A question this client does not model, built from [JsonContent] -- typically a
  /// [JsonContent.Obj] carrying the fields a future API version adds.
  static RawQuestion raw(final String type, final JsonContent json) {
    return new RawQuestion(type, requireNonNull(json, "json").toJson());
  }

  private static JsonContent text(final String value) {
    return value == null ? null : JsonContent.text(value);
  }

  /// Shared prefix writer: `{"type":"<type>"` plus `,"instructions":<json>` when present.
  static void writeHead(final StringBuilder out, final Question question) {
    out.append("{\"type\":\"").append(question.type()).append('"');
    final var instructions = question.instructions();
    if (instructions != null) {
      out.append(",\"instructions\":");
      instructions.writeTo(out);
    }
  }

  /// A pre-serialized question object, written to the wire exactly as given. The caller
  /// guarantees the text is a valid JSON object carrying its own `type`; nothing here parses
  /// it, and an invalid body surfaces as a 422 from the API.
  ///
  /// @param type the wire `type` the text declares, kept so callers can group questions by
  ///             type without parsing; it is not written, the text is
  /// @param json the question object, `{`-to-`}` after trimming
  record RawQuestion(String type, String json) implements Question {

    public RawQuestion {
      requireNonNull(type, "type");
      requireNonNull(json, "json");
      if (type.isBlank()) {
        throw new IllegalArgumentException("a raw question needs a type");
      }
      json = json.trim();
      if (!json.startsWith("{") || !json.endsWith("}")) {
        throw new IllegalArgumentException("a raw question must be a JSON object, got " + json);
      }
    }

    /// Always null: the instructions, if any, live inside [#json()].
    @Override
    public JsonContent instructions() {
      return null;
    }

    @Override
    public void writeTo(final StringBuilder out) {
      out.append(json);
    }
  }
}
