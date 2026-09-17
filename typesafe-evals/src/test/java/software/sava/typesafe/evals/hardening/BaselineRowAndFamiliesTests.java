package software.sava.typesafe.evals.hardening;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BaselineRowAndFamiliesTests {

  @Test
  void rowsParseLabelsLineHintsAndClassNames() {
    final var row = BaselineRow.parse("s", "p.q.Outer$Inner,doIt,MathMutator,SURVIVED # allocation size # line 30");
    assertNotNull(row);
    assertEquals("s", row.suite());
    assertEquals("p.q.Outer$Inner", row.className());
    assertEquals("Outer$Inner", row.binaryClassName());
    assertEquals("Inner", row.simpleClassName());
    assertEquals("doIt", row.method());
    assertEquals("MathMutator", row.mutator());
    assertEquals("SURVIVED", row.status());
    assertEquals(List.of("allocation size"), row.labels());
    assertEquals(30, row.lineHint());
    assertEquals("allocation size", row.label());
    assertFalse(row.untriaged());
    final var untriaged = BaselineRow.parse("s", "p.C,m,NullReturnValsMutator,NO_COVERAGE # untriaged # line 68");
    assertTrue(untriaged.untriaged());
    assertNull(untriaged.label(), "untriaged is not a family");
    assertEquals("C", untriaged.simpleClassName(), "no nesting, no package");
    final var two = BaselineRow.parse("s", "C,m,X,SURVIVED # untriaged # second # line 1");
    assertEquals(List.of("untriaged", "second"), two.labels());
    assertEquals("second", two.label(), "the first non-untriaged label");
    final var bare = BaselineRow.parse("s", "C,m,X,KILLED");
    assertEquals(List.of(), bare.labels());
    assertNull(bare.lineHint());
    assertNull(bare.label());
    assertEquals("KILLED", bare.status());
    final var spaced = BaselineRow.parse("s", "  C , m , X , SURVIVED  #  spaced label  ");
    assertEquals("spaced label", spaced.label());
    assertEquals("C", spaced.className());
    assertNull(BaselineRow.parse("s", ""));
    assertNull(BaselineRow.parse("s", "   "));
    assertNull(BaselineRow.parse("s", "!sava-hardening-baseline-schema,1"));
    assertNull(BaselineRow.parse("s", "# a comment row"));
    assertThrows(IllegalArgumentException.class, () -> BaselineRow.parse("s", "only,two,cells"));
    assertEquals("C,m,X,SURVIVED # l", BaselineRow.parse("s", " C,m,X,SURVIVED # l ").raw());
  }

  @Test
  void filesAreReadBySuiteName(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("core-accepted.csv");
    Files.writeString(file, "!sava-hardening-baseline-schema,1\nA,m,MathMutator,SURVIVED # x # line 1\n\nB,n,MathMutator,SURVIVED # untriaged\n");
    assertEquals("core", BaselineRow.suiteOf(file));
    assertNull(BaselineRow.suiteOf(dir.resolve("core-timeouts.csv")));
    assertNull(BaselineRow.suiteOf(dir.resolve("README.md")));
    final var rows = BaselineRow.read(file);
    assertEquals(2, rows.size());
    assertEquals("core", rows.getFirst().suite());
    assertEquals("A", rows.getFirst().className());
    assertTrue(rows.get(1).untriaged());
    assertThrows(IllegalArgumentException.class, () -> BaselineRow.read(dir.resolve("README.md")));
    assertThrows(java.io.UncheckedIOException.class, () -> BaselineRow.read(dir.resolve("missing-accepted.csv")));
  }

  private static final List<String> README = """
      # Mutation-testing baseline & triage policy

      Preamble with no label.

      ## Triaged equivalent mutants

      **Allocation-size only** — baseline label `# allocation size` — the mutant changes
      how much is allocated, never what is computed:
      - `Base58.decode`: the limb-array sizing only over-allocates.
      - `Ed25519Util$PointAccum.create`: a record.

      A plain paragraph declares `# verdict invisible` and `# merge redundant` together
      and wraps onto a second line.
      - `Transaction.merge` 12: the merged value is the same object.
        Continued bullet text.
      - `Verdict.of`: the result is never read.

      Prose between families with no label at all.
      - `Orphan.bullet`: belongs to nobody.

      ### Another section

      - `# self-union-noop` (1 row, `Clusters.union`): a bullet that declares its own label.
      - `# self-union-noop` repeated: first declaration wins.

      **Static init** — baseline label `# static init`.
      """.lines().toList();

  @Test
  void familiesAreDeclaredByBoldAndPlainParagraphsAndByBullets() {
    final var families = ReadmeFamilies.parse(README);
    assertEquals(List.of("allocation size", "verdict invisible", "merge redundant", "static init", "self-union-noop"), families.labels(),
        "paragraph-declared labels in README order, then labels only a bullet declares");
    final var allocation = families.family("allocation size");
    assertEquals("Triaged equivalent mutants", allocation.section());
    assertTrue(allocation.paragraph().startsWith("**Allocation-size only** — baseline label `# allocation size` — the mutant changes how much"), allocation.paragraph());
    assertEquals(List.of("- `Base58.decode`: the limb-array sizing only over-allocates.", "- `Ed25519Util$PointAccum.create`: a record."), allocation.bullets());
    assertEquals(7, allocation.anchorLine());
    final var verdict = families.family("verdict invisible");
    assertEquals(verdict.paragraph(), families.family("merge redundant").paragraph(), "one paragraph, two labels");
    assertTrue(verdict.paragraph().endsWith("and wraps onto a second line."), verdict.paragraph());
    assertEquals(List.of("- `Transaction.merge` 12: the merged value is the same object. Continued bullet text.", "- `Verdict.of`: the result is never read."),
        verdict.bullets(), "indented lines continue a bullet; the orphan bullet after the unlabeled prose is not ours");
    assertEquals(12, verdict.anchorLine());
    final var selfUnion = families.family("self-union-noop");
    assertEquals("Another section", selfUnion.section());
    assertEquals("- `# self-union-noop` (1 row, `Clusters.union`): a bullet that declares its own label.", selfUnion.paragraph());
    assertEquals(List.of(), selfUnion.bullets());
    assertEquals(23, selfUnion.anchorLine());
    final var staticInit = families.family("static init");
    assertEquals(List.of(), staticInit.bullets(), "a family at the end of the file has no bullets");
    assertEquals(26, staticInit.anchorLine());
    assertNull(families.family("untriaged"));
    assertEquals(5, families.families().size());
    assertEquals(List.of("a", "b"), ReadmeFamilies.labelsIn("x `# a` y `# b` z `#c` `not a label`"));
    assertEquals(List.of(), ReadmeFamilies.labelsIn(""));
  }

  @Test
  void headingsCloseAFamilyAndBulletsBeforeAnyParagraphBelongToNobody() {
    final var families = ReadmeFamilies.parse(List.of(
        "- `Early.bullet`: before any paragraph.",
        "Declares `# one`.",
        "- `A.b`: one's bullet.",
        "## Heading",
        "- `C.d`: after a heading, nobody's.",
        "Declares `# two`.",
        "",
        "- `E.f`: two's bullet after a blank line."));
    assertEquals(List.of("one", "two"), families.labels());
    assertEquals(List.of("- `A.b`: one's bullet."), families.family("one").bullets());
    assertEquals(List.of("- `E.f`: two's bullet after a blank line."), families.family("two").bullets());
    assertEquals("Heading", families.family("two").section());
    assertEquals("", families.family("one").section());
  }

  @Test
  void operatorsHaveFamiliesWordsAndSwaps() {
    final var boundary = MutatorDescriptions.describe("ConditionalsBoundaryMutator");
    assertEquals("conditional", boundary.family());
    assertTrue(boundary.description().contains("< became <="), boundary.description());
    assertEquals("return", MutatorDescriptions.describe("NullReturnValsMutator").family());
    assertEquals("call", MutatorDescriptions.describe("VoidMethodCallMutator").family());
    assertEquals("arithmetic", MutatorDescriptions.describe("IncrementsMutator").family());
    final var unknown = MutatorDescriptions.describe("FancyNewMutator");
    assertEquals("other", unknown.family());
    assertEquals("mutation operator FancyNewMutator", unknown.description());
    assertFalse(MutatorDescriptions.known("FancyNewMutator"));
    assertTrue(MutatorDescriptions.known("MathMutator"));
    assertEquals("NullReturnValsMutator", MutatorDescriptions.swapFor("ConditionalsBoundaryMutator"), "conditional swaps to return");
    assertEquals("VoidMethodCallMutator", MutatorDescriptions.swapFor("BooleanTrueReturnValsMutator"), "return swaps to call");
    assertEquals("MathMutator", MutatorDescriptions.swapFor("NakedReceiverMutator"), "call swaps to arithmetic");
    assertEquals("RemoveConditionalMutator_EQUAL_ELSE", MutatorDescriptions.swapFor("MathMutator"), "arithmetic swaps to conditional");
    assertEquals("RemoveConditionalMutator_EQUAL_ELSE", MutatorDescriptions.swapFor("FancyNewMutator"), "an unknown operator swaps to conditional");
    assertNotEquals(MutatorDescriptions.describe("MathMutator").family(),
        MutatorDescriptions.describe(MutatorDescriptions.swapFor("MathMutator")).family(), "every swap crosses a family");
    assertTrue(MutatorDescriptions.mentionsFamily("the Boundary of the loop", "conditional"), "case-insensitive");
    assertFalse(MutatorDescriptions.mentionsFamily("the boundary of the loop", "call"));
    assertTrue(MutatorDescriptions.mentionsFamily("returns null", "return"));
    assertFalse(MutatorDescriptions.mentionsFamily("anything", "other"), "no words for an unknown family");
    assertEquals(List.of(), MutatorDescriptions.familyWords("other"));
    assertTrue(MutatorDescriptions.familyWords("arithmetic").contains("increment"));
  }
}
