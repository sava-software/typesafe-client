package software.sava.typesafe.evals.corpus;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GitRepoTests {

  private static final class ScriptedGit implements CommandRunner {

    final Map<String, String> answers;
    final List<List<String>> calls = new ArrayList<>();

    ScriptedGit(final Map<String, String> answers) {
      this.answers = answers;
    }

    @Override
    public String run(final List<String> command, final Path directory) {
      calls.add(command);
      assertEquals(List.of("git", "-C", "/repo"), command.subList(0, 3));
      final var answer = answers.get(String.join(" ", command.subList(3, command.size())));
      if (answer == null) {
        throw new CommandFailedException(command, 128, "fatal: bad object");
      }
      return answer;
    }
  }

  @Test
  void originFormsParseToOwnerRepo() {
    for (final var url : new String[]{
        "git@github.com:sava-software/sava.git\n",
        "https://github.com/sava-software/sava\n",
        "https://github.com/sava-software/sava.git",
        "ssh://git@github.com/sava-software/sava.git",
        "https://github.com/sava-software/sava/"
    }) {
      final var repo = new GitRepo(Path.of("/repo"), new ScriptedGit(Map.of("remote get-url origin", url)));
      assertEquals("sava-software/sava", repo.originOwnerRepo(), url);
    }
    final var other = new GitRepo(Path.of("/repo"), new ScriptedGit(Map.of("remote get-url origin", "git@gitlab.com:x/y.git")));
    final var refused = assertThrows(IllegalStateException.class, other::originOwnerRepo);
    assertEquals("cannot read owner/repo from origin 'git@gitlab.com:x/y.git' of /repo", refused.getMessage());
  }

  @Test
  void showHeadAndChangedFiles() {
    final var git = new ScriptedGit(Map.of(
        "rev-parse HEAD", "abc123\n",
        "show deadbeef:sava-core/README.md", "# title\n",
        "diff --name-only deadbeef..HEAD -- sava-core/src/main/java",
        "sava-core/src/main/java/A.java\n sava-core/src/main/java/B.java\nsava-core/src/main/resources/x.properties\n\n"
    ));
    final var repo = new GitRepo(Path.of("/repo"), git);
    assertEquals("abc123", repo.head());
    assertEquals("# title\n", repo.show("deadbeef", "sava-core/README.md"));
    assertEquals(List.of("sava-core/src/main/java/A.java", "sava-core/src/main/java/B.java"),
        repo.changedJavaFiles("deadbeef", "sava-core/src/main/java"));
    final var missing = assertThrows(CommandRunner.CommandFailedException.class, () -> repo.show("deadbeef", "nope"));
    assertEquals(128, missing.exitCode());
    assertTrue(missing.getMessage().contains("fatal: bad object"));
    assertEquals("fatal: bad object", missing.stderr());
  }

  @Test
  void theRealRunnerRunsGit() {
    final var out = ProcessCommandRunner.INSTANCE.run(List.of("git", "--version"), null);
    assertTrue(out.startsWith("git version"), out);
    final var failure = assertThrows(CommandRunner.CommandFailedException.class,
        () -> ProcessCommandRunner.INSTANCE.run(List.of("git", "rev-parse", "--verify", "definitely-not-a-ref"), Path.of(System.getProperty("java.io.tmpdir"))));
    assertNotEquals(0, failure.exitCode());
  }

  @Test
  void theRealRunnerHonoursTheDirectoryAndReportsStderr(@org.junit.jupiter.api.io.TempDir final Path dir) throws Exception {
    final var real = dir.toRealPath();
    final var temp = java.nio.file.Files.createDirectory(real.resolve("tmp"));
    final var runner = new ProcessCommandRunner(temp);
    assertEquals(real.toString(), runner.run(List.of("pwd"), real).strip());
    final var loud = assertThrows(CommandRunner.CommandFailedException.class,
        () -> runner.run(List.of("sh", "-c", "echo out; echo ERR-LINE >&2; exit 3"), real));
    assertEquals(3, loud.exitCode());
    assertEquals("ERR-LINE\n", loud.stderr());
    assertEquals("`sh -c echo out; echo ERR-LINE >&2; exit 3` exited 3: ERR-LINE", loud.getMessage());
    final var quiet = assertThrows(CommandRunner.CommandFailedException.class,
        () -> runner.run(List.of("sh", "-c", "exit 4"), real));
    assertEquals("`sh -c exit 4` exited 4", quiet.getMessage());
    assertEquals("", quiet.stderr());
    try (final var files = java.nio.file.Files.list(temp)) {
      assertEquals(List.of(), files.toList(), "every run removes its stderr file, on success and on failure");
    }
    final var missingTemp = new ProcessCommandRunner(real.resolve("does-not-exist"));
    final var noFile = assertThrows(java.io.UncheckedIOException.class, () -> missingTemp.run(List.of("true"), null));
    assertTrue(noFile.getMessage().startsWith("failed to create a stderr file under"));
  }

  @Test
  void anInterruptedWaitRestoresTheFlag() {
    Thread.currentThread().interrupt();
    try {
      // waitFor only notices the flag when it has to wait: the command closes stdout at once
      // (so the read returns) and then keeps running, so the wait is reached with the flag set
      final var failure = assertThrows(IllegalStateException.class,
          () -> ProcessCommandRunner.INSTANCE.run(List.of("sh", "-c", "exec >&-; sleep 2"), null));
      assertTrue(failure.getMessage().startsWith("interrupted running `sh -c exec >&-; sleep 2`"), failure.getMessage());
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is re-set for the caller");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aFailureMessageOmitsAnEmptyStderr() {
    final var blank = new CommandRunner.CommandFailedException(List.of("x", "y"), 2, " \n");
    assertEquals("`x y` exited 2", blank.getMessage());
    assertEquals(" \n", blank.stderr());
    final var full = new CommandRunner.CommandFailedException(List.of("x"), 5, " boom \n");
    assertEquals("`x` exited 5: boom", full.getMessage());
  }
}
