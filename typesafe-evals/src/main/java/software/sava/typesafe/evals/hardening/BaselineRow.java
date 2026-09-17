package software.sava.typesafe.evals.hardening;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// One accepted-baseline row from a `config/pitest/<suite>-accepted.csv`:
/// `class,method,mutator,status # label [# label...] [# line N]`.
///
/// @param labels    family labels in order, without `#`; `untriaged` is kept here and
///                  filtered by callers
/// @param lineHint  the `# line N` coordinate, or null
public record BaselineRow(String suite,
                          String className,
                          String method,
                          String mutator,
                          String status,
                          List<String> labels,
                          Integer lineHint,
                          String raw) {

  private static final Pattern LINE = Pattern.compile("^line (\\d+)$");

  public String simpleClassName() {
    final var binary = binaryClassName();
    final int dollar = binary.lastIndexOf('$');
    return dollar < 0 ? binary : binary.substring(dollar + 1);
  }

  /// `Outer$Inner` without the package.
  public String binaryClassName() {
    final int dot = className.lastIndexOf('.');
    return dot < 0 ? className : className.substring(dot + 1);
  }

  public boolean untriaged() {
    return labels.contains("untriaged");
  }

  /// The first non-`untriaged` label, or null.
  public String label() {
    for (final var label : labels) {
      if (!label.equals("untriaged")) {
        return label;
      }
    }
    return null;
  }

  /// `suite` from `<suite>-accepted.csv`; null for another file name.
  public static String suiteOf(final Path file) {
    final var name = file.getFileName().toString();
    return name.endsWith("-accepted.csv") ? name.substring(0, name.length() - "-accepted.csv".length()) : null;
  }

  /// Rows of one CSV; header (`!...`) and comment (`#...`) lines are skipped.
  public static List<BaselineRow> read(final Path file) {
    final var suite = suiteOf(file);
    if (suite == null) {
      throw new IllegalArgumentException("not an accepted baseline: " + file);
    }
    final List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
    final var rows = new ArrayList<BaselineRow>();
    for (final var line : lines) {
      final var row = parse(suite, line);
      if (row != null) {
        rows.add(row);
      }
    }
    return rows;
  }

  /// One row, or null for blank, header, and comment lines.
  static BaselineRow parse(final String suite, final String line) {
    final var stripped = line.strip();
    if (stripped.isEmpty() || stripped.startsWith("!") || stripped.startsWith("#")) {
      return null;
    }
    final var cells = stripped.split(",", 4);
    if (cells.length < 4) {
      throw new IllegalArgumentException("malformed baseline row: " + line);
    }
    final var tail = cells[3];
    final int hash = tail.indexOf('#');
    final var status = (hash < 0 ? tail : tail.substring(0, hash)).strip();
    final var labels = new ArrayList<String>();
    Integer lineHint = null;
    if (hash >= 0) {
      for (final var part : tail.substring(hash + 1).split("#")) {
        final var text = part.strip();
        if (text.isEmpty()) {
          continue;
        }
        final var matcher = LINE.matcher(text);
        if (matcher.matches()) {
          lineHint = Integer.valueOf(matcher.group(1));
        } else {
          labels.add(text);
        }
      }
    }
    return new BaselineRow(suite, cells[0].strip(), cells[1].strip(), cells[2].strip(), status, List.copyOf(labels), lineHint, stripped);
  }
}
