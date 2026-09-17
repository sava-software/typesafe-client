package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.corpus.CommandRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/// Whitebox over [RotCorpus]: the static text helpers with small inputs, and the row-level
/// paths over a module fixture whose `git` reads are scripted rather than run, so the
/// snapshot blob and the changed-file count are inputs like any other.
final class RotCorpusTests {

  private static final String COMMIT = "0123456deadbeef";
  private static final String SHORT_COMMIT = "0123456";

  /// `sum` 15-24, `render` 26-28, 30-32 and 34-36, `compareTo` 38-41; the header names
  /// `Widget` itself, which an implementor count must not take for a subtype.
  private static final String WIDGET = """
      package p;

      /// The widget.
      public class Widget implements Comparable<Widget> {

        private final int[] data;

        private int id;

        public Widget(final int[] data) {
          this.data = data;
        }

        /// The fast path returns early for an empty array.
        public int sum() {
          if (data.length == 0) {
            return 0;
          }
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }

        public String render() {
          return render(10);
        }

        public String render(final int width) {
          return render(width, ' ');
        }

        public String render(final int width, final char pad) {
          return String.valueOf(pad).repeat(width);
        }

        @Override
        public int compareTo(final Widget other) {
          return Integer.compare(sum(), other.sum());
        }
      }
      """;

  /// The same type at the snapshot commit: it declared `gone`, which HEAD does not.
  private static final String WIDGET_AT_SNAPSHOT = """
      package p;

      public class Widget implements Comparable<Widget> {

        private final int[] data;

        public boolean gone() {
          return data == null;
        }
      }
      """;

  private static final String GADGET = """
      package p;

      final class Gadget extends Widget {

        Gadget() {
          super(new int[0]);
        }
      }
      """;

  private static final String DOODAD = """
      package p;

      final class Doodad extends Widget {

        Doodad() {
          super(new int[0]);
        }
      }
      """;

  /// `helper` 5-7.
  private static final String UTIL = """
      package p;

      final class Util {

        static int helper(final int x) {
          return x + 1;
        }
      }
      """;

  /// `write` 5-5: an abstract method ends at its semicolon.
  private static final String SINK = """
      package p;

      interface Sink {

        void write(byte[] bytes);
      }
      """;

  /// `write` 5-7, and the only header that names `Sink` after `implements`.
  private static final String FILE_SINK = """
      package p;

      final class FileSink implements Sink {

        @Override
        public void write(final byte[] bytes) {
        }
      }
      """;

  private static final String WIDGET_TESTS = """
      package p;

      final class WidgetTests {

        void sumsAnEmptyArray() {
        }
      }
      """;

  /// A type of another module of the same checkout.
  private static final String REMOTE = """
      package q;

      final class Remote {

        void ping() {
        }
      }
      """;

  /// Seven notes, on lines 6, 11, 12, 13, 14, 15 and 16.
  private static final String README = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Fast-path routing** `# fast-path`:
      - `Widget.sum:17/21` (`NullReturnValsMutator`): the `data` guard keeps the `total`
        accumulator off the empty array; `id` is not read there. `sum` and `Widget` name the
        subject, `fast` the suite, `SURVIVED` the status, `EQUAL_IF` and `ORDER_ELSE` the
        variants; `limbsLength(to - i)` is no identifier and `x` is one letter. Covered by
        `WidgetTests.sumsAnEmptyArray`.
      - `Widget.compareTo`: the only implementation delegates to the `helper` in `Util.helper`, never to `fallback`.
      - `Sink.write`: the sole implementor is the one that matters.
      - `Nowhere.method`: the only subclass, and nowhere to be found.
      - `Widget.gone` (`NullReturnValsMutator`): the removed null check.
      - `Remote.ping`: another module's.
      - `Widget.render`, `Util.helper`, `Sink.write` and `FileSink.write`: trivial.
      """;

  /// The first note's bullet on its own, for the identifier rules.
  private static final ReadmeNotes.Note BULLET = new ReadmeNotes.Note(6, "s", "f",
      "- `Widget.sum:17/21` (`NullReturnValsMutator`): the `data` guard keeps the `total` accumulator off the empty "
          + "array; `id` is not read there. `sum` and `Widget` name the subject, `fast` the suite, `SURVIVED` the "
          + "status, `EQUAL_IF` and `ORDER_ELSE` the variants; `limbsLength(to - i)` is no identifier and `x` is one letter.");

  private static ReadmeNotes.MemberRef ref(final String classPart, final String memberPart, final String lineHints) {
    return new ReadmeNotes.MemberRef(BULLET, classPart, memberPart, lineHints, null);
  }

  private static MemberResolver.Resolution resolved(final TypeIndex.TypeDecl type, final List<TypeIndex.Member> members) {
    return new MemberResolver.Resolution(MemberResolver.Status.RESOLVED, type, members, members.size() + " declaration(s)");
  }

  private static MemberResolver.Resolution removed(final TypeIndex.TypeDecl type) {
    return new MemberResolver.Resolution(MemberResolver.Status.REMOVED_MEMBER, type, List.of(),
        "existed at " + SHORT_COMMIT + ", absent at HEAD");
  }

  /// Lines `from..to` of `source`, inclusive and 1-based, as a body joins them.
  private static String lines(final String source, final int from, final int to) {
    return String.join("\n", source.lines().toList().subList(from - 1, to));
  }

  /// A class whose single method `big` spans lines 5 to `bodyLines + 8`.
  private static String longSource(final String name, final int bodyLines) {
    final var out = new StringBuilder("package p;\n\nfinal class ").append(name).append(" {\n\n  int big() {\n    int n = 0;\n");
    for (int i = 0; i < bodyLines; i++) {
      out.append("    n += ").append(i).append(";\n");
    }
    return out.append("    return n;\n  }\n}\n").toString();
  }

  /// Answers the three reads a corpus builder makes: the changed-file diff, the snapshot
  /// tree listing, and one blob out of it.
  private static CommandRunner scriptedGit() {
    final var atSnapshot = Map.of(
        "mod/src/main/java/p/Widget.java", WIDGET_AT_SNAPSHOT,
        "mod/src/main/java/p/Util.java", UTIL,
        "mod/src/main/java/p/Sink.java", SINK);
    return (command, directory) -> {
      final var args = command.subList(3, command.size());
      return switch (args.getFirst()) {
        case "diff" -> "mod/src/main/java/p/Widget.java\nmod/src/main/java/p/Util.java\nmod/src/main/java/p/Gadget.java\n";
        case "ls-tree" -> String.join("\n", new TreeSet<>(atSnapshot.keySet()));
        case "show" -> {
          final var spec = args.get(1);
          final var blob = atSnapshot.get(spec.substring(spec.indexOf(':') + 1));
          if (blob == null) {
            throw new CommandRunner.CommandFailedException(command, 128, "path does not exist");
          }
          yield blob;
        }
        default -> throw new AssertionError("unscripted git " + args);
      };
    };
  }

  /// A checkout of module `mod` with the fixture types, a golden-fleet snapshot holding the
  /// README and two suite files, and one sibling module.
  private static RotCorpus corpus(final Path dir) throws Exception {
    final var checkout = dir.resolve("checkout");
    final var src = checkout.resolve("mod/src/main/java/p");
    final var test = checkout.resolve("mod/src/test/java/p");
    Files.createDirectories(src);
    Files.createDirectories(test);
    Files.writeString(src.resolve("Widget.java"), WIDGET);
    Files.writeString(src.resolve("Gadget.java"), GADGET);
    Files.writeString(src.resolve("Doodad.java"), DOODAD);
    Files.writeString(src.resolve("Util.java"), UTIL);
    Files.writeString(src.resolve("Sink.java"), SINK);
    Files.writeString(src.resolve("FileSink.java"), FILE_SINK);
    Files.writeString(test.resolve("WidgetTests.java"), WIDGET_TESTS);
    final var snapshot = dir.resolve("golden-fleet/repo/mod");
    Files.createDirectories(snapshot);
    Files.writeString(snapshot.resolve("README.md"), README);
    Files.writeString(snapshot.resolve("fast-accepted.csv"), "p.Widget,sum,17,RemoveConditionalMutator_EQUAL_IF,SURVIVED\n");
    Files.writeString(snapshot.resolve("dispatch-timeouts.csv"), "p.Widget,render,26,RemoveConditionalMutator_ORDER_ELSE,TIMED_OUT\n");
    return new RotCorpus(new Manifest.Entry("repo", "mod", COMMIT), checkout, snapshot,
        Map.of("other", TypeIndex.of(Path.of("Remote.java"), REMOTE)), scriptedGit(),
        Map.of("repo/mod#Widget.sum", "present", "repo/mod#Widget.gone", "absent"));
  }

  private static RotRow row(final List<RotRow> rows, final String id) {
    return rows.stream().filter(r -> r.id().equals(id)).findFirst()
        .orElseThrow(() -> new AssertionError(id + " is not among " + rows.stream().map(RotRow::id).toList()));
  }

  /// One premise fact as the JSON it is sent as, or null when the fact is the JSON null.
  private static String fact(final RotRow row, final String name) {
    final var facts = (JsonContent.Obj) row.state().premiseFacts();
    assertTrue(facts.fields().containsKey(name), name + " is not a premise fact: " + facts.toJson());
    final var value = facts.fields().get(name);
    return value == null ? null : value.toJson();
  }

  @Test
  void suiteNamesComeFromTheAcceptedAndTimeoutFiles(@TempDir final Path dir) throws Exception {
    final var snapshot = dir.resolve("snapshot");
    Files.createDirectories(snapshot);
    Files.writeString(snapshot.resolve("fast-accepted.csv"), "");
    Files.writeString(snapshot.resolve("dispatch-timeouts.csv"), "");
    Files.writeString(snapshot.resolve("README.md"), "");
    Files.writeString(snapshot.resolve("-accepted.csv"), "");
    Files.writeString(snapshot.resolve("-accepted.csv-timeouts.csv"), "");
    assertEquals(List.of("dispatch", "fast"), List.copyOf(RotCorpus.suiteNames(snapshot)),
        "one name per suite, in file-name order, from either kind of file; a file with no suite name before the "
            + "`-accepted.csv` marker names none, and that marker is read before `-timeouts.csv`");
    assertEquals(Set.of(), RotCorpus.suiteNames(dir.resolve("absent")), "no snapshot directory, no suite names");
    assertEquals(Set.of(), RotCorpus.suiteNames(snapshot.resolve("README.md")), "a file is not a snapshot directory");
  }

  @Test
  void noteWindowIsSectionThenFamilyThenBulletCapped() {
    assertEquals("## S\nfamily\n- bullet", RotCorpus.noteWindow(new ReadmeNotes.Note(1, "S", "family", "- bullet")));
    assertEquals("family\n- bullet", RotCorpus.noteWindow(new ReadmeNotes.Note(1, "", "family", "- bullet")),
        "a note outside any section gets no heading line");
    assertEquals("## S\n- bullet", RotCorpus.noteWindow(new ReadmeNotes.Note(1, "S", "", "- bullet")),
        "a note with no family paragraph gets no line for it");
    assertEquals("- bullet", RotCorpus.noteWindow(new ReadmeNotes.Note(1, "", "", "- bullet")));
    final var exact = "x".repeat(RotCorpus.NOTE_WINDOW_CHARS);
    assertEquals(exact, RotCorpus.noteWindow(new ReadmeNotes.Note(1, "", "", exact)),
        "a window exactly at the cap is kept whole and unmarked");
    final var capped = RotCorpus.noteWindow(new ReadmeNotes.Note(1, "", "", "y".repeat(RotCorpus.NOTE_WINDOW_CHARS + 1)));
    assertEquals("y".repeat(RotCorpus.NOTE_WINDOW_CHARS) + " …", capped,
        "one character over the cap: cut back to the cap, then marked");
    assertEquals(RotCorpus.NOTE_WINDOW_CHARS + 2, capped.length());
  }

  @Test
  void firstLineHintIsTheFirstHintInsideTheMember() {
    final var index = TypeIndex.of(Path.of("Widget.java"), WIDGET);
    final var widget = index.byBinaryName("Widget");
    final var sum = index.members(widget, "sum").getFirst();
    assertEquals(15, sum.startLine());
    assertEquals(24, sum.endLine());
    assertEquals(15, RotCorpus.firstLineHint(ref("Widget", "sum", null), sum),
        "with no hint the anchor is the member's first line");
    assertEquals(17, RotCorpus.firstLineHint(ref("Widget", "sum", "17"), sum));
    assertEquals(15, RotCorpus.firstLineHint(ref("Widget", "sum", "15/17"), sum),
        "the member's own first line is a hint inside it");
    assertEquals(24, RotCorpus.firstLineHint(ref("Widget", "sum", "24"), sum), "so is its last line");
    assertEquals(15, RotCorpus.firstLineHint(ref("Widget", "sum", "14"), sum), "the line above the member is not inside it");
    assertEquals(15, RotCorpus.firstLineHint(ref("Widget", "sum", "25"), sum), "nor is the line below it");
    assertEquals(21, RotCorpus.firstLineHint(ref("Widget", "sum", "25/21"), sum),
        "the first hint that is inside wins, not the first hint");
    assertEquals(17, RotCorpus.firstLineHint(ref("Widget", "sum", "/17"), sum), "an empty hint is no line");
  }

  @Test
  void bodyIsTheWholeMemberOrASliceAroundTheAnchor() {
    final var index = TypeIndex.of(Path.of("Widget.java"), WIDGET);
    final var widget = index.byBinaryName("Widget");
    final var sum = index.members(widget, "sum").getFirst();
    assertEquals("// Widget lines 15-24\n" + lines(WIDGET, 15, 24), RotCorpus.body(widget, sum, ref("Widget", "sum", "17")),
        "a member under the cap is shown whole, hint or no hint");

    final var exactSource = longSource("Exact", 196);
    final var exactIndex = TypeIndex.of(Path.of("Exact.java"), exactSource);
    final var exact = exactIndex.byBinaryName("Exact");
    final var exactBig = exactIndex.members(exact, "big").getFirst();
    assertEquals(RotCorpus.BODY_LINE_CAP, exactBig.length());
    assertEquals("// Exact lines 5-204\n" + lines(exactSource, 5, 204),
        RotCorpus.body(exact, exactBig, ref("Exact", "big", "100")),
        "a member exactly at the cap is still shown whole");

    final var bigSource = longSource("Big", 297);
    final var bigIndex = TypeIndex.of(Path.of("Big.java"), bigSource);
    final var big = bigIndex.byBinaryName("Big");
    final var member = bigIndex.members(big, "big").getFirst();
    assertEquals(5, member.startLine());
    assertEquals(305, member.endLine());
    assertEquals(301, member.length());
    final var sliced = RotCorpus.body(big, member, ref("Big", "big", "200"));
    assertEquals("// Big lines 100-299 of 5-305 (truncated around line 200)\n" + lines(bigSource, 100, 299), sliced,
        "the slice starts half a cap before the anchor and runs a full cap of lines");
    assertEquals(RotCorpus.BODY_LINE_CAP + 1, sliced.lines().count(), "one header line, then at most the cap");
    assertEquals("// Big lines 5-204 of 5-305 (truncated around line 5)\n" + lines(bigSource, 5, 204),
        RotCorpus.body(big, member, ref("Big", "big", null)),
        "with no hint the slice starts at the member and is not pulled back before it");
  }

  @Test
  void lineHintsInsideNeedsEveryHintInAMatchedBody() {
    final var index = TypeIndex.of(Path.of("Widget.java"), WIDGET);
    final var widget = index.byBinaryName("Widget");
    final var sum = resolved(widget, index.members(widget, "sum"));
    assertNull(RotCorpus.lineHintsInside(ref("Widget", "sum", null), sum), "no hints, nothing to be inside or outside");
    assertNull(RotCorpus.lineHintsInside(ref("Widget", "gone", "17"), removed(widget)),
        "no matched body, nothing for a hint to be inside");
    assertEquals(Boolean.TRUE, RotCorpus.lineHintsInside(ref("Widget", "sum", "17/21"), sum));
    assertEquals(Boolean.TRUE, RotCorpus.lineHintsInside(ref("Widget", "sum", "15"), sum), "the body's first line is inside it");
    assertEquals(Boolean.TRUE, RotCorpus.lineHintsInside(ref("Widget", "sum", "24"), sum), "so is its last line");
    assertEquals(Boolean.FALSE, RotCorpus.lineHintsInside(ref("Widget", "sum", "14"), sum),
        "the line above the body is outside it");
    assertEquals(Boolean.FALSE, RotCorpus.lineHintsInside(ref("Widget", "sum", "25"), sum), "so is the line below it");
    assertEquals(Boolean.FALSE, RotCorpus.lineHintsInside(ref("Widget", "sum", "17/99"), sum), "one hint outside is enough");
    assertEquals(Boolean.TRUE, RotCorpus.lineHintsInside(ref("Widget", "sum", "/17"), sum), "an empty hint is no line");
    final var renders = resolved(widget, index.members(widget, "render"));
    assertEquals(Boolean.TRUE, RotCorpus.lineHintsInside(ref("Widget", "render", "27/35"), renders),
        "with several matched bodies a hint may fall in any of them");
  }

  @Test
  void identifiersAreBacktickedCodeThatIsNotTheSubject(@TempDir final Path dir) throws Exception {
    final var corpus = corpus(dir);
    assertEquals(List.of("data", "total", "id"), corpus.identifiers(BULLET, ref("Widget", "sum", "17/21")),
        "a class-qualified token, a mutator name, a suite name, a status, a mutant variant, a call, a single letter, "
            + "and the row's own member and class are all excluded; a two-character name is not");
    assertEquals(List.of("data", "total", "id", "sum", "Widget"), corpus.identifiers(BULLET, ref("Sink", "write", null)),
        "only the row's own member and class name are excluded as its subject");
    assertEquals(List.of("alpha"), corpus.identifiers(new ReadmeNotes.Note(1, "", "", "- `alpha` and `alpha` again"),
        ref("Widget", "sum", null)), "a repeated identifier is listed once");
    assertEquals(List.of(), corpus.identifiers(new ReadmeNotes.Note(1, "", "", "- nothing backticked here"),
        ref("Widget", "sum", null)));
  }

  @Test
  void implementorsCountsTheModulesSubtypes(@TempDir final Path dir) throws Exception {
    final var corpus = corpus(dir);
    assertEquals(2, corpus.rung(), "three changed .java files since the snapshot commit");
    final var index = corpus.mainIndex();
    assertEquals(List.of("Doodad", "FileSink", "Gadget", "Sink", "Util", "Widget"),
        index.types().stream().map(TypeIndex.TypeDecl::binaryName).sorted().toList());
    assertEquals(2, corpus.implementors(index.byBinaryName("Widget")),
        "Gadget and Doodad extend Widget; Widget's own `implements Comparable<Widget>` header is not a third");
    assertEquals(1, corpus.implementors(index.byBinaryName("Sink")), "only FileSink implements Sink");
    assertEquals(0, corpus.implementors(index.byBinaryName("Util")), "nothing extends Util");
  }

  @Test
  void methodSourceShowsTwoBodiesThenSignatures(@TempDir final Path dir) throws Exception {
    final var corpus = corpus(dir);
    final var index = corpus.mainIndex();
    final var widget = index.byBinaryName("Widget");
    assertEquals("// Widget lines 26-28\n" + lines(WIDGET, 26, 28)
            + "\n\n// Widget lines 30-32\n" + lines(WIDGET, 30, 32)
            + "\n\n// also declared: public String render(final int width, final char pad)",
        corpus.methodSource(ref("Widget", "render", null), resolved(widget, index.members(widget, "render"))),
        "three overloads: a body for the first two, a signature line for the rest");

    final var gone = corpus.methodSource(ref("Widget", "gone", null), removed(widget));
    assertTrue(gone.startsWith("// `gone` is not declared in Widget at HEAD (existed at " + SHORT_COMMIT
        + ", absent at HEAD). Members declared now:\n"), gone);
    assertTrue(gone.contains("//   public int sum()\n"), gone);
    assertTrue(gone.endsWith("//   public int compareTo(final Widget other)\n"), gone);
    assertNull(corpus.methodSource(ref("Nowhere", "method", null),
            new MemberResolver.Resolution(MemberResolver.Status.MISSING_TYPE, null, List.of(), "no such type")),
        "no type at HEAD, no source to show");
  }

  @Test
  void siblingSourceShowsTheOtherMembersTheNoteNames(@TempDir final Path dir) throws Exception {
    final var corpus = corpus(dir);
    final var index = corpus.mainIndex();
    final var widget = index.byBinaryName("Widget");
    final var self = ref("Widget", "render", null);
    final var siblings = new LinkedHashMap<ReadmeNotes.MemberRef, MemberResolver.Resolution>();
    siblings.put(self, resolved(widget, index.members(widget, "render")));
    final var util = index.byBinaryName("Util");
    siblings.put(ref("Util", "helper", null), resolved(util, index.members(util, "helper")));
    final var sink = index.byBinaryName("Sink");
    siblings.put(ref("Sink", "write", null), resolved(sink, index.members(sink, "write")));
    final var fileSink = index.byBinaryName("FileSink");
    siblings.put(ref("FileSink", "write", null), resolved(fileSink, index.members(fileSink, "write")));
    assertEquals("// Util lines 5-7\n" + lines(UTIL, 5, 7)
            + "\n\n// Sink lines 5-5\n" + lines(SINK, 5, 5)
            + "\n\n// also named: FileSink public void write(final byte[] bytes)",
        corpus.siblingSource(self, siblings),
        "a body for the first two other members, a signature line for the rest, and never the reference itself");

    assertNull(corpus.siblingSource(self, Map.of(self, resolved(widget, index.members(widget, "render")))),
        "a reference is not its own sibling, so a note naming one member has no sibling source");
    final var unresolvedOnly = new LinkedHashMap<ReadmeNotes.MemberRef, MemberResolver.Resolution>();
    unresolvedOnly.put(self, resolved(widget, index.members(widget, "render")));
    unresolvedOnly.put(ref("Widget", "gone", null), removed(widget));
    assertNull(corpus.siblingSource(self, unresolvedOnly), "a sibling that does not resolve has no body to show");
  }

  @Test
  void rowsResolveEveryNamedMemberAndCarryTheComputedFacts(@TempDir final Path dir) throws Exception {
    final var corpus = corpus(dir);
    final var rows = corpus.rows();
    assertEquals(List.of(
            "repo/mod#6#Widget.sum",
            "repo/mod#11#Widget.compareTo",
            "repo/mod#11#Util.helper",
            "repo/mod#12#Sink.write",
            "repo/mod#13#Nowhere.method",
            "repo/mod#14#Widget.gone",
            "repo/mod#16#Widget.render",
            "repo/mod#16#Util.helper",
            "repo/mod#16#Sink.write",
            "repo/mod#16#FileSink.write"),
        rows.stream().map(RotRow::id).toList(),
        "one row per note and member, in note order; a covering test class and another module's type are named by "
            + "notes but are never subjects");

    final var sum = row(rows, "repo/mod#6#Widget.sum");
    assertEquals(2, sum.rung());
    assertEquals("present", sum.goldHint());
    assertEquals(List.of("identifier_missing:id"), sum.controlFlags());
    assertEquals("\"RESOLVED\"", fact(sum, "member_status"));
    assertEquals("\"1 declaration(s)\"", fact(sum, "member_status_detail"));
    assertEquals("\"Widget\"", fact(sum, "resolved_type"));
    assertEquals("1", fact(sum, "declarations"));
    assertEquals("1", fact(sum, "bodies_shown"));
    assertEquals("[\"data\",\"total\"]", fact(sum, "identifiers_present"), "both are words of the body shown");
    assertEquals("[\"id\"]", fact(sum, "identifiers_missing"), "the `id` field is not read in `sum`");
    assertEquals("false", fact(sum, "claims_only_implementation"));
    assertNull(fact(sum, "implementor_count"), "the note claims nothing about implementors, so none are counted");
    assertEquals("true", fact(sum, "line_hints_inside_body"));
    assertEquals("[\"WidgetTests.sumsAnEmptyArray\"]", fact(sum, "covering_tests_named"),
        "only the sibling that resolved to a test class is a named covering test");
    assertEquals("mod/src/main/java/p/Widget.java", sum.state().filePath());
    assertEquals("// Widget lines 15-24\n" + lines(WIDGET, 15, 24), sum.state().methodSource());
    assertNull(sum.state().siblingSource(), "the note's only other reference is a covering test, which has no body here");
    assertTrue(sum.state().note().startsWith("## Triaged equivalent mutants (accepted with reasons)\n"
        + "**Fast-path routing** `# fast-path`:\n- `Widget.sum:17/21`"), sum.state().note());

    final var compareTo = row(rows, "repo/mod#11#Widget.compareTo");
    assertEquals(List.of("identifier_missing:fallback", "only_implementation_contradicted"), compareTo.controlFlags());
    assertEquals("true", fact(compareTo, "claims_only_implementation"));
    assertEquals("2", fact(compareTo, "implementor_count"), "two types extend Widget, so the claim is contradicted");
    assertEquals("[\"helper\"]", fact(compareTo, "identifiers_present"),
        "`helper` is a word of the sibling body rather than of `compareTo`, and the sibling body counts");
    assertEquals("[\"fallback\"]", fact(compareTo, "identifiers_missing"), "no body shown has that word");
    assertNull(fact(compareTo, "line_hints_inside_body"), "the reference carries no line hints");
    assertEquals("[]", fact(compareTo, "covering_tests_named"));
    assertEquals("// Util lines 5-7\n" + lines(UTIL, 5, 7), compareTo.state().siblingSource());
    assertNull(compareTo.goldHint(), "the survey named no label for this member");

    final var helper = row(rows, "repo/mod#11#Util.helper");
    assertEquals(List.of("identifier_missing:fallback"), helper.controlFlags(),
        "nothing extends Util, so the note's only-implementation claim stands and only the identifier is flagged");
    assertEquals("true", fact(helper, "claims_only_implementation"));
    assertEquals("0", fact(helper, "implementor_count"));
    assertEquals("[]", fact(helper, "identifiers_present"),
        "`helper` is this row's own member name, not an identifier to look for");

    final var write = row(rows, "repo/mod#12#Sink.write");
    assertEquals(List.of(), write.controlFlags(), "one implementor is what a sole-implementor claim says");
    assertEquals("1", fact(write, "implementor_count"));

    final var nowhere = row(rows, "repo/mod#13#Nowhere.method");
    assertEquals(List.of("member_missing"), nowhere.controlFlags());
    assertEquals("\"MISSING_TYPE\"", fact(nowhere, "member_status"));
    assertNull(fact(nowhere, "resolved_type"));
    assertEquals("true", fact(nowhere, "claims_only_implementation"));
    assertNull(fact(nowhere, "implementor_count"), "with no type at HEAD there is nothing to count implementors of");
    assertNull(nowhere.state().methodSource());
    assertNull(nowhere.state().filePath());

    final var gone = row(rows, "repo/mod#14#Widget.gone");
    assertEquals(List.of("member_missing"), gone.controlFlags());
    assertEquals("\"REMOVED_MEMBER\"", fact(gone, "member_status"));
    assertEquals("\"existed at " + SHORT_COMMIT + ", absent at HEAD\"", fact(gone, "member_status_detail"));
    assertEquals("\"Widget\"", fact(gone, "resolved_type"));
    assertEquals("0", fact(gone, "declarations"));
    assertEquals("0", fact(gone, "bodies_shown"));
    assertEquals("[]", fact(gone, "identifiers_present"), "nothing is matched against the code when the member is gone");
    assertEquals("[]", fact(gone, "identifiers_missing"));
    assertNull(fact(gone, "line_hints_inside_body"));
    assertEquals("absent", gone.goldHint());
    assertTrue(gone.state().methodSource().startsWith("// `gone` is not declared in Widget at HEAD"),
        gone.state().methodSource());

    final var render = row(rows, "repo/mod#16#Widget.render");
    assertEquals("3", fact(render, "declarations"));
    assertEquals("2", fact(render, "bodies_shown"), "at most two bodies are shown, however many overloads there are");
    assertTrue(render.state().methodSource().endsWith("// also declared: public String render(final int width, final char pad)"),
        render.state().methodSource());
    assertTrue(render.state().siblingSource().endsWith("// also named: FileSink public void write(final byte[] bytes)"),
        render.state().siblingSource());
  }
}
