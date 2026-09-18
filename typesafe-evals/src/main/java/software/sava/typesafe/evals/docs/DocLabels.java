package software.sava.typesafe.evals.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Labels for Experiment C1's sheets: a TSV with `row_id` and `label` columns, labels in
/// {consistent, contradicted, not_checkable}; blank labels are skipped.
public record DocLabels(Map<String, String> byKey) {

  public static final Set<String> LABELS = Set.of(DocQuestions.CONSISTENT, DocQuestions.CONTRADICTED, DocQuestions.NOT_CHECKABLE);

  public static DocLabels read(final Path file) {
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
    final int keyIndex = header.indexOf("row_id");
    final int labelIndex = header.indexOf("label");
    if (keyIndex < 0 || labelIndex < 0) {
      throw new IllegalArgumentException(file + " needs row_id and label columns; header is " + header);
    }
    final var out = new LinkedHashMap<String, String>();
    for (int i = 1; i < lines.size(); i++) {
      final var cells = lines.get(i).split("\t", -1);
      if (cells.length <= Math.max(keyIndex, labelIndex)) {
        continue;
      }
      final var label = cells[labelIndex].strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
      if (label.isEmpty()) {
        continue;
      }
      if (!LABELS.contains(label)) {
        throw new IllegalArgumentException(file + " line " + (i + 1) + ": label '" + label + "' is not consistent, contradicted, or not_checkable");
      }
      out.put(cells[keyIndex].strip(), label);
    }
    return new DocLabels(Map.copyOf(out));
  }

  public int size() {
    return byKey.size();
  }
}
