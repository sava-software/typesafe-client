package software.sava.typesafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Rates the state against ordered levels, level 0 first. The answer is the
/// probability-weighted mean level plus the per-level distribution. Levels should describe
/// concrete situations that stand on their own, not relative degrees.
///
/// One level is accepted. That matches the Python SDK, which rejects only an empty list
/// (`_core/questions.py`, fixture `Score(instructions="Quality?", criteria=["good"])` in
/// tests/test_questions.py), and the generated schema, whose `ScoreQuestion.criteria` carries
/// `min_length=1` (`_schemas/models.py`). It diverges from the JS SDK, which rejects fewer
/// than two levels at both the type level and runtime ('at least two scores are required').
/// No reference bounds the levels above, and neither does this client.
///
/// A level may be a string, an object or an array, but not null: the schema's
/// `list[str | dict | list]` has no null member, and a null level made the two
/// [Question] factories disagree (one threw, the other sent `null`).
///
/// @param levels one description per level (string, object, or array), at least one, none null
public record Score(JsonContent instructions, List<JsonContent> levels) implements Question {

  public static final String TYPE = "score";

  public Score {
    requireNonNull(levels, "levels");
    if (levels.isEmpty()) {
      throw new IllegalArgumentException("a score needs at least one level");
    }
    final var copy = new ArrayList<JsonContent>(levels.size());
    for (final var level : levels) {
      if (level == null) {
        throw new IllegalArgumentException("score level " + copy.size() + " must not be null");
      }
      copy.add(level);
    }
    levels = Collections.unmodifiableList(copy);
  }

  @Override
  public String type() {
    return TYPE;
  }

  /// The highest level number, for normalizing a score onto 0..1.
  public int topLevel() {
    return levels.size() - 1;
  }

  @Override
  public void writeTo(final StringBuilder out) {
    Question.writeHead(out, this);
    out.append(",\"criteria\":");
    JsonContent.array(levels).writeTo(out);
    out.append('}');
  }
}
