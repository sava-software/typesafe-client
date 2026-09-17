package software.sava.typesafe.evals.rot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Hand labels for Experiment A: a TSV with `row_id` and `label` columns, labels in
/// {absent, present, cannot}; blank labels are skipped so the sheet can be filled in place.
/// The same reader loads the provisional gold hints, keyed by `<module>#<Class>.<member>`.
public record RotLabels(Map<String, String> byKey) {

  private static final Set<String> LABELS = Set.of("absent", "present", "cannot");

  public static RotLabels read(final Path file, final String keyColumn) {
    final List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
    if (lines.isEmpty()) {
      throw new IllegalArgumentException(file + " is empty");
    }
    final var header = List.of(lines.getFirst().split("\t", -1));
    final int keyIndex = header.indexOf(keyColumn);
    final int labelIndex = header.indexOf("label");
    if (keyIndex < 0 || labelIndex < 0) {
      throw new IllegalArgumentException(file + " needs " + keyColumn + " and label columns; header is " + header);
    }
    final var out = new LinkedHashMap<String, String>();
    for (int i = 1; i < lines.size(); i++) {
      final var cells = lines.get(i).split("\t", -1);
      if (cells.length <= Math.max(keyIndex, labelIndex)) {
        continue;
      }
      final var label = cells[labelIndex].strip().toLowerCase(java.util.Locale.ROOT);
      if (label.isEmpty()) {
        continue;
      }
      if (!LABELS.contains(label)) {
        throw new IllegalArgumentException(file + " line " + (i + 1) + ": label '" + label + "' is not absent, present, or cannot");
      }
      out.put(cells[keyIndex].strip(), label);
    }
    return new RotLabels(Map.copyOf(out));
  }

  public String get(final String key) {
    return byKey.get(key);
  }

  public int size() {
    return byKey.size();
  }
}
