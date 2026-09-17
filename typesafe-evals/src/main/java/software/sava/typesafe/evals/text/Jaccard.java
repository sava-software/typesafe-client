package software.sava.typesafe.evals.text;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Token-set Jaccard similarity: the lexical baseline every prose judgment in the
/// experiments has to beat. Tokens are maximal runs of letters and digits, lower-cased;
/// order and repetition are ignored.
public final class Jaccard {

  private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{N}]+");

  /// The distinct lower-case tokens of `text`; empty for a null or token-free string.
  public static Set<String> tokens(final String text) {
    final var tokens = new HashSet<String>();
    final var source = text == null ? "" : text.toLowerCase(Locale.ROOT);
    for (final var token : NON_TOKEN.split(source)) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  /// `|A ∩ B| / |A ∪ B|` over the token sets, in 0..1. Two token-free strings score 0: with
  /// nothing to compare there is no evidence of similarity.
  public static double similarity(final String a, final String b) {
    return similarity(tokens(a), tokens(b));
  }

  public static double similarity(final Set<String> a, final Set<String> b) {
    int intersection = 0;
    for (final var token : a) {
      if (b.contains(token)) {
        ++intersection;
      }
    }
    final int union = a.size() + b.size() - intersection;
    return union == 0 ? 0.0 : (double) intersection / union;
  }

  private Jaccard() {
  }
}
