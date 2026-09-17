package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReadmeNotesTests {

  private static final List<String> README = """
      # Mutation-testing baseline & triage policy

      Preamble prose.

      ## Triaged equivalent mutants (accepted with reasons)

      **Allocation-size only** — baseline label `# allocation size` — the mutant
      changes how much is allocated, never what is computed:
      - `Base58.decode` (all six variants): the limb-array sizing
        `limbsLength(to - i)` → `to + i` only over-allocates; `used` from
        `toLimbs` bounds what is read back out.
      - `Ed25519Util$PointAccum.create` and `$PointExtended.create`: both records.

      **Fast-path routing** `# fast-path` (`encoding`):
      - `Transaction.createTx(..., AccountMeta[], LookupTableAccountMeta[])` 432: the tail.

        Continued after a blank line because it is indented.
      - `EpochInfoServiceImpl.awaitInitialized:137/141` and `JsonRpcValueResponseParser.Parser.parse`
        and `V1FilterBoundaryTests#anInvokedProgram` and `#secondTest`.
      - `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`, `Map.copyOf`, `pack25519:385`, `JHex$INIT_DIGITS`.
      - `TxBuilderImpl.MERGE_ACCOUNT_META` (`lambda$static$0`), `JIUtil.escapeQuotes*`, `ExponentialBackoffErrorHandler.<init>:14`.

      ### Audited timeouts (`dispatch-timeouts.csv`)

      * `JdkQueryHandler.handle` 44, 46: drops `process(exchange)`.
      Not a bullet line.
      """.lines().toList();

  @Test
  void bulletsCarryTheirSectionAndFamily() {
    final var notes = ReadmeNotes.notes(README);
    assertEquals(7, notes.size(), notes.toString());
    final var first = notes.getFirst();
    assertEquals(9, first.line());
    assertEquals("Triaged equivalent mutants (accepted with reasons)", first.section());
    assertEquals("**Allocation-size only** — baseline label `# allocation size` — the mutant "
        + "changes how much is allocated, never what is computed:", first.family(),
        "the family is its own wrapped lines and stops at the bullet below them");
    assertEquals("- `Base58.decode` (all six variants): the limb-array sizing `limbsLength(to - i)` → `to + i` only over-allocates; `used` from `toLimbs` bounds what is read back out.", first.bullet());
    final var createTx = notes.get(2);
    assertEquals("**Fast-path routing** `# fast-path` (`encoding`):", createTx.family());
    assertTrue(createTx.bullet().endsWith("432: the tail. Continued after a blank line because it is indented."), createTx.bullet());
    final var timeout = notes.get(6);
    assertEquals("Audited timeouts (`dispatch-timeouts.csv`)", timeout.section());
    assertEquals("", timeout.family(), "a new section resets the family");
    assertEquals("* `JdkQueryHandler.handle` 44, 46: drops `process(exchange)`. Not a bullet line.", timeout.bullet());
    assertTrue(ReadmeNotes.isBullet("  - x"));
    assertTrue(ReadmeNotes.isBullet("* x"));
    assertFalse(ReadmeNotes.isBullet("-x"));
    assertFalse(ReadmeNotes.isBullet("## - heading"));
  }

  @Test
  void memberFormsAreRecognized() {
    final var notes = ReadmeNotes.notes(README);
    final var decode = ReadmeNotes.members(notes.get(0));
    assertEquals(List.of("Base58.decode"), decode.stream().map(ReadmeNotes.MemberRef::display).toList(),
        "`limbsLength(to - i)`, `used`, and `toLimbs` are not class-qualified");
    final var records = ReadmeNotes.members(notes.get(1));
    assertEquals(List.of("Ed25519Util$PointAccum.create", "Ed25519Util$PointExtended.create"),
        records.stream().map(ReadmeNotes.MemberRef::display).toList(), "the $Inner continuation inherits the outer class");
    assertEquals("PointExtended", records.get(1).simpleClassName());
    assertEquals("Ed25519Util$PointExtended", records.get(1).binaryClassName());
    final var createTx = ReadmeNotes.members(notes.get(2));
    assertEquals(1, createTx.size());
    assertEquals("Transaction", createTx.getFirst().classPart());
    assertEquals("createTx", createTx.getFirst().memberPart());
    assertEquals("..., AccountMeta[], LookupTableAccountMeta[]", createTx.getFirst().sigHint());
    assertNull(createTx.getFirst().lineHints());
    final var mixed = ReadmeNotes.members(notes.get(3));
    assertEquals(List.of("EpochInfoServiceImpl.awaitInitialized", "JsonRpcValueResponseParser.Parser.parse", "V1FilterBoundaryTests.anInvokedProgram"),
        mixed.stream().map(ReadmeNotes.MemberRef::display).toList(), "a bare #method continuation is not a $Inner continuation");
    assertEquals("137/141", mixed.get(0).lineHints());
    assertEquals("JsonRpcValueResponseParser$Parser", mixed.get(1).binaryClassName());
    assertEquals("Parser", mixed.get(1).simpleClassName());
    final var external = ReadmeNotes.members(notes.get(4));
    assertEquals(List.of("JHex.INIT_DIGITS"), external.stream().map(ReadmeNotes.MemberRef::display).toList(),
        "JDK types, bare methods, and a lone Outer$CONSTANT token: only the last is a member reference");
    final var lambdas = ReadmeNotes.members(notes.get(5));
    assertEquals(List.of("TxBuilderImpl.MERGE_ACCOUNT_META", "JIUtil.escapeQuotes*", "ExponentialBackoffErrorHandler.<init>"),
        lambdas.stream().map(ReadmeNotes.MemberRef::display).toList());
    assertEquals(List.of("JdkQueryHandler.handle"), ReadmeNotes.members(notes.get(6)).stream().map(ReadmeNotes.MemberRef::display).toList());
  }

  @Test
  void initLambdaWildcardAndExternalPrefixes() {
    final var note = new ReadmeNotes.Note(1, "s", "",
        "- `TxBuilderImpl.MERGE_ACCOUNT_META` (`lambda$static$0`), `JIUtil.escapeQuotes*`, `ExponentialBackoffErrorHandler.<init>:14`, `Foo.bar` `lowercase.thing`");
    final var refs = ReadmeNotes.members(note);
    assertEquals(List.of("TxBuilderImpl.MERGE_ACCOUNT_META", "JIUtil.escapeQuotes*", "ExponentialBackoffErrorHandler.<init>", "Foo.bar"),
        refs.stream().map(ReadmeNotes.MemberRef::display).toList());
    assertEquals("14", refs.get(2).lineHints());
    assertTrue(ReadmeNotes.isExternal("java"), "a bare package name is foreign, dotted or not");
    assertFalse(ReadmeNotes.isExternal("thing"), "a bare lowercase name that is no package prefix is not foreign");
    assertThrows(StringIndexOutOfBoundsException.class, () -> ReadmeNotes.isExternal(".Map"),
        "the segment before the first dot is what is classified, and a leading dot leaves none");
    assertTrue(ReadmeNotes.isExternal("java.util.Map"));
    assertTrue(ReadmeNotes.isExternal("com.sun.management.ThreadMXBean"));
    assertTrue(ReadmeNotes.isExternal("systems.comodal.JHex"), "any lowercase dotted prefix is a package, not a class");
    assertTrue(ReadmeNotes.isExternal("Map"));
    assertTrue(ReadmeNotes.isExternal("Math"));
    assertFalse(ReadmeNotes.isExternal("Map.Entry"), "a dotted name is this codebase's nested type, not the JDK's");
    assertFalse(ReadmeNotes.isExternal("Foo"));
    assertFalse(ReadmeNotes.isExternal("Outer$Inner"));
    assertEquals(List.of(), ReadmeNotes.members(new ReadmeNotes.Note(1, "", "", "- see `AGENTS.md` and `x-accepted.csv` and `Map.of`")));
    assertEquals(List.of(), ReadmeNotes.members(new ReadmeNotes.Note(1, "", "", "- `$Orphan.method` with no previous reference")));
    assertEquals(List.of(), ReadmeNotes.members(new ReadmeNotes.Note(1, "", "", "- nothing backticked")));
    final var duplicate = ReadmeNotes.members(new ReadmeNotes.Note(1, "", "", "- `A.b` then `A.b` again"));
    assertEquals(1, duplicate.size());
  }

  @Test
  void aBulletEndsWhereItsContinuationStops() {
    final var firstLine = ReadmeNotes.notes(List.of("- `A.b` on the first line"));
    assertEquals(1, firstLine.size(), "a bullet on the first line has no line above it to read");
    assertEquals(1, firstLine.getFirst().line());
    assertEquals("- `A.b` on the first line", firstLine.getFirst().bullet());

    final var prose = ReadmeNotes.notes(List.of("- `A.b` bullet", "", "plain prose"));
    assertEquals("- `A.b` bullet", prose.getFirst().bullet(),
        "a blank line followed by an unindented line ends the bullet");

    final var trailingBlank = ReadmeNotes.notes(List.of("- `A.b` bullet", ""));
    assertEquals("- `A.b` bullet", trailingBlank.getFirst().bullet(),
        "a blank last line ends the bullet; there is no line after it to look at");

    final var twoBlanks = ReadmeNotes.notes(List.of("- `A.b` bullet", "", "", "  indented tail"));
    assertEquals(1, twoBlanks.size());
    assertEquals("- `A.b` bullet", twoBlanks.getFirst().bullet(),
        "only the line right after a single blank can continue a bullet");

    final var indented = ReadmeNotes.notes(List.of("- `A.b` bullet", "", "  indented tail"));
    assertEquals("- `A.b` bullet indented tail", indented.getFirst().bullet(),
        "an indented line after a blank continues the bullet");

    final var heading = ReadmeNotes.notes(List.of("- `A.b` one", "## Next", "- `C.d` two"));
    assertEquals(2, heading.size());
    assertEquals("- `A.b` one", heading.getFirst().bullet(), "a heading on the next line ends the bullet");
    assertEquals("Next", heading.get(1).section(), "and the heading is still read as a section");

    final var paragraph = ReadmeNotes.notes(List.of("- `A.b` one", "**F** two", "- `C.d` three"));
    assertEquals(2, paragraph.size());
    assertEquals("- `A.b` one", paragraph.getFirst().bullet(), "a family paragraph on the next line ends the bullet");
    assertEquals("**F** two", paragraph.get(1).family(), "and the paragraph is still read as a family");
  }

  @Test
  void aFamilyParagraphEndsWhereItsWrappingStops() {
    final var leading = ReadmeNotes.notes(List.of("**F** on the first line", "- `A.b`"));
    assertEquals("**F** on the first line", leading.getFirst().family(),
        "a family on the first line has no line above it to read");

    assertEquals(List.of(), ReadmeNotes.notes(List.of("**F** one", "wrapped prose")),
        "a family that wraps to the last line is not read past it");

    final var blank = ReadmeNotes.notes(List.of("**F** one", "", "- `A.b`"));
    assertEquals("**F** one", blank.getFirst().family(), "a blank line ends the family paragraph");

    final var hash = ReadmeNotes.notes(List.of("**F** one", "# H", "- `A.b`"));
    assertEquals("**F** one", hash.getFirst().family(), "a heading ends the family paragraph");
    assertEquals("", hash.getFirst().section(), "a single-hash line is no section of its own");

    final var bullet = ReadmeNotes.notes(List.of("**F** one", "- `A.b`", "**G** two", "- `C.d`"));
    assertEquals(2, bullet.size());
    assertEquals("**F** one", bullet.getFirst().family(), "a bullet ends the family paragraph");
    assertEquals("**G** two", bullet.get(1).family());
  }
}
