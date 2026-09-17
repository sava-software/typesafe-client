package software.sava.typesafe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import static java.util.Objects.requireNonNull;

/// Picks one option from `criteria`. The answer carries the winning key, the probability of
/// every key, and a confidence summarizing how concentrated that distribution is. Include an
/// `other` or `none` key when the list might not cover every input.
///
/// @param criteria option name to description (string, object, array, or null); insertion
///                 order is the order sent. At most [#MAX_OPTIONS] entries.
public record Choice(JsonContent instructions, SequencedMap<String, JsonContent> criteria) implements Question {

  /// The documented per-question option limit.
  public static final int MAX_OPTIONS = 255;

  public static final String TYPE = "choice";

  public Choice {
    requireNonNull(criteria, "criteria");
    if (criteria.isEmpty()) {
      throw new IllegalArgumentException("a choice needs at least one option");
    }
    if (criteria.size() > MAX_OPTIONS) {
      throw new IllegalArgumentException("a choice takes at most " + MAX_OPTIONS + " options, got " + criteria.size());
    }
    final var copy = new LinkedHashMap<String, JsonContent>();
    for (final var entry : criteria.entrySet()) {
      final var option = entry.getKey();
      if (option == null || option.isBlank()) {
        throw new IllegalArgumentException("choice option names must not be blank");
      }
      copy.put(option, entry.getValue());
    }
    criteria = Collections.unmodifiableSequencedMap(copy);
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void writeTo(final StringBuilder out) {
    Question.writeHead(out, this);
    out.append(",\"criteria\":");
    JsonContent.object(criteria).writeTo(out);
    out.append('}');
  }
}
