package software.sava.typesafe.evals.hardening;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.rot.TypeIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// [HardeningCorpus] over in-memory sources and synthetic families: one row at a time, with
/// no checkout and no git. The line numbers quoted below are those of the fixtures in this
/// file, computed from the text so they stay true when a fixture is edited.
final class HardeningCorpusRowTests {

  private static final Path CHECKOUT = Path.of("/checkouts/repo");
  private static final Path FILE = CHECKOUT.resolve("mod/src/main/java/p/Widget.java");

  private static final String SOURCE = """
      package p;

      public final class Widget {

        private final int[] data;
        private int seen = 1;

        int dup() {
          return 1;
        }

        int dup(final int a) {
          return a + seen;
        }

        int dup(final int a, final int b) {
          return a + b;
        }

        int sum() {
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }
      }
      """;

  /// A type whose members cover every shape the `<clinit>` scan has to judge: a static
  /// initializer, a static field with an initializer, a static field without one, and an
  /// instance field with one.
  private static final String PARTS_SOURCE = """
      package p;

      public final class Parts {

        private final int[] data;
        private int seen = 1;
        static int total;
        static final java.util.function.IntUnaryOperator NEXT = x -> x + 1;

        static {
          total = 2;
        }

        Parts(final int[] data) {
          this.data = data;
        }

        int sum() {
          return data.length + total;
        }
      }
      """;

  private static final String ROOT_SOURCE = """
      package p;

      public final class Root {

        int go() {
          return 1;
        }
      }
      """;

  /// `row` reads the index and the paragraph, never a command.
  private static CommandRunner noCommands() {
    return (command, directory) -> {
      throw new IllegalStateException("no command belongs in this fixture: " + String.join(" ", command));
    };
  }

  private static HardeningCorpus.Module module() {
    return new HardeningCorpus.Module("mod", CHECKOUT.resolve("mod/config/pitest"), CHECKOUT.resolve("mod/src/main/java"));
  }

  private static ReadmeFamilies.Family family(final String paragraph, final List<String> bullets) {
    return new ReadmeFamilies.Family("x", "Section", paragraph, bullets, 7);
  }

  /// The 1-based line of the first fixture line containing `needle`.
  private static int lineOf(final String source, final String needle) {
    final var lines = source.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(needle)) {
        return i + 1;
      }
    }
    throw new IllegalArgumentException("no line of the fixture contains " + needle);
  }

  private static HardeningRow row(final String source, final Path file, final String baseline, final ReadmeFamilies.Family family) {
    final var corpus = new HardeningCorpus("repo", CHECKOUT, new GitRepo(CHECKOUT, noCommands()));
    return corpus.row("repo/mod", module(), BaselineRow.parse("alloc", baseline), family, TypeIndex.of(file, source));
  }

  private static HardeningRow row(final String baseline, final ReadmeFamilies.Family family) {
    return row(SOURCE, FILE, baseline, family);
  }

  private static List<String> names(final List<TypeIndex.Member> members) {
    return members.stream().map(TypeIndex.Member::name).toList();
  }

  private static void write(final Path file, final String content) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void theDeclarationSpanningTheLineHintIsShownFirstAndOnlyTwoBodiesAreShown() {
    final int first = lineOf(SOURCE, "int dup() {");
    final int second = lineOf(SOURCE, "int dup(final int a) {");
    final var built = row("p.Widget,dup,MathMutator,SURVIVED # x # line " + (second + 1), family("para", List.of("- one", "- two")));
    assertEquals("RESOLVED", built.memberStatus());
    assertEquals(3, built.declarations());
    assertEquals(2, built.bodiesShown(), "BODY_CAP bodies, then signatures");
    assertEquals(("""
        // Widget lines %d-%d
          int dup(final int a) {
            return a + seen;
          }

        // Widget lines %d-%d
          int dup() {
            return 1;
          }
        // also declared: int dup(final int a, final int b)""")
            .formatted(second, second + 2, first, first + 2),
        built.state().memberSource(),
        "the overload holding the hinted line comes first, the other body follows after a blank line, the third is a signature");
    final var facts = built.state().premiseFacts().toJson();
    assertTrue(facts.contains("\"declarations\":3,\"bodies_shown\":2,\"lines_total\":9,\"paragraph_bullets\":2"), facts);
    assertTrue(facts.contains("\"line_hint_inside_body\":true"), facts);
  }

  @Test
  void withoutALineHintTheDeclarationsKeepSourceOrderAndNoLineIsReported() {
    final int first = lineOf(SOURCE, "int dup() {");
    final var built = row("p.Widget,dup,MathMutator,SURVIVED # x", family("para", List.of()));
    assertNull(built.row().lineHint());
    assertTrue(built.state().memberSource().startsWith("// Widget lines " + first + "-" + (first + 2) + "\n  int dup() {"),
        built.state().memberSource());
    assertTrue(built.state().premiseFacts().toJson().contains("\"line_hint_inside_body\":null"),
        built.state().premiseFacts().toJson());
    assertTrue(built.state().row().toJson().contains("\"status\":\"SURVIVED\",\"label\":\"x\",\"line\":null"),
        built.state().row().toJson());
  }

  @Test
  void aLineHintOutsideEveryDeclarationSaysSo() {
    final var built = row("p.Widget,dup,MathMutator,SURVIVED # x # line " + lineOf(SOURCE, "package p;"), family("para", List.of()));
    assertTrue(built.state().premiseFacts().toJson().contains("\"line_hint_inside_body\":false"),
        "the hint is on the package line, inside no member: " + built.state().premiseFacts().toJson());
  }

  @Test
  void aBodyLongerThanTheLineCapIsCutWithACountOfWhatIsMissing() {
    final var text = new StringBuilder("package p;\n\npublic final class Big {\n\n  int big() {\n    int n = 0;\n");
    for (int i = 0; i < 200; i++) {
      text.append("    n += ").append(i).append(";\n");
    }
    text.append("    return n;\n  }\n}\n");
    final var source = text.toString();
    final int start = lineOf(source, "int big() {");
    final int end = lineOf(source, "return n;") + 1;
    final int lastShown = start + HardeningCorpus.BODY_LINE_CAP - 1;
    final var built = row(source, CHECKOUT.resolve("mod/src/main/java/p/Big.java"),
        "p.Big,big,MathMutator,SURVIVED # x # line " + start, family("para", List.of()));
    assertEquals(204, end - start + 1, "the fixture body is longer than BODY_LINE_CAP");
    assertTrue(built.state().memberSource().startsWith("// Big lines " + start + "-" + end + "\n  int big() {"),
        built.state().memberSource());
    assertTrue(built.state().memberSource().endsWith("    n += " + (lastShown - start - 2) + ";\n// … " + (end - lastShown)
            + " more lines not shown"),
        "the last shown line is BODY_LINE_CAP lines in, and the rest are counted: " + built.state().memberSource());
    assertTrue(built.state().premiseFacts().toJson().contains("\"bodies_shown\":1,\"lines_total\":" + (end - start + 1)),
        "lines_total counts the whole declaration, not what is shown: " + built.state().premiseFacts().toJson());
  }

  @Test
  void aParagraphIsTruncatedOnlyOnceItIsPastTheCap() {
    final var head = "The cap counts characters: ";
    final var atCap = head + "x".repeat(HardeningCorpus.PARAGRAPH_CHARS - head.length());
    final var hint = "p.Widget,sum,MathMutator,SURVIVED # x # line " + lineOf(SOURCE, "int sum() {");
    var built = row(hint, family(atCap, List.of()));
    assertEquals(HardeningCorpus.PARAGRAPH_CHARS, built.paragraphChars());
    assertEquals(atCap, built.state().paragraph(), "a paragraph exactly at the cap is shown whole");
    assertTrue(built.state().premiseFacts().toJson().contains("\"paragraph_truncated\":false"),
        built.state().premiseFacts().toJson());
    built = row(hint, family(atCap + "y", List.of()));
    assertEquals(atCap + " …", built.state().paragraph(), "one character more is cut at the cap and marked");
    assertEquals(HardeningCorpus.PARAGRAPH_CHARS + 2, built.paragraphChars(), "the mark is part of what is shown");
    assertTrue(built.state().premiseFacts().toJson().contains("\"paragraph_truncated\":true"),
        built.state().premiseFacts().toJson());
  }

  @Test
  void identifiersAreTheBacktickedNamesWorthCheckingAgainstTheBody() {
    final var paragraph = "The `data` array is the state and `NEXT` is elsewhere. `Widget` and `Widget.sum` are this row's own, "
        + "and `MathMutator`, `notes.csv`, `README.md`, `# x` and `Widget$Inner.f` are not names to look for.";
    final var built = row("p.Widget,sum,MathMutator,SURVIVED # x # line " + lineOf(SOURCE, "int sum() {"), family(paragraph, List.of()));
    assertEquals(List.of("NEXT"), built.identifiersMissing());
    assertTrue(built.state().premiseFacts().toJson().contains("\"identifiers_present\":[\"data\"],\"identifiers_missing\":[\"NEXT\"]"),
        "a mutator name, a file name, a label, a nested-class span, and the row's own class and method are all left out: "
            + built.state().premiseFacts().toJson());
  }

  @Test
  void aBaselineMethodCellResolvesToTheDeclarationsThatCanHoldIt() {
    final var index = TypeIndex.of(CHECKOUT.resolve("mod/src/main/java/p/Parts.java"), PARTS_SOURCE);
    final var type = index.byBinaryName("Parts");
    final var sum = HardeningCorpus.members(index, type, "sum");
    assertEquals(List.of("sum"), names(sum));
    assertEquals(sum, HardeningCorpus.members(index, type, "lambda$sum$0"), "a lambda is declared in the member it lives in");
    assertEquals(sum, HardeningCorpus.members(index, type, "lambda$sum"), "an index-less lambda name resolves the same way");
    assertEquals(List.of("<init>"), names(HardeningCorpus.members(index, type, "lambda$new$0")));
    assertEquals(HardeningCorpus.members(index, type, "<init>"), HardeningCorpus.members(index, type, "lambda$new$0"),
        "`lambda$new$N` is declared in a constructor");
    assertEquals(List.of("<clinit>", "NEXT"), names(HardeningCorpus.members(index, type, "lambda$static$0")),
        "the static initializer and the static fields that have one: `total` has no initializer and `seen` is not static");
    assertEquals(HardeningCorpus.members(index, type, "<clinit>"), HardeningCorpus.members(index, type, "lambda$static$0"),
        "a `<clinit>` cell and a static lambda resolve to the same declarations");
    assertEquals(List.of(), HardeningCorpus.members(index, type, "gone"));
  }

  @Test
  void aDeclarationThatIsNotAFieldIsNoStaticFieldInitializer() {
    // a head ending in the type's own name reads as a constructor to TypeIndex, and this one
    // carries both marks of a static field with an initializer
    final var source = """
        package p;

        public final class Shadow {

          static int PREV = 1, Shadow;
        }
        """;
    final var index = TypeIndex.of(CHECKOUT.resolve("mod/src/main/java/p/Shadow.java"), source);
    final var type = index.byBinaryName("Shadow");
    final var only = index.members(type).getFirst();
    assertEquals("<init>", only.name());
    assertTrue(only.signature().contains("static ") && only.signature().contains("="), only.signature());
    assertEquals(List.of(), HardeningCorpus.members(index, type, "lambda$static$0"), "the kind decides, not the text");
  }

  @Test
  void modulesComeFromTheIndexAndNeedAReadme(@TempDir final Path dir) throws Exception {
    // `git ls-files` output: a config directory at the repository root, one under a module,
    // one without a README, and the blank line a trailing newline leaves behind
    final var listing = "config/pitest/root-accepted.csv\nmod/config/pitest/mod-accepted.csv\nno-readme/config/pitest/x-accepted.csv\n\n";
    final var readme = "Declares `# fine`.\n";
    write(dir.resolve("config/pitest/README.md"), readme);
    write(dir.resolve("config/pitest/root-accepted.csv"), "p.Root,go,MathMutator,SURVIVED # fine # line 5\n");
    write(dir.resolve("src/main/java/p/Root.java"), ROOT_SOURCE);
    write(dir.resolve("mod/config/pitest/README.md"), readme);
    write(dir.resolve("mod/config/pitest/mod-accepted.csv"), "");
    write(dir.resolve("no-readme/config/pitest/x-accepted.csv"), "");
    final var corpus = new HardeningCorpus("repo", dir, new GitRepo(dir, (command, directory) -> listing));

    final var modules = corpus.modules();
    assertEquals(List.of("", "mod"), modules.stream().map(HardeningCorpus.Module::modulePath).toList(),
        "a config directory without a README is not a module, and one at the root has no module path");
    assertEquals(dir.resolve("src/main/java"), modules.getFirst().sourceRoot(), "the root module's sources are the checkout's");
    assertEquals(dir.resolve("mod/src/main/java"), modules.get(1).sourceRoot());

    final var rows = corpus.rows(modules.getFirst());
    assertEquals(1, rows.size());
    assertEquals("repo", rows.getFirst().module(), "a root module adds no path to the repository name");
    assertEquals("repo#root#Root.go#MathMutator#SURVIVED#5", rows.getFirst().id());
    assertEquals("RESOLVED", rows.getFirst().memberStatus());
    assertEquals("src/main/java/p/Root.java", rows.getFirst().state().filePath());
    assertEquals(List.of(), corpus.rows(modules.get(1)), "an empty baseline has no rows");
  }
}
