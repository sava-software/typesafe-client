package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.evals.text.Jaccard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/// Two findings from the same workflow that share a file basename: the blocking key. The
/// Jaccard similarity and the line facts are computed once here for the baselines and the
/// ablation arm.
public record FindingPair(String id, CorpusFinding a, CorpusFinding b, double jaccard) {

  public boolean exactLine() {
    // Integer.equals(null) is false, so only the left side needs a guard
    return a.line() != null && a.line().equals(b.line());
  }

  /// `|line(a) - line(b)|`, or null when either side has no line.
  public Integer lineDelta() {
    return a.line() == null || b.line() == null ? null : Math.abs(a.line() - b.line());
  }

  /// All same-workflow, same-basename pairs in a stable order (by first appearance of the
  /// block, then the two finding ids). Findings without a basename, or whose location was
  /// scrubbed to a placeholder such as `<scratchpad>`, are never paired: a placeholder is not
  /// a file.
  public static List<FindingPair> block(final List<CorpusFinding> findings) {
    final var byKey = new LinkedHashMap<String, List<CorpusFinding>>();
    for (final var finding : findings) {
      if (finding.basename() == null || finding.basename().startsWith("<")) {
        continue;
      }
      byKey.computeIfAbsent(finding.workflow() + "::" + finding.basename(), _ -> new ArrayList<>()).add(finding);
    }
    final var pairs = new ArrayList<FindingPair>();
    for (final var group : byKey.values()) {
      for (int i = 0; i < group.size(); i++) {
        final var a = group.get(i);
        for (final var b : group.subList(i + 1, group.size())) {
          pairs.add(new FindingPair(a.id() + " | " + b.id(), a, b, Jaccard.similarity(a.tokens(), b.tokens())));
        }
      }
    }
    return pairs;
  }
}
