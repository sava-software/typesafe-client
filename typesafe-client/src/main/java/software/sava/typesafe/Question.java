package software.sava.typesafe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/// One System One question: a `type`, optional `instructions`, and type-specific `criteria`.
/// The question id is the key the caller stores it under in the request; it is not sent to
/// the model, so the instructions must carry the whole meaning.
public sealed interface Question permits Choice, Noul, Score {

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

  static Noul noul(final String instructions, final String trueDescription, final String falseDescription) {
    return new Noul(text(instructions), new NoulCriteria(text(trueDescription), text(falseDescription)));
  }

  /// A [Score] whose levels are the given descriptions, level 0 first.
  static Score score(final String instructions, final List<String> levels) {
    return new Score(text(instructions), levels.stream().map(Question::text).toList());
  }

  static Score score(final String instructions, final String... levels) {
    return score(instructions, List.of(levels));
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
}
