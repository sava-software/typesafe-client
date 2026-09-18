package software.sava.typesafe.evals.drift;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.docs.DocCorpus;
import software.sava.typesafe.evals.docs.FileMembers;
import software.sava.typesafe.evals.docs.HistoryMiner;
import software.sava.typesafe.evals.text.Jaccard;
import software.sava.typesafe.evals.text.PathScrubber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Experiment D's rows: historical changes to documented members, labeled by whether the
/// same commit also changed the comment as shown (CO_EDIT) or left the comment byte-identical
/// (BODY_ONLY); one row per member, its most recent qualifying change.
public final class DriftCorpus {

  public static final String CO_EDIT = "CO_EDIT";
  public static final String BODY_ONLY = "BODY_ONLY";
  public static final int MIN_COMMENT_CHARS = DocCorpus.MIN_COMMENT_CHARS;
  static final int LINE_CAP = 200;
  public static final double RETOUCH_JACCARD = 0.9;

  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern IDENTIFIER_LIKE = Pattern.compile("\\b(?:[a-z]+[A-Z][\\w$]*|[A-Z][a-z]+[A-Z][\\w$]*|[A-Za-z]+_[A-Za-z_]+|[A-Z][A-Z0-9_]{2,})\\b");

  /// One change to one documented member.
  ///
  /// @param klass          CO_EDIT or BODY_ONLY
  /// @param diffSize       changed lines (removed plus added)
  /// @param overlap        fraction of the comment's identifier-like tokens that occur in the changed lines
  /// @param linesBefore    member lines before the change
  /// @param linesAfter     member lines after the change
  /// @param abstractToggle the member gained or lost a body (a `;` head on one side, braces on the other)
  /// @param diffText       a unified-style rendering for the labeling sheet, never sent
  public record Row(String id,
                    String repo,
                    String commit,
                    String path,
                    FileMembers.Key key,
                    String kind,
                    String klass,
                    String oldComment,
                    String newComment,
                    int commentChars,
                    int diffSize,
                    double overlap,
                    int linesBefore,
                    int linesAfter,
                    boolean abstractToggle,
                    String diffText,
                    DriftQuestions.State state) {
  }

  /// Why events were left out, for the summary.
  public record Excluded(String reason, int count) {
  }

  public record Result(List<Row> rows, List<Excluded> excluded, int beforeDedupe) {
  }

  private DriftCorpus() {
  }

  /// Rows from a repository's events (the miner already restricts them to documented members
  /// of main, non-generated sources), then one row per member: the latest in commit order.
  public static Result rows(final String repo, final List<HistoryMiner.Event> events) {
    final var candidates = new ArrayList<Row>();
    int noComment = 0;
    int whitespace = 0;
    int shortComment = 0;
    int retouch = 0;
    int removed = 0;
    int commentOnly = 0;
    for (final var event : events) {
      if (!event.bodyChanged()) {
        commentOnly++;
        continue;
      }
      if (event.oldComment() == null) {
        noComment++;
        continue;
      }
      if (DocCorpus.normalize(event.oldBody()).equals(DocCorpus.normalize(event.newBody()))) {
        whitespace++;
        continue;
      }
      final var name = memberName(event.key());
      final var comment = DocCorpus.shown(event.oldComment(), List.of(name));
      if (comment.length() < MIN_COMMENT_CHARS) {
        shortComment++;
        continue;
      }
      final String klass;
      if (!event.commentChanged()) {
        klass = BODY_ONLY;
      } else if (event.newComment() == null) {
        removed++;
        continue;
      } else {
        final var shownNew = DocCorpus.shown(event.newComment(), List.of(name));
        if (DocCorpus.normalize(shownNew).equals(DocCorpus.normalize(comment)) || Jaccard.similarity(comment, shownNew) >= RETOUCH_JACCARD) {
          retouch++;
          continue;
        }
        klass = CO_EDIT;
      }
      candidates.add(row(repo, event, klass, comment));
    }
    // events arrive in commit order, so the last row per member is its most recent change
    final var latest = new LinkedHashMap<String, Row>();
    for (final var row : candidates) {
      latest.put(row.path() + '#' + row.key(), row);
    }
    final var excluded = List.of(
        new Excluded("comment-only event", commentOnly),
        new Excluded("no comment before the change", noComment),
        new Excluded("whitespace-only body change", whitespace),
        new Excluded("comment shorter than " + MIN_COMMENT_CHARS + " as shown", shortComment),
        new Excluded("comment retouched (shown text equal or jaccard >= " + RETOUCH_JACCARD + ")", retouch),
        new Excluded("comment removed", removed),
        new Excluded("earlier change of the same member", candidates.size() - latest.size()));
    return new Result(List.copyOf(latest.values()), excluded, candidates.size());
  }

  static Row row(final String repo, final HistoryMiner.Event event, final String klass, final String comment) {
    final var diff = LineDiff.of(event.oldBody(), event.newBody(), LINE_CAP);
    final var removedLines = new ArrayList<String>();
    final var addedLines = new ArrayList<String>();
    for (final var op : LineDiff.ops(event.oldBody().split("\n", -1), event.newBody().split("\n", -1))) {
      if (op.startsWith("-")) {
        removedLines.add(op.substring(1));
      } else if (op.startsWith("+")) {
        addedLines.add(op.substring(1));
      }
    }
    final var newLines = event.newBody().split("\n", -1);
    final int shownNew = Math.min(newLines.length, LINE_CAP);
    final var newSource = new StringBuilder();
    for (int i = 0; i < shownNew; i++) {
      if (i > 0) {
        newSource.append('\n');
      }
      newSource.append(newLines[i]);
    }
    if (shownNew < newLines.length) {
      newSource.append("\n// … ").append(newLines.length - shownNew).append(" more lines not shown");
    }
    final var extent = JsonContent.object().put("member_kind", event.kind()).build();
    final var state = new DriftQuestions.State(comment, cap(removedLines), cap(addedLines), newSource.toString(), extent,
        PathScrubber.scrub(event.path()));
    final var id = repo + '#' + event.commit().substring(0, Math.min(7, event.commit().length())) + '#' + event.path() + '#' + event.key();
    final int linesBefore = event.oldBody().split("\n", -1).length;
    return new Row(id, repo, event.commit(), event.path(), event.key(), event.kind(), klass, event.oldComment(), event.newComment(),
        comment.length(), removedLines.size() + addedLines.size(), overlap(comment, removedLines, addedLines), linesBefore, newLines.length,
        abstractToggle(event.oldBody(), event.newBody()), diff.text(), state);
  }

  /// At most LINE_CAP lines, the cap stated as a final entry.
  static List<String> cap(final List<String> lines) {
    if (lines.size() <= LINE_CAP) {
      return List.copyOf(lines);
    }
    final var out = new ArrayList<>(lines.subList(0, LINE_CAP));
    out.add("// … " + (lines.size() - LINE_CAP) + " more lines not shown");
    return List.copyOf(out);
  }

  /// A declaration that ends in `;` on one side and carries a braced body on the other.
  static boolean abstractToggle(final String oldBody, final String newBody) {
    return bodiless(oldBody) != bodiless(newBody);
  }

  static boolean bodiless(final String body) {
    return !body.contains("{") && body.strip().endsWith(";");
  }

  /// The fraction of the comment's identifier-like tokens (backticked, CamelCase, snake_case,
  /// CONSTANT_CASE) that occur, case-insensitively, in the changed lines; 0 with none.
  static double overlap(final String comment, final List<String> removedLines, final List<String> addedLines) {
    final var names = identifiers(comment);
    if (names.isEmpty()) {
      return 0.0;
    }
    final var changed = new StringBuilder();
    for (final var line : removedLines) {
      changed.append(line).append('\n');
    }
    for (final var line : addedLines) {
      changed.append(line).append('\n');
    }
    final var haystack = changed.toString().toLowerCase(Locale.ROOT);
    int hits = 0;
    for (final var name : names) {
      if (Pattern.compile("(?<![\\w$])" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?![\\w$])").matcher(haystack).find()) {
        hits++;
      }
    }
    return (double) hits / names.size();
  }

  static Set<String> identifiers(final String comment) {
    final var names = new LinkedHashSet<String>();
    final var backticked = BACKTICKED.matcher(comment);
    while (backticked.find()) {
      final var span = backticked.group(1).strip();
      final var last = span.substring(span.lastIndexOf('.') + 1).replaceAll("\\(.*$", "");
      if (!last.isEmpty() && Character.isJavaIdentifierStart(last.charAt(0))) {
        names.add(last);
      }
    }
    final var plain = IDENTIFIER_LIKE.matcher(comment.replaceAll("`[^`]*`", " "));
    while (plain.find()) {
      names.add(plain.group());
    }
    names.remove("METHOD");
    return names;
  }

  static String memberName(final FileMembers.Key key) {
    if (key.name().equals("<init>")) {
      return key.binaryName().substring(key.binaryName().lastIndexOf('$') + 1);
    }
    return key.name();
  }
}
