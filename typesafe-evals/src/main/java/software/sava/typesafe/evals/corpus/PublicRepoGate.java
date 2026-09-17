package software.sava.typesafe.evals.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The data policy in code: only content from a PUBLIC GitHub repository may become request
/// state. Visibility comes from `gh repo view <owner>/<repo> --json visibility` and is cached
/// in a two-column TSV so a corpus build asks once per repository. Anything the lookup
/// cannot classify is treated as private: the gate fails closed.
public final class PublicRepoGate {

  private final CommandRunner runner;
  private final Path cacheFile;
  private final Map<String, String> visibility = new LinkedHashMap<>();

  public PublicRepoGate(final CommandRunner runner, final Path cacheFile) {
    this.runner = runner;
    this.cacheFile = cacheFile;
    load();
  }

  /// `PUBLIC` or `PRIVATE` (or whatever `gh` reports, upper-cased); never null.
  public String visibility(final String ownerRepo) {
    final var key = normalize(ownerRepo);
    var known = visibility.get(key);
    if (known == null) {
      known = lookup(key);
      visibility.put(key, known);
      save();
    }
    return known;
  }

  public boolean isPublic(final String ownerRepo) {
    return "PUBLIC".equals(visibility(ownerRepo));
  }

  /// @throws IllegalStateException when the repository is not public
  public void requirePublic(final String ownerRepo) {
    final var seen = visibility(ownerRepo);
    if (!"PUBLIC".equals(seen)) {
      throw new IllegalStateException(normalize(ownerRepo) + " is " + seen + "; only public repositories may be sent to the API");
    }
  }

  /// `owner/repo`, stripped and lower-cased; both segments must be non-empty.
  static String normalize(final String ownerRepo) {
    if (ownerRepo == null) {
      throw new IllegalArgumentException("expected owner/repo, got null");
    }
    final var parts = ownerRepo.strip().split("/", -1);
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw new IllegalArgumentException("expected owner/repo, got '" + ownerRepo + "'");
    }
    return ownerRepo.strip().toLowerCase(Locale.ROOT);
  }

  private String lookup(final String ownerRepo) {
    final String out;
    try {
      out = runner.run(List.of("gh", "repo", "view", ownerRepo, "--json", "visibility", "--jq", ".visibility"), null);
    } catch (final RuntimeException e) {
      // unknown, unreachable, or not permitted: none of those is public
      return "UNKNOWN";
    }
    final var reported = out.strip().toUpperCase(Locale.ROOT);
    return reported.isEmpty() ? "UNKNOWN" : reported;
  }

  private void load() {
    if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
      return;
    }
    try {
      for (final var line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
        final int tab = line.indexOf('\t');
        if (tab > 0) {
          visibility.put(line.substring(0, tab), line.substring(tab + 1).strip());
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + cacheFile, e);
    }
  }

  private void save() {
    if (cacheFile == null) {
      return;
    }
    try {
      final var out = new StringBuilder();
      for (final var entry : visibility.entrySet()) {
        out.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
      }
      Files.createDirectories(cacheFile.toAbsolutePath().getParent());
      Files.writeString(cacheFile, out.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to write " + cacheFile, e);
    }
  }
}
