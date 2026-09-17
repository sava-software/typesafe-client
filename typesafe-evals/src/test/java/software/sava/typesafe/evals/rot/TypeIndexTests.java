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
    Files.writeString(root.resolve("module-info.java"), "module m {}\n");
    final var index = TypeIndex.scan(root);
    assertEquals(List.of("Outer", "Outer$Point", "Outer$Builder", "Outer$Kind", "Second"),
        index.types().stream().map(TypeIndex.TypeDecl::binaryName).toList());
    final var outer = index.byBinaryName("Outer");
    assertEquals("class", outer.kind());
    assertEquals(6, outer.declLine());
    assertEquals(file, outer.file());
    assertTrue(outer.header().startsWith("class Outer implements Runnable, Comparable<Outer>"), outer.header());
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
  }
}
