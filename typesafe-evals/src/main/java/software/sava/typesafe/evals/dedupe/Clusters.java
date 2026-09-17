package software.sava.typesafe.evals.dedupe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Union-find over finding ids: every merged pair joins its two findings, and a cluster is
/// a connected component. Findings that merged with nothing form singleton clusters.
public final class Clusters {

  private final LinkedHashMap<String, String> parent = new LinkedHashMap<>();
  private final LinkedHashMap<String, Integer> order = new LinkedHashMap<>();

  public void add(final String id) {
    if (parent.putIfAbsent(id, id) == null) {
      order.put(id, order.size());
    }
  }

  /// Joins the two components; the root stays the member that was added first. Joining a
  /// component with itself changes nothing.
  public void union(final String a, final String b) {
    add(a);
    add(b);
    final var rootA = find(a);
    final var rootB = find(b);
    if (order.get(rootA) < order.get(rootB)) {
      parent.put(rootB, rootA);
    } else {
      // equal roots write a self-parent, which is the no-op it already was
      parent.put(rootA, rootB);
    }
  }

  /// The root of `id`'s component. Recursive rather than looped so a broken exit fails
  /// fast instead of spinning.
  public String find(final String id) {
    final var up = parent.get(id);
    return up.equals(id) ? id : find(up);
  }

  /// Clusters in first-seen order of their first member, each member list in first-seen order.
  public List<List<String>> clusters() {
    final var byRoot = new LinkedHashMap<String, List<String>>();
    for (final var id : parent.keySet()) {
      byRoot.computeIfAbsent(find(id), _ -> new ArrayList<>()).add(id);
    }
    return List.copyOf(byRoot.values());
  }

  public Map<String, Integer> sizes() {
    final var out = new LinkedHashMap<String, Integer>();
    for (final var cluster : clusters()) {
      out.put(cluster.getFirst(), cluster.size());
    }
    return out;
  }
}
