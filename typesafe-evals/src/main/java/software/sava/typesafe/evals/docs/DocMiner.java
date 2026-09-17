package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.corpus.PublicRepoGate;
import software.sava.typesafe.evals.report.Tsv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Sizes and writes the Experiment C1 label source: stale/fresh doc-comment pairs mined
/// from the history of public checkouts.
///
/// Arguments: `--checkouts <dir>` `--repos <name,name,...>` `--out <dir>`
/// `--visibility-cache <file>` (optional)
public final class DocMiner {

  public record RepoSummary(String repo, String head, int commits, int events, int bodyOnly, int commentOnly, int coEdited,
                            int staleLater, int staleCoEdit, int staleStrict, int excludedRetouch, int excludedNoOverlap) {
  }

  private DocMiner() {
  }

  public static void main(final String[] args) {
    final var options = options(args);
    final var checkouts = Path.of(options.get("--checkouts"));
    final var out = Path.of(options.getOrDefault("--out", "build/experiments/docs"));
    final var repos = List.of(options.get("--repos").split(","));
    final var gate = new PublicRepoGate(software.sava.typesafe.evals.corpus.ProcessCommandRunner.INSTANCE,
        Path.of(options.getOrDefault("--visibility-cache", out.resolve("visibility.tsv").toString())));
    final var rows = new Tsv("repo", "label", "source", "reconciling_commit", "stale_since", "path", "member", "kind", "signature",
        "comment_jaccard", "overlap", "strict_overlap", "comment", "body");
    final var excluded = new Tsv("repo", "commit", "member", "reason");
    final var summaries = new ArrayList<RepoSummary>();
    for (final var name : repos) {
      final var root = checkouts.resolve(name);
      final var repo = new GitRepo(root);
      final var ownerRepo = repo.originOwnerRepo();
      if (!gate.isPublic(ownerRepo)) {
        System.err.println("skipping " + name + ": " + ownerRepo + " is not public");
        continue;
      }
      final var miner = new HistoryMiner(root);
      final var events = miner.mine();
      final var result = StaleEvents.pair(events);
      int retouch = 0;
      for (final var ex : result.excluded()) {
        excluded.row(name, ex.commit(), ex.key().toString(), ex.reason());
        if (ex.reason().contains("retouch")) {
          ++retouch;
        }
      }
      for (final var row : result.rows()) {
        rows.row(name, row.label(), row.source(), row.reconcilingCommit(), row.staleSince(), row.path(), row.key().toString(), row.kind(),
            row.signature(), String.format(java.util.Locale.ROOT, "%.3f", row.commentJaccard()), row.overlap(),
            row.strictOverlap(), row.comment(), row.body());
      }
      summaries.add(new RepoSummary(name, repo.head().substring(0, 7), miner.commits().size(), events.size(),
          result.bodyOnlyEvents(), result.commentOnlyEvents(), result.coEditedEvents(),
          (int) result.count("stale", "later-reconciliation"), (int) result.count("stale", "co-edit"),
          (int) result.strictCount("stale"), retouch,
          result.excluded().size() - retouch));
      System.out.println(summaries.getLast());
    }
    try {
      Files.createDirectories(out);
      rows.write(out.resolve("stale-pairs.tsv"));
      excluded.write(out.resolve("excluded.tsv"));
      final var summary = new Tsv("repo", "head", "commits", "events", "body_only", "comment_only", "co_edited",
          "stale_later", "stale_co_edit", "stale_strict", "excluded_retouch", "excluded_no_overlap");
      for (final var s : summaries) {
        summary.row(s.repo(), s.head(), s.commits(), s.events(), s.bodyOnly(), s.commentOnly(), s.coEdited(), s.staleLater(),
            s.staleCoEdit(), s.staleStrict(), s.excludedRetouch(), s.excludedNoOverlap());
      }
      summary.write(out.resolve("summary.tsv"));
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  static Map<String, String> options(final String[] args) {
    final var options = new java.util.HashMap<String, String>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected an option at " + args[i]);
      }
      options.put(args[i], args[i + 1]);
    }
    if (!options.containsKey("--checkouts") || !options.containsKey("--repos")) {
      throw new IllegalArgumentException("--checkouts and --repos are required");
    }
    return options;
  }
}
