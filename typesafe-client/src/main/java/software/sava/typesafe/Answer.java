package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/// One answer from the `answers` map of a System One response, keyed by the question id it
/// was asked under. The concrete type follows the wire `type`; an unrecognized type parses to
/// [UnknownAnswer] rather than failing, so a newer API does not break an older client.
public sealed interface Answer permits ChoiceAnswer, NoulAnswer, ScoreAnswer, UnknownAnswer {

  /// The wire `type` value.
  String type();

  /// Parses one answer object. Field order on the wire is not assumed: `type` may follow the
  /// fields it interprets.
  static Answer parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(Parser.FIELDS, parser);
    return parser.create();
  }

  final class Parser implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of(
        "type", "noul", "choice", "probabilities", "confidence", "score", "legend"
    );

    private String type;
    private double noul;
    private String choice;
    private LinkedHashMap<String, Double> probabilities;
    private double confidence;
    private double score;
    private LinkedHashMap<String, String> legend;

    private Parser() {
    }

    Answer create() {
      if (type == null) {
        throw new IllegalStateException("answer without a type");
      }
      return switch (type) {
        case ChoiceAnswer.TYPE -> new ChoiceAnswer(
            choice,
            probabilities == null ? Map.of() : Collections.unmodifiableMap(probabilities),
            confidence
        );
        case NoulAnswer.TYPE -> new NoulAnswer(noul);
        case ScoreAnswer.TYPE -> new ScoreAnswer(score, levelKeys(probabilities), levelKeys(legend), confidence);
        default -> new UnknownAnswer(type);
      };
    }

    /// Score maps come keyed by level number as a string; re-key by the number, ascending.
    private static <V> Map<Integer, V> levelKeys(final Map<String, V> byLevelText) {
      if (byLevelText == null) {
        return Map.of();
      }
      final var byLevel = new TreeMap<Integer, V>();
      for (final var entry : byLevelText.entrySet()) {
        byLevel.put(Integer.parseInt(entry.getKey()), entry.getValue());
      }
      return Collections.unmodifiableMap(byLevel);
    }

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> type = ji.readString();
        case 1 -> noul = ji.readDouble();
        case 2 -> choice = ji.readString();
        case 3 -> {
          final var map = new LinkedHashMap<String, Double>();
          ji.testObject((buf, offset, len, ji1) -> {
            map.put(new String(buf, offset, len), ji1.readDouble());
            return true;
          });
          probabilities = map;
        }
        case 4 -> confidence = ji.readDouble();
        case 5 -> score = ji.readDouble();
        case 6 -> {
          final var map = new LinkedHashMap<String, String>();
          ji.testObject((buf, offset, len, ji1) -> {
            map.put(new String(buf, offset, len), ji1.readString());
            return true;
          });
          legend = map;
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
