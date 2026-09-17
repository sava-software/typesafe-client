package software.sava.typesafe.evals.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TsvTests {

  @Test
  void rendersHeaderAndFlattenedCells(@TempDir final Path dir) throws Exception {
    final var tsv = new Tsv("id", "text", "score")
        .row("r1", "line one\nline\ttwo\r", 0.5)
        .row(java.util.Arrays.asList("r2", "", null));
    assertEquals(2, tsv.size());
    assertEquals(List.of("id", "text", "score"), tsv.header());
    assertEquals("id\ttext\tscore\nr1\tline one line two \t0.5\nr2\t\t\n", tsv.render());
    final var file = dir.resolve("out/sheet.tsv");
    tsv.write(file);
    assertEquals(tsv.render(), Files.readString(file));
  }

  @Test
  void rowsMustMatchTheHeader() {
    final var tsv = new Tsv("a", "b");
    assertThrows(IllegalArgumentException.class, () -> tsv.row("only one"));
    assertThrows(IllegalArgumentException.class, () -> new Tsv(List.of()));
    assertEquals("a\tb\n\tx\n", new Tsv("a", "b").row((Object) null, "x").render(), "varargs rows accept null cells");
    assertEquals("", Tsv.cell(null));
    assertEquals("x y", Tsv.cell("x\ny"));
  }
}
