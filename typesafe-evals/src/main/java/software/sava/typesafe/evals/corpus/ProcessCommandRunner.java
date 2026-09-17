package software.sava.typesafe.evals.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// [CommandRunner] backed by [ProcessBuilder]. Standard error goes to a temporary file
/// (read back only after exit), so a chatty command can never block on a full pipe and
/// the failure message never races a drain thread. The file is removed after every run.
public final class ProcessCommandRunner implements CommandRunner {

  public static final CommandRunner INSTANCE = new ProcessCommandRunner(Path.of(System.getProperty("java.io.tmpdir")));

  private final Path tempDirectory;

  // package-private for tests: a private temp directory proves the stderr file is removed
  ProcessCommandRunner(final Path tempDirectory) {
    this.tempDirectory = tempDirectory;
  }

  @Override
  public String run(final List<String> command, final Path directory) {
    final Path stderrFile;
    try {
      stderrFile = Files.createTempFile(tempDirectory, "cmd-", ".stderr");
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to create a stderr file under " + tempDirectory, e);
    }
    try {
      final var builder = new ProcessBuilder(command).redirectError(stderrFile.toFile());
      if (directory != null) {
        builder.directory(directory.toFile());
      }
      final var process = builder.start();
      final String stdout;
      try (final var in = process.getInputStream()) {
        stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new CommandFailedException(command, exitCode, Files.readString(stderrFile, StandardCharsets.UTF_8));
      }
      return stdout;
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to run `" + String.join(" ", command) + '`', e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted running `" + String.join(" ", command) + '`', e);
    } finally {
      try {
        Files.deleteIfExists(stderrFile);
      } catch (final IOException ignored) {
        // a leftover temp file is not worth masking the real outcome
      }
    }
  }
}
