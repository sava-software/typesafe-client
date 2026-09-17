package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.rot.TypeIndex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// The documented members of one Java source file, keyed so the same member can be found
/// again in another revision of the file.
public final class FileMembers {

  /// Identity of a member across revisions: declaring type, name, and parameter types
  /// (parameter names and `final` dropped). `parameterTypes` is null for fields and
  /// initializers and empty for a parameterless method or constructor.
  public record Key(String binaryName, String name, String parameterTypes) {

    @Override
    public String toString() {
      return binaryName + '.' + name + (parameterTypes == null ? "" : "(" + parameterTypes + ")");
    }
  }

  /// One member at one revision. `comment` is null when the member has no doc comment.
  public record Snapshot(Key key, String kind, String signature, DocComment comment, String body, int startLine, int endLine) {

    public String commentText() {
      return comment == null ? null : comment.text();
    }
  }

  private static final Pattern WS = Pattern.compile("\\s+");

  private FileMembers() {
  }

  /// Every member of every type in `content`, in declaration order; later duplicates of a
  /// key (identical overloads) keep the first.
  public static Map<Key, Snapshot> of(final Path file, final String content) {
    final var index = TypeIndex.of(file, content);
    final var members = new LinkedHashMap<Key, Snapshot>();
    for (final var type : index.types()) {
      for (final var member : index.members(type)) {
        final var key = new Key(type.binaryName(), member.name(), parameterTypes(member));
        final var comment = DocComment.above(type.lines(), member.startLine());
        final var body = type.slice(member.startLine(), member.endLine());
        members.putIfAbsent(key, new Snapshot(key, member.kind(), member.signature(), comment, body, member.startLine(), member.endLine()));
      }
    }
    return members;
  }

  /// `int, List<String>, byte[]` from a method or constructor head; null for fields and
  /// initializers, empty for a parameterless member.
  static String parameterTypes(final TypeIndex.Member member) {
    if (!member.kind().equals("method") && !member.kind().equals("ctor")) {
      return null;
    }
    final var head = member.signature();
    final int open = head.indexOf('(');
    if (open < 0) {
      return "";
    }
    final int close = matchingParen(head, open);
    final var inside = head.substring(open + 1, close).strip();
    if (inside.isEmpty()) {
      return "";
    }
    final var types = new StringBuilder();
    for (final var param : splitTopLevel(inside)) {
      final var tokens = WS.split(param.replace("final ", "").strip());
      // the type is everything but the trailing parameter name; `int... xs` keeps `int...`
      final var type = new StringBuilder();
      for (int i = 0; i < tokens.length - 1; ++i) {
        if (!type.isEmpty()) {
          type.append(' ');
        }
        type.append(tokens[i]);
      }
      if (!types.isEmpty()) {
        types.append(", ");
      }
      types.append(type.isEmpty() ? tokens[0] : type);
    }
    return types.toString();
  }

  private static int matchingParen(final String text, final int open) {
    int depth = 0;
    for (int i = open; i < text.length(); ++i) {
      final char c = text.charAt(i);
      if (c == '(') {
        ++depth;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return text.length();
  }

  /// Splits on commas outside `<>`, `()`, and `[]`.
  static List<String> splitTopLevel(final String text) {
    final var parts = new java.util.ArrayList<String>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < text.length(); ++i) {
      final char c = text.charAt(i);
      if (c == '<' || c == '(' || c == '[') {
        ++depth;
      } else if (c == '>' || c == ')' || c == ']') {
        --depth;
      } else if (c == ',' && depth == 0) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(text.substring(start));
    return parts;
  }
}
