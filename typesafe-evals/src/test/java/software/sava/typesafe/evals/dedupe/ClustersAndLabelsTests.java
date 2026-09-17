package software.sava.typesafe.evals.dedupe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ClustersAndLabelsTests {

  @Test
  void unionFindGroupsConnectedFindings() {
    final var clusters = new Clusters();
    clusters.add("a");
    clusters.add("b");
    clusters.add("c");
    clusters.add("d");
    clusters.union("a", "b");
    clusters.union("c", "b");
    clusters.union("a", "a");
    clusters.union("b", "c");
    clusters.union("e", "f");
    assertEquals("a", clusters.find("c"), "the first-added member stays the root");
    assertEquals("a", clusters.find("a"));
    assertEquals("e", clusters.find("f"));
    assertEquals(List.of(List.of("a", "b", "c"), List.of("d"), List.of("e", "f")), clusters.clusters());
    assertEquals(Map.of("a", 3, "d", 1, "e", 2), clusters.sizes());
    assertEquals(List.of("a", "d", "e"), List.copyOf(clusters.sizes().keySet()));
    clusters.union("f", "d");
    assertEquals("d", clusters.find("e"), "d was added before e, so d becomes the root of the merged component");
    assertEquals(List.of(List.of("a", "b", "c"), List.of("d", "e", "f")), clusters.clusters());
    clusters.add("a");
    assertEquals(2, clusters.clusters().size(), "re-adding an id changes nothing");
    assertEquals(List.of(), new Clusters().clusters());
    assertThrows(NullPointerException.class, () -> new Clusters().find("unknown"));
  }

  @Test
  void labelsReadTheSheetInPlace(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, """
        pair_id\tlabel\tneeded_source\tnotes\ttext_a
        p1\t2\ty\tsame thing\tx
        p2\t\t\t\tunlabeled
        p3\t 0 \tn\t\tx
        short
        p4\t1\t\t\tx
        """);
    final var labels = Labels.read(file);
    assertEquals(3, labels.size());
    assertEquals(2, labels.get("p1"));
    assertEquals(0, labels.get("p3"));
    assertEquals(1, labels.get("p4"));
    assertNull(labels.get("p2"));
    assertNull(labels.get("missing"));
    assertThrows(UnsupportedOperationException.class, () -> labels.byPairId().put("x", 1));
    // the columns may sit anywhere, including label first
    Files.writeString(file, "label\tpair_id\n2\tq1\n");
    assertEquals(2, Labels.read(file).get("q1"));
  }

  @Test
  void labelsRejectBadSheets(@TempDir final Path dir) throws Exception {
    final var noColumns = dir.resolve("a.tsv");
    Files.writeString(noColumns, "id\tvalue\np1\t2\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> Labels.read(noColumns)).getMessage().contains("pair_id and label"));
    final var noLabel = dir.resolve("a2.tsv");
    Files.writeString(noLabel, "pair_id\tvalue\np1\t2\n");
    assertThrows(IllegalArgumentException.class, () -> Labels.read(noLabel));
    final var noId = dir.resolve("a3.tsv");
    Files.writeString(noId, "id\tlabel\np1\t2\n");
    assertThrows(IllegalArgumentException.class, () -> Labels.read(noId));
    final var empty = dir.resolve("b.tsv");
    Files.writeString(empty, "");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> Labels.read(empty)).getMessage().contains("is empty"));
    final var bad = dir.resolve("c.tsv");
    Files.writeString(bad, "pair_id\tlabel\np1\tsame\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> Labels.read(bad)).getMessage().contains("line 2"));
    final var range = dir.resolve("d.tsv");
    Files.writeString(range, "pair_id\tlabel\np1\t0\np2\t3\n");
    final var tooBig = assertThrows(IllegalArgumentException.class, () -> Labels.read(range));
    assertTrue(tooBig.getMessage().contains("line 3"), tooBig.getMessage());
    assertTrue(tooBig.getMessage().contains("not 0, 1, or 2"));
    final var negative = dir.resolve("e.tsv");
    Files.writeString(negative, "pair_id\tlabel\np1\t-1\n");
    assertThrows(IllegalArgumentException.class, () -> Labels.read(negative));
    assertThrows(java.io.UncheckedIOException.class, () -> Labels.read(dir.resolve("absent.tsv")));
  }
}
