package software.sava.typesafe.evals.text;

import java.util.regex.Pattern;

/// Rewrites machine-local absolute paths before text leaves the machine: a checkout path
/// becomes repository-relative, a Claude Code scratchpad or project path becomes a
/// placeholder, and any other home-directory path loses the user segment.
public final class PathScrubber {

  private static final Pattern SRC_CHECKOUT = Pattern.compile("/Users/[^/\\s]+/src/([^/\\s]+)/");
  private static final Pattern SCRATCHPAD = Pattern.compile("/private/tmp/claude-501/[^\\s'\"`)\\]]*");
  private static final Pattern CLAUDE_PROJECTS = Pattern.compile("/Users/[^/\\s]+/\\.claude/[^\\s'\"`)\\]]*");
  private static final Pattern HOME = Pattern.compile("/Users/[^/\\s]+/");

  public static String scrub(final String text) {
    if (text == null) {
      return null;
    }
    var out = SRC_CHECKOUT.matcher(text).replaceAll("$1/");
    out = SCRATCHPAD.matcher(out).replaceAll("<scratchpad>");
    out = CLAUDE_PROJECTS.matcher(out).replaceAll("<claude-projects>");
    return HOME.matcher(out).replaceAll("~/");
  }

  private PathScrubber() {
  }
}
