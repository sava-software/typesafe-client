package software.sava.typesafe.evals.docs;

import java.util.ArrayList;
import java.util.List;

/// A documentation comment directly above a declaration: a run of `///` lines or one
/// `/** ... */` block, with no blank line between the comment and the declaration.
///
/// @param startLine 1-based first line of the comment
/// @param endLine   1-based last line of the comment
/// @param style     `markdown` for `///`, `javadoc` for `/** */`
/// @param text      the comment with its markers stripped, lines joined by `\n`
public record DocComment(int startLine, int endLine, String style, String text) {

  /// The comment whose last line is directly above `memberStartLine`, or null.
  static DocComment above(final List<String> lines, final int memberStartLine) {
    int i = memberStartLine - 1; // 1-based line above the member
    if (i < 1) {
      return null;
    }
    final var last = lines.get(i - 1).strip();
    if (last.startsWith("///")) {
      int first = i;
      while (first > 1 && lines.get(first - 2).strip().startsWith("///")) {
        --first;
      }
      final var text = new ArrayList<String>();
      for (int n = first; n <= i; ++n) {
        text.add(stripMarker(lines.get(n - 1).strip(), "///"));
      }
      return new DocComment(first, i, "markdown", String.join("\n", text));
    }
    if (last.endsWith("*/")) {
      // the opening line, or line 1 when the block has no opening line above it
      int first = i;
      while (first > 1 && !lines.get(first - 1).strip().startsWith("/*")) {
        --first;
      }
      if (!lines.get(first - 1).strip().startsWith("/**")) {
        return null; // a plain block comment, or a close with no open, is not documentation
      }
      final var text = new ArrayList<String>();
      for (int n = first; n <= i; ++n) {
        var line = lines.get(n - 1).strip();
        if (n == first) {
          line = line.substring(3);
        }
        if (n == i) {
          line = line.substring(0, line.length() - 2);
        }
        line = line.strip();
        if (line.startsWith("*")) {
          line = stripMarker(line, "*");
        }
        if (!line.isEmpty() || (n != first && n != i)) {
          text.add(line);
        }
      }
      return new DocComment(first, i, "javadoc", String.join("\n", text));
    }
    return null;
  }

  private static String stripMarker(final String line, final String marker) {
    final var rest = line.substring(marker.length());
    return rest.startsWith(" ") ? rest.substring(1) : rest;
  }
}
