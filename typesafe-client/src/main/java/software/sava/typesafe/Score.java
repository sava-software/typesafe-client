package software.sava.typesafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Rates the state against ordered levels, level 0 first. The answer is the
/// probability-weighted mean level plus the per-level distribution. Levels should describe
/// concrete situations that stand on their own, not relative degrees.
///
/// @param levels one description per level (string, object, array, or null), at least one
public record Score(JsonContent instructions, List<JsonContent> levels) implements Question {

  public static final String TYPE = "score";

  public Score {
    requireNonNull(levels, "levels");
    if (levels.isEmpty()) {
      throw new IllegalArgumentException("a score needs at least one level");
    }
    levels = Collections.unmodifiableList(new ArrayList<>(levels));
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
