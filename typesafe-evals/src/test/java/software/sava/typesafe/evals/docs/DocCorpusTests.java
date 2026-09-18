package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.evals.corpus.GitRepo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DocCorpusTests {

  static final String SOURCE = """
      package p;

      /// Type doc, not a member.
      public final class Widget {

        /// Sums the data with an empty fast path; see {@link #size(int)} and [#NEXT].
        /// @param unused ignored
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

        /// short
        static int tiny() {
          return 1;
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

        /// A second constructor, also documented at length, to give the first a sibling.
        Widget(final int x) {
        }
      }
      """;

  private static List<FileMembers.Snapshot> documented() {
    return FileMembers.of(Path.of("p/Widget.java"), SOURCE).values().stream().filter(DocCorpus::documented).toList();
  }

  @Test
  void documentedMembersAreConcreteWithARealComment() {
    final var names = documented().stream().map(s -> s.key().toString()).toList();
    assertEquals(List.of("Widget.sum(int[])", "Widget.size(int)", "Widget.SCALE", "Widget.<init>()", "Widget.<init>(int)"), names,
        "tiny (short comment), NEXT (no initializer), toString (inheritDoc), and nothing (no body) are out");
  }

  @Test
  void siblingsAreTheNextDocumentedMemberOfTheSameKindCyclically() {
    final var docs = documented();
    final var sum = docs.get(0);
    final var size = docs.get(1);
    final var scale = docs.get(2);
    final var ctor0 = docs.get(3);
    final var ctor1 = docs.get(4);
    assertEquals(size, DocCorpus.sibling(docs, sum));
    assertEquals(sum, DocCorpus.sibling(docs, size), "wraps around");
    assertNull(DocCorpus.sibling(docs, scale), "the only documented field has no swap");
    assertEquals(ctor1, DocCorpus.sibling(docs, ctor0));
    assertEquals(ctor0, DocCorpus.sibling(docs, ctor1));
  }

  @Test
  void theShownCommentDropsTagsResolvesLinksAndMasksNames() {
    final var docs = documented();
    final var sum = docs.get(0);
    assertEquals("Sums the data with an empty fast path; see size and NEXT.", DocCorpus.shown(sum.commentText(), List.of("sum")),
        "tag lines gone, links reduced to their targets, no name to mask in the prose");
    assertEquals("Sizes the allocation from `count` using SCALE and the helperValue; <METHOD> is unrelated.",
        DocCorpus.shown(docs.get(1).commentText(), List.of("size", "sum")), "both the member and its sibling are masked");
    assertEquals("see <METHOD> and NEXT.", DocCorpus.shown("see {@link #size(int)} and [#NEXT].\n@return x", List.of("size")));
    assertEquals("a\n\nb", DocCorpus.shown("a\n\n\n\nb", List.of()), "blank-line runs collapse");
    assertEquals("sizeOf stays, <METHOD> goes", DocCorpus.shown("sizeOf stays, size goes", List.of("size")), "whole-word masking");
    assertEquals("Widget", DocCorpus.memberName(docs.get(3)), "a constructor is named after its type");
    assertEquals("sum", DocCorpus.memberName(sum));
    assertEquals("Inner", DocCorpus.memberName(new FileMembers.Snapshot(new FileMembers.Key("Outer$Inner", "<init>", ""), "ctor", "Inner()", null, "{}", 1, 1)));
  }

  @Test
  void factsNameIdentifiersAndTheMismatchFractionIsTheBaseline() {
    final var docs = documented();
    final var size = docs.get(1);
    final var source = DocCorpus.memberSource(size);
    assertEquals("  static int size(final int count) {\n    return count * SCALE;\n  }", source.text());
    assertEquals(3, source.linesShown());
    assertEquals(3, source.linesTotal());
    final var facts = DocCorpus.facts(DocCorpus.shown(size.commentText(), List.of("size", "sum")), size, source);
    assertEquals(List.of("helperValue"), facts.missing(), "count and SCALE are in the source; <METHOD> is never an identifier");
    assertEquals(1.0 / 3.0, facts.mismatch(), 1e-12);
    assertTrue(facts.json().toJson().contains("\"identifiers_present\":[\"count\",\"SCALE\"],\"identifiers_missing\":[\"helperValue\"],\"lines_shown\":3,\"lines_total\":3"), facts.json().toJson());
    assertTrue(facts.json().toJson().startsWith("{\"member_kind\":\"method\""), facts.json().toJson());
    final var none = DocCorpus.facts("plain words only here", size, source);
    assertEquals(0.0, none.mismatch(), "no identifiers: nothing mismatches");
    assertEquals(List.of(), none.missing());
    final var swappedFacts = DocCorpus.facts(DocCorpus.shown(docs.get(0).commentText(), List.of("size", "sum")), size, source);
    assertEquals(List.of("NEXT"), swappedFacts.missing(), "the swapped comment names NEXT, which size() lacks");
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
  void rowsCarryBothArmsAndTheCorpusReadsTheIndex() {
    final var git = new GitRepo(Path.of("/nowhere"), (command, dir) -> switch (command.get(3)) {
      case "ls-files" -> "mod/src/main/java/p/Widget.java\nmod/src/test/java/p/WidgetTests.java\nmod/src/main/java/p/generated/G.java\n";
      case "show" -> SOURCE;
      default -> throw new IllegalStateException(String.join(" ", command));
    });
    final var corpus = new DocCorpus("repo", Path.of("/nowhere"), git, 0);
    assertEquals(List.of("mod/src/main/java/p/Widget.java"), corpus.files(), "tests and generated sources are not corpus files");
    final var rows = corpus.rows();
    assertEquals(5, rows.size());
    final var sum = rows.getFirst();
    assertEquals("repo#mod/src/main/java/p/Widget.java#Widget.sum(int[])", sum.id());
    assertEquals("method", sum.kind());
    assertTrue(sum.hasSwap());
    assertEquals("Widget.size(int)", sum.swappedFrom());
    assertEquals(sum.real().memberSource(), sum.swapped().memberSource(), "the swap changes only the comment and its facts");
    assertEquals(sum.real().filePath(), sum.swapped().filePath());
    assertNotEquals(sum.real().comment(), sum.swapped().comment());
    assertEquals(1.0, sum.mismatchReal(), "the only identifier-like token in sum's comment is NEXT, which sum's body lacks; 'size' is a plain word");
    assertEquals(List.of("NEXT"), sum.identifiersMissing());
    assertEquals("mod/src/main/java/p/Widget.java", sum.real().filePath());
    final var scale = rows.get(2);
    assertFalse(scale.hasSwap());
    assertNull(scale.swappedFrom());
    assertTrue(Double.isNaN(scale.mismatchSwapped()));
    assertEquals(10, sum.bodyLines(), "from the declaration line to its closing brace");
    assertEquals(DocCorpus.countsByRepo(rows), java.util.Map.of("repo", 5));
    final var capped = new DocCorpus("repo", Path.of("/nowhere"), git, 2).rows();
    assertEquals(2, capped.size(), "per-repo cap");
  }

  @Test
  void scoresReadTheChoiceAndTheNoul() {
    final var body = """
        {"model":"m","answers":{"agreement":{"type":"choice","choice":"contradicted","confidence":0.7,"probabilities":{"consistent":0.2,"contradicted":0.75,"not_checkable":0.05}},"names_missing":{"type":"noul","noul":0.4}}}""";
    final var score = DocScore.of(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), null));
    assertEquals("contradicted", score.choice());
    assertEquals(0.2, score.pConsistent());
    assertEquals(0.75, score.pContradicted());
    assertEquals(0.05, score.pNotCheckable());
    assertEquals(0.7, score.confidence());
    assertEquals(0.4, score.namesMissing());
  }
}
