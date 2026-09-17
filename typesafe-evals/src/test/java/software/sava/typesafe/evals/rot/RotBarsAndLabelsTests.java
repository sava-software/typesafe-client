package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.SystemOneResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RotBarsAndLabelsTests {

  static RotScore score(final double pPresent, final double pAbsent, final double pCannot, final double confidence) {
    final var choice = pAbsent >= pPresent && pAbsent >= pCannot ? "construct_absent" : pPresent >= pCannot ? "construct_present" : "cannot_resolve";
    final var body = String.format(java.util.Locale.ROOT, """
        {"model":"m","answers":{"construct":{"type":"choice","choice":"%s","confidence":%.3f,"probabilities":{"construct_present":%.3f,"construct_absent":%.3f,"cannot_resolve":%.3f}},"contradicted":{"type":"noul","noul":0.1},"depends_on_unseen":{"type":"noul","noul":0.2}}}""",
        choice, confidence, pPresent, pAbsent, pCannot);
    return RotScore.of(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), null));
  }

  private static RotBars.Row row(final String id, final String gold, final int rung, final boolean flag, final RotScore score) {
    return new RotBars.Row(id, gold, rung, flag, score);
  }

  @Test
  void rotScoreReadsTheChoice() {
    final var absent = score(0.1, 0.8, 0.1, 0.75);
    assertEquals("construct_absent", absent.choice());
    assertEquals(0.8, absent.pAbsent());
    assertEquals(0.1, absent.pPresent());
    assertEquals(0.1, absent.pCannot());
    assertEquals(0.75, absent.confidence());
    assertEquals(0.1, absent.contradicted());
    assertEquals(0.2, absent.dependsOnUnseen());
    assertTrue(absent.saysAbsent());
    assertFalse(absent.confidentlyPresent(0.5));
    final var present = score(0.9, 0.05, 0.05, 0.8);
    assertFalse(present.saysAbsent());
    assertTrue(present.confidentlyPresent(0.8));
    assertFalse(present.confidentlyPresent(0.81));
  }

  @Test
  void barsOverALabeledSet() {
    final var rows = List.of(
        row("r1", "absent", 3, true, score(0.1, 0.85, 0.05, 0.8)),
        row("r2", "absent", 3, false, score(0.2, 0.7, 0.1, 0.6)),
        row("r3", "absent", 2, false, score(0.3, 0.6, 0.1, 0.5)),
        row("r4", "absent", 3, false, score(0.85, 0.1, 0.05, 0.85)),
        row("r5", "present", 0, false, score(0.9, 0.05, 0.05, 0.9)),
        row("r6", "present", 0, false, score(0.3, 0.6, 0.1, 0.4)),
        row("r7", "present", 3, false, score(0.5, 0.4, 0.1, 0.3)),
        row("r8", "cannot", 1, false, score(0.2, 0.2, 0.6, 0.5)),
        row("r9", "present", 0, false, score(0.95, 0.03, 0.02, 0.95)),
        row("r10", "present", 1, false, score(0.9, 0.05, 0.05, 0.9))
    );
    assertEquals(List.of("r1", "r2", "r3", "r6", "r7", "r8", "r4", "r10", "r5", "r9"),
        RotBars.ranked(rows).stream().map(RotBars.Row::id).toList(), "ties by P(absent) break on id");
    // top 30% of 10 = 3 rows: r1, r2, r3, all rot -> 3 of 4 rot rows
    assertEquals(0.75, RotBars.recallWithinTop(rows));
    assertEquals(List.of("r2", "r3"), RotBars.caughtOnlyByJev(rows).stream().map(RotBars.Row::id).toList());
    assertEquals(List.of("r4"), RotBars.confidentlyWrong(rows).stream().map(RotBars.Row::id).toList());
    assertEquals(List.of("r6"), RotBars.falseAlarms(rows).stream().map(RotBars.Row::id).toList());
    final var control = RotBars.controlArm(rows);
    assertEquals(new RotBars.ControlArm(1, 1, 4), control);
    assertEquals(0.25, control.recall());
    assertEquals(1.0, control.precision());
    assertEquals(0.0, new RotBars.ControlArm(0, 0, 0).recall());
    assertEquals(0.0, new RotBars.ControlArm(0, 0, 0).precision());
    final var checks = RotBars.checks(rows);
    assertEquals(4, checks.size());
    assertFalse(checks.get(0).pass(), "recall 0.75 < 0.9");
    assertFalse(checks.get(1).pass(), "2 catches < 3");
    assertFalse(checks.get(2).pass(), "r4 is a confident wrong present");
    assertTrue(checks.get(3).pass(), "one false alarm is allowed");
    assertFalse(RotBars.keep(rows));
    final var confusion = RotBars.confusion(rows);
    assertEquals(3, confusion.count("construct_absent", "construct_absent"));
    assertEquals(1, confusion.count("construct_absent", "construct_present"));
    assertEquals(1, confusion.count("cannot_resolve", "cannot_resolve"));
    assertEquals(1, confusion.count("construct_present", "construct_absent"), "r6 only; r7's top choice is present");
    assertEquals(4, confusion.count("construct_present", "construct_present"));
  }

  @Test
  void aCleanSetKeeps() {
    final var rows = List.of(
        row("a1", "absent", 3, false, score(0.05, 0.9, 0.05, 0.9)),
        row("a2", "absent", 3, false, score(0.1, 0.85, 0.05, 0.85)),
        row("a3", "absent", 2, false, score(0.1, 0.8, 0.1, 0.7)),
        row("p1", "present", 0, false, score(0.9, 0.05, 0.05, 0.9)),
        row("p2", "present", 0, false, score(0.9, 0.05, 0.05, 0.9)),
        row("p3", "present", 3, false, score(0.7, 0.2, 0.1, 0.6)),
        row("p4", "present", 1, false, score(0.8, 0.1, 0.1, 0.7)),
        row("p5", "present", 0, false, score(0.8, 0.1, 0.1, 0.7)),
        row("p6", "present", 2, false, score(0.8, 0.1, 0.1, 0.7)),
        row("p7", "present", 0, false, score(0.8, 0.1, 0.1, 0.7))
    );
    assertEquals(1.0, RotBars.recallWithinTop(rows));
    assertEquals(3, RotBars.caughtOnlyByJev(rows).size());
    assertTrue(RotBars.confidentlyWrong(rows).isEmpty());
    assertTrue(RotBars.falseAlarms(rows).isEmpty());
    assertTrue(RotBars.checks(rows).stream().allMatch(RotBars.Check::pass), RotBars.checks(rows).toString());
    assertTrue(RotBars.keep(rows));
    assertEquals(List.of(), RotBars.ranked(List.of()));
    assertEquals(1.0, RotBars.recallWithinTop(List.of()), "nothing to miss");
  }

  @Test
  void labelsReadBothSheets(@TempDir final Path dir) throws Exception {
    final var sheet = dir.resolve("labels.tsv");
    Files.writeString(sheet, """
        row_id\tlabel\tnotes\tmodule
        m#1#A.b\tabsent\t\tm
        m#2#C.d\t\t\tm
        m#3#E.f\t Present \t\tm
        m#4#G.h\tcannot\t\tm
        short
        """);
    final var labels = RotLabels.read(sheet, "row_id");
    assertEquals(3, labels.size());
    assertEquals("absent", labels.get("m#1#A.b"));
    assertEquals("present", labels.get("m#3#E.f"));
    assertEquals("cannot", labels.get("m#4#G.h"));
    assertNull(labels.get("m#2#C.d"));
    assertThrows(UnsupportedOperationException.class, () -> labels.byKey().put("x", "y"));
    final var hints = dir.resolve("hints.tsv");
    Files.writeString(hints, "key\tlabel\tsource\nsava/sava-core#Base58.limbsLength\tpresent\tG1\n");
    assertEquals(Map.of("sava/sava-core#Base58.limbsLength", "present"), RotLabels.read(hints, "key").byKey());
    Files.writeString(hints, "key\tlabel\nx\tgone\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> RotLabels.read(hints, "key")).getMessage().contains("line 2"));
    Files.writeString(hints, "row_id\tlabel\nx\tabsent\n");
    assertThrows(IllegalArgumentException.class, () -> RotLabels.read(hints, "key"), "the key column must exist");
    Files.writeString(hints, "key\tvalue\nx\tabsent\n");
    assertThrows(IllegalArgumentException.class, () -> RotLabels.read(hints, "key"), "the label column must exist");
    Files.writeString(hints, "");
    assertThrows(IllegalArgumentException.class, () -> RotLabels.read(hints, "key"));
    assertThrows(java.io.UncheckedIOException.class, () -> RotLabels.read(dir.resolve("absent.tsv"), "key"));
  }
}
