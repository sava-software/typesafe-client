package software.sava.typesafe.evals.docs;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.text.PathScrubber;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Experiment C1's rows: the documented members of a public checkout at HEAD, each with a
/// REAL state (its own comment) and, when a suitable sibling exists, a SWAPPED state (that
/// sibling's comment over this member's body). Generated sources are excluded; one row per
/// distinct shown comment per repository.
public final class DocCorpus {

  public static final int MIN_COMMENT_CHARS = 40;
  static final int BODY_LINE_CAP = 200;
  public static final String MASK = "<METHOD>";
  static final int NAME_WORD_MIN = 4;

  private static final Pattern BLOCK_TAG = Pattern.compile("^\\s*@[a-zA-Z]+\\b");
  private static final Pattern LINK = Pattern.compile("\\{@link(?:plain)?\\s+(?:[\\w$.]+)?#?([A-Za-z_][\\w$]*)(?:\\([^)]*\\))?\\}|\\[#([A-Za-z_][\\w$]*)(?:\\([^)]*\\))?\\]");
  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern IDENTIFIER_LIKE = Pattern.compile("\\b(?:[a-z]+[A-Z][\\w$]*|[A-Z][a-z]+[A-Z][\\w$]*|[A-Za-z]+_[A-Za-z_]+|[A-Z][A-Z0-9_]{2,})\\b");
  private static final Pattern WORD_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_+|\\$+");
  private static final Set<String> STOP_WORDS = Set.of("this", "that", "with", "from", "into", "when", "then", "than", "also", "only",
      "over", "some", "such", "each", "here", "there", "have", "will", "been", "were", "them", "they", "what", "which", "while");

  /// One documented member with both arms.
  ///
  /// @param swappedFrom     the sibling whose comment the SWAPPED arm shows, or null (REAL-only)
  /// @param baselineReal    the deterministic baseline for the REAL arm: how swapped the comment looks
  /// @param baselineSwapped the same for the SWAPPED arm; NaN without a swap
  /// @param generated       whether the file looked generated (such rows are excluded upstream; kept for tests)
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
                    double baselineReal,
                    double baselineSwapped,
                    List<String> identifiersMissing) {

    public boolean hasSwap() {
      return swapped != null;
    }
  }

  private final String repoName;
  private final GitRepo git;

  public DocCorpus(final String repoName, final GitRepo git) {
    this.repoName = repoName;
    this.git = git;
  }

  /// Main-source, non-generated Java files of the checkout's index, in path order.
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

  /// A file whose first non-blank line marks it as machine-generated.
  static boolean generatedHeader(final String content) {
    for (final var line : content.split("\n", 8)) {
      final var stripped = line.strip();
      if (stripped.isEmpty()) {
        continue;
      }
      return stripped.contains("@generated") || stripped.contains("DO NOT EDIT");
    }
    return false;
  }

  /// Rows for every documented member, one per distinct shown comment.
  public List<Row> rows() {
    final var rows = new ArrayList<Row>();
    final var seenComments = new HashSet<String>();
    for (final var path : files()) {
      final var content = git.show("HEAD", path);
      if (generatedHeader(content)) {
        continue;
      }
      final var members = FileMembers.of(Path.of(path), content);
      final var documented = new ArrayList<FileMembers.Snapshot>();
      for (final var snapshot : members.values()) {
        if (documented(snapshot)) {
          documented.add(snapshot);
        }
      }
      for (final var snapshot : documented) {
        final var row = row(path, snapshot, sibling(documented, snapshot));
        if (seenComments.add(normalize(row.real().comment()))) {
          rows.add(row);
        }
      }
    }
    return rows;
  }

  /// A concrete member (a method or constructor with a body, a field with an initializer)
  /// whose comment as shown is at least MIN_COMMENT_CHARS long and not `{@inheritDoc}`.
  static boolean documented(final FileMembers.Snapshot snapshot) {
    final var comment = snapshot.commentText();
    if (comment == null || comment.contains("{@inheritDoc}")) {
      return false;
    }
    final boolean concrete = switch (snapshot.kind()) {
      case "method", "ctor" -> snapshot.body().contains("{");
      case "field" -> snapshot.signature().contains("=");
      default -> false;
    };
    return concrete && shown(comment, List.of(memberName(snapshot))).length() >= MIN_COMMENT_CHARS;
  }

  /// The next documented member of the same kind in the same type whose simple name differs
  /// and whose shown comment differs; failing that, such a member elsewhere in the file;
  /// failing that, null.
  static FileMembers.Snapshot sibling(final List<FileMembers.Snapshot> documented, final FileMembers.Snapshot self) {
    final var own = normalize(shown(self.commentText(), List.of(memberName(self))));
    final int start = documented.indexOf(self);
    FileMembers.Snapshot elsewhere = null;
    for (int step = 1; step < documented.size(); step++) {
      final var candidate = documented.get((start + step) % documented.size());
      if (!candidate.kind().equals(self.kind()) || memberName(candidate).equals(memberName(self))) {
        continue;
      }
      if (normalize(shown(candidate.commentText(), List.of(memberName(self), memberName(candidate)))).equals(own)) {
        continue;
      }
      if (candidate.key().binaryName().equals(self.key().binaryName())) {
        return candidate;
      }
      if (elsewhere == null) {
        elsewhere = candidate;
      }
    }
    return elsewhere;
  }

  Row row(final String path, final FileMembers.Snapshot self, final FileMembers.Snapshot sibling) {
    final var id = repoName + '#' + path + '#' + self.key();
    final var source = memberSource(self);
    final var extent = JsonContent.object()
        .put("member_kind", self.kind())
        .put("lines_shown", (long) source.linesShown())
        .put("lines_total", (long) source.linesTotal())
        .build();
    final var filePath = PathScrubber.scrub(path);
    final var realComment = shown(self.commentText(), List.of(memberName(self)));
    final var realFacts = facts(realComment, self, source);
    final var real = new DocQuestions.State(realComment, source.text(), extent, filePath);
    DocQuestions.State swapped = null;
    double baselineSwapped = Double.NaN;
    String swappedFrom = null;
    if (sibling != null) {
      final var swappedComment = shown(sibling.commentText(), List.of(memberName(self), memberName(sibling)));
      if (!normalize(swappedComment).equals(normalize(realComment))) {
        swapped = new DocQuestions.State(swappedComment, source.text(), extent, filePath);
        baselineSwapped = baseline(swappedComment, self, source);
        swappedFrom = sibling.key().toString();
      }
    }
    return new Row(id, repoName, path, self.key(), self.kind(), self.commentText(), realComment.length(),
        self.endLine() - self.startLine() + 1, real, swapped, swappedFrom, realFacts.mismatch(),
        baseline(realComment, self, source), baselineSwapped, realFacts.missing());
  }

  /// How swapped a comment looks without reading it: the larger of its identifier-mismatch
  /// fraction and one minus the fraction of the member's name words it echoes.
  static double baseline(final String comment, final FileMembers.Snapshot self, final Source source) {
    return Math.max(facts(comment, self, source).mismatch(), 1.0 - nameEcho(comment, memberName(self)));
  }

  /// The fraction of the member name's words (NAME_WORD_MIN letters or more, not stop words)
  /// that the comment contains as word prefixes, case-insensitively; 0 when the name has none.
  static double nameEcho(final String comment, final String memberName) {
    final var words = nameWords(memberName);
    if (words.isEmpty()) {
      return 0.0;
    }
    final var lower = comment.toLowerCase(Locale.ROOT);
    int hits = 0;
    for (final var word : words) {
      if (Pattern.compile("\\b" + Pattern.quote(word)).matcher(lower).find()) {
        hits++;
      }
    }
    return (double) hits / words.size();
  }

  static List<String> nameWords(final String memberName) {
    final var out = new ArrayList<String>();
    for (final var part : WORD_SPLIT.split(memberName)) {
      final var word = part.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
      if (word.length() >= NAME_WORD_MIN && !STOP_WORDS.contains(word)) {
        out.add(word);
      }
    }
    return out;
  }

  /// The comment as Jev sees it: block tags removed with their continuation lines, links
  /// reduced to their target name, and each of `names` masked as an identifier and as its
  /// word sequence.
  static String shown(final String comment, final List<String> names) {
    final var kept = new ArrayList<String>();
    boolean inTag = false;
    for (final var line : comment.split("\n", -1)) {
      if (BLOCK_TAG.matcher(line).find()) {
        inTag = true;
        continue;
      }
      if (!inTag) {
        kept.add(line);
      }
    }
    var text = String.join("\n", kept);
    text = LINK.matcher(text).replaceAll(m -> {
      final var target = m.group(1) != null ? m.group(1) : m.group(2);
      return names.contains(target) ? MASK : target;
    });
    for (final var name : names) {
      if (name.isEmpty()) {
        continue;
      }
      text = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(text).replaceAll(MASK);
      final var phrase = phrasePattern(name);
      if (phrase != null) {
        text = phrase.matcher(text).replaceAll(MASK);
      }
    }
    return text.strip().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
  }

  /// The member name as prose: its words in order, each a word prefix, up to two other
  /// words between consecutive ones; null when the name has fewer than two usable words
  /// (a single word would mask ordinary prose).
  static Pattern phrasePattern(final String memberName) {
    final var words = nameWords(memberName);
    if (words.size() < 2) {
      return null;
    }
    final var regex = new StringBuilder("(?i)\\b");
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        regex.append("\\w*(?:\\W+\\w+){0,2}?\\W+");
      }
      regex.append(Pattern.quote(words.get(i)));
    }
    regex.append("\\w*");
    return Pattern.compile(regex.toString());
  }

  static String normalize(final String text) {
    return text.replaceAll("\\s+", " ").strip();
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

  record Facts(double mismatch, List<String> missing) {
  }

  /// Identifiers the comment names (backticked, CamelCase, snake_case, or CONSTANT_CASE),
  /// checked against the member's source; the mismatch fraction feeds the baseline and is
  /// never shown to Jev.
  static Facts facts(final String comment, final FileMembers.Snapshot self, final Source source) {
    final var names = new LinkedHashSet<String>();
    final Matcher backticked = BACKTICKED.matcher(comment);
    while (backticked.find()) {
      final var span = backticked.group(1).strip();
      final var last = span.substring(span.lastIndexOf('.') + 1).replaceAll("\\(.*$", "");
      if (!last.isEmpty() && Character.isJavaIdentifierStart(last.charAt(0)) && !last.equals(MASK)) {
        names.add(last);
      }
    }
    final Matcher plain = IDENTIFIER_LIKE.matcher(comment.replaceAll("`[^`]*`", " "));
    while (plain.find()) {
      names.add(plain.group());
    }
    names.remove("METHOD");
    final var missing = new ArrayList<String>();
    final var haystack = self.signature() + '\n' + source.text();
    for (final var name : names) {
      if (!Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(haystack).find()) {
        missing.add(name);
      }
    }
    return new Facts(names.isEmpty() ? 0.0 : (double) missing.size() / names.size(), List.copyOf(missing));
  }

  public static Map<String, Integer> countsByRepo(final List<Row> rows) {
    final var out = new LinkedHashMap<String, Integer>();
    for (final var row : rows) {
      out.merge(row.repo(), 1, Integer::sum);
    }
    return out;
  }
}
