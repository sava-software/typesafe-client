package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the miner over a real temporary repository: three commits on one file, a test
/// file that must be ignored, and a rename that carries no before-and-after pair.
final class HistoryMinerTests {

  private static String git(final Path repo, final String... args) {
    final var command = new java.util.ArrayList<String>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@x"));
    command.addAll(List.of(args));
    return ProcessCommandRunner.INSTANCE.run(command, null);
  }

  private static void commit(final Path repo, final String message) {
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", message);
  }

  @Test
  void eventsFollowDocumentedMembersAcrossCommits(@TempDir final Path dir) throws Exception {
    final var repo = dir.resolve("repo");
    final var main = repo.resolve("mod/src/main/java/p");
    final var test = repo.resolve("mod/src/test/java/p");
    Files.createDirectories(main);
    Files.createDirectories(test);
    git(repo, "init", "-q", "-b", "main");
    Files.writeString(main.resolve("W.java"), """
        package p;
        class W {
          /// Uses the cache.
          int m(int k) {
            return cache.get(k);
          }
          int undocumented() {
            return 1;
          }
        }
        """);
    Files.writeString(test.resolve("WTests.java"), "class WTests {\n  /// t\n  void t() {\n  }\n}\n");
    commit(repo, "one");
    // body-only edit of m, plus an edit of the undocumented member (no event: never documented)
    Files.writeString(main.resolve("W.java"), """
        package p;
        class W {
          /// Uses the cache.
          int m(int k) {
            return registry.get(k);
          }
          int undocumented() {
            return 2;
          }
        }
        """);
    Files.writeString(test.resolve("WTests.java"), "class WTests {\n  /// t2\n  void t() {\n  }\n}\n");
    commit(repo, "two");
    // comment-only reconciliation of m, and the test file renamed (ignored either way)
    Files.writeString(main.resolve("W.java"), """
        package p;
        class W {
          /// Uses the registry.
          int m(int k) {
            return registry.get(k);
          }
          int undocumented() {
            return 2;
          }
        }
        """);
    Files.move(test.resolve("WTests.java"), test.resolve("WTest.java"));
    commit(repo, "three");

    final var miner = new HistoryMiner(repo);
    final var commits = miner.commits();
    assertEquals(3, commits.size(), "oldest first");
    assertEquals(git(repo, "rev-list", "--max-parents=0", "HEAD").strip(), commits.getFirst());
    assertEquals(List.of(), miner.modifiedFiles(commits.get(0)), "the root commit only adds files");
    assertEquals(List.of("mod/src/main/java/p/W.java"), miner.modifiedFiles(commits.get(1)), "test sources are excluded");
    assertEquals(List.of("mod/src/main/java/p/W.java"), miner.modifiedFiles(commits.get(2)), "a rename is not a modification");

    final var events = miner.mine();
    assertEquals(2, events.size(), events.toString());
    final var bodyOnly = events.get(0);
    assertEquals(commits.get(1), bodyOnly.commit());
    assertEquals(1, bodyOnly.ordinal());
    assertEquals("W.m(int)", bodyOnly.key().toString());
    assertTrue(bodyOnly.bodyChanged());
    assertFalse(bodyOnly.commentChanged());
    assertEquals("Uses the cache.", bodyOnly.oldComment());
    assertEquals("Uses the cache.", bodyOnly.newComment());
    assertTrue(bodyOnly.oldBody().contains("cache.get(k)"));
    assertTrue(bodyOnly.newBody().contains("registry.get(k)"));
    assertEquals("int m(int k)", bodyOnly.newSignature());
    final var commentOnly = events.get(1);
    assertEquals(commits.get(2), commentOnly.commit());
    assertEquals(2, commentOnly.ordinal());
    assertFalse(commentOnly.bodyChanged());
    assertTrue(commentOnly.commentChanged());
    assertEquals("Uses the registry.", commentOnly.newComment());
    assertEquals("method", commentOnly.kind());

    final var groups = HistoryMiner.byMember(events);
    assertEquals(1, groups.size());
    assertEquals(events, groups.get(bodyOnly.key()));

    final var paired = StaleEvents.pair(events);
    assertEquals(2, paired.rows().size());
    assertEquals("stale", paired.rows().getFirst().label());
    assertEquals("cache registry", paired.rows().getFirst().overlap());
    assertEquals("cache", paired.rows().getFirst().strictOverlap(), "the word the comment dropped names the identifier commit two dropped");

    // the include predicate is the only path filter
    final var everything = new HistoryMiner(new GitRepo(repo), path -> path.endsWith(".java"));
    assertEquals(List.of("mod/src/main/java/p/W.java", "mod/src/test/java/p/WTests.java"), everything.modifiedFiles(commits.get(1)));
    assertEquals(3, everything.mine().size(), "the test member's comment changed once; its rename carries no pair");
  }

  /// A commit that only adds, deletes, or leaves alone a member's comment.
  @Test
  void commentsAppearingAndDisappearingAreBothEvents(@TempDir final Path dir) throws Exception {
    final var repo = dir.resolve("repo");
    final var main = repo.resolve("mod/src/main/java/p");
    Files.createDirectories(main);
    git(repo, "init", "-q", "-b", "main");
    Files.writeString(main.resolve("B.java"), """
        package p;
        class B {
          /// Documented and unchanged.
          int stable() {
            return 1;
          }
          /// Loses its comment.
          int dropped() {
            return 2;
          }
          int adopts() {
            return 3;
          }
        }
        """);
    commit(repo, "one");
    Files.writeString(main.resolve("B.java"), """
        package p;
        class B {
          /// Documented and unchanged.
          int stable() {
            return 1;
          }
          int dropped() {
            return 2;
          }
          /// Now documented.
          int adopts() {
            return 3;
          }
          /// Brand new.
          int added() {
            return 4;
          }
        }
        """);
    commit(repo, "two");

    final var events = new HistoryMiner(repo).mine();
    assertEquals(2, events.size(),
        "the unchanged documented member and the member this commit added carry no event: " + events);
    final var lost = events.getFirst();
    assertEquals("B.dropped()", lost.key().toString());
    assertEquals(1, lost.ordinal());
    assertTrue(lost.commentChanged());
    assertFalse(lost.bodyChanged(), "only the comment was deleted");
    assertEquals("Loses its comment.", lost.oldComment());
    assertNull(lost.newComment(), "a deleted comment is null after the commit");
    final var gained = events.get(1);
    assertEquals("B.adopts()", gained.key().toString());
    assertTrue(gained.commentChanged());
    assertFalse(gained.bodyChanged());
    assertNull(gained.oldComment(), "the member was undocumented before the commit");
    assertEquals("Now documented.", gained.newComment());
  }

  /// The two git reads parse their output row by row; a scripted runner pins what each row
  /// has to look like to count.
  @Test
  void onlyWellFormedGitRowsCount() {
    final CommandRunner scripted = (command, directory) -> {
      if (command.contains("log")) {
        return "aaa\n\nbbb\n";
      }
      if (command.contains("show")) {
        return """
            M\tmod/src/main/java/p/A.java
            M\tmod/src/main/java/p/B.java\tmod/src/main/java/p/C.java

            A\tmod/src/main/java/p/D.java
            D\tmod/src/main/java/p/E.java
            """;
      }
      throw new IllegalStateException(command.toString());
    };
    final var miner = new HistoryMiner(new GitRepo(Path.of("."), scripted), HistoryMiner.MAIN_SOURCES);
    assertEquals(List.of("aaa", "bbb"), miner.commits(), "a blank line in the log is not a commit");
    assertEquals(List.of("mod/src/main/java/p/A.java"), miner.modifiedFiles("aaa"),
        "only a two-cell M row names a file modified in place");
  }

  @Test
  void mainSourcesPredicate() {
    assertTrue(HistoryMiner.MAIN_SOURCES.test("a/src/main/java/p/X.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/src/test/java/p/X.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/src/main/java/generated/X.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/build/X.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/src/main/java/module-info.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/src/main/java/p/package-info.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("a/src/main/java/p/X.kt"));
  }

  @Test
  void minerOptions() {
    final var options = DocMiner.options(new String[]{"--checkouts", "c", "--repos", "a,b", "--out", "o"});
    assertEquals(Map.of("--checkouts", "c", "--repos", "a,b", "--out", "o"), options);
    assertThrows(IllegalArgumentException.class, () -> DocMiner.options(new String[]{"--checkouts", "c"}));
    assertThrows(IllegalArgumentException.class, () -> DocMiner.options(new String[]{"checkouts", "c", "--repos", "a"}));
    assertThrows(IllegalArgumentException.class, () -> DocMiner.options(new String[]{"--repos", "a", "--checkouts"}), "a dangling option is dropped, leaving --checkouts missing");
    final var stray = assertThrows(IllegalArgumentException.class,
        () -> DocMiner.options(new String[]{"--checkouts", "c", "--repos", "a", "out", "o"}));
    assertEquals("expected an option at out", stray.getMessage(),
        "a bare word is rejected even when both required options are present");
  }
}
