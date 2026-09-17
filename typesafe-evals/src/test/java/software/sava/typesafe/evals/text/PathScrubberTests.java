package software.sava.typesafe.evals.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PathScrubberTests {

  @Test
  void checkoutPathsBecomeRepositoryRelative() {
    assertEquals("sava/sava-core/src/main/java/A.java:12 and ravina/README.md",
        PathScrubber.scrub("/Users/jim/src/sava/sava-core/src/main/java/A.java:12 and /Users/jim/src/ravina/README.md"));
  }

  @Test
  void scratchpadAndClaudePathsBecomePlaceholders() {
    assertEquals("see <scratchpad> (and <claude-projects>)",
        PathScrubber.scrub("see /private/tmp/claude-501/-Users-jim-src-sava/abc/scratchpad/x.txt (and /Users/jim/.claude/projects/p/journal.jsonl)"));
  }

  @Test
  void otherHomePathsLoseTheUser() {
    assertEquals("~/docs/solana/agave/x.rs", PathScrubber.scrub("/Users/someone/docs/solana/agave/x.rs"));
    assertEquals("<tmp>/sava-triage.log:202", PathScrubber.scrub("/private/tmp/sava-triage.log:202"));
    assertEquals("nothing to do", PathScrubber.scrub("nothing to do"));
    assertNull(PathScrubber.scrub(null));
  }
}
