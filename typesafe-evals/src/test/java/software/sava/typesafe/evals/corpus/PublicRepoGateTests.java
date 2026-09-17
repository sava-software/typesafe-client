package software.sava.typesafe.evals.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PublicRepoGateTests {

  private static final class ScriptedGh implements CommandRunner {

    final Map<String, String> answers;
    final List<List<String>> calls = new ArrayList<>();

    ScriptedGh(final Map<String, String> answers) {
      this.answers = answers;
    }

    @Override
    public String run(final List<String> command, final Path directory) {
      calls.add(command);
      assertEquals(List.of("gh", "repo", "view"), command.subList(0, 3));
      assertNull(directory);
      final var answer = answers.get(command.get(3));
      if (answer == null) {
        throw new CommandFailedException(command, 1, "GraphQL: Could not resolve to a Repository");
      }
      return answer + "\n";
    }
  }

  @Test
  void visibilityIsLookedUpOnceAndCached(@TempDir final Path dir) throws Exception {
    final var gh = new ScriptedGh(Map.of("sava-software/sava", "public", "glamsystems/glam", "private"));
    final var cache = dir.resolve("cache/visibility.tsv");
    final var gate = new PublicRepoGate(gh, cache);

    assertTrue(gate.isPublic("sava-software/sava"));
    assertTrue(gate.isPublic("SAVA-SOFTWARE/sava "));
    assertFalse(gate.isPublic("glamsystems/glam"));
    assertEquals(2, gh.calls.size(), "one gh call per distinct repository");
    assertEquals("sava-software/sava\tPUBLIC\nglamsystems/glam\tPRIVATE\n", Files.readString(cache));

    final var reloaded = new PublicRepoGate(new ScriptedGh(Map.of()), cache);
    assertTrue(reloaded.isPublic("sava-software/sava"));
    assertEquals("PRIVATE", reloaded.visibility("glamsystems/glam"));
  }

  @Test
  void unknownRepositoriesFailClosed(@TempDir final Path dir) {
    final var gate = new PublicRepoGate(new ScriptedGh(Map.of("o/blank", " ")), dir.resolve("v.tsv"));
    assertEquals("UNKNOWN", gate.visibility("o/missing"));
    assertFalse(gate.isPublic("o/missing"));
    assertEquals("UNKNOWN", gate.visibility("o/blank"));
    final var refused = assertThrows(IllegalStateException.class, () -> gate.requirePublic("o/missing"));
    assertTrue(refused.getMessage().contains("o/missing is UNKNOWN"));
    assertDoesNotThrow(() -> new PublicRepoGate(new ScriptedGh(Map.of("o/p", "PUBLIC")), null).requirePublic("o/p"));
  }

  @Test
  void ownerRepoIsValidated() {
    for (final var bad : new String[]{"sava", "/sava", "sava/", " ", "", "a/b/c", " / "}) {
      assertThrows(IllegalArgumentException.class, () -> PublicRepoGate.normalize(bad), "'" + bad + "'");
    }
    final var forNull = assertThrows(IllegalArgumentException.class, () -> PublicRepoGate.normalize(null));
    assertEquals("expected owner/repo, got null", forNull.getMessage());
    assertEquals("a/b", PublicRepoGate.normalize(" A/B "));
    assertEquals("owner-1/repo.name", PublicRepoGate.normalize("Owner-1/Repo.Name"));
  }

  @Test
  void cacheLinesWithoutAKeyAreDropped(@TempDir final Path dir) throws Exception {
    final var cache = dir.resolve("v.tsv");
    Files.writeString(cache, "garbage line\n\tPUBLIC\nsava-software/sava\tPUBLIC\n");
    final var gate = new PublicRepoGate(new ScriptedGh(Map.of("o/new", "public")), cache);
    assertTrue(gate.isPublic("sava-software/sava"));
    assertTrue(gate.isPublic("o/new"));
    // the rewrite after the lookup carries only keyed rows
    assertEquals("sava-software/sava\tPUBLIC\no/new\tPUBLIC\n", Files.readString(cache));
  }
}
