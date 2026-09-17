package software.sava.typesafe.evals.rot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// sava-build's golden-fleet `MANIFEST.txt`: one snapshot per line, `repo<TAB>module-path<TAB>commit`,
/// with `#` comments. The snapshot directory replaces `/` in the module path with `__`.
public record Manifest(List<Entry> entries) {

  public record Entry(String repo, String modulePath, String commit) {

    /// `<repo>/<module-path with / as __>` under the golden-fleet root.
    public String snapshotDir() {
      return repo + '/' + modulePath.replace("/", "__");
    }

    /// The module's source root inside its checkout.
    public String sourceRoot() {
      return modulePath + "/src/main/java";
    }

    public String testRoot() {
      return modulePath + "/src/test/java";
    }

    public String id() {
      return repo + '/' + modulePath;
    }
  }

  public static Manifest read(final Path file) {
    final List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
    final var entries = new ArrayList<Entry>();
    for (int i = 0; i < lines.size(); i++) {
      final var line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      final var cells = line.split("\t");
      if (cells.length != 3) {
        throw new IllegalArgumentException(file + " line " + (i + 1) + ": expected repo<TAB>module<TAB>commit, got '" + line + "'");
      }
      entries.add(new Entry(cells[0].strip(), cells[1].strip(), cells[2].strip()));
    }
    return new Manifest(List.copyOf(entries));
  }
}
