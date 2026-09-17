package software.sava.typesafe.evals.dedupe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The hand labels for Experiment B: a TSV with a header naming at least `pair_id` and
/// `label`, one row per labeled pair, label in {0, 1, 2}. Blank labels are unlabeled rows
/// and are skipped, so the sheet the harness wrote can be filled in place.
public record Labels(Map<String, Integer> byPairId) {

  public static Labels read(final Path file) {
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
    final int idColumn = header.indexOf("pair_id");
    final int labelColumn = header.indexOf("label");
    if (idColumn < 0 || labelColumn < 0) {
      throw new IllegalArgumentException(file + " needs pair_id and label columns; header is " + header);
    }
    final var out = new LinkedHashMap<String, Integer>();
    for (int i = 1; i < lines.size(); i++) {
      final var cells = lines.get(i).split("\t", -1);
      if (cells.length <= Math.max(idColumn, labelColumn)) {
        continue;
      }
      final var label = cells[labelColumn].strip();
      if (label.isEmpty()) {
        continue;
      }
      final int value;
      try {
        value = Integer.parseInt(label);
      } catch (final NumberFormatException e) {
        throw new IllegalArgumentException(file + " line " + (i + 1) + ": label '" + label + "' is not 0, 1, or 2");
      }
      if (value < 0 || value > 2) {
        throw new IllegalArgumentException(file + " line " + (i + 1) + ": label " + value + " is not 0, 1, or 2");
      }
      out.put(cells[idColumn], value);
    }
    return new Labels(Map.copyOf(out));
  }

  public Integer get(final String pairId) {
    return byPairId.get(pairId);
  }

  public int size() {
    return byPairId.size();
  }
}
