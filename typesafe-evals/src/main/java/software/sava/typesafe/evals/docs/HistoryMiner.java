package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.corpus.GitRepo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/// Walks a repository's history, oldest first, and records every commit in which a
/// documented member's comment or body changed while the member kept its identity.
public final class HistoryMiner {

  /// One member at one commit, compared with the commit's parent.
  ///
  /// @param oldComment the comment before the commit, null when there was none
  /// @param newComment the comment after, null when removed or still absent
  public record Event(String commit,
                      int ordinal,
                      String path,
                      FileMembers.Key key,
                      String kind,
                      boolean commentChanged,
                      boolean bodyChanged,
                      String oldComment,
                      String newComment,
                      String oldSignature,
                      String newSignature,
                      String oldBody,
                      String newBody) {
  }

  /// Main sources only: no tests, no generated code (`/generated/` or `/gen/` path segments;
  /// callers also check for a `@generated` header), no build output.
  public static final Predicate<String> MAIN_SOURCES = path ->
      path.endsWith(".java")
          && !path.contains("/src/test/")
          && !path.contains("/generated/")
          && !path.contains("/gen/")
          && !path.contains("/build/")
          && !path.endsWith("module-info.java")
          && !path.endsWith("package-info.java");

  private final GitRepo repo;
  private final Predicate<String> include;

  public HistoryMiner(final GitRepo repo, final Predicate<String> include) {
    this.repo = repo;
    this.include = include;
  }

  public HistoryMiner(final Path root) {
    this(new GitRepo(root), MAIN_SOURCES);
  }

  /// Non-merge commits touching Java files, oldest first.
  public List<String> commits() {
    return repo.run("log", "--no-merges", "--format=%H", "--reverse", "--", "*.java").lines()
        .map(String::strip).filter(line -> !line.isEmpty()).toList();
  }

  /// Java files modified in place by `commit` (`M` rows of `--name-status`; additions,
  /// deletions, and renames carry no before-and-after pair for the same path).
  public List<String> modifiedFiles(final String commit) {
    final var files = new ArrayList<String>();
    for (final var line : repo.run("show", "--format=", "--name-status", "--no-renames", commit).lines().toList()) {
      final var cells = line.split("\t");
      if (cells.length == 2 && cells[0].equals("M") && include.test(cells[1])) {
        files.add(cells[1]);
      }
    }
    return files;
  }

  /// The file's members as they stood before `commit`.
  public Map<FileMembers.Key, FileMembers.Snapshot> membersBefore(final String commit, final String path) {
    return FileMembers.of(Path.of(path), repo.show(commit + "^", path));
  }

  /// Events for every documented member whose comment or body differs between the parent
  /// and `commit`, for one file. A member is "documented" when it has a doc comment on
  /// either side.
  public List<Event> events(final String commit, final int ordinal, final String path) {
    final var before = FileMembers.of(Path.of(path), repo.show(commit + "^", path));
    final var after = FileMembers.of(Path.of(path), repo.show(commit, path));
    final var events = new ArrayList<Event>();
    for (final var entry : after.entrySet()) {
      final var old = before.get(entry.getKey());
      if (old == null) {
        continue;
      }
      final var now = entry.getValue();
      if (old.comment() == null && now.comment() == null) {
        continue;
      }
      final boolean commentChanged = !Objects.equals(old.commentText(), now.commentText());
      final boolean bodyChanged = !old.body().equals(now.body());
      if (commentChanged || bodyChanged) {
        events.add(new Event(commit, ordinal, path, entry.getKey(), now.kind(), commentChanged, bodyChanged,
            old.commentText(), now.commentText(), old.signature(), now.signature(), old.body(), now.body()));
      }
    }
    return events;
  }

  /// All events in the repository, in commit order.
  public List<Event> mine() {
    final var events = new ArrayList<Event>();
    final var commits = commits();
    for (int i = 0; i < commits.size(); ++i) {
      final var commit = commits.get(i);
      for (final var path : modifiedFiles(commit)) {
        events.addAll(events(commit, i, path));
      }
    }
    return events;
  }

  /// Events grouped by member, each group in commit order.
  public static Map<FileMembers.Key, List<Event>> byMember(final List<Event> events) {
    final var groups = new java.util.LinkedHashMap<FileMembers.Key, List<Event>>();
    for (final var event : events) {
      groups.computeIfAbsent(event.key(), k -> new ArrayList<>()).add(event);
    }
    return groups;
  }
}
