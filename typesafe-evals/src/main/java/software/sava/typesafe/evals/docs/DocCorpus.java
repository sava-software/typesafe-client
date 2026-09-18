package software.sava.typesafe.evals.docs;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.text.PathScrubber;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Experiment C1's rows: the documented members of a public checkout at HEAD, each with a
/// REAL state (its own comment) and, when the type has another documented member of the
/// same kind, a SWAPPED state (that sibling's comment over this member's body).
public final class DocCorpus {

  public static final int MIN_COMMENT_CHARS = 40;
  static final int BODY_LINE_CAP = 200;
  public static final String MASK = "<METHOD>";

  /// Tag lines the deterministic control arm owns; they are removed from the comment shown.
  private static final Pattern TAG_LINE = Pattern.compile("^\\s*@(param|return|throws|exception|see)\\b.*$", Pattern.MULTILINE);
  private static final Pattern LINK = Pattern.compile("\\{@link(?:plain)?\\s+#?([A-Za-z_][\\w$]*)(?:\\([^)]*\\))?\\}|\\[#([A-Za-z_][\\w$]*)(?:\\([^)]*\\))?\\]");
  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern IDENTIFIER_LIKE = Pattern.compile("\\b(?:[a-z]+[A-Z][\\w$]*|[A-Z][a-z]+[A-Z][\\w$]*|[A-Za-z]+_[A-Za-z_]+|[A-Z][A-Z0-9_]{2,})\\b");

  /// One documented member with both arms.
  ///
  /// @param id            `repo#path#Type.member(params)`
  /// @param swappedFrom   the sibling whose comment the SWAPPED arm shows, or null (REAL-only)
  /// @param mismatchReal  identifier-mismatch fraction of the REAL comment (the baseline score)
  /// @param mismatchSwapped the same for the SWAPPED comment; NaN without a swap
  public record Row(String id,
                    String repo,
                    String path,
                    FileMembers.Key key,
                    String kind,
                    String rawComment,
                    int commentChars,
                    int bodyLines,
                    DocQuestions.State real,
                    DocQuestions.State swapped,
                    String swappedFrom,
                    double mismatchReal,
                    double mismatchSwapped,
                    List<String> identifiersMissing) {

    public boolean hasSwap() {
      return swapped != null;
    }
  }

  private final String repoName;
  private final Path checkout;
  private final GitRepo git;
  private final int perRepo;

  public DocCorpus(final String repoName, final Path checkout, final GitRepo git, final int perRepo) {
    this.repoName = repoName;
    this.checkout = checkout;
    this.git = git;
    this.perRepo = perRepo;
  }

  /// Main-source Java files of the checkout's index, in path order.
  public List<String> files() {
    final var files = new ArrayList<String>();
    for (final var line : git.run("ls-files", "--", "*.java").lines().toList()) {
      final var path = line.strip();
      if (!path.isEmpty() && HistoryMiner.MAIN_SOURCES.test(path)) {
        files.add(path);
      }
    }
    files.sort(null);
    return files;
  }

  /// Rows for the first `perRepo` documented members in path order (all of them when
  /// `perRepo` is 0 or negative).
  public List<Row> rows() {
    final var rows = new ArrayList<Row>();
    for (final var path : files()) {
      if (perRepo > 0 && rows.size() >= perRepo) {
        break;
      }
      final var members = FileMembers.of(Path.of(path), git.show("HEAD", path));
      final var documented = new ArrayList<FileMembers.Snapshot>();
      for (final var snapshot : members.values()) {
        if (documented(snapshot)) {
          documented.add(snapshot);
        }
      }
      for (final var snapshot : documented) {
        if (perRepo > 0 && rows.size() >= perRepo) {
          break;
        }
        rows.add(row(path, snapshot, sibling(documented, snapshot)));
      }
    }
    return rows;
  }

  /// A concrete member with a comment of at least MIN_COMMENT_CHARS that is not just
  /// `{@inheritDoc}`: methods and constructors with a body, fields with an initializer.
  static boolean documented(final FileMembers.Snapshot snapshot) {
    final var comment = snapshot.commentText();
    if (comment == null || comment.strip().length() < MIN_COMMENT_CHARS || comment.contains("{@inheritDoc}")) {
      return false;
    }
    return switch (snapshot.kind()) {
      case "method", "ctor" -> snapshot.body().contains("{");
      case "field" -> snapshot.signature().contains("=");
      default -> false;
    };
  }

  /// The next documented member of the same kind in the same type, cyclically; null when
  /// this member is the only one.
  static FileMembers.Snapshot sibling(final List<FileMembers.Snapshot> documented, final FileMembers.Snapshot self) {
    final var same = new ArrayList<FileMembers.Snapshot>();
    for (final var candidate : documented) {
      if (candidate.key().binaryName().equals(self.key().binaryName()) && candidate.kind().equals(self.kind())) {
        same.add(candidate);
      }
    }
    if (same.size() < 2) {
      return null;
    }
    final int index = same.indexOf(self);
    return same.get((index + 1) % same.size());
  }

  Row row(final String path, final FileMembers.Snapshot self, final FileMembers.Snapshot sibling) {
    final var id = repoName + '#' + path + '#' + self.key();
    final var source = memberSource(self);
    final var realComment = shown(self.commentText(), List.of(memberName(self)));
    final var realFacts = facts(realComment, self, source);
    final var filePath = PathScrubber.scrub(path);
    final var real = new DocQuestions.State(realComment, source.text(), realFacts.json(), filePath);
    DocQuestions.State swapped = null;
    double mismatchSwapped = Double.NaN;
    String swappedFrom = null;
    if (sibling != null) {
      final var swappedComment = shown(sibling.commentText(), List.of(memberName(self), memberName(sibling)));
      final var swappedFacts = facts(swappedComment, self, source);
      swapped = new DocQuestions.State(swappedComment, source.text(), swappedFacts.json(), filePath);
      mismatchSwapped = swappedFacts.mismatch();
      swappedFrom = sibling.key().toString();
    }
    return new Row(id, repoName, path, self.key(), self.kind(), self.commentText(), realComment.length(), self.endLine() - self.startLine() + 1,
        real, swapped, swappedFrom, realFacts.mismatch(), mismatchSwapped, realFacts.missing());
  }

  /// The comment as Jev sees it: tag lines removed, links reduced to their target name,
  /// and each of `names` masked (a constructor's name is its type's simple name).
  static String shown(final String comment, final List<String> names) {
    var text = TAG_LINE.matcher(comment).replaceAll("");
    text = LINK.matcher(text).replaceAll(m -> {
      final var target = m.group(1) != null ? m.group(1) : m.group(2);
      return names.contains(target) ? MASK : target;
    });
    for (final var name : names) {
      if (!name.isEmpty()) {
        text = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(text).replaceAll(MASK);
      }
    }
    return text.strip().replaceAll("\\n{3,}", "\n\n");
  }

  static String memberName(final FileMembers.Snapshot snapshot) {
    if (snapshot.key().name().equals("<init>")) {
      final var binary = snapshot.key().binaryName();
      return binary.substring(binary.lastIndexOf('$') + 1);
    }
    return snapshot.key().name();
  }

  record Source(String text, int linesShown, int linesTotal) {
  }

  /// Signature and body, capped at BODY_LINE_CAP lines with the cap stated.
  static Source memberSource(final FileMembers.Snapshot snapshot) {
    final var lines = snapshot.body().split("\n", -1);
    final int shown = Math.min(lines.length, BODY_LINE_CAP);
    final var out = new StringBuilder();
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        out.append('\n');
      }
      out.append(lines[i]);
    }
    if (shown < lines.length) {
      out.append("\n// … ").append(lines.length - shown).append(" more lines not shown");
    }
    return new Source(out.toString(), shown, lines.length);
  }

  record Facts(JsonContent json, double mismatch, List<String> missing) {
  }

  /// Identifiers the comment names (backticked, CamelCase, snake_case, or CONSTANT_CASE),
  /// split into present and missing against the member's source; the mismatch fraction is
  /// the deterministic baseline.
  static Facts facts(final String comment, final FileMembers.Snapshot self, final Source source) {
    final var names = new LinkedHashSet<String>();
    final var backticked = BACKTICKED.matcher(comment);
    while (backticked.find()) {
      final var span = backticked.group(1).strip();
      final var last = span.substring(span.lastIndexOf('.') + 1).replaceAll("\\(.*$", "");
      if (!last.isEmpty() && Character.isJavaIdentifierStart(last.charAt(0)) && !last.equals(MASK)) {
        names.add(last);
      }
    }
    final var plain = IDENTIFIER_LIKE.matcher(comment.replaceAll("`[^`]*`", " "));
    while (plain.find()) {
      names.add(plain.group());
    }
    names.remove("METHOD");
    final var present = new ArrayList<String>();
    final var missing = new ArrayList<String>();
    final var haystack = self.signature() + '\n' + source.text();
    for (final var name : names) {
      (Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(haystack).find() ? present : missing).add(name);
    }
    final double mismatch = names.isEmpty() ? 0.0 : (double) missing.size() / names.size();
    final var json = JsonContent.object()
        .put("member_kind", self.kind())
        .put("identifiers_present", JsonContent.array(present.toArray(String[]::new)))
        .put("identifiers_missing", JsonContent.array(missing.toArray(String[]::new)))
        .put("lines_shown", (long) source.linesShown())
        .put("lines_total", (long) source.linesTotal())
        .build();
    return new Facts(json, mismatch, List.copyOf(missing));
  }

  public static Map<String, Integer> countsByRepo(final List<Row> rows) {
    final var out = new LinkedHashMap<String, Integer>();
    for (final var row : rows) {
      out.merge(row.repo(), 1, Integer::sum);
    }
    return out;
  }
}
