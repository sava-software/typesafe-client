package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.evals.rot.TypeIndex;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DocCommentAndMembersTests {

  private static final String SOURCE = """
      package p;

      /// Type doc.
      public final class Widget {

        /// Markdown doc
        ///   with an indented continuation
        ///and a marker with no space.
        public int documented(final int a, final List<String> b) {
          return a;
        }

        /**
         * Javadoc style.
         *
         * @param x the value
         */
        public void javadoc(int x) {
        }

        /* a plain block comment */
        public void plain() {
        }

        /// Not attached: a blank line follows.

        public void detached() {
        }

        /** one-liner */
        static final int CONSTANT = 1;

        public Widget(final int... xs) {
        }

        <T> T generic(final java.util.Map<String, List<T>> m, final int[] arr, byte b) {
          return null;
        }
      }
      """;

  @Test
  void docCommentsAttachOnlyWhenDirectlyAbove() {
    final var lines = SOURCE.lines().toList();
    final var markdown = DocComment.above(lines, 9);
    assertNotNull(markdown);
    assertEquals("markdown", markdown.style());
    assertEquals(6, markdown.startLine());
    assertEquals(8, markdown.endLine());
    assertEquals("Markdown doc\n  with an indented continuation\nand a marker with no space.", markdown.text());
    final var javadoc = DocComment.above(lines, 18);
    assertNotNull(javadoc);
    assertEquals("javadoc", javadoc.style());
    assertEquals(13, javadoc.startLine());
    assertEquals(17, javadoc.endLine());
    assertEquals("Javadoc style.\n\n@param x the value", javadoc.text());
    assertNull(DocComment.above(lines, 22), "a plain block comment is not documentation");
    assertNull(DocComment.above(lines, 27), "a blank line detaches the comment");
    final var oneLiner = DocComment.above(lines, 31);
    assertNotNull(oneLiner);
    assertEquals("one-liner", oneLiner.text());
    assertEquals(30, oneLiner.startLine());
    assertEquals(30, oneLiner.endLine());
    assertNull(DocComment.above(lines, 1), "nothing above the first line");
    assertNull(DocComment.above(List.of("/** open", "int x;"), 2), "an unterminated block is not a comment above line 2");
    assertNull(DocComment.above(List.of("x */", "int y;"), 2), "a close with no open runs off the top");
    final var onLineOne = DocComment.above(List.of("/// doc", "int x;"), 2);
    assertNotNull(onLineOne, "line 1 is directly above line 2");
    assertEquals("markdown", onLineOne.style());
    assertEquals(1, onLineOne.startLine());
    assertEquals(1, onLineOne.endLine());
    assertEquals("doc", onLineOne.text());
    final var runToLineOne = DocComment.above(List.of("/// one", "/// two", "int x;"), 3);
    assertNotNull(runToLineOne);
    assertEquals(1, runToLineOne.startLine(), "the run of markers stops at the first line of the file");
    assertEquals(2, runToLineOne.endLine());
    assertEquals("one\ntwo", runToLineOne.text());
    final var blockOnLineOne = DocComment.above(List.of("/** doc */", "int x;"), 2);
    assertNotNull(blockOnLineOne, "a javadoc block that opens on line 1 is documentation");
    assertEquals("javadoc", blockOnLineOne.style());
    assertEquals(1, blockOnLineOne.startLine());
    assertEquals(1, blockOnLineOne.endLine());
    assertEquals("doc", blockOnLineOne.text());
    final var blockFromLineOne = DocComment.above(List.of("/**", " * doc", " */", "int x;"), 4);
    assertNotNull(blockFromLineOne);
    assertEquals(1, blockFromLineOne.startLine());
    assertEquals(3, blockFromLineOne.endLine());
    assertEquals("doc", blockFromLineOne.text());
  }

  @Test
  void membersAreKeyedByTypeNameAndParameterTypes() {
    final var members = FileMembers.of(Path.of("p/Widget.java"), SOURCE);
    final var keys = members.keySet().stream().map(FileMembers.Key::toString).toList();
    assertEquals(List.of("Widget.documented(int, List<String>)", "Widget.javadoc(int)", "Widget.plain()", "Widget.detached()",
        "Widget.CONSTANT", "Widget.<init>(int...)", "Widget.generic(java.util.Map<String, List<T>>, int[], byte)"), keys,
        "a field has no parameter list; a parameterless method has an empty one");
    final var documented = members.get(new FileMembers.Key("Widget", "documented", "int, List<String>"));
    assertEquals("method", documented.kind());
    assertEquals("Markdown doc\n  with an indented continuation\nand a marker with no space.", documented.commentText());
    assertTrue(documented.body().startsWith("  public int documented("), documented.body());
    assertTrue(documented.body().endsWith("  }"), documented.body());
    assertEquals(9, documented.startLine());
    assertEquals(11, documented.endLine());
    assertNull(members.get(new FileMembers.Key("Widget", "plain", "")).commentText());
    assertEquals("one-liner", members.get(new FileMembers.Key("Widget", "CONSTANT", null)).commentText());
    assertNull(FileMembers.parameterTypes(new TypeIndex.Member("f", "field", 1, 1, "int f = 1")));
    assertNull(FileMembers.parameterTypes(new TypeIndex.Member("<clinit>", "initializer", 1, 1, "static")));
    assertEquals("", FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "void m()")));
    assertEquals("", FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "void m")), "no parenthesis at all");
    assertEquals("int", FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "void m(int")), "unterminated list");
    assertEquals("a", FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "void m(a)")), "a lone token is the type");
    assertEquals("int", FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "(int a)")),
        "a parameter list that opens at the first character of the head");
    assertEquals("@Size(min = 1) String",
        FileMembers.parameterTypes(new TypeIndex.Member("m", "method", 1, 1, "void m(@Size(min = 1) String s)")),
        "the list ends at the paren matching the opening one, not at the first close paren");
    assertEquals(List.of("a", " b<c, d>", " e(f, g)", " h[i]"), FileMembers.splitTopLevel("a, b<c, d>, e(f, g), h[i]"));
    assertEquals(List.of(""), FileMembers.splitTopLevel(""));
  }

  @Test
  void duplicateKeysKeepTheFirstDeclaration() {
    final var source = """
        class T {
          /// first
          void m(int a) {
          }
          /// second
          void m(int b) {
          }
        }
        """;
    final var members = FileMembers.of(Path.of("T.java"), source);
    assertEquals(1, members.size());
    assertEquals("first", members.values().iterator().next().commentText());
  }
}
