package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.evals.corpus.GitRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DocCorpusTests {

  static final String SOURCE = """
      package p;

      /// Type doc, not a member.
      public final class Widget {

        /// Sums the data with an empty fast path; see {@link #size(int)} and [#NEXT].
        /// @param data the values,
        ///     wrapped onto a continuation line
        /// @return the total, never negative
        public int sum(final int[] data) {
          if (data.length == 0) {
            return 0;
          }
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }

        /// Sizes the allocation from `count` using SCALE and the helperValue; sum is unrelated.
        static int size(final int count) {
          return count * SCALE;
        }

        /// An overload of size whose comment differs from the other size comment entirely.
        static int size(final long count) {
          return (int) count;
        }

        /// short
        static int tiny() {
          return 1;
        }

        /// @return only a tag, so nothing is left once the tag goes and this is not documented
        static int tagOnly() {
          return 2;
        }

        /// A documented constant with enough words to count as a comment here.
        static final int SCALE = 2;

        /// A documented constant that is long enough but has no initializer to show.
        static final int NEXT;

        /// {@inheritDoc}
        public String toString() {
          return "w";
        }

        /// An abstract-looking method comment that is long enough to count as one.
        native void nothing();

        /// Constructs a Widget from nothing, which is documented at length here.
        Widget() {
        }

        /// Constructs a Widget from nothing, which is documented at length here.
        Widget(final int x) {
        }
      }
      """;

  private static List<FileMembers.Snapshot> documented() {
    return FileMembers.of(Path.of("p/Widget.java"), SOURCE).values().stream().filter(DocCorpus::documented).toList();
  }

  @Test
  void documentedMembersAreConcreteWithARealCommentAsShown() {
    final var names = documented().stream().map(s -> s.key().toString()).toList();
    assertEquals(List.of("Widget.sum(int[])", "Widget.size(int)", "Widget.size(long)", "Widget.SCALE", "Widget.<init>()", "Widget.<init>(int)"), names,
        "tiny (short), tagOnly (empty once the tag goes), NEXT (no initializer), toString (inheritDoc), and nothing (no body) are out");
  }

  @Test
  void siblingsSkipOverloadsAndIdenticalCommentsAndFallBackToTheFile() {
    final var docs = documented();
    final var sum = docs.get(0);
    final var sizeInt = docs.get(1);
    final var sizeLong = docs.get(2);
    final var scale = docs.get(3);
    final var ctor0 = docs.get(4);
    final var ctor1 = docs.get(5);
    assertEquals(sizeInt, DocCorpus.sibling(docs, sum));
    assertEquals(sum, DocCorpus.sibling(docs, sizeInt), "size(long) is an overload of size(int), so it is skipped");
    assertEquals(sum, DocCorpus.sibling(docs, sizeLong));
    assertNull(DocCorpus.sibling(docs, scale), "the only documented field has no swap");
    assertNull(DocCorpus.sibling(docs, ctor0), "the two constructors share a name and a comment: nothing to swap");
    assertNull(DocCorpus.sibling(docs, ctor1));
    final var other = new FileMembers.Snapshot(new FileMembers.Key("Other", "run", ""), "method", "void run()",
        new DocComment(1, 1, "markdown", "Runs the other thing, documented at some length here."), "void run() {\n}", 2, 3);
    final var lone = new FileMembers.Snapshot(new FileMembers.Key("Lone", "go", ""), "method", "void go()",
        new DocComment(5, 5, "markdown", "Goes somewhere else, documented at some length here."), "void go() {\n}", 6, 7);
    assertEquals(other, DocCorpus.sibling(List.of(lone, other), lone), "no same-type sibling, so the other type's member is used");
  }

  @Test
  void theShownCommentDropsBlockTagsWithContinuationsResolvesLinksAndMasksNames() {
    final var docs = documented();
    final var sum = docs.get(0);
    assertEquals("Sums the data with an empty fast path; see size and NEXT.", DocCorpus.shown(sum.commentText(), List.of("sum")),
        "the @param tag with its wrapped continuation and the @return tag are gone; links reduce to their targets");
    assertEquals("Sizes the allocation from `count` using SCALE and the helperValue; <METHOD> is unrelated.",
        DocCorpus.shown(docs.get(1).commentText(), List.of("size", "sum")), "both names are masked as identifiers");
    assertEquals("see <METHOD> and NEXT.", DocCorpus.shown("see {@link #size(int)} and [#NEXT].\n@return x\n   continued", List.of("size")));
    assertEquals("Uses size here.", DocCorpus.shown("Uses {@link Widget#size} here.", List.of("sum")), "a qualified link keeps only its member name");
    assertEquals("a\n\nb", DocCorpus.shown("a\n\n\n\nb", List.of()), "blank-line runs collapse");
    assertEquals("sizeOf stays, <METHOD> goes", DocCorpus.shown("sizeOf stays, size goes", List.of("size")), "whole-word masking");
    assertEquals("Prose before.", DocCorpus.shown("Prose before.\n@throws X when\n  it fails\n@since 1\nMore prose.", List.of()),
        "every block tag goes with everything after it up to the next tag or the end");
    assertEquals("Widget", DocCorpus.memberName(docs.get(4)), "a constructor is named after its type");
    assertEquals("sum", DocCorpus.memberName(sum));
    assertEquals("Inner", DocCorpus.memberName(new FileMembers.Snapshot(new FileMembers.Key("Outer$Inner", "<init>", ""), "ctor", "Inner()", null, "{}", 1, 1)));
  }

  @Test
  void theMemberNameIsMaskedAsProseToo() {
    assertEquals("Returns the maximum <METHOD> for the account.", DocCorpus.shown("Returns the maximum permitted data length for the account.", List.of("MAX_PERMITTED_DATA_LENGTH")),
        "the de-camel-cased word sequence is masked; 'max' is too short to be a name word, so 'maximum' stays");
    assertEquals("The <METHOD> is cached.", DocCorpus.shown("The lookup table cache is cached.", List.of("lookupTableCache")));
    assertEquals("The <METHOD> is cached.", DocCorpus.shown("The lookup of the table cache is cached.", List.of("lookupTableCache")), "up to two words between");
    assertEquals("Reads the sizes.", DocCorpus.shown("Reads the sizes.", List.of("size")), "a single-word name is masked only as a whole identifier, never as a prose prefix");
    assertEquals("Gets the balance now.", DocCorpus.shown("Gets the balance now.", List.of("getBalance")), "'get' is too short: one word left, no prose mask");
    assertEquals(List.of("permitted", "data", "length"), DocCorpus.nameWords("MAX_PERMITTED_DATA_LENGTH"));
    assertEquals(List.of("lookup", "table", "cache"), DocCorpus.nameWords("lookupTableCache"));
    assertEquals(List.of("parse", "json", "value"), DocCorpus.nameWords("parseJSONValue"), "an acronym run splits before its last capital");
    assertEquals(List.of(), DocCorpus.nameWords("get"));
    assertEquals(List.of("wrap", "signer"), DocCorpus.nameWords("wrapThisSigner"), "'this' is a stop word");
    assertNull(DocCorpus.phrasePattern("size"));
    assertNotNull(DocCorpus.phrasePattern("lookupTableCache"));
    assertEquals(1.0, DocCorpus.nameEcho("the lookup tables are cached", "lookupTableCache"), "every name word appears as a prefix");
    assertEquals(1.0 / 3.0, DocCorpus.nameEcho("the cache", "lookupTableCache"), 1e-12);
    assertEquals(0.0, DocCorpus.nameEcho("nothing here", "lookupTableCache"));
    assertEquals(0.0, DocCorpus.nameEcho("anything", "get"), "no usable words: no echo");
  }

  @Test
  void theBaselineIsTheLargerOfMismatchAndMissingEcho() {
    final var docs = documented();
    final var size = docs.get(1);
    final var source = DocCorpus.memberSource(size);
    assertEquals("  static int size(final int count) {\n    return count * SCALE;\n  }", source.text());
    assertEquals(3, source.linesShown());
    assertEquals(3, source.linesTotal());
    final var own = DocCorpus.shown(size.commentText(), List.of("size"));
    final var facts = DocCorpus.facts(own, size, source);
    assertEquals(List.of("helperValue"), facts.missing(), "count and SCALE are in the source; <METHOD> is never an identifier");
    assertEquals(1.0 / 3.0, facts.mismatch(), 1e-12);
    assertEquals(1.0 / 3.0, DocCorpus.baseline(own, size, source), 1e-12, "'Sizes' echoes the name as a prefix, so the echo term is 0 and the mismatch third remains");
    assertEquals(1.0, DocCorpus.baseline("Allocates using SCALE and the helperValue.", size, source), 1e-12, "no echo at all: 1 - 0 wins over the mismatch");
    final var lookup = new FileMembers.Snapshot(new FileMembers.Key("T", "lookupTableCache", ""), "method", "int lookupTableCache()", null,
        "int lookupTableCache() {\n  return 1;\n}", 1, 3);
    final var lookupSource = DocCorpus.memberSource(lookup);
    assertEquals(0.0, DocCorpus.baseline("Returns the cached lookup table.", lookup, lookupSource), 1e-12,
        "no identifiers to mismatch and every name word echoed");
    assertEquals(2.0 / 3.0, DocCorpus.baseline("Returns the cache.", lookup, lookupSource), 1e-12, "one of three name words echoed");
    assertEquals(1.0, DocCorpus.baseline("Uses `Missing` only.", lookup, lookupSource), "an identifier the source lacks");
    final var none = DocCorpus.facts("plain words only here", size, source);
    assertEquals(0.0, none.mismatch(), "no identifiers: nothing mismatches");
  }

  @Test
  void longBodiesAreCappedWithTheCapStated() {
    final var body = new StringBuilder("void m() {");
    for (int i = 0; i < 250; i++) {
      body.append("\n  x").append(i).append("();");
    }
    body.append("\n}");
    final var snapshot = new FileMembers.Snapshot(new FileMembers.Key("T", "m", ""), "method", "void m()", null, body.toString(), 1, 252);
    final var source = DocCorpus.memberSource(snapshot);
    assertEquals(200, source.linesShown());
    assertEquals(252, source.linesTotal());
    assertTrue(source.text().endsWith("\n// … 52 more lines not shown"), source.text().substring(source.text().length() - 60));
  }

  @Test
  void generatedFilesAreSkippedByPathOrHeader() {
    assertTrue(DocCorpus.generatedHeader("\n\n// @generated by anchor-gen\npackage p;"));
    assertTrue(DocCorpus.generatedHeader("// DO NOT EDIT\npackage p;"));
    assertFalse(DocCorpus.generatedHeader("package p;\n// @generated later does not count"));
    assertFalse(DocCorpus.generatedHeader(""));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("sdk/src/main/java/systems/glam/gen/X.java"));
    assertFalse(HistoryMiner.MAIN_SOURCES.test("sdk/src/main/java/systems/glam/next/gen/X.java"));
    assertTrue(HistoryMiner.MAIN_SOURCES.test("sdk/src/main/java/systems/glam/general/X.java"), "'general' is not 'gen'");
  }

  @Test
  void rowsCarryBothArmsDedupeByShownCommentAndReadTheIndex() {
    final var git = new GitRepo(Path.of("/nowhere"), (command, dir) -> switch (command.get(3)) {
      case "ls-files" -> "mod/src/main/java/p/Widget.java\nmod/src/main/java/p/Gen.java\nmod/src/test/java/p/WidgetTests.java\nmod/src/main/java/p/gen/G.java\n";
      case "show" -> command.get(4).endsWith("Gen.java") ? "// @generated\npackage p;\nclass Gen {\n  /// A comment long enough to count if this file were not generated.\n  int m() {\n    return 1;\n  }\n}\n" : SOURCE;
      default -> throw new IllegalStateException(String.join(" ", command));
    });
    final var corpus = new DocCorpus("repo", git);
    assertEquals(List.of("mod/src/main/java/p/Gen.java", "mod/src/main/java/p/Widget.java"), corpus.files(), "tests and /gen/ paths are not corpus files");
    final var rows = corpus.rows();
    assertEquals(5, rows.size(), "six documented members, but the two constructors share a shown comment: one row; Gen.java's header excludes it");
    final var sum = rows.getFirst();
    assertEquals("repo#mod/src/main/java/p/Widget.java#Widget.sum(int[])", sum.id());
    assertEquals("method", sum.kind());
    assertTrue(sum.hasSwap());
    assertEquals("Widget.size(int)", sum.swappedFrom());
    assertEquals(sum.real().memberSource(), sum.swapped().memberSource(), "the swap changes only the comment");
    assertEquals(sum.real().sourceExtent(), sum.swapped().sourceExtent());
    assertEquals(sum.real().filePath(), sum.swapped().filePath());
    assertNotEquals(sum.real().comment(), sum.swapped().comment());
    assertEquals(1.0, sum.mismatchReal(), "NEXT is named and absent from sum's body; 'size' is a plain word");
    assertEquals(List.of("NEXT"), sum.identifiersMissing());
    assertEquals(1.0, sum.baselineReal(), "'sum' has no usable name words");
    assertEquals(1.0, sum.baselineSwapped(), "the swapped comment names helperValue, which sum lacks");
    assertEquals("mod/src/main/java/p/Widget.java", sum.real().filePath());
    assertEquals("{\"member_kind\":\"method\",\"lines_shown\":10,\"lines_total\":10}", sum.real().sourceExtent().toJson());
    final var scale = rows.get(3);
    assertEquals("Widget.SCALE", scale.key().toString());
    assertFalse(scale.hasSwap());
    assertNull(scale.swappedFrom());
    assertTrue(Double.isNaN(scale.baselineSwapped()));
    assertEquals(10, sum.bodyLines(), "from the declaration line to its closing brace");
    assertEquals(Map.of("repo", 5), DocCorpus.countsByRepo(rows));
    assertEquals("Widget.<init>()", rows.get(4).key().toString(), "the first constructor is kept, the second is a duplicate comment");
  }

  @Test
  void aSwapWhoseShownCommentEqualsTheRealOneIsDropped() {
    final var a = new FileMembers.Snapshot(new FileMembers.Key("T", "alpha", ""), "method", "int alpha()", new DocComment(1, 1, "markdown", "Returns the value computed for the caller here."), "int alpha() {\n  return 1;\n}", 2, 4);
    final var b = new FileMembers.Snapshot(new FileMembers.Key("T", "beta", ""), "method", "int beta()", new DocComment(5, 5, "markdown", "Returns the value computed for the caller here."), "int beta() {\n  return 2;\n}", 6, 8);
    assertNull(DocCorpus.sibling(List.of(a, b), a), "identical shown comments never pair");
    final var corpus = new DocCorpus("repo", new GitRepo(Path.of("/nowhere"), (c, d) -> ""));
    final var forced = corpus.row("T.java", a, b);
    assertFalse(forced.hasSwap(), "even a forced pairing drops an equal comment");
    assertNull(forced.swappedFrom());
  }
}
