package software.sava.typesafe.evals.drift;

import java.util.ArrayList;
import java.util.List;

/// A small line diff (longest common subsequence) rendered in unified style with `-`, `+`,
/// and ` ` prefixes and a little context, for member bodies of a few hundred lines.
public final class LineDiff {

  static final int CONTEXT = 2;
  static final int MAX_LINES = 400;

  /// @param text        the rendered diff
  /// @param changed     `-` and `+` lines
  /// @param linesTotal  lines of the full rendering before any cap
  /// @param linesShown  lines rendered
  public record Result(String text, int changed, int linesTotal, int linesShown) {
  }

  private LineDiff() {
  }

  /// Unified-style diff of `before` and `after`, capped at `cap` rendered lines with the cap
  /// stated. Inputs longer than MAX_LINES lines are diffed on their first MAX_LINES lines.
  public static Result of(final String before, final String after, final int cap) {
    final var a = limit(before.split("\n", -1));
    final var b = limit(after.split("\n", -1));
    final var ops = ops(a, b);
    final var rendered = render(ops);
    int changed = 0;
    for (final var line : rendered) {
      if (line.startsWith("-") || line.startsWith("+")) {
        changed++;
      }
    }
    final int shown = Math.min(rendered.size(), cap);
    final var text = new StringBuilder();
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        text.append('\n');
      }
      text.append(rendered.get(i));
    }
    if (shown < rendered.size()) {
      text.append("\n// … ").append(rendered.size() - shown).append(" more diff lines not shown");
    }
    return new Result(text.toString(), changed, rendered.size(), shown);
  }

  private static String[] limit(final String[] lines) {
    return lines.length <= MAX_LINES ? lines : java.util.Arrays.copyOf(lines, MAX_LINES);
  }

  /// Edit script as prefixed lines: ' ' equal, '-' removed, '+' added.
  static List<String> ops(final String[] a, final String[] b) {
    final int n = a.length;
    final int m = b.length;
    final int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    final var ops = new ArrayList<String>();
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (a[i].equals(b[j])) {
        ops.add(" " + a[i]);
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        ops.add("-" + a[i]);
        i++;
      } else {
        ops.add("+" + b[j]);
        j++;
      }
    }
    while (i < n) {
      ops.add("-" + a[i++]);
    }
    while (j < m) {
      ops.add("+" + b[j++]);
    }
    return ops;
  }

  /// Keeps changed lines and CONTEXT equal lines around them; elided runs become `@@ n lines @@`.
  static List<String> render(final List<String> ops) {
    final var keep = new boolean[ops.size()];
    for (int i = 0; i < ops.size(); i++) {
      if (!ops.get(i).startsWith(" ")) {
        for (int k = Math.max(0, i - CONTEXT); k <= Math.min(ops.size() - 1, i + CONTEXT); k++) {
          keep[k] = true;
        }
      }
    }
    final var out = new ArrayList<String>();
    int elided = 0;
    for (int i = 0; i < ops.size(); i++) {
      if (keep[i]) {
        if (elided > 0) {
          out.add("@@ " + elided + " unchanged lines @@");
          elided = 0;
        }
        out.add(ops.get(i));
      } else {
        elided++;
      }
    }
    if (elided > 0) {
      out.add("@@ " + elided + " unchanged lines @@");
    }
    return out;
  }
}
