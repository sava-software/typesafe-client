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

import java.io.IOException;
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

  /// Answers construct_absent when the facts say the member is not RESOLVED, else present;
  /// a request whose state contains `failWhenStateContains` fails instead of answering.
  private static final class StubClient implements TypeSafeClient {

    private final String failWhenStateContains;

    StubClient() {
      this(null);
    }

    StubClient(final String failWhenStateContains) {
      this.failWhenStateContains = failWhenStateContains;
    }

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      if (failWhenStateContains != null && state.contains(failWhenStateContains)) {
        return CompletableFuture.failedFuture(new IllegalStateException("no answer for this row"));
      }
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
    assertThrows(IllegalArgumentException.class, () -> RotExperiment.Config.parse(new String[]{"--manifest", "m",
        "--golden-fleet", "g", "--checkouts", "c", "stray", "value"}), "a pair whose name is not an option is rejected");
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

    final var recording = RotExperiment.Config.parse(new String[]{"--manifest", "m", "--golden-fleet", "g",
        "--checkouts", "c", "--recordings", "rec"});
    assertEquals("record", recording.mode());
    try {
      assertEquals(RecordingTypeSafeClient.Mode.RECORD, RotExperiment.runnerFor(recording).client().mode(),
          "any mode but replay records over the live API");
    } catch (final IllegalStateException noKey) {
      // no key here, so the live client cannot be built; the point is that the fallback is
      // never a replay-only client that would silently answer from an old recording
      assertTrue(noKey.getMessage().contains(TypeSafeClient.API_KEY_ENV), noKey.getMessage());
    }
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

  // A second fixture: seven manifest entries over three repositories, with git scripted
  // through the CommandRunner seam instead of run. Nothing here needs a process, so a
  // module's rung, its snapshot tree, and a repository's visibility are all inputs.

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  private static final String ALPHA_HEAD = """
      package p;

      /// Alpha at HEAD: dropped() is gone and help() lives in the sibling module.
      final class Alpha {

        private final int[] data;

        Alpha(final int[] data) {
          this.data = data;
        }

        /// The fast path returns early for an empty array.
        int total() {
          if (data.length == 0) {
            return 0;
          }
          int sum = 0;
          for (final int d : data) {
            sum += d;
          }
          return sum;
        }
      }
      """;

  private static final String ALPHA_SNAPSHOT = """
      package p;

      final class Alpha {

        private final int[] data;

        Alpha(final int[] data) {
          this.data = data;
        }

        int total() {
          return data.length;
        }

        int dropped() {
          return 0;
        }
      }
      """;

  private static final String GHOST_SNAPSHOT = """
      package p;

      final class Ghost {

        static void vanished() {
        }
      }
      """;

  private static final String ZETA = """
      package p;

      final class Zeta {

        static boolean guard(final String text) {
          return text != null && !text.isBlank();
        }
      }
      """;

  private static final String HELPER = """
      package p;

      final class Helper {

        static int help(final int x) {
          return x + 1;
        }
      }
      """;

  private static final String XI = """
      package p;

      final class Xi {

        static int far(final int x) {
          return x * 2;
        }
      }
      """;

  private static final String FILLER = """
      package p;

      final class Filler {

        int one() {
          return 1;
        }
      }
      """;

  private static final String ALPHA_NOTES = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Fast-path routing** `# fast-path`:
      - `Alpha.total`: removing the empty-array fast path routes it through the loop, which
        sums nothing. Covered by `AlphaTests.sumsNothing`.
      - `Alpha.dropped` (`NullReturnVals`): the only implementation returned a constant.
      - `Helper.help`: the helper moved to the sibling module.
      - `Ghost.vanished`: the whole type went away.
      - `Xi.far`: a module of another repository, not a subject here.
      """;

  private static final String ZETA_NOTES = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Guards** `# guards`:
      - `Zeta.guard`: the blank check is the only branch.
      - `Helper.help`: trivial delegation.
      """;

  private static final String XI_NOTES = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Doubling** `# doubling`:
      - `Xi.far`: unchanged since the snapshot.
      - `Xi.missing`: never declared here.
      """;

  private static final String SPARE_NOTES = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants (accepted with reasons)

      **Spare** `# spare`:
      - `Nowhere.method`: nothing here resolves.
      """;

  /// `git ls-tree` output per module source root at the snapshot commit.
  private static final Map<String, String> SNAPSHOT_TREES = Map.of(
      "mod-a/src/main/java", "mod-a/src/main/java/p/Alpha.java\nmod-a/src/main/java/p/Ghost.java\n",
      "mod-z/src/main/java", "mod-z/src/main/java/p/Zeta.java\nmod-z/src/main/java/p/Helper.java\n",
      "mod-x/src/main/java", "mod-x/src/main/java/p/Xi.java\n");

  private static final Map<String, String> SNAPSHOT_BLOBS = Map.of(
      "mod-a/src/main/java/p/Alpha.java", ALPHA_SNAPSHOT,
      "mod-a/src/main/java/p/Ghost.java", GHOST_SNAPSHOT,
      "mod-z/src/main/java/p/Zeta.java", ZETA,
      "mod-z/src/main/java/p/Helper.java", HELPER,
      "mod-x/src/main/java/p/Xi.java", XI);

  /// `git diff --name-only <commit>..HEAD` per module source root: three changed files is
  /// rung 2, one is rung 1, none is rung 0.
  private static final Map<String, String> CHANGED_FILES = Map.of(
      "mod-z/src/main/java", """
          mod-z/src/main/java/p/Zeta.java
          mod-z/src/main/java/p/Helper.java
          mod-z/src/main/java/p/Filler.java
          """,
      "mod-x/src/main/java", "mod-x/src/main/java/p/Xi.java\n");

  /// Scripts every read `run` makes: the origin of a checkout, a repository's visibility,
  /// the files a module changed since its snapshot, and the snapshot tree and its blobs.
  private static CommandRunner scriptedGit() {
    return (command, directory) -> {
      if (command.getFirst().equals("gh")) {
        return command.get(3).contains("repo-private") ? "PRIVATE\n" : "PUBLIC\n";
      }
      final var repo = Path.of(command.get(2)).getFileName().toString();
      if (repo.equals("repo-broken")) {
        throw new CommandRunner.CommandFailedException(command, 128, "fatal: not a git repository");
      }
      return switch (command.get(3)) {
        case "remote" -> "git@github.com:test-org/" + repo + ".git\n";
        case "diff" -> CHANGED_FILES.getOrDefault(command.getLast(), "");
        case "ls-tree" -> SNAPSHOT_TREES.getOrDefault(command.getLast(), "");
        case "show" -> snapshotBlob(command);
        default -> throw new CommandRunner.CommandFailedException(command, 1, "unscripted: " + command.get(3));
      };
    };
  }

  private static String snapshotBlob(final List<String> command) {
    final var spec = command.get(4);
    final var blob = SNAPSHOT_BLOBS.get(spec.substring(spec.indexOf(':') + 1));
    if (blob == null) {
      throw new CommandRunner.CommandFailedException(command, 128, "fatal: path does not exist: " + spec);
    }
    return blob;
  }

  private static void write(final Path file, final String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  /// Checkouts for every manifest entry but `repo-ghost`, which was never cloned.
  private static Path checkouts(final Path dir) throws IOException {
    final var checkouts = dir.resolve("src");
    write(checkouts.resolve("repo-one/mod-a/src/main/java/p/Alpha.java"), ALPHA_HEAD);
    write(checkouts.resolve("repo-one/mod-a/src/test/java/p/AlphaTests.java"),
        "package p;\n\nfinal class AlphaTests {\n\n  void sumsNothing() {\n  }\n}\n");
    write(checkouts.resolve("repo-one/mod-z/src/main/java/p/Zeta.java"), ZETA);
    write(checkouts.resolve("repo-one/mod-z/src/main/java/p/Helper.java"), HELPER);
    write(checkouts.resolve("repo-two/mod-x/src/main/java/p/Xi.java"), XI);
    write(checkouts.resolve("repo-private/mod-p/src/main/java/p/Filler.java"), FILLER);
    write(checkouts.resolve("repo-broken/mod-b/src/main/java/p/Filler.java"), FILLER);
    write(checkouts.resolve("repo-nofleet/mod-n/src/main/java/p/Filler.java"), FILLER);
    return checkouts;
  }

  /// Snapshots for every entry but `repo-nofleet`, whose snapshot directory is missing.
  private static Path fleet(final Path dir) throws IOException {
    final var fleet = dir.resolve("golden-fleet");
    write(fleet.resolve("repo-one/mod-a/README.md"), ALPHA_NOTES);
    write(fleet.resolve("repo-one/mod-z/README.md"), ZETA_NOTES);
    write(fleet.resolve("repo-two/mod-x/README.md"), XI_NOTES);
    write(fleet.resolve("repo-ghost/mod-g/README.md"), SPARE_NOTES);
    write(fleet.resolve("repo-private/mod-p/README.md"), SPARE_NOTES);
    write(fleet.resolve("repo-broken/mod-b/README.md"), SPARE_NOTES);
    write(fleet.resolve("MANIFEST.txt"), """
        # golden fleet
        repo-one\tmod-a\t%1$s
        repo-one\tmod-z\t%1$s
        repo-two\tmod-x\t%1$s
        repo-ghost\tmod-g\t%1$s
        repo-private\tmod-p\t%1$s
        repo-broken\tmod-b\t%1$s
        repo-nofleet\tmod-n\t%1$s
        """.formatted(COMMIT));
    return fleet;
  }

  private static String[] args(final Path fleet, final Path checkouts, final Path out, final String... extra) {
    final var all = new java.util.ArrayList<String>(List.of("--manifest", fleet.resolve("MANIFEST.txt").toString(),
        "--golden-fleet", fleet.toString(), "--checkouts", checkouts.toString(), "--out", out.toString()));
    all.addAll(List.of(extra));
    return all.toArray(String[]::new);
  }

  @Test
  void aModuleIsOnlyACorpusWhenItsCheckoutSnapshotAndVisibilityAllAllowIt(@TempDir final Path dir) throws Exception {
    final var checkouts = checkouts(dir);
    final var fleet = fleet(dir);
    final var out = dir.resolve("out");
    // a gold-hints path that is not a file leaves the hints empty rather than failing
    final var config = RotExperiment.Config.parse(args(fleet, checkouts, out, "--mode", "corpus",
        "--gold-hints", dir.resolve("no-such-hints.tsv").toString()));
    final var summary = RotExperiment.run(config, null, scriptedGit());

    assertEquals(3, summary.modules(), "mod-a, mod-z, and mod-x are the only usable snapshots");
    assertEquals(4, summary.skippedPrivate(),
        "no checkout, no origin to verify, a private repository, and a missing snapshot directory");
    assertEquals(8, summary.rows());
    assertEquals(Map.of("RESOLVED", 4, "REMOVED_MEMBER", 1, "REMOVED_TYPE", 1, "MISSING_MEMBER", 1, "MISSING_TYPE", 1),
        summary.byStatus());
    assertNull(summary.spend());

    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals(9, rows.size());
    assertEquals(List.of("repo-one/mod-z", "repo-one/mod-z", "repo-two/mod-x", "repo-two/mod-x",
            "repo-one/mod-a", "repo-one/mod-a", "repo-one/mod-a", "repo-one/mod-a"),
        rows.subList(1, rows.size()).stream().map(line -> line.split("\t", -1)[1]).toList(),
        "rows come out by rung, most drift first, and by row id inside a rung");
    assertTrue(rows.stream().anyMatch(line -> line.contains("\trepo-one/mod-z\t2\t")), "three changed files is rung 2");
    assertTrue(rows.stream().anyMatch(line -> line.contains("\trepo-two/mod-x\t1\t")), "one changed file is rung 1");
    assertTrue(rows.stream().anyMatch(line -> line.contains("\trepo-one/mod-a\t0\t")), "no changed file is rung 0");
    assertTrue(rows.stream().anyMatch(line -> line.contains("\tAlpha.dropped\tREMOVED_MEMBER\texisted at 0123456")), rows.toString());
    assertTrue(rows.stream().anyMatch(line -> line.contains("\tGhost.vanished\tREMOVED_TYPE\texisted at 0123456")), rows.toString());
    assertTrue(rows.stream().anyMatch(line -> line.contains("\tXi.far\tMISSING_TYPE\t")),
        "a module of another repository is no sibling: " + rows);
    assertTrue(rows.stream().anyMatch(line -> line.contains("\tXi.missing\tMISSING_MEMBER\t")), rows.toString());
    assertTrue(rows.stream().anyMatch(line -> line.contains("\tHelper.help\tRESOLVED\t")), rows.toString());
    assertTrue(rows.stream().noneMatch(line -> line.contains("CROSS_MODULE")),
        "the sibling module's declaration is found, and that row is dropped: " + rows);
    assertTrue(rows.stream().noneMatch(line -> line.contains("AlphaTests")), "covering-test rows are dropped");

    final var resolved = cells(rows, "\tAlpha.total\t");
    assertTrue(Integer.parseInt(resolved[10]) > 0, "a resolved member's body is measured: " + resolved[10]);
    assertEquals("0", cells(rows, "\tGhost.vanished\t")[10], "a type that is gone has no body to measure");

    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals(8, sheet.size(), "the MISSING_TYPE row has no subject to judge and stays off the sheet");
    assertTrue(cells(sheet, "\tAlpha.total\t")[9].startsWith("// Alpha lines "),
        "the sheet shows the head of the member's source");
    assertEquals("", cells(sheet, "\tGhost.vanished\t")[9], "a type that is gone shows no source");
    assertTrue(Files.readString(out.resolve("report.md")).contains("Not scored (corpus mode)"));
  }

  private static String[] cells(final List<String> lines, final String marker) {
    return lines.stream().filter(line -> line.contains(marker)).findFirst().orElseThrow().split("\t", -1);
  }

  @Test
  void scoringReportsOnlyTheBarsItsLabelsSupport(@TempDir final Path dir) throws Exception {
    final var checkouts = checkouts(dir);
    final var fleet = fleet(dir);
    final var out = dir.resolve("out");
    final var recordings = dir.resolve("rec");
    final var hints = dir.resolve("hints.tsv");
    Files.writeString(hints, """
        key\tlabel
        repo-one/mod-a#Alpha.total\tpresent
        repo-one/mod-a#Alpha.dropped\tabsent
        repo-one/mod-a#Ghost.vanished\tabsent
        repo-two/mod-x#Xi.missing\tabsent
        """);
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new StubClient("Xi.missing"), recordings), 3);
    final var config = RotExperiment.Config.parse(args(fleet, checkouts, out,
        "--gold-hints", hints.toString(), "--recordings", recordings.toString()));
    assertNull(config.labels(), "this run names no labels file");
    final var summary = RotExperiment.run(config, runner, scriptedGit());
    assertEquals(new JevRunner.Totals(7, 6, 1800, 30, 0, 7), summary.spend(), "one of the seven requests failed");

    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals(8, jev.size(), "every scorable row gets a line, answered or not");
    assertTrue(jev.stream().anyMatch(line ->
            line.contains("#Alpha.dropped\tconstruct_absent\t0.900\t0.050\t0.050\t0.900\t0.100\t0.100\tmember_missing\tabsent")),
        jev.toString());
    assertTrue(jev.stream().anyMatch(line -> line.endsWith("#Xi.missing\t\t\t\t\t\t\t\tmember_missing\tabsent")),
        "a row with no answer keeps its own columns and leaves Jev's empty: " + jev);

    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 3 | 4 | 8 |"), report);
    assertTrue(report.contains("Requests 7 (6 answered), input tokens 1800"), report);
    assertTrue(report.contains("## Bars against hand labels\n\nNo labeled and scored rows yet."), report);
    assertTrue(report.contains("""
        ## Bars against PROVISIONAL survey gold hints (4 hints; not a substitute for labels)

        3 labeled rows. Control arm: flagged 2, true positives 2 of 2 rot rows (recall 1.000, precision 1.000)."""),
        "only the three hinted rows that were answered are on the bars: " + report);
    assertTrue(report.contains("| recall of rot within the top 30% by P(absent) | 0.500 | >= 0.900 | NO |"), report);
    assertTrue(report.contains("#Alpha.dropped | 0.900 | 0.900 | member_missing | absent |"),
        "the ranking carries the hint the survey gave: " + report);
    assertTrue(report.contains("#Zeta.guard | 0.050 | 0.850 |  |  |"),
        "and leaves the cell empty for a row the survey never named: " + report);
    assertTrue(report.lines().noneMatch(line -> line.contains("#Xi.missing")),
        "a row with no answer is not in the ranking: " + report);

    // a labeling sheet from somewhere else names no row of this run
    final var strangers = dir.resolve("strangers.tsv");
    Files.writeString(strangers, "row_id\tlabel\nsomewhere/else#1#Other.member\tabsent\n");
    var replayed = RotExperiment.run(RotExperiment.Config.parse(args(fleet, checkouts, out, "--mode", "replay",
        "--labels", strangers.toString(), "--recordings", recordings.toString())), null, scriptedGit());
    assertEquals(6, replayed.spend().hits());
    assertEquals(1, replayed.spend().misses(), "the request that failed was never recorded");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("## Bars against hand labels\n\nNo labeled and scored rows yet."),
        "labels that name no row leave the bars with nothing to stand on: " + report);
    assertTrue(report.contains("## Bars against PROVISIONAL survey gold hints (0 hints"), report);

    // hand labels over the rows this run wrote take over the first table
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    final var labeled = new StringBuilder(sheet.getFirst()).append('\n');
    for (final var line : sheet.subList(1, sheet.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = cells[0].contains("Alpha.dropped") || cells[0].contains("Ghost.vanished") ? "absent"
          : cells[0].contains("Alpha.total") ? "present" : "";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    final var labels = dir.resolve("labels.tsv");
    Files.writeString(labels, labeled.toString());
    assertEquals(3, RotLabels.read(labels, "row_id").size());
    replayed = RotExperiment.run(RotExperiment.Config.parse(args(fleet, checkouts, out, "--mode", "replay",
        "--labels", labels.toString(), "--recordings", recordings.toString())), null, scriptedGit());
    assertEquals(6, replayed.spend().hits());
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("""
        ## Bars against hand labels

        3 labeled rows. Control arm: flagged 2, true positives 2 of 2 rot rows (recall 1.000, precision 1.000)."""),
        report);
    assertTrue(report.contains("gold \\ predicted"), report);
  }

  @Test
  void barsWithoutTheirBasisAreReportedAsUnavailable(@TempDir final Path dir) throws Exception {
    final var entry = new Manifest.Entry("repo-one", "mod-a", COMMIT);
    final var ref = new ReadmeNotes.MemberRef(new ReadmeNotes.Note(6, "Triaged equivalent mutants", "",
        "- `Alpha.total`: a fixture reference."), "Alpha", "total", null, null);
    final var resolution = new MemberResolver.Resolution(MemberResolver.Status.RESOLVED, null, List.of(), "1 declaration(s)");
    final var state = new RotQuestions.State("- `Alpha.total`", "int total() {\n}", null,
        software.sava.typesafe.JsonContent.object().build(), "mod-a/src/main/java/p/Alpha.java");
    final var row = new RotRow("repo-one/mod-a#6#Alpha.total", entry, 0, ref, resolution, state, List.of(), null);
    final var scores = Map.of(row.id(), RotBarsAndLabelsTests.score(0.05, 0.90, 0.05, 0.9));
    final var summary = new RotExperiment.Summary(1, 0, 1, Map.of("RESOLVED", 1),
        new JevRunner.Totals(1, 1, 300, 5, 0, 1));

    // the row is scored, but this run was given no hints and no labels
    final var file = dir.resolve("report.md");
    RotExperiment.writeReport(file, summary, List.of(row), scores, null, Map.of());
    final var report = Files.readString(file);
    assertTrue(report.contains("## Bars against hand labels\n\nNo labeled and scored rows yet."), report);
    assertTrue(report.contains("""
        ## Bars against PROVISIONAL survey gold hints (0 hints; not a substitute for labels)

        No labeled and scored rows yet."""), report);

    // and a basis with no labeled rows says so
    final var out = new StringBuilder();
    RotExperiment.appendBars(out, "hand labels", List.of());
    assertEquals("## Bars against hand labels\n\nNo labeled and scored rows yet.\n\n", out.toString());
  }
}
