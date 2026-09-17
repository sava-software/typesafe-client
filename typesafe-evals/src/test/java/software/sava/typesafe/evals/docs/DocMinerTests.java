package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the miner over two temporary checkouts that each have an `origin` remote, one
/// answered PUBLIC and the other PRIVATE by a scripted `gh`, and reads back the three TSVs.
/// The fixture history gives one labeled pair, one retouch, and one comment rewrite that
/// names nothing in the code.
final class DocMinerTests {

  private static final String FIRST = """
      package p;
      class A {
        /// Reads the cache.
        int m(int k) {
          return cache.get(k);
        }
        /// Returns the cached total of all the items.
        int total() {
          return items;
        }
        /// One two three.
        int plain() {
          return 1;
        }
      }
      """;

  /// Every body edited, every comment left alone.
  private static final String BODIES_EDITED = """
      package p;
      class A {
        /// Reads the cache.
        int m(int k) {
          return registry.get(k);
        }
        /// Returns the cached total of all the items.
        int total() {
          return values;
        }
        /// One two three.
        int plain() {
          return 2;
        }
      }
      """;

  /// Every comment edited, every body left alone: a reconciliation, a typo fix, and a
  /// rewrite that names nothing in the code.
  private static final String COMMENTS_EDITED = """
      package p;
      class A {
        /// Reads the registry.
        int m(int k) {
          return registry.get(k);
        }
        /// Returns the cached total of all the items!
        int total() {
          return values;
        }
        /// Four five six.
        int plain() {
          return 2;
        }
      }
      """;

  private static final String PATH = "mod/src/main/java/p/A.java";

  private static String git(final Path repo, final String... args) {
    final var command = new ArrayList<String>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@x"));
    command.addAll(List.of(args));
    return ProcessCommandRunner.INSTANCE.run(command, null);
  }

  private static Path checkout(final Path checkouts, final String name) throws Exception {
    final var repo = checkouts.resolve(name);
    Files.createDirectories(repo.resolve("mod/src/main/java/p"));
    git(repo, "init", "-q", "-b", "main");
    git(repo, "remote", "add", "origin", "git@github.com:test-org/" + name + ".git");
    int revision = 0;
    for (final var content : List.of(FIRST, BODIES_EDITED, COMMENTS_EDITED)) {
      Files.writeString(repo.resolve(PATH), content);
      git(repo, "add", "-A");
      git(repo, "commit", "-q", "-m", "revision " + ++revision);
    }
    return repo;
  }

  private static List<List<String>> table(final Path file) throws Exception {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(line -> List.of(line.split("\t", -1))).toList();
  }

  @Test
  void minesPublicCheckoutsAndWritesTheThreeTables(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("checkouts");
    final var alpha = checkout(checkouts, "alpha");
    checkout(checkouts, "beta");
    final var out = dir.resolve("out");
    final var asked = new ArrayList<List<String>>();
    final CommandRunner gh = (command, directory) -> {
      asked.add(List.copyOf(command));
      return command.contains("test-org/alpha") ? "PUBLIC\n" : "PRIVATE\n";
    };

    final var stdout = new ByteArrayOutputStream();
    final var stderr = new ByteArrayOutputStream();
    final var priorOut = System.out;
    final var priorErr = System.err;
    final List<DocMiner.RepoSummary> summaries;
    try {
      System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      summaries = DocMiner.run(Map.of("--checkouts", checkouts.toString(), "--repos", "alpha,beta", "--out", out.toString()), gh);
    } finally {
      System.setOut(priorOut);
      System.setErr(priorErr);
    }

    assertEquals(List.of(
            List.of("gh", "repo", "view", "test-org/alpha", "--json", "visibility", "--jq", ".visibility"),
            List.of("gh", "repo", "view", "test-org/beta", "--json", "visibility", "--jq", ".visibility")), asked,
        "visibility is asked once per checkout, through the gate");
    assertEquals("skipping beta: test-org/beta is not public", stderr.toString(StandardCharsets.UTF_8).strip(),
        "a checkout that is not public is named and skipped");

    final var shas = git(alpha, "log", "--format=%H", "--reverse").lines().toList();
    assertEquals(3, shas.size());
    final var head = shas.get(2);

    assertEquals(1, summaries.size(), "only the public checkout was mined");
    final var summary = summaries.getFirst();
    assertEquals("alpha", summary.repo());
    assertEquals(head.substring(0, 7), summary.head());
    assertEquals(3, summary.commits());
    assertEquals(6, summary.events(), "three body-only edits and three comment-only edits");
    assertEquals(3, summary.bodyOnly());
    assertEquals(3, summary.commentOnly());
    assertEquals(0, summary.coEdited());
    assertEquals(1, summary.staleLater());
    assertEquals(0, summary.staleCoEdit());
    assertEquals(1, summary.staleStrict());
    assertEquals(1, summary.excludedRetouch());
    assertEquals(1, summary.excludedNoOverlap(), "the other exclusion is the rewrite that names no code");
    assertEquals(summary.toString(), stdout.toString(StandardCharsets.UTF_8).strip(), "each mined repository is printed");

    final var body = "  int m(int k) {\n    return registry.get(k);\n  }".replace('\n', ' ');
    final var pairs = table(out.resolve("stale-pairs.tsv"));
    assertEquals(List.of("repo", "label", "source", "reconciling_commit", "stale_since", "path", "member", "kind", "signature",
        "comment_jaccard", "overlap", "strict_overlap", "comment", "body"), pairs.getFirst());
    assertEquals(3, pairs.size(), "one stale row and one fresh row");
    assertEquals(List.of("alpha", "stale", "later-reconciliation", head, shas.get(1), PATH, "A.m(int)", "method", "int m(int k)",
        "0.500", "cache registry", "cache", "Reads the cache.", body), pairs.get(1));
    assertEquals(List.of("alpha", "fresh", "later-reconciliation", head, "", PATH, "A.m(int)", "method", "int m(int k)",
        "0.500", "cache registry", "cache", "Reads the registry.", body), pairs.get(2));

    final var excluded = table(out.resolve("excluded.tsv"));
    assertEquals(List.of("repo", "commit", "member", "reason"), excluded.getFirst());
    assertEquals(3, excluded.size());
    assertEquals(List.of("alpha", head, "A.total()", "later-reconciliation: retouch: comment jaccard 1.00"), excluded.get(1));
    assertEquals(List.of("alpha", head, "A.plain()", "later-reconciliation: no changed token names code"), excluded.get(2));

    final var written = table(out.resolve("summary.tsv"));
    assertEquals(List.of("repo", "head", "commits", "events", "body_only", "comment_only", "co_edited",
        "stale_later", "stale_co_edit", "stale_strict", "excluded_retouch", "excluded_no_overlap"), written.getFirst());
    assertEquals(2, written.size());
    assertEquals(List.of("alpha", head.substring(0, 7), "3", "6", "3", "3", "0", "1", "0", "1", "1", "1"), written.get(1));

    assertEquals(List.of("test-org/alpha\tPUBLIC", "test-org/beta\tPRIVATE"),
        Files.readAllLines(out.resolve("visibility.tsv"), StandardCharsets.UTF_8),
        "both answers are cached beside the output, the default cache location");
  }
}
