package software.sava.typesafe.evals.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// A tab-separated table for labeling sheets and reports: one header, one row per record,
/// cells with tabs and line breaks flattened to single spaces so a spreadsheet and `awk`
/// read the same rows.
public final class Tsv {

  private final List<String> header;
  private final List<List<String>> rows = new ArrayList<>();

  public Tsv(final List<String> header) {
    if (header.isEmpty()) {
      throw new IllegalArgumentException("a TSV needs at least one column");
    }
    this.header = List.copyOf(header);
  }

  public Tsv(final String... header) {
    this(List.of(header));
  }

  public List<String> header() {
    return header;
  }

  public int size() {
    return rows.size();
  }

  public Tsv row(final List<?> cells) {
    if (cells.size() != header.size()) {
      throw new IllegalArgumentException("row has " + cells.size() + " cells; header has " + header.size());
    }
    rows.add(cells.stream().map(Tsv::cell).toList());
    return this;
  }

  /// Varargs form; null cells are allowed (`List.of` would reject them).
  public Tsv row(final Object... cells) {
    return row(java.util.Arrays.asList(cells));
  }

  /// A cell: null is empty; tabs, carriage returns, and line feeds become single spaces.
  static String cell(final Object value) {
    if (value == null) {
      return "";
    }
    final var text = value.toString();
    final var out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      out.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
    }
    return out.toString();
  }

  public String render() {
    final var out = new StringBuilder();
    out.append(String.join("\t", header)).append('\n');
    for (final var row : rows) {
      out.append(String.join("\t", row)).append('\n');
    }
    return out.toString();
  }

  public void write(final Path file) {
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, render(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + file, e);
    }
  }
}
