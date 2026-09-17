package software.sava.typesafe.evals.rot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Every type declared under a source root, with brace-balanced ranges, and the members
/// declared directly inside each type. Comments and string literals are masked before any
/// brace or keyword is read, so a `{` inside a text block never opens a scope. Nested types
/// get binary names (`Outer$Inner`), and a file is not assumed to be named after the types
/// it holds.
public final class TypeIndex {

  /// One declared type. `lines` is the whole file, 1-based through `line(n)`.
  public record TypeDecl(String binaryName,
                         String simpleName,
                         String kind,
                         Path file,
                         int declLine,
                         int endLine,
                         List<String> lines,
                         String header) {

    public boolean isRecord() {
      return "record".equals(kind);
    }

    public String line(final int number) {
      return lines.get(number - 1);
    }

    /// Lines `from..to` inclusive, joined.
    public String slice(final int from, final int to) {
      return String.join("\n", lines.subList(from - 1, Math.min(to, lines.size())));
    }
  }

  /// One member declared directly in a type.
  ///
  /// @param kind      `method`, `ctor`, `field`, or `initializer`
  /// @param signature the declaration head with whitespace collapsed
  public record Member(String name, String kind, int startLine, int endLine, String signature) {

    public int length() {
      return endLine - startLine + 1;
    }
  }

  private static final Pattern TYPE_DECL = Pattern.compile(
      "(?<![\\w$.])(class|interface|enum|record|@interface)\\s+([A-Z][\\w$]*)"
  );
  private static final Pattern IDENTIFIER_BEFORE_PAREN = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\($");
  private static final Pattern FIELD_NAME = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*(?:=|$)");
  private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
  private static final java.util.Set<String> STATEMENT_KEYWORDS = java.util.Set.of(
      "if", "for", "while", "switch", "synchronized", "catch", "try", "return", "new", "else", "do", "throw");

  private final Map<String, TypeDecl> byBinaryName = new LinkedHashMap<>();
  private final Map<String, List<TypeDecl>> bySimpleName = new LinkedHashMap<>();
  private final Map<String, List<Member>> membersByType = new LinkedHashMap<>();

  private TypeIndex() {
  }

  public static TypeIndex scan(final Path sourceRoot) {
    final var index = new TypeIndex();
    if (!Files.isDirectory(sourceRoot)) {
      return index;
    }
    try (final Stream<Path> files = Files.walk(sourceRoot)) {
      for (final var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        if (file.getFileName().toString().equals("module-info.java")) {
          continue;
        }
        index.addFile(file, Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to scan " + sourceRoot, e);
    }
    return index;
  }

  /// An index over one file's content, for a blob read out of git history.
  public static TypeIndex of(final Path file, final String content) {
    final var index = new TypeIndex();
    index.addFile(file, content);
    return index;
  }

  public TypeDecl byBinaryName(final String pkgLessBinaryName) {
    return byBinaryName.get(pkgLessBinaryName);
  }

  public List<TypeDecl> bySimpleName(final String simpleName) {
    return bySimpleName.getOrDefault(simpleName, List.of());
  }

  public List<TypeDecl> types() {
    return List.copyOf(byBinaryName.values());
  }

  /// Members declared directly in `type` (nested types' members are not included).
  public List<Member> members(final TypeDecl type) {
    return membersByType.getOrDefault(type.binaryName(), List.of());
  }

  public List<Member> members(final TypeDecl type, final String name) {
    return members(type).stream().filter(m -> m.name().equals(name)).toList();
  }

  /// Every type in the index that declares a member called `name`.
  public List<TypeDecl> typesDeclaring(final String name) {
    final var out = new ArrayList<TypeDecl>();
    for (final var type : byBinaryName.values()) {
      if (!members(type, name).isEmpty()) {
        out.add(type);
      }
    }
    return out;
  }

  void addFile(final Path file, final String content) {
    final var lines = Collections.unmodifiableList(new ArrayList<>(content.lines().toList()));
    final var masked = mask(content);
    final var lineStarts = lineStarts(content);
    // type declarations with their brace ranges, outermost first by position
    record Found(String kind, String simpleName, int declOffset, int open, int close) {
    }
    final var found = new ArrayList<Found>();
    final var matcher = TYPE_DECL.matcher(masked);
    while (matcher.find()) {
      final int open = masked.indexOf('{', matcher.end());
      if (open == -1) {
        continue;
      }
      final int close = matchingBrace(masked, open);
      found.add(new Found(matcher.group(1), matcher.group(2), matcher.start(), open, close));
    }
    for (final var f : found) {
      final var chain = new ArrayList<String>();
      for (final var outer : found) {
        if (nestedIn(f.declOffset(), f.close(), outer.open(), outer.close())) {
          chain.add(outer.simpleName());
        }
      }
      chain.add(f.simpleName());
      final var binaryName = String.join("$", chain);
      final int declLine = lineOf(lineStarts, f.declOffset());
      final int endLine = lineOf(lineStarts, f.close());
      final var header = masked.substring(f.declOffset(), f.open()).replaceAll("\\s+", " ").strip();
      final var type = new TypeDecl(binaryName, f.simpleName(), f.kind(), file, declLine, endLine, lines, header);
      byBinaryName.put(binaryName, type);
      bySimpleName.computeIfAbsent(f.simpleName(), _ -> new ArrayList<>()).add(type);
      // nested type ranges are blanked before members are read
      final var body = new StringBuilder(masked);
      for (final var inner : found) {
        if (nestedIn(inner.declOffset(), inner.close(), f.open(), f.close())) {
          blank(body, inner.declOffset(), inner.close() + 1);
        }
      }
      membersByType.put(binaryName, members(body, f.open(), f.close(), f.simpleName(), lineStarts));
    }
  }

  /// True when a type declared at `declOffset` and closed at `close` sits inside the braces
  /// `open..outerClose`. A declaration never starts at its own `{`, so a type is never nested
  /// in itself.
  private static boolean nestedIn(final int declOffset, final int close,
                                  final int open, final int outerClose) {
    return open < declOffset && close < outerClose;
  }

  /// Splits a type body at depth 1 into declarations: a head followed by `{...}` is a method,
  /// constructor, or initializer; a head ended by `;` is a field or an abstract method.
  private static List<Member> members(final CharSequence masked, final int open, final int close,
                                      final String simpleName, final int[] lineStarts) {
    final var members = new ArrayList<Member>();
    int headStart = open + 1;
    int depth = 0;
    for (int i = open + 1; i < close; i++) {
      final char c = masked.charAt(i);
      if (c == '(' || c == '[') {
        depth++;
      } else if (c == ')' || c == ']') {
        depth--;
      } else if (c == '{' && depth == 0) {
        final int end = matchingBrace(masked, i);
        final var head = masked.subSequence(headStart, i).toString();
        final var member = member(head, simpleName, lineOf(lineStarts, firstNonBlank(masked, headStart, i)), lineOf(lineStarts, end));
        if (member != null) {
          members.add(member);
        }
        i = end;
        headStart = end + 1;
      } else if (c == ';' && depth == 0) {
        final var head = masked.subSequence(headStart, i).toString();
        final var member = member(head, simpleName, lineOf(lineStarts, firstNonBlank(masked, headStart, i)), lineOf(lineStarts, i));
        if (member != null) {
          members.add(member);
        }
        headStart = i + 1;
      }
    }
    return List.copyOf(members);
  }

  /// Classifies one declaration head; null for an empty or unrecognizable one.
  static Member member(final String rawHead, final String simpleName, final int startLine, final int endLine) {
    var head = ANNOTATION.matcher(rawHead).replaceAll(" ").replaceAll("\\s+", " ").strip();
    if (head.isEmpty()) {
      return null;
    }
    if (head.equals("static")) {
      return new Member("<clinit>", "initializer", startLine, endLine, "static");
    }
    if (head.equals(simpleName) || head.endsWith(" " + simpleName)) {
      // a compact record constructor: the type name alone, no parameter list
      return new Member("<init>", "ctor", startLine, endLine, head);
    }
    final int paren = head.indexOf('(');
    final int equals = head.indexOf('=');
    if (paren >= 0 && (equals < 0 || paren < equals)) {
      final var before = IDENTIFIER_BEFORE_PAREN.matcher(head.substring(0, paren + 1));
      if (!before.find()) {
        return null;
      }
      final var name = before.group(1);
      if (STATEMENT_KEYWORDS.contains(name)) {
        return null;
      }
      if (name.equals(simpleName)) {
        return new Member("<init>", "ctor", startLine, endLine, head);
      }
      return new Member(name, "method", startLine, endLine, head);
    }
    final var declaration = equals < 0 ? head : head.substring(0, equals).strip();
    final var name = declaration.substring(declaration.lastIndexOf(' ') + 1);
    if (!FIELD_NAME.matcher(name).matches()) {
      return null;
    }
    return new Member(name, "field", startLine, endLine, head);
  }

  static int firstNonBlank(final CharSequence text, final int from, final int to) {
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(text.charAt(i))) {
        return i;
      }
    }
    return from;
  }

  /// The index of the `}` matching the `{` at `open`; the end of the text when unbalanced.
  static int matchingBrace(final CharSequence masked, final int open) {
    int depth = 0;
    for (int i = open; i < masked.length(); i++) {
      final char c = masked.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return masked.length() - 1;
  }

  /// Comments and string/char literals replaced by spaces of the same length (newlines
  /// kept), so offsets and line numbers still refer to the original text.
  static String mask(final String source) {
    final var out = new StringBuilder(source);
    final int n = source.length();
    int i = 0;
    while (i < n) {
      final char c = source.charAt(i);
      if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
        int end = source.indexOf('\n', i);
        end = end == -1 ? n : end;
        blank(out, i, end);
        i = end;
      } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
        int end = source.indexOf("*/", i + 2);
        end = end == -1 ? n : end + 2;
        blank(out, i, end);
        i = end;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        end = end == -1 ? n : end + 3;
        blank(out, i, end);
        i = end;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
          if (source.charAt(j) == '\\') {
            j++;
          }
          j++;
        }
        final int end = Math.min(n, j + 1);
        blank(out, i, end);
        i = end;
      } else {
        i++;
      }
    }
    return out.toString();
  }

  private static void blank(final StringBuilder out, final int from, final int to) {
    for (int i = from; i < to; i++) {
      if (out.charAt(i) != '\n') {
        out.setCharAt(i, ' ');
      }
    }
  }

  static int[] lineStarts(final String text) {
    final var starts = new ArrayList<Integer>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /// 1-based line holding `offset`.
  static int lineOf(final int[] lineStarts, final int offset) {
    int low = 0;
    int high = lineStarts.length - 1;
    while (low < high) {
      final int mid = (low + high + 1) >>> 1;
      if (lineStarts[mid] <= offset) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low + 1;
  }
}
