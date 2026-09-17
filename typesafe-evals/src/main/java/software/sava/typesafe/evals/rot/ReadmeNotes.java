package software.sava.typesafe.evals.rot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/// Parses a `config/pitest/README.md` into acceptance notes. A note is one bullet (with its
/// continuation lines) under the section and family paragraph it sits in; a member
/// reference is a backticked, class-qualified token inside the bullet such as
/// `` `Base58.decode` ``, `` `Ed25519Util$PointAccum.create` ``, `` `Transaction.createTx(..., AccountMeta[])` ``,
/// `` `EpochInfoServiceImpl.awaitInitialized:137/141` ``, or a `` `$Inner.method` `` continuation.
public final class ReadmeNotes {

  /// One bullet: where it is and what it says.
  public record Note(int line, String section, String family, String bullet) {
  }

  /// One class-qualified member token inside a note.
  ///
  /// @param classPart  `Outer`, `Outer$Inner`, or `Outer.Inner` as written (continuations expanded)
  /// @param memberPart the member name (`create`, `<init>`, `lambda$static$0`, `escapeQuotes*`)
  /// @param lineHints  the `:137/141` suffix without the colon, or null
  /// @param sigHint    the text inside a trailing `(...)`, or null
  public record MemberRef(Note note, String classPart, String memberPart, String lineHints, String sigHint) {

    public String display() {
      return classPart + '.' + memberPart;
    }

    /// The last segment of `classPart`: the type's simple name.
    public String simpleClassName() {
      final var trimmed = classPart.replace('$', '.');
      return trimmed.substring(trimmed.lastIndexOf('.') + 1);
    }

    /// `classPart` with dots between type names turned into `$`, the binary form.
    public String binaryClassName() {
      return classPart.replace('.', '$');
    }
  }

  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern MEMBER = Pattern.compile(
      "^([A-Za-z_][\\w$]*(?:\\.[A-Z][\\w$]*)*)([.#])([A-Za-z_<][\\w$<>*]*)(?::([\\d/,–-]+))?(?:\\(([^)]*)\\))?$"
  );
  private static final Pattern CONTINUATION = Pattern.compile(
      "^\\$([A-Z][\\w$]*)\\.([A-Za-z_<][\\w$<>*]*)(?::([\\d/,–-]+))?(?:\\(([^)]*)\\))?$"
  );
  private static final Set<String> EXTERNAL_PREFIXES = Set.of("java", "javax", "jdk", "com", "org", "sun", "io", "net");
  /// Simple names of JDK types that notes cite as `Map.of`, `Math.max`, or `Integer.MIN_VALUE`.
  static final Set<String> JDK_TYPES = Set.of(
      "Map", "List", "Set", "Collections", "Arrays", "Objects", "Optional", "Stream", "IntStream", "LongStream",
      "Math", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character", "String", "StringBuilder",
      "System", "Thread", "Runtime", "CompletableFuture", "Executors", "ExecutorService", "TimeUnit", "Duration",
      "Instant", "Files", "Path", "Paths", "Base64", "BigInteger", "BigDecimal", "ByteBuffer", "HttpClient",
      "HttpRequest", "HttpResponse", "URI", "Throwable", "Exception", "RuntimeException", "Error", "Class",
      "Iterator", "Iterable", "Collection", "Comparator", "Function", "Supplier", "Consumer", "Predicate",
      "AtomicInteger", "AtomicLong", "AtomicReference", "ConcurrentHashMap", "HashMap", "ArrayList", "LinkedHashMap");
  /// Tokens shaped like `AGENTS.md` are file names, not members.
  private static final Set<String> FILE_SUFFIXES = Set.of("md", "csv", "tsv", "txt", "json", "kts", "yml", "yaml", "properties", "java");
  private static final Pattern OUTER_CONSTANT = Pattern.compile("^([A-Z][\\w]*)\\$([A-Z][A-Z0-9_]*)$");

  public static List<Note> notes(final List<String> lines) {
    final var notes = new ArrayList<Note>();
    var section = "";
    var family = "";
    for (int i = 0; i < lines.size(); i++) {
      final var line = lines.get(i);
      if (line.startsWith("## ") || line.startsWith("### ")) {
        section = line.replaceFirst("^#+\\s*", "").strip();
        family = "";
        continue;
      }
      if (isBullet(line)) {
        final var bullet = new StringBuilder(line.strip());
        int j = i + 1;
        while (j < lines.size() && continues(lines, j)) {
          if (!lines.get(j).isBlank()) {
            bullet.append(' ').append(lines.get(j).strip());
          }
          j++;
        }
        notes.add(new Note(i + 1, section, family, bullet.toString()));
        i = j - 1;
        continue;
      }
      if (line.stripLeading().startsWith("**")) {
        final var paragraph = new StringBuilder(line.strip());
        int j = i + 1;
        while (j < lines.size() && !lines.get(j).isBlank() && !isBullet(lines.get(j)) && !lines.get(j).startsWith("#")) {
          paragraph.append(' ').append(lines.get(j).strip());
          j++;
        }
        family = paragraph.toString();
        i = j - 1;
      }
    }
    return notes;
  }

  static boolean isBullet(final String line) {
    final var stripped = line.stripLeading();
    return stripped.startsWith("- ") || stripped.startsWith("* ");
  }

  /// A bullet continues through indented or wrapped lines and across a blank line only
  /// when the line after it is indented; a heading or a new bullet ends it.
  private static boolean continues(final List<String> lines, final int j) {
    final var line = lines.get(j);
    if (line.isBlank()) {
      return j + 1 < lines.size() && !lines.get(j + 1).isBlank()
          && Character.isWhitespace(lines.get(j + 1).charAt(0));
    }
    return !isBullet(line) && !line.startsWith("#") && !line.stripLeading().startsWith("**");
  }

  /// The member references in a note, in order, duplicates removed. A `$Inner.method`
  /// continuation inherits the outer class of the previous reference in the same note.
  public static List<MemberRef> members(final Note note) {
    final var refs = new LinkedHashSet<MemberRef>();
    String previousOuter = null;
    final var spans = BACKTICKED.matcher(note.bullet());
    while (spans.find()) {
      final var span = spans.group(1).strip();
      final var continuation = CONTINUATION.matcher(span);
      if (continuation.matches()) {
        if (previousOuter != null) {
          refs.add(new MemberRef(note, previousOuter + '$' + continuation.group(1), continuation.group(2),
              continuation.group(3), continuation.group(4)));
        }
        continue;
      }
      final var constant = OUTER_CONSTANT.matcher(span);
      if (constant.matches()) {
        previousOuter = constant.group(1);
        refs.add(new MemberRef(note, constant.group(1), constant.group(2), null, null));
        continue;
      }
      final var member = MEMBER.matcher(span);
      if (!member.matches()) {
        continue;
      }
      final var classPart = member.group(1);
      if (isExternal(classPart) || !Character.isUpperCase(lastSegment(classPart).charAt(0))
          || FILE_SUFFIXES.contains(member.group(3))) {
        continue;
      }
      previousOuter = outer(classPart);
      refs.add(new MemberRef(note, classPart, member.group(3), member.group(4), member.group(5)));
    }
    return List.copyOf(refs);
  }

  /// A package-qualified name, or a bare JDK type such as `Map` or `Math`. `JDK_TYPES` holds
  /// simple names only, so a dotted or `$`-joined name never matches one.
  static boolean isExternal(final String classPart) {
    final int dot = classPart.indexOf('.');
    final var first = dot < 0 ? classPart : classPart.substring(0, dot);
    if (Character.isLowerCase(first.charAt(0))) {
      return EXTERNAL_PREFIXES.contains(first) || dot >= 0;
    }
    return JDK_TYPES.contains(classPart);
  }

  private static String lastSegment(final String classPart) {
    final var dotted = classPart.replace('$', '.');
    return dotted.substring(dotted.lastIndexOf('.') + 1);
  }

  private static String outer(final String classPart) {
    final int cut = classPart.indexOf('$');
    return cut < 0 ? classPart : classPart.substring(0, cut);
  }

  private ReadmeNotes() {
  }
}
