package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.evals.corpus.CommandRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Whitebox over the resolver's parts: the arity of a signature hint, the score and order of
/// overloads, the member-name forms a note may use, type lookup by binary then simple name,
/// and every status `resolve` can answer with. The snapshot commit is scripted through the
/// [CommandRunner] seam, so no git process runs and the fixture sources are strings.
final class MemberResolverTests {

  /// The module at HEAD. Several types in one text because [TypeIndex] reads text, not files:
  /// two types share the simple name `Notes`, `Inner` is nested and unique, `Point` is a
  /// record, and `Shape`'s `permits` list has the shape of a record component list.
  private static final String HEAD = """
      package p;

      /// Notes at HEAD.
      public final class Notes {

        static final int MAX = 7;

        private int count;

        static {
          assert MAX > 0;
        }

        Notes(final int count) {
          this.count = count;
        }

        Notes() {
          this(0);
        }

        static int helper(final int x) {
          return x + 1;
        }

        String escapeQuotes(final String s) {
          return s.strip();
        }

        String escapeQuotesAndNewlines(final String s) {
          return escapeQuotes(s).stripTrailing();
        }

        void run() {
          final Runnable task = () -> count++;
          task.run();
        }

        int add(final int a) {
          return count + a;
        }

        int add(final int a, final int b) {
          return count + a + b;
        }

        /// Named the way a mutation report prints a lambda; there is no `compute` member.
        void lambda$compute$0() {
          count = 0;
        }
      }

      final class Ledger {

        static String summarize(final int n) {
          return Integer.toString(n);
        }
      }

      final class Holder {

        record Notes(int n) {
        }

        static final class Inner {

          int only() {
            return 1;
          }
        }
      }

      record Point(int x, int y) {
      }

      sealed interface Shape permits Circle, Square, Triangle, Hexagon {

        double area();
      }

      record Circle(double radius) implements Shape {

        public double area() {
          return radius;
        }
      }

      record Square(double side) implements Shape {

        public double area() {
          return side * side;
        }
      }

      record Triangle(double base, double height) implements Shape {

        public double area() {
          return base * height / 2;
        }
      }

      record Hexagon(double side) implements Shape {

        public double area() {
          return side * 6;
        }
      }
      """;

  private static final String TEST_SOURCE = """
      package p;

      final class NotesTests {

        void parsesNotes() {
        }
      }
      """;

  private static final String SIBLING = """
      package p;

      final class Sibling {

        static int help() {
          return 1;
        }
      }
      """;

  private static final String UNRELATED = """
      package p;

      final class Unrelated {

        int nothing() {
          return 0;
        }
      }
      """;

  /// Two types called `Widget`, neither of them at the plain binary name `Widget`.
  private static final String AMBIGUOUS = """
      package p;

      final class Box {

        record Widget(int n) {
        }
      }

      final class Crate {

        record Widget(int n) {
        }
      }
      """;

  /// `Notes` as it was at the snapshot commit: `tally` was there and is gone at HEAD.
  private static final String SNAPSHOT_NOTES = """
      package p;

      public final class Notes {

        private int count;

        int tally() {
          return count;
        }

        int add(final int a) {
          return count + a;
        }
      }
      """;

  private static final String SNAPSHOT_GHOST = """
      package p;

      final class Ghost {

        static void vanished() {
        }
      }
      """;

  private static final Manifest.Entry ENTRY =
      new Manifest.Entry("owner/repo", "mod", "0123456789abcdef0123456789abcdef01234567");
  private static final Path CHECKOUT = Path.of("checkouts", "repo");
  private static final String SOURCE_ROOT = "mod/src/main/java";

  /// What `git ls-tree` reports under the module source root at the snapshot commit.
  private static final String LISTING = """
      mod/src/main/java/p/Notes.java
      mod/src/main/java/p/Ghost.java
      mod/src/main/java/p/Ledger.java
      """;

  private static final Map<String, String> BLOBS = Map.of(
      "mod/src/main/java/p/Notes.java", SNAPSHOT_NOTES,
      "mod/src/main/java/p/Ghost.java", SNAPSHOT_GHOST);

  private static TypeIndex index(final String simpleName, final String source) {
    return TypeIndex.of(CHECKOUT.resolve(SOURCE_ROOT).resolve("p/" + simpleName + ".java"), source);
  }

  private static ReadmeNotes.MemberRef ref(final String classPart,
                                           final String memberPart,
                                           final String lineHints,
                                           final String sigHint) {
    final var note = new ReadmeNotes.Note(12, "Triaged equivalent mutants", "**Fixture**:",
        "- `" + classPart + '.' + memberPart + "`: a fixture reference.");
    return new ReadmeNotes.MemberRef(note, classPart, memberPart, lineHints, sigHint);
  }

  private static List<String> names(final List<TypeIndex.Member> members) {
    return members.stream().map(TypeIndex.Member::name).toList();
  }

  /// Scripts the two reads [MemberResolver#atSnapshot] makes and appends each command to
  /// `seen`: `ls-tree` of the module source root at the snapshot commit, then `show` of one
  /// blob. A path with no blob fails the way git fails, so the catch is exercised too.
  private static CommandRunner snapshotGit(final List<List<String>> seen) {
    return (command, directory) -> {
      seen.add(List.copyOf(command));
      final var subcommand = command.get(3);
      if (subcommand.equals("ls-tree")) {
        return LISTING;
      }
      if (subcommand.equals("show")) {
        final var spec = command.get(4);
        final var blob = BLOBS.get(spec.substring(spec.indexOf(':') + 1));
        if (blob == null) {
          throw new CommandRunner.CommandFailedException(command, 128, "fatal: path does not exist: " + spec);
        }
        return blob;
      }
      throw new CommandRunner.CommandFailedException(command, 1, "unscripted git subcommand " + subcommand);
    };
  }

  /// The module at HEAD, its test root, and two sibling modules; only the second declares
  /// `Sibling`, so the search cannot stop at the first module it looks in.
  private static MemberResolver resolver(final List<List<String>> seen) {
    final var others = new LinkedHashMap<String, TypeIndex>();
    others.put("mod-empty", index("Unrelated", UNRELATED));
    others.put("mod-sibling", index("Sibling", SIBLING));
    return new MemberResolver(ENTRY, CHECKOUT, index("Notes", HEAD), index("NotesTests", TEST_SOURCE),
        others, snapshotGit(seen));
  }

  private static MemberResolver resolver() {
    return resolver(new ArrayList<>());
  }

  @Test
  void arityCountsParametersOutsideAngleBrackets() {
    assertEquals(0, MemberResolver.arity(""), "no parameters");
    assertEquals(0, MemberResolver.arity("   "), "whitespace is no parameter");
    assertEquals(1, MemberResolver.arity("final String s"));
    assertEquals(2, MemberResolver.arity("int a, int b"));
    assertEquals(1, MemberResolver.arity("Map<String, Integer> m"),
        "one parameter, whatever its type argument list holds");
    assertEquals(2, MemberResolver.arity("Map<String, Integer> m, int b"),
        "a comma inside angle brackets does not separate parameters");
    assertEquals(3, MemberResolver.arity("int a, Map<String, List<Integer>> m, byte[] raw"),
        "nested angle brackets close back to depth zero");
    assertEquals(-1, MemberResolver.arity("Object... parts"), "an ellipsis makes the count unknown");
    assertEquals(-1, MemberResolver.arity("int a, String... rest"));
  }

  @Test
  void scoreRewardsAMatchingArityAndALineHintInsideTheBody() {
    final var sum = new TypeIndex.Member("sum", "method", 10, 20, "public int sum()");
    final var add = new TypeIndex.Member("add", "method", 30, 40, "int add(final int a, final int b)");
    final var field = new TypeIndex.Member("count", "field", 5, 5, "private int count");

    assertEquals(0, MemberResolver.score(sum, ref("Notes", "sum", null, null)), "no hints, nothing to reward");
    assertEquals(2, MemberResolver.score(sum, ref("Notes", "sum", null, "")),
        "an empty parameter list matches a method that takes none");
    assertEquals(0, MemberResolver.score(sum, ref("Notes", "sum", null, "int a")), "a different arity is no match");
    assertEquals(2, MemberResolver.score(add, ref("Notes", "add", null, "int a, int b")));
    assertEquals(0, MemberResolver.score(add, ref("Notes", "add", null, "int a")));
    assertEquals(0, MemberResolver.score(field, ref("Notes", "count", null, "int a")),
        "a signature hint says nothing about a field");

    assertEquals(1, MemberResolver.score(sum, ref("Notes", "sum", "10", null)), "the first line of the body counts");
    assertEquals(1, MemberResolver.score(sum, ref("Notes", "sum", "20", null)), "the last line of the body counts");
    assertEquals(1, MemberResolver.score(sum, ref("Notes", "sum", "15/99", null)), "one hint inside the body is enough");
    assertEquals(0, MemberResolver.score(sum, ref("Notes", "sum", "9/21", null)),
        "hints on either side of the body count for nothing");
    assertEquals(1, MemberResolver.score(sum, ref("Notes", "sum", "1--15", null)),
        "a malformed hint is skipped and the next one still counts");
    assertEquals(3, MemberResolver.score(add, ref("Notes", "add", "35", "int a, int b")),
        "arity and line hint add up");
  }

  @Test
  void rankPutsTheOverloadTheSignatureHintNamesFirst() {
    final var one = new TypeIndex.Member("add", "method", 10, 12, "int add(final int a)");
    final var two = new TypeIndex.Member("add", "method", 20, 22, "int add(final int a, final int b)");
    final var three = new TypeIndex.Member("add", "method", 30, 32, "int add(final long a)");
    final var declared = List.of(one, two);

    final var ranked = MemberResolver.rank(declared, ref("Notes", "add", null, "int a, int b"));
    assertEquals(List.of(two, one), ranked, "the overload whose arity the hint names comes first");
    assertEquals(List.of(one, two), declared, "the list passed in is not reordered");
    assertThrows(UnsupportedOperationException.class, () -> ranked.add(three), "the ranking is immutable");
    assertEquals(List.of(one, two, three), MemberResolver.rank(List.of(three, two, one), ref("Notes", "add", null, null)),
        "with nothing to score, the earliest declaration comes first");
  }

  @Test
  void memberNameFormsANoteMayUse() {
    final var index = index("Notes", HEAD);
    final var notes = index.byBinaryName("Notes");

    assertEquals(List.of(), MemberResolver.memberMatches(index, null, "sum"), "no type, no members");
    assertEquals(List.of(), MemberResolver.memberMatches(index, notes, "absent"));
    assertEquals(List.of("escapeQuotes"), names(MemberResolver.memberMatches(index, notes, "escapeQuotes")),
        "an exact name is not a prefix");
    assertEquals(List.of("escapeQuotes", "escapeQuotesAndNewlines"),
        names(MemberResolver.memberMatches(index, notes, "escapeQuotes*")),
        "a trailing star matches every member with that prefix, and nothing else");
    assertEquals(List.of("<init>", "<init>"), names(MemberResolver.memberMatches(index, notes, "<init>")),
        "<init> is every constructor");
    assertEquals(List.of("run"), names(MemberResolver.memberMatches(index, notes, "lambda$run$0")),
        "a synthetic lambda name resolves to the member that encloses it");
    assertEquals(List.of("MAX", "<clinit>"), names(MemberResolver.memberMatches(index, notes, "lambda$static$0")),
        "lambda$static$n is the static initializer and the static fields: not the instance field, not the static method");
  }

  @Test
  void findTypeTakesTheBinaryNameThenAUniqueSimpleName() {
    final var index = index("Notes", HEAD);

    assertEquals(2, MemberResolver.findTypes(index, ref("Notes", "run", null, null)).size(),
        "two types are called Notes");
    assertEquals("Notes", MemberResolver.findType(index, ref("Notes", "run", null, null)).binaryName(),
        "an exact binary name wins over an ambiguous simple name");
    assertEquals("Holder$Notes", MemberResolver.findType(index, ref("Holder.Notes", "n", null, null)).binaryName(),
        "a dotted reference is the binary name of the nested type");
    assertEquals("Holder$Inner", MemberResolver.findType(index, ref("Inner", "only", null, null)).binaryName(),
        "a simple name that only one type carries resolves to it");
    assertNull(MemberResolver.findType(index, ref("Nowhere", "method", null, null)), "no type of that name");
    assertNull(MemberResolver.findType(index("Box", AMBIGUOUS), ref("Widget", "n", null, null)),
        "an ambiguous simple name with no binary match is no match at all");
  }

  @Test
  void resolvedMembersComeBackBestMatchFirst() {
    final var resolution = resolver().resolve(ref("Notes", "add", null, "int a, int b"));
    assertTrue(resolution.resolved());
    assertEquals(MemberResolver.Status.RESOLVED, resolution.status());
    assertEquals("Notes", resolution.type().binaryName());
    assertEquals("2 declaration(s)", resolution.detail());
    assertEquals(List.of("int add(final int a, final int b)", "int add(final int a)"),
        resolution.members().stream().map(TypeIndex.Member::signature).toList(),
        "the overload the signature hint names is first");
  }

  @Test
  void aTypeUnderTheTestRootIsACoveringTestAndNeverASubject() {
    final var resolution = resolver().resolve(ref("NotesTests", "parsesNotes", null, null));
    assertEquals(MemberResolver.Status.TEST_CLASS, resolution.status());
    assertNull(resolution.type());
    assertEquals(List.of(), resolution.members());
    assertEquals("declared under mod/src/test/java", resolution.detail());
  }

  @Test
  void aTypeInAnotherModuleOfTheCheckoutIsCrossModule() {
    final var resolution = resolver().resolve(ref("Sibling", "help", null, null));
    assertEquals(MemberResolver.Status.CROSS_MODULE, resolution.status());
    assertEquals("Sibling", resolution.type().binaryName());
    assertEquals("declared in module mod-sibling", resolution.detail(),
        "the module that declares it is named, not the first one searched");
  }

  @Test
  void aTypeNeitherAtHeadNorAtTheSnapshotIsMissing() {
    final var resolution = resolver().resolve(ref("Nowhere", "method", null, null));
    assertEquals(MemberResolver.Status.MISSING_TYPE, resolution.status(),
        "a module that does not declare it is not a cross-module answer");
    assertNull(resolution.type());
    assertEquals("no such type at HEAD or at 0123456", resolution.detail());
  }

  @Test
  void aTypeOnlyAtTheSnapshotCommitIsRemoved() {
    final var seen = new ArrayList<List<String>>();
    final var resolution = resolver(seen).resolve(ref("Ghost", "vanished", null, null));
    assertEquals(MemberResolver.Status.REMOVED_TYPE, resolution.status());
    assertNull(resolution.type());
    assertEquals("existed at 0123456, absent at HEAD", resolution.detail());
    assertEquals(List.of("git", "-C", CHECKOUT.toString(), "ls-tree", "-r", "--name-only", ENTRY.commit(),
        "--", SOURCE_ROOT), seen.getFirst(), "the snapshot is listed at the manifest commit under the source root");
    assertEquals(ENTRY.commit() + ":mod/src/main/java/p/Ghost.java", seen.get(1).get(4),
        "and the matching path is read at that commit");
  }

  @Test
  void aMemberOnlyAtTheSnapshotCommitIsRemoved() {
    final var resolution = resolver().resolve(ref("Notes", "tally", null, null));
    assertEquals(MemberResolver.Status.REMOVED_MEMBER, resolution.status());
    assertEquals("Notes", resolution.type().binaryName());
    assertEquals(List.of(), resolution.members(), "a removed member has no body to show");
    assertEquals("existed at 0123456, absent at HEAD", resolution.detail());
  }

  @Test
  void aMemberAnotherTypeDeclaresNowHasMoved() {
    final var resolution = resolver().resolve(ref("Notes", "summarize", null, null));
    assertEquals(MemberResolver.Status.MOVED_MEMBER, resolution.status());
    assertEquals("Notes", resolution.type().binaryName());
    assertEquals("now declared by [Ledger]", resolution.detail());
  }

  @Test
  void aMemberDeclaredAtNeitherEndIsMissing() {
    final var resolution = resolver().resolve(ref("Notes", "neverThere", null, null));
    assertEquals(MemberResolver.Status.MISSING_MEMBER, resolution.status(),
        "the snapshot has the type but not the member");
    assertEquals("Notes", resolution.type().binaryName());
    assertEquals("not declared at HEAD nor at 0123456", resolution.detail());
  }

  @Test
  void recordComponentsResolveThroughTheHeader() {
    final var first = resolver().resolve(ref("Point", "x", null, null));
    assertEquals(MemberResolver.Status.RESOLVED, first.status());
    assertEquals("record component accessor", first.detail());
    assertEquals(List.of("x"), names(first.members()));
    assertEquals("method", first.members().getFirst().kind());
    assertEquals(MemberResolver.Status.RESOLVED, resolver().resolve(ref("Point", "y", null, null)).status(),
        "the last component is a component too");
    assertEquals(MemberResolver.Status.MISSING_MEMBER, resolver().resolve(ref("Point", "z", null, null)).status(),
        "a name the header does not list is not a component, and Point.java is not in the snapshot");
  }

  @Test
  void aPermitsListIsNotARecordComponentList() {
    // Shape's header reads "interface Shape permits Circle, Square, Triangle, Hexagon", which
    // has the shape of a component list; only a record declares components
    final var resolution = resolver().resolve(ref("Shape", "Triangle", null, null));
    assertEquals(MemberResolver.Status.MISSING_MEMBER, resolution.status());
    assertEquals("Shape", resolution.type().binaryName());
    assertEquals(List.of(), resolution.members());
  }

  @Test
  void aSyntheticLambdaNameIsNotItsOwnNewHome() {
    // Notes declares a method literally called lambda$compute$0, but the reference means the
    // member that encloses the lambda, and Notes declares no `compute`
    final var resolution = resolver().resolve(ref("Notes", "lambda$compute$0", null, null));
    assertEquals(MemberResolver.Status.MISSING_MEMBER, resolution.status(),
        "the type that carries the literal name is not where the member moved to");
    assertEquals("Notes", resolution.type().binaryName());
  }

  @Test
  void aSnapshotThatCannotBeReadIsNoSnapshot() {
    final CommandRunner failing = (command, directory) -> {
      throw new CommandRunner.CommandFailedException(command, 128, "fatal: not a git repository");
    };
    final var listingFailed = new MemberResolver(ENTRY, CHECKOUT, index("Notes", HEAD),
        index("NotesTests", TEST_SOURCE), Map.of(), failing).resolve(ref("Ghost", "vanished", null, null));
    assertEquals(MemberResolver.Status.MISSING_TYPE, listingFailed.status(),
        "a snapshot whose tree cannot be listed proves nothing about the past");

    final var blobFailed = resolver().resolve(ref("Ledger", "gone", null, null));
    assertEquals(MemberResolver.Status.MISSING_MEMBER, blobFailed.status(),
        "Ledger.java is in the snapshot tree but its blob cannot be read");
    assertEquals("Ledger", blobFailed.type().binaryName());
  }
}
