package software.sava.typesafe.evals.rot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.ModelCard;
import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;
import software.sava.typesafe.evals.jev.JevRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// End to end over a real temporary git repository: a snapshot commit, then edits that
/// remove one member and move another, a golden-fleet snapshot README naming them, and a
/// stub Jev that answers from the premise facts.
final class RotExperimentTests {

  private static final String V1 = """
      package p;

      /// Widget at the snapshot.
      public final class Widget {

        private final int[] data;

        public Widget(final int[] data) {
          this.data = data;
        }

        /// The fast path returns early for an empty array.
        public int sum() {
          if (data.length == 0) {
            return 0;
          }
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }

        public boolean gone() {
          return data == null;
        }

        static int helper(final int x) {
          return x + 1;
        }
      }
      """;

  private static final String V2 = """
      package p;

      /// Widget at HEAD: gone() removed, helper() moved to Util, the fast path kept.
      public final class Widget {

        private final int[] data;

        public Widget(final int[] data) {
          this.data = data;
        }

        /// The fast path returns early for an empty array.
        public int sum() {
          if (data.length == 0) {
            return 0;
          }
          int total = 0;
          for (final int d : data) {
            total += d;
          }
          return total;
        }
      }
      """;

  private static final String UTIL = """
      package p;

      final class Util {

        static int helper(final int x) {
          return x + 1;
        }
      }
      """;

  private static final String README = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Fast-path routing** `# fast-path`:
      - `Widget.sum`: removing the `data.length == 0` fast path routes the empty array
        through the loop, which sums nothing. Covered by `WidgetTests.sumsAnEmptyArray`.
      - `Widget.gone` (`NullReturnVals`): the only implementation checks `data` for null.
      - `Widget.helper` and `Widget.<init>`: trivial.
      - `Map.of` and `Nowhere.method` are not subjects.
      """;

  /// Delegates git to the real runner and answers gh with "public".
  private static CommandRunner commands() {
    return (command, directory) -> command.getFirst().equals("gh")
        ? "public\n"
        : ProcessCommandRunner.INSTANCE.run(command, directory);
  }

  /// Answers construct_absent when the facts say the member is not RESOLVED, else present.
  private static final class StubClient implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      final boolean absent = !state.contains("\"member_status\":\"RESOLVED\"");
      final var body = absent
          ? "{\"model\":\"jev-stub\",\"answers\":{\"construct\":{\"type\":\"choice\",\"choice\":\"construct_absent\",\"confidence\":0.9,\"probabilities\":{\"construct_present\":0.05,\"construct_absent\":0.9,\"cannot_resolve\":0.05}},\"contradicted\":{\"type\":\"noul\",\"noul\":0.1},\"depends_on_unseen\":{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"construct\":{\"type\":\"choice\",\"choice\":\"construct_present\",\"confidence\":0.85,\"probabilities\":{\"construct_present\":0.9,\"construct_absent\":0.05,\"cannot_resolve\":0.05}},\"contradicted\":{\"type\":\"noul\",\"noul\":0.05},\"depends_on_unseen\":{\"type\":\"noul\",\"noul\":0.2}},\"usage\":{\"input_tokens\":300,\"output_tokens\":5}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), "req_stub"));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static String git(final Path repo, final String... args) {
    final var command = new java.util.ArrayList<String>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    return ProcessCommandRunner.INSTANCE.run(command, null);
  }

  /// A checkout at `<checkouts>/repo` with module `mod`, a snapshot commit, and HEAD edits.
  private static String checkoutWithHistory(final Path checkouts) throws Exception {
    final var repo = checkouts.resolve("repo");
    final var src = repo.resolve("mod/src/main/java/p");
    final var test = repo.resolve("mod/src/test/java/p");
    Files.createDirectories(src);
    Files.createDirectories(test);
    Files.writeString(src.resolve("Widget.java"), V1);
    Files.writeString(test.resolve("WidgetTests.java"), "package p;\nfinal class WidgetTests {\n  void sumsAnEmptyArray() {\n  }\n}\n");
    git(repo, "init", "-q", "-b", "main");
    git(repo, "remote", "add", "origin", "git@github.com:test-org/repo.git");
    git(repo, "-c", "user.name=t", "-c", "user.email=t@x", "add", "-A");
    git(repo, "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-q", "-m", "snapshot");
    final var commit = git(repo, "rev-parse", "HEAD").strip();
    Files.writeString(src.resolve("Widget.java"), V2);
    Files.writeString(src.resolve("Util.java"), UTIL);
    git(repo, "-c", "user.name=t", "-c", "user.email=t@x", "add", "-A");
    git(repo, "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-q", "-m", "head");
    return commit;
  }

  private static Path goldenFleet(final Path dir, final String commit) throws Exception {
    final var fleet = dir.resolve("golden-fleet");
    final var snapshot = fleet.resolve("repo/mod");
    Files.createDirectories(snapshot);
    Files.writeString(snapshot.resolve("README.md"), README);
    Files.writeString(snapshot.resolve("fast-accepted.csv"), "p.Widget,sum,12,RemoveConditionalMutator_EQUAL_IF,SURVIVED # fast-path\n");
    Files.writeString(fleet.resolve("MANIFEST.txt"), "# comment\nrepo\tmod\t" + commit + "\nprivate-repo\tmod\t" + commit + "\n");
    return fleet;
  }

  @Test
  void resolvesMembersAgainstHeadAndClassifiesTheMissing(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    final var commit = checkoutWithHistory(checkouts);
    final var fleet = goldenFleet(dir, commit);
    final var out = dir.resolve("out");
    final var hints = dir.resolve("hints.tsv");
    Files.writeString(hints, "key\tlabel\nrepo/mod#Widget.sum\tpresent\nrepo/mod#Widget.gone\tabsent\nrepo/mod#Widget.helper\tabsent\n");

    final var config = RotExperiment.Config.parse(new String[]{"--manifest", fleet.resolve("MANIFEST.txt").toString(),
        "--golden-fleet", fleet.toString(), "--checkouts", checkouts.toString(), "--out", out.toString(), "--mode", "corpus",
        "--gold-hints", hints.toString()});
    final var summary = RotExperiment.run(config, null, commands());
    assertEquals(1, summary.modules());
    assertEquals(1, summary.skippedPrivate(), "the manifest names a repo with no checkout");
    assertEquals(5, summary.rows(), "sum, gone, helper, <init>, and Nowhere.method; Map.of is external");
    assertEquals(Map.of("RESOLVED", 2, "REMOVED_MEMBER", 1, "MOVED_MEMBER", 1, "MISSING_TYPE", 1), summary.byStatus());
    assertNull(summary.spend());

    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals("row_id\tmodule\trung\tsection\tmember\tstatus\tdetail\tcontrol_flags\tfile\tnote_chars\tsource_chars", rows.getFirst());
    assertEquals(6, rows.size());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.gone\tREMOVED_MEMBER\texisted at " + commit.substring(0, 7))), rows.toString());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.helper\tMOVED_MEMBER\tnow declared by [Util]\tmember_missing")), rows.toString());
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.sum\tRESOLVED\t1 declaration(s)\t\t")), "sum has no control flag: " + rows);
    assertTrue(rows.stream().anyMatch(l -> l.contains("\tWidget.<init>\tRESOLVED\t")), rows.toString());
    assertTrue(rows.stream().allMatch(l -> l.startsWith("row_id") || l.contains("\trepo/mod\t1\t")), "rung 1: two changed files");
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals(5, sheet.size(), "MISSING_TYPE rows are not scorable and stay off the sheet");
    assertTrue(sheet.getFirst().startsWith("row_id\tlabel\tnotes\t"));
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 1 | 1 | 5 |"), report);
    assertTrue(report.contains("| repo/mod (rung 1) | 4 | 2 | 2 |"), report);
    assertTrue(report.contains("Not scored (corpus mode)"), report);
  }

  @Test
  void scoresRowsAndComputesBarsAgainstHintsAndLabels(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    final var commit = checkoutWithHistory(checkouts);
    final var fleet = goldenFleet(dir, commit);
    final var out = dir.resolve("out");
    final var hints = dir.resolve("hints.tsv");
    Files.writeString(hints, "key\tlabel\nrepo/mod#Widget.sum\tpresent\nrepo/mod#Widget.gone\tabsent\nrepo/mod#Widget.helper\tabsent\nrepo/mod#Widget.<init>\tpresent\n");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new StubClient(), dir.resolve("rec")), 2);
    var config = RotExperiment.Config.parse(new String[]{"--manifest", fleet.resolve("MANIFEST.txt").toString(),
        "--golden-fleet", fleet.toString(), "--checkouts", checkouts.toString(), "--out", out.toString(),
        "--gold-hints", hints.toString(), "--labels", dir.resolve("labels.tsv").toString(), "--recordings", dir.resolve("rec").toString()});
    assertEquals("record", config.mode());
    final var summary = RotExperiment.run(config, runner, commands());
    assertEquals(new JevRunner.Totals(4, 4, 1200, 20, 0, 4), summary.spend());

    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals("row_id\tchoice\tp_absent\tp_present\tp_cannot\tconfidence\tcontradicted\tdepends_on_unseen\tcontrol_flags\tgold_hint", jev.getFirst());
    assertEquals(5, jev.size());
    assertTrue(jev.stream().anyMatch(l -> l.contains("Widget.gone\tconstruct_absent\t0.900\t") && l.endsWith("\tmember_missing\tabsent")), jev.toString());
    assertTrue(jev.stream().anyMatch(l -> l.contains("Widget.sum\tconstruct_present\t0.050\t") && l.endsWith("\t\tpresent")), jev.toString());
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Requests 4 (4 answered), input tokens 1200, cost $0.0001"), report);
    assertTrue(report.contains("## Bars against hand labels\n\nNo labeled and scored rows yet."), report);
    assertTrue(report.contains("## Bars against PROVISIONAL survey gold hints (4 hints"), report);
    assertTrue(report.contains("4 labeled rows. Control arm: flagged 2, true positives 2 of 2 rot rows (recall 1.000, precision 1.000)."), report);
    assertTrue(report.contains("| rot rows in the top 30% with no control flag | 0 | >= 3 | NO |"), report);
    assertTrue(report.contains("| recall of rot within the top 30% by P(absent) | 1.000 | >= 0.900 | yes |"), report);
    assertTrue(report.contains("## Top rows by P(construct_absent)"), report);
    assertTrue(report.indexOf("Widget.gone") < report.indexOf("Widget.sum", report.indexOf("## Top rows")), "rot ranks first");

    // hand labels take over the first table; replay needs no key
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    final var labeled = new StringBuilder(sheet.getFirst()).append('\n');
    for (final var line : sheet.subList(1, sheet.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = cells[0].contains("Widget.gone") || cells[0].contains("Widget.helper") ? "absent" : "present";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(dir.resolve("labels.tsv"), labeled.toString());
    config = RotExperiment.Config.parse(new String[]{"--manifest", fleet.resolve("MANIFEST.txt").toString(),
        "--golden-fleet", fleet.toString(), "--checkouts", checkouts.toString(), "--out", out.toString(), "--mode", "replay",
        "--labels", dir.resolve("labels.tsv").toString(), "--recordings", dir.resolve("rec").toString()});
    final var replayed = RotExperiment.run(config, null, commands());
    assertEquals(4, replayed.spend().hits());
    assertEquals(0, replayed.spend().misses());
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("## Bars against hand labels\n\n4 labeled rows."), report);
    assertTrue(report.contains("## Bars against PROVISIONAL survey gold hints (0 hints"), report);
    assertTrue(report.contains("gold \\ predicted"), report);
  }

  @Test
  void configValidation() {
    assertThrows(IllegalArgumentException.class, () -> RotExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> RotExperiment.Config.parse(new String[]{"--manifest", "m", "--golden-fleet", "g"}));
    assertThrows(IllegalArgumentException.class, () -> RotExperiment.Config.parse(new String[]{"manifest", "m"}));
    final var config = RotExperiment.Config.parse(new String[]{"--manifest", "m", "--golden-fleet", "g", "--checkouts", "c",
        "--mode", "replay", "--concurrency", "2", "--visibility-cache", "v.tsv", "--dangling"});
    assertEquals(Path.of("m"), config.manifest());
    assertEquals(Path.of("build/experiments/rot"), config.out());
    assertEquals(Path.of("build/experiments/rot/recordings"), config.recordings());
    assertEquals("replay", config.mode());
    assertEquals(2, config.concurrency());
    assertNull(config.labels());
    assertNull(config.goldHints());
    assertEquals(Path.of("v.tsv"), config.visibilityCache());
    final var replay = RotExperiment.runnerFor(config);
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, replay.client().mode());
  }

  @Test
  void manifestParsing(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("MANIFEST.txt");
    Files.writeString(file, "# header\n\nsava\tsava-core\tabc\nravina\travina-kms/core\tdef\n");
    final var manifest = Manifest.read(file);
    assertEquals(2, manifest.entries().size());
    final var kms = manifest.entries().get(1);
    assertEquals("ravina/ravina-kms__core", kms.snapshotDir());
    assertEquals("ravina-kms/core/src/main/java", kms.sourceRoot());
    assertEquals("ravina-kms/core/src/test/java", kms.testRoot());
    assertEquals("ravina/ravina-kms/core", kms.id());
    Files.writeString(file, "sava\tsava-core\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> Manifest.read(file)).getMessage().contains("line 1"));
    assertThrows(java.io.UncheckedIOException.class, () -> Manifest.read(dir.resolve("absent")));
    assertEquals(0, RotCorpus.rung(0));
    assertEquals(1, RotCorpus.rung(2));
    assertEquals(2, RotCorpus.rung(3));
    assertEquals(2, RotCorpus.rung(12));
    assertEquals(3, RotCorpus.rung(13));
  }
}
