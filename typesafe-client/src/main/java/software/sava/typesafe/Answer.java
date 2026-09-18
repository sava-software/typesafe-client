package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/// One answer from the `answers` map of a System One response, keyed by the question id it
/// was asked under. The concrete type follows the wire `type`; an unrecognized type parses to
/// [UnknownAnswer] rather than failing, so a newer API does not break an older client.
///
/// The fields a known type needs are required, as they are in the reference SDKs: a noul
/// without `noul`, or a choice or score without its payload or `confidence`, fails the parse
/// with an [IllegalStateException] rather than reading back as 0.0 or null. A wire `null`
/// counts as absent. `probabilities` and `legend` stay optional and parse to an empty map.
/// Numbers must be JSON numbers: a quoted number is rejected, matching the Python SDK's
/// strict models.
public sealed interface Answer permits ChoiceAnswer, NoulAnswer, ScoreAnswer, UnknownAnswer {

  /// The wire `type` value.
  String type();

  /// Parses one answer object. Field order on the wire is not assumed: `type` may follow the
  /// fields it interprets.
  static Answer parse(final JsonIterator ji) {
    final int start = ji.mark();
    final var parser = new Parser();
    ji.testObject(Parser.FIELDS, parser);
    return parser.create(ji, start);
  }

  final class Parser implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of(
        "type", "noul", "choice", "probabilities", "confidence", "score", "legend"
    );

    private static final int NOUL = 1;
    private static final int CONFIDENCE = 4;
    private static final int SCORE = 5;

    /// Depth ceiling for the JSON reconstruction below. Legend values and unknown answer
    /// payloads are walked recursively; past this depth the parse fails with a
    /// [RuntimeException] instead of exhausting the stack on pathological input.
    static final int MAX_DEPTH = 64;

    private String type;
    private double noul;
    private String choice;
    private LinkedHashMap<String, Double> probabilities;
    private double confidence;
    private double score;
    private LinkedHashMap<String, JsonContent> legend;
    /// One bit per matched field index, set only for a value that was actually present.
    private int seen;

    private Parser() {
    }

    Answer create(final JsonIterator ji, final int start) {
      if (type == null) {
        throw new IllegalStateException("answer without a type");
      }
      return switch (type) {
        case ChoiceAnswer.TYPE -> {
          if (choice == null) { // a null choice is as absent as no choice field at all
            throw new IllegalStateException(type + " answer without a 'choice' field");
          }
          require(CONFIDENCE, "confidence");
          yield new ChoiceAnswer(
              choice,
              probabilities == null ? Map.of() : Collections.unmodifiableMap(probabilities),
              confidence
          );
        }
        case NoulAnswer.TYPE -> {
          require(NOUL, "noul");
          yield new NoulAnswer(noul);
        }
        case ScoreAnswer.TYPE -> {
          require(SCORE, "score");
          require(CONFIDENCE, "confidence");
          yield new ScoreAnswer(
              score,
              levelKeys(probabilities, "probabilities"),
              levelKeys(legend, "legend"),
              confidence
          );
        }
        default -> new UnknownAnswer(type, reread(ji, start));
      };
    }

    private void require(final int fieldIndex, final String field) {
      if ((seen & (1 << fieldIndex)) == 0) {
        throw new IllegalStateException(type + " answer without a '" + field + "' field");
      }
    }

    /// Score maps come keyed by level number as a string; re-key by the number, ascending.
    /// A key that is not a level number fails the parse with the key named.
    private static <V> Map<Integer, V> levelKeys(final Map<String, V> byLevelText, final String field) {
      if (byLevelText == null) {
        return Map.of();
      }
      final var byLevel = new TreeMap<Integer, V>();
      for (final var entry : byLevelText.entrySet()) {
        final var key = entry.getKey();
        final int level;
        try {
          level = Integer.parseInt(key);
        } catch (final NumberFormatException cause) {
          throw new IllegalStateException(
              "score answer '" + field + "' key '" + key + "' is not a level number", cause);
        }
        byLevel.put(level, entry.getValue());
      }
      // not Map.copyOf: a legend level echoed back as JSON null is a null value.
      return Collections.unmodifiableMap(byLevel);
    }

    /// Re-reads the answer object from `start` as JSON text, so an unrecognized type keeps its
    /// payload. The iterator is left exactly where the field walk finished.
    private static String reread(final JsonIterator ji, final int start) {
      final int end = ji.mark();
      final var json = new StringBuilder(64);
      ji.reset(start);
      writeValue(ji, json, 0);
      ji.reset(end);
      return json.toString();
    }

    /// Reads one JSON value: a string becomes [JsonContent.Text], a wire `null` becomes a null
    /// reference, and every other value becomes [JsonContent.Raw] holding its JSON text.
    static JsonContent readValue(final JsonIterator ji) {
      return switch (ji.whatIsNext()) {
        case STRING -> JsonContent.text(ji.readString());
        case NULL -> {
          ji.skip();
          yield null;
        }
        default -> {
          final var json = new StringBuilder(32);
          writeValue(ji, json, 0);
          yield JsonContent.raw(json.toString());
        }
      };
    }

    /// Appends the next value as JSON text, escaping strings and field names the way
    /// [JsonContent] does on the way out.
    private static void writeValue(final JsonIterator ji, final StringBuilder out, final int depth) {
      if (depth > MAX_DEPTH) {
        throw new IllegalStateException("JSON value nested deeper than " + MAX_DEPTH + " levels");
      }
      switch (ji.whatIsNext()) {
        case STRING -> JsonContent.text(ji.readString()).writeTo(out);
        case NUMBER -> out.append(ji.readNumberAsString());
        case BOOLEAN -> out.append(ji.readBoolean());
        case NULL -> {
          ji.skip();
          out.append("null");
        }
        case ARRAY -> {
          out.append('[');
          for (int i = 0; ji.readArray(); ++i) {
            if (i > 0) {
              out.append(',');
            }
            writeValue(ji, out, depth + 1);
          }
          out.append(']');
        }
        case OBJECT -> {
          out.append('{');
          final var written = new int[1];
          ji.testObject((buf, offset, len, ji1) -> {
            if (written[0]++ > 0) {
              out.append(',');
            }
            JsonContent.text(new String(buf, offset, len)).writeTo(out);
            out.append(':');
            writeValue(ji1, out, depth + 1);
            return true;
          });
          out.append('}');
        }
        default -> throw new IllegalStateException("not a JSON value at " + ji.currentBuffer());
      }
    }

    /// Both SDKs treat a quoted number as a bad response; json-iterator would unwrap it.
    private static double readNumber(final JsonIterator ji, final String field) {
      final var valueType = ji.whatIsNext();
      if (valueType != ValueType.NUMBER) {
        throw new IllegalStateException("answer field '" + field + "' must be a JSON number, not " + valueType);
      }
      return ji.readDouble();
    }

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> type = ji.readString();
        case 1 -> noul = readNumber(ji, "noul");
        case 2 -> choice = ji.readString();
        case 3 -> {
          final var map = new LinkedHashMap<String, Double>();
          ji.testObject((buf, offset, len, ji1) -> {
            map.put(new String(buf, offset, len), readNumber(ji1, "probabilities"));
            return true;
          });
          probabilities = map;
        }
        case 4 -> confidence = readNumber(ji, "confidence");
        case 5 -> score = readNumber(ji, "score");
        case 6 -> {
          final var map = new LinkedHashMap<String, JsonContent>();
          ji.testObject((buf, offset, len, ji1) -> {
            map.put(new String(buf, offset, len), readValue(ji1));
            return true;
          });
          legend = map;
        }
        default -> {
          ji.skip();
          return true;
        }
      }
      seen |= 1 << fieldIndex;
      return true;
    }
  }
}
