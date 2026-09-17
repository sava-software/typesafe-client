package software.sava.typesafe.evals.rot;

import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Resolves a note's member reference to a type and its member bodies at HEAD, and when
/// nothing matches, says why: the reference was a covering test, another module, an
/// external type, or a member that existed at the snapshot commit and is gone now.
public final class MemberResolver {

  public enum Status {
    /// Type and member found in this module.
    RESOLVED,
    /// A type in `src/test/java`: a covering test, never an acceptance subject.
    TEST_CLASS,
    /// A type in another module of the same checkout.
    CROSS_MODULE,
    /// The type is in this module but the member is not; it was there at the snapshot commit.
    REMOVED_MEMBER,
    /// The type is in this module but the member is not; another type declares it now.
    MOVED_MEMBER,
    /// The type is in this module but the member is not, and never was at the snapshot commit.
    MISSING_MEMBER,
    /// The type is gone; it existed at the snapshot commit.
    REMOVED_TYPE,
    /// The type is nowhere in this checkout at HEAD or at the snapshot commit.
    MISSING_TYPE
  }

  /// @param members the matching members at HEAD, best match first; empty unless RESOLVED
  /// @param detail  what was checked, for the report and the premise facts
  public record Resolution(Status status, TypeIndex.TypeDecl type, List<TypeIndex.Member> members, String detail) {

    public boolean resolved() {
      return status == Status.RESOLVED;
    }
  }

  private static final Pattern LAMBDA = Pattern.compile("^lambda\\$([A-Za-z_$][\\w$]*)\\$\\d+$");

  private final Manifest.Entry entry;
  private final Path checkout;
  private final TypeIndex main;
  private final TypeIndex test;
  private final Map<String, TypeIndex> otherModules;
  private final GitRepo git;

  public MemberResolver(final Manifest.Entry entry,
                        final Path checkout,
                        final TypeIndex main,
                        final TypeIndex test,
                        final Map<String, TypeIndex> otherModules,
                        final CommandRunner runner) {
    this.entry = entry;
    this.checkout = checkout;
    this.main = main;
    this.test = test;
    this.otherModules = otherModules;
    this.git = new GitRepo(checkout, runner);
  }

  public Resolution resolve(final ReadmeNotes.MemberRef ref) {
    final var type = findType(main, ref);
    if (type == null) {
      if (!findTypes(test, ref).isEmpty()) {
        return new Resolution(Status.TEST_CLASS, null, List.of(), "declared under " + entry.testRoot());
      }
      for (final var other : otherModules.entrySet()) {
        final var elsewhere = findType(other.getValue(), ref);
        if (elsewhere != null) {
          return new Resolution(Status.CROSS_MODULE, elsewhere, List.of(), "declared in module " + other.getKey());
        }
      }
      return atSnapshot(ref) != null
          ? new Resolution(Status.REMOVED_TYPE, null, List.of(), "existed at " + entry.commit().substring(0, 7) + ", absent at HEAD")
          : new Resolution(Status.MISSING_TYPE, null, List.of(), "no such type at HEAD or at " + entry.commit().substring(0, 7));
    }
    final var members = memberMatches(main, type, ref.memberPart());
    if (!members.isEmpty()) {
      return new Resolution(Status.RESOLVED, type, rank(members, ref), members.size() + " declaration(s)");
    }
    if (type.isRecord() && type.header().matches(".*[(,]\\s*[\\w<>\\[\\], .?]+\\s+" + Pattern.quote(ref.memberPart()) + "\\s*[,)].*")) {
      return new Resolution(Status.RESOLVED, type,
          List.of(new TypeIndex.Member(ref.memberPart(), "method", type.declLine(), type.declLine(), type.header())),
          "record component accessor");
    }
    final var movedTo = main.typesDeclaring(ref.memberPart()).stream()
        .filter(t -> !t.binaryName().equals(type.binaryName()))
        .toList();
    if (!movedTo.isEmpty()) {
      return new Resolution(Status.MOVED_MEMBER, type, List.of(),
          "now declared by " + movedTo.stream().map(TypeIndex.TypeDecl::binaryName).sorted().toList());
    }
    final var snapshot = atSnapshot(ref);
    if (snapshot != null && !memberMatches(snapshot, findType(snapshot, ref), ref.memberPart()).isEmpty()) {
      return new Resolution(Status.REMOVED_MEMBER, type, List.of(), "existed at " + entry.commit().substring(0, 7) + ", absent at HEAD");
    }
    return new Resolution(Status.MISSING_MEMBER, type, List.of(), "not declared at HEAD nor at " + entry.commit().substring(0, 7));
  }

  /// Binary name first, then a unique simple name.
  static TypeIndex.TypeDecl findType(final TypeIndex index, final ReadmeNotes.MemberRef ref) {
    final var exact = index.byBinaryName(ref.binaryClassName());
    if (exact != null) {
      return exact;
    }
    final var candidates = findTypes(index, ref);
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  static List<TypeIndex.TypeDecl> findTypes(final TypeIndex index, final ReadmeNotes.MemberRef ref) {
    return index.bySimpleName(ref.simpleClassName());
  }

  /// Members named by the reference: `<init>` is every constructor, `lambda$m$n` is the
  /// enclosing member `m` (`static` is the static initializer or a static field), a
  /// trailing `*` is a prefix, anything else is an exact name.
  static List<TypeIndex.Member> memberMatches(final TypeIndex index, final TypeIndex.TypeDecl type, final String memberPart) {
    if (type == null) {
      return List.of();
    }
    final var lambda = LAMBDA.matcher(memberPart);
    if (lambda.matches()) {
      final var enclosing = lambda.group(1);
      if (enclosing.equals("static")) {
        return index.members(type).stream()
            .filter(m -> m.kind().equals("initializer") || (m.kind().equals("field") && m.signature().contains("static")))
            .toList();
      }
      return index.members(type, enclosing);
    }
    if (memberPart.endsWith("*")) {
      final var prefix = memberPart.substring(0, memberPart.length() - 1);
      return index.members(type).stream().filter(m -> m.name().startsWith(prefix)).toList();
    }
    return index.members(type, memberPart);
  }

  /// Best match first: a signature hint's arity, then a line hint inside the body, then
  /// declaration order.
  static List<TypeIndex.Member> rank(final List<TypeIndex.Member> members, final ReadmeNotes.MemberRef ref) {
    final var ranked = new ArrayList<>(members);
    ranked.sort(Comparator.comparingInt((TypeIndex.Member m) -> -score(m, ref)).thenComparingInt(TypeIndex.Member::startLine));
    return List.copyOf(ranked);
  }

  static int score(final TypeIndex.Member member, final ReadmeNotes.MemberRef ref) {
    int score = 0;
    if (ref.sigHint() != null && member.kind().equals("method")) {
      final int hinted = arity(ref.sigHint());
      final int declared = arity(member.signature().substring(member.signature().indexOf('(') + 1, member.signature().lastIndexOf(')')));
      if (hinted == declared) {
        score += 2;
      }
    }
    if (ref.lineHints() != null) {
      for (final var hint : ref.lineHints().split("[/,–-]")) {
        try {
          final int line = Integer.parseInt(hint);
          if (line >= member.startLine() && line <= member.endLine()) {
            score += 1;
            break;
          }
        } catch (final NumberFormatException ignored) {
          // a malformed hint is no hint
        }
      }
    }
    return score;
  }

  /// Parameter count of a `(...)` body; an `...` ellipsis counts as unknown (-1).
  static int arity(final String params) {
    final var trimmed = params.strip();
    if (trimmed.isEmpty()) {
      return 0;
    }
    if (trimmed.contains("...")) {
      return -1;
    }
    int depth = 0;
    int count = 1;
    for (int i = 0; i < trimmed.length(); i++) {
      final char c = trimmed.charAt(i);
      if (c == '<') {
        depth++;
      } else if (c == '>') {
        depth--;
      } else if (c == ',' && depth == 0) {
        count++;
      }
    }
    return count;
  }

  /// The type's file as it was at the snapshot commit, indexed; null when no file of that
  /// simple name existed under the module's source root then.
  TypeIndex atSnapshot(final ReadmeNotes.MemberRef ref) {
    final String listing;
    try {
      listing = git.run("ls-tree", "-r", "--name-only", entry.commit(), "--", entry.sourceRoot());
    } catch (final RuntimeException e) {
      return null;
    }
    final var wanted = "/" + ref.simpleClassName() + ".java";
    for (final var path : listing.lines().map(String::strip).toList()) {
      if (path.endsWith(wanted)) {
        try {
          return TypeIndex.of(checkout.resolve(path), git.show(entry.commit(), path));
        } catch (final RuntimeException e) {
          return null;
        }
      }
    }
    return null;
  }
}
