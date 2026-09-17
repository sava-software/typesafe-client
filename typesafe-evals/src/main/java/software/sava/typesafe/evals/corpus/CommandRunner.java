package software.sava.typesafe.evals.corpus;

import java.nio.file.Path;
import java.util.List;

/// Runs one command and returns its standard output. The seam every corpus builder goes
/// through so tests can script `git` and `gh` without a process.
@FunctionalInterface
public interface CommandRunner {

  /// @throws CommandFailedException when the command exits non-zero
  String run(final List<String> command, final Path directory);

  final class CommandFailedException extends RuntimeException {

    private final int exitCode;
    private final String stderr;

    public CommandFailedException(final List<String> command, final int exitCode, final String stderr) {
      super("`" + String.join(" ", command) + "` exited " + exitCode + (stderr.isBlank() ? "" : ": " + stderr.strip()));
      this.exitCode = exitCode;
      this.stderr = stderr;
    }

    public int exitCode() {
      return exitCode;
    }

    public String stderr() {
      return stderr;
    }
  }
}
