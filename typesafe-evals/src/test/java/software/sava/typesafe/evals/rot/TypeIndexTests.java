package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TypeIndexTests {

  static final String SOURCE = """
      package p;

      import java.util.List;

      /// A doc with a brace { and a class keyword.
      public final class Outer implements Runnable, Comparable<Outer> {

        static final String TEXT = "a { brace in a string";
        static final char OPEN = '{';
        static final java.util.function.BiFunction<Outer, Outer, Outer> MERGE = (a, b) -> a == null ? b : a;
        private int count;

        static {
          System.out.println("static init { with brace");
        }

        public Outer() {
          this(0);
        }

        Outer(final int count) {
          this.count = count; // a } in a comment
        }

        @Override
        public void run() {
          if (count > 0) {
            count--;
          }
        }

        public int run(final int times) {
          return times;
        }

        public <T> List<T> generic(final List<? extends T> in, final int[] extra) {
          return List.of();
        }

        /* block comment with class Fake { */
        record Point(int x, int y) {
          Point {
            if (x < 0) {
              throw new IllegalArgumentException();
            }
          }

          static Point origin() {
            return new Point(0, 0);
          }
        }

        interface Builder {
          default Builder clock(final Object clock) {
            return this;
          }

          Builder build();
        }

        enum Kind {
          A, B;

          Kind next() {
            return this == A ? B : A;
          }
        }

        @Override
        public int compareTo(final Outer o) {
          final var text = \"\"\"
              a text block { with braces }
              \"\"\";
          return text.length();
        }
      }

      class Second {
        void second() {
        }
      }
      """;

  @Test
  void typesAreIndexedWithNestingAndRanges(@TempDir final Path dir) throws Exception {
    final var root = dir.resolve("src/main/java");
    final var file = root.resolve("p/Outer.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, SOURCE);
    Files.writeString(root.resolve("module-info.java"), "module m {\n}\nenum Hidden {\n  A;\n}\n");
    final var index = TypeIndex.scan(root);
    assertEquals(List.of("Outer", "Outer$Point", "Outer$Builder", "Outer$Kind", "Second"),
        index.types().stream().map(TypeIndex.TypeDecl::binaryName).toList());
    final var outer = index.byBinaryName("Outer");
    assertEquals("class", outer.kind());
    assertEquals(6, outer.declLine());
    assertEquals(file, outer.file());
    assertTrue(outer.header().startsWith("class Outer implements Runnable, Comparable<Outer>"), outer.header());
    assertFalse(outer.isRecord(), "a class is not a record");
    assertNull(index.byBinaryName("Hidden"), "module-info.java is skipped whatever text it holds");
    assertEquals(List.of(), index.bySimpleName("Hidden"));
    final var point = index.byBinaryName("Outer$Point");
    assertTrue(point.isRecord());
    assertEquals("record Point(int x, int y)", point.header());
    assertEquals(List.of(point), index.bySimpleName("Point"));
    assertEquals(List.of(), index.bySimpleName("Fake"), "a class keyword inside a comment declares nothing");
    assertNull(index.byBinaryName("Fake"));
    assertEquals("Second", index.byBinaryName("Second").simpleName());
    assertTrue(index.byBinaryName("Second").declLine() > outer.endLine());
    assertEquals(TypeIndex.scan(dir.resolve("absent")).types(), List.of());
  }

  @Test
  void membersAreDeclaredDirectlyInTheirType() {
    final var index = TypeIndex.of(Path.of("Outer.java"), SOURCE);
    final var outer = index.byBinaryName("Outer");
    final var names = index.members(outer).stream().map(m -> m.kind() + ':' + m.name()).toList();
    assertEquals(List.of("field:TEXT", "field:OPEN", "field:MERGE", "field:count", "initializer:<clinit>",
        "ctor:<init>", "ctor:<init>", "method:run", "method:run", "method:generic", "method:compareTo"), names,
        "nested types' members are not the outer type's");
    final var runs = index.members(outer, "run");
    assertEquals(2, runs.size());
    assertEquals("public void run()", runs.get(0).signature());
    assertEquals(25, runs.get(0).startLine(), "a member starts at its first annotation");
    assertEquals(30, runs.get(0).endLine());
    assertEquals(6, runs.get(0).length());
    assertEquals("public int run(final int times)", runs.get(1).signature());
    final var merge = index.members(outer, "MERGE").getFirst();
    assertEquals("field", merge.kind());
    assertTrue(merge.signature().startsWith("static final java.util.function.BiFunction<Outer, Outer, Outer> MERGE = (a, b) ->"), merge.signature());
    assertEquals(10, merge.startLine());
    final var ctors = index.members(outer, "<init>");
    assertEquals(2, ctors.size());
    assertEquals("Outer(final int count)", ctors.get(1).signature());
    final var compareTo = index.members(outer, "compareTo").getFirst();
    assertEquals(69, compareTo.startLine());
    assertEquals(75, compareTo.endLine(), "a text block's braces do not open scopes");
    final var point = index.byBinaryName("Outer$Point");
    assertEquals(List.of("ctor:<init>", "method:origin"), index.members(point).stream().map(m -> m.kind() + ':' + m.name()).toList());
    final var builder = index.byBinaryName("Outer$Builder");
    assertEquals(List.of("method:clock", "method:build"), index.members(builder).stream().map(m -> m.kind() + ':' + m.name()).toList());
    assertEquals("Builder build()", index.members(builder, "build").getFirst().signature(), "an abstract method ends at its semicolon");
    final var kind = index.byBinaryName("Outer$Kind");
    assertEquals(List.of("field:B", "method:next"), index.members(kind).stream().map(m -> m.kind() + ':' + m.name()).toList(),
        "enum constants read as one field statement named by the last constant");
    assertEquals(List.of("Outer", "Outer$Point"), index.typesDeclaring("<init>").stream().map(TypeIndex.TypeDecl::binaryName).toList());
    assertEquals(List.of(), index.typesDeclaring("nope"));
    assertEquals(List.of(), index.members(outer, "nope"));
    assertEquals("    return times;", outer.line(33));
    assertEquals("    if (count > 0) {\n      count--;", outer.slice(27, 28));
    assertEquals("ctor", index.members(point, "<init>").getFirst().kind(), "a compact record constructor");
    assertEquals(42, index.members(point, "<init>").getFirst().startLine());
  }

  @Test
  void maskingAndBraceMatching() {
    final var masked = TypeIndex.mask("a \"x{\" b // c {\nd /* { */ e '{' f \"\"\"\n{\n\"\"\" g");
    assertEquals("a      b       \nd         e     f    \n \n    g", masked);
    assertEquals(masked.length(), "a \"x{\" b // c {\nd /* { */ e '{' f \"\"\"\n{\n\"\"\" g".length(), "same length, same offsets");
    assertEquals("x \"esc\\\"aped\" y".length(), TypeIndex.mask("x \"esc\\\"aped\" y").length());
    assertEquals("x             y", TypeIndex.mask("x \"esc\\\"aped\" y"), "an escaped quote does not end the literal");
    assertEquals("q      ", TypeIndex.mask("q \"open"), "an unterminated literal runs to the end of its line");
    assertEquals("r      ", TypeIndex.mask("r /* op"), "an unterminated comment runs to the end");
    assertEquals(6, TypeIndex.matchingBrace("{ { } }", 0));
    assertEquals(4, TypeIndex.matchingBrace("{ { } }", 2));
    assertEquals(4, TypeIndex.matchingBrace("{ { }", 0), "unbalanced text ends at its last character");
    final var starts = TypeIndex.lineStarts("ab\ncd\n\nef");
    assertArrayEquals(new int[]{0, 3, 6, 7}, starts);
    assertEquals(1, TypeIndex.lineOf(starts, 0));
    assertEquals(1, TypeIndex.lineOf(starts, 2));
    assertEquals(2, TypeIndex.lineOf(starts, 3));
    assertEquals(3, TypeIndex.lineOf(starts, 6));
    assertEquals(4, TypeIndex.lineOf(starts, 8));
  }

  @Test
  void declarationHeadsAreClassified() {
    assertNull(TypeIndex.member("   ", "T", 1, 2));
    assertEquals("initializer", TypeIndex.member(" static ", "T", 1, 2).kind());
    assertEquals("<clinit>", TypeIndex.member("static", "T", 1, 2).name());
    final var ctor = TypeIndex.member("@Deprecated public T(final int x)", "T", 3, 9);
    assertEquals("ctor", ctor.kind());
    assertEquals("<init>", ctor.name());
    assertEquals("public T(final int x)", ctor.signature());
    assertEquals(3, ctor.startLine());
    assertEquals(9, ctor.endLine());
    final var method = TypeIndex.member("@SuppressWarnings(\"x\")\n  public static <T> T of(final T t)", "T", 1, 1);
    assertEquals("method", method.kind());
    assertEquals("of", method.name());
    assertEquals("public static <T> T of(final T t)", method.signature());
    final var field = TypeIndex.member("private final int count", "T", 1, 1);
    assertEquals("field", field.kind());
    assertEquals("count", field.name());
    final var initialized = TypeIndex.member("static final Runnable R = () -> run()", "T", 1, 1);
    assertEquals("field", initialized.kind());
    assertEquals("R", initialized.name());
    assertNull(TypeIndex.member("if (x)", "T", 1, 1), "a statement keyword is not a member");
    assertNull(TypeIndex.member("synchronized (lock)", "T", 1, 1));
    assertEquals("<init>", TypeIndex.member("public T", "T", 1, 1).name(), "compact record constructor");
    assertEquals("<init>", TypeIndex.member("T", "T", 1, 1).name());
    assertNull(TypeIndex.member("1 +", "T", 1, 1), "not an identifier");
    assertNull(TypeIndex.member("(int) count", "T", 1, 1), "nothing names the '(', so the head declares nothing");
    assertNull(TypeIndex.member("= f(x)", "T", 1, 1), "a head that begins with '=' declares nothing, call or not");
    assertNull(TypeIndex.member("= x", "T", 1, 1));
    assertEquals("m", TypeIndex.member("int m() = 0", "T", 1, 1).name(),
        "the '(' comes before the '=', so the head is read as a call and named by it");
    assertEquals("method", TypeIndex.member("int m() = 0", "T", 1, 1).kind());
    final var lone = TypeIndex.member("A", "Kind", 1, 1);
    assertEquals("field", lone.kind(), "a lone enum constant is a one-word field declaration");
    assertEquals("A", lone.name());
  }

  @Test
  void maskingRunsToEachDelimitersEnd() {
    assertEquals("a /", TypeIndex.mask("a /"), "a slash at the very end starts nothing");
    assertEquals("a / b * c", TypeIndex.mask("a / b * c"), "division and multiplication open no comment");
    assertEquals("a */ b", TypeIndex.mask("a */ b"), "a comment end that closes nothing masks nothing");
    assertEquals("x     ", TypeIndex.mask("x // y"), "a line comment with no newline after it runs to the end");
    assertEquals("a      ", TypeIndex.mask("a /*/ b"), "the slash of /*/ cannot also close the comment it opened");
    assertEquals("x        ", TypeIndex.mask("x \"\"\"open"), "an unterminated text block runs to the end");
    assertEquals("    \n ", TypeIndex.mask("\"\"\"a\nb"),
        "an unterminated text block runs past its newline, unlike a string literal");
    assertEquals("    ", TypeIndex.mask("/* x"), "an unterminated comment masks the text from its start");
    assertEquals("x      \ny", TypeIndex.mask("x \"open\ny"), "an unterminated literal stops at its newline");
    assertEquals("c      d", TypeIndex.mask("c '\\'' d"), "an escaped quote does not end a char literal");
    assertEquals("s       t", TypeIndex.mask("s \"i'm\" t"), "an apostrophe inside a string starts no char literal");
    assertEquals(0, TypeIndex.firstNonBlank("  x", 0, 2),
        "a wholly blank region reports its own start, not the character after it");
    assertEquals(1, TypeIndex.firstNonBlank("x  ", 1, 3), "the start reported is the region's own, not zero");
    assertEquals(2, TypeIndex.firstNonBlank("  x ", 0, 4));
  }

  @Test
  void truncatedSourcesEndAtTheirLastCharacter() {
    assertEquals(List.of(), TypeIndex.of(Path.of("Header.java"), "class Foo\n").types(),
        "a type keyword with no brace after it opens no type");
    final var index = TypeIndex.of(Path.of("Unclosed.java"), "class A {\n  class B {\n    void b() {\n    }\n");
    assertEquals(List.of("A", "B"), index.types().stream().map(TypeIndex.TypeDecl::binaryName).toList(),
        "both unclosed types end at the last character, so neither range contains the other");
    final var a = index.byBinaryName("A");
    assertEquals(4, a.endLine());
    assertEquals(List.of("field:B"), index.members(a).stream().map(m -> m.kind() + ':' + m.name()).toList(),
        "an unclosed nested type is not blanked out of its outer body, so its head reads as a field");
    assertEquals(2, index.members(a).getFirst().startLine());
    assertEquals(List.of("method:b"), index.members(index.byBinaryName("B")).stream().map(m -> m.kind() + ':' + m.name()).toList());
    final var cut = TypeIndex.of(Path.of("Cut.java"), "class T {\n  void m();");
    assertEquals(List.of("T"), cut.types().stream().map(TypeIndex.TypeDecl::binaryName).toList());
    assertEquals(List.of(), cut.members(cut.byBinaryName("T")),
        "the last character of a truncated file closes the type, so it ends no declaration");
  }

  @Test
  void bracesAndSemicolonsInsideParenthesesAreNotDeclarations() {
    final var index = TypeIndex.of(Path.of("T.java"), """
        class T {
          static final Runnable TASK = wrap(() -> { step(); });

          {
            init();
          }
          ;

          void m() {
          }
        }
        """);
    final var type = index.byBinaryName("T");
    assertEquals(List.of("field:TASK", "method:m"), index.members(type).stream().map(m -> m.kind() + ':' + m.name()).toList(),
        "an initializer block and a stray semicolon declare nothing");
    final var task = index.members(type, "TASK").getFirst();
    assertEquals("static final Runnable TASK = wrap(() -> { step(); })", task.signature(),
        "a brace and a semicolon nested in parentheses neither open a body nor end the field");
    assertEquals(2, task.startLine());
    assertEquals(2, task.endLine());
    assertEquals(9, index.members(type, "m").getFirst().startLine());
  }
}
