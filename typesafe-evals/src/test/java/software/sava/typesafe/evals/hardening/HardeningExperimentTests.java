package software.sava.typesafe.evals.hardening;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.ModelCard;
import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.CommandRunner.CommandFailedException;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.corpus.ProcessCommandRunner;
import software.sava.typesafe.evals.jev.JevRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// End to end over a real temporary checkout: one module with a README, two baselines, and
/// sources; a stub Jev that reads the mutator description to decide whether the paragraph
/// applies (so the REAL arm scores low and the SWAPPED arm high).
final class HardeningExperimentTests {

  private static final String SOURCE = """
      package p;

      public final class Widget {

        private final int[] data;

        public Widget(final int[] data) {
          this.data = data;
        }

        /// Sums with an empty fast path.
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

        static int size(final int n) {
          return n + 1;
        }

        static final java.util.function.IntUnaryOperator NEXT = x -> x + 1;
      }
      """;

  private static final String README = """
      # Mutation-testing baseline & triage policy

      ## Triaged equivalent mutants

      **Allocation-size only** — baseline label `# allocation size` — the mutant changes how much
      is allocated, never what is computed:
      - `Widget.size`: the sizing arithmetic only over-allocates.

      The fast path bullets below carry `# fast-path` rows; removing the `data.length == 0`
      conditional routes the empty array through the loop, which sums nothing.
      - `Widget.sum` 13: the empty-array guard.
      - `Widget.NEXT` (`lambda$static$0`): the increment is a boundary detail.

      A provenance paragraph, `# killed retained`, records history rather than reasoning.
      """;

  /// Delegates git to the real runner and answers gh with "public".
  private static CommandRunner commands() {
    return (command, directory) -> command.getFirst().equals("gh")
        ? "public\n"
        : ProcessCommandRunner.INSTANCE.run(command, directory);
  }

  /// Applies when the description's family word appears in the paragraph; the fixture's
  /// paragraphs mention arithmetic ("arithmetic", "increment") and conditionals.
  private static final class StubClient implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      final boolean swapped = state.contains("\"mutator_description\":\"replaced a return value with null\"")
          || state.contains("\"mutator_description\":\"removed a call to a void method\"")
          || state.contains("\"mutator_description\":\"removed an equality conditional (== or !=) by replacing it with false");
      final var body = swapped
          ? "{\"model\":\"jev-stub\",\"answers\":{\"applies\":{\"type\":\"choice\",\"choice\":\"does_not_apply\",\"confidence\":0.9,\"probabilities\":{\"applies\":0.05,\"does_not_apply\":0.9,\"cannot_tell\":0.05}},\"construct_absent\":{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{\"input_tokens\":400,\"output_tokens\":5}}"
          : "{\"model\":\"jev-stub\",\"answers\":{\"applies\":{\"type\":\"choice\",\"choice\":\"applies\",\"confidence\":0.85,\"probabilities\":{\"applies\":0.85,\"does_not_apply\":0.1,\"cannot_tell\":0.05}},\"construct_absent\":{\"type\":\"noul\",\"noul\":0.05}},\"usage\":{\"input_tokens\":400,\"output_tokens\":5}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), "req_stub"));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  /// Answers with a P(does_not_apply) that rises with the row's method name, so the ranking
  /// and the id order of the labeling sheet are different orders.
  private static final class RankingStub implements TypeSafeClient {

    @Override
    public String defaultModel() {
      return "jev-rank";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      final double p = state.contains("\"method\":\"<init>\"") ? 0.2
          : state.contains("\"method\":\"lambda$static$0\"") ? 0.3
          : state.contains("\"method\":\"size\"") ? 0.4
          : 0.5;
      final var body = "{\"model\":\"jev-rank\",\"answers\":{\"applies\":{\"type\":\"choice\",\"choice\":\"applies\",\"confidence\":0.6,"
          + "\"probabilities\":{\"applies\":" + HardeningExperiment.fmt(0.9 - p) + ",\"does_not_apply\":" + p + ",\"cannot_tell\":0.1}},"
          + "\"construct_absent\":{\"type\":\"noul\",\"noul\":0.2}},\"usage\":{\"input_tokens\":100,\"output_tokens\":2}}";
      return CompletableFuture.completedFuture(SystemOneResponse.parse(body.getBytes(StandardCharsets.UTF_8), null));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static String git(final Path repo, final String... args) {
    final var command = new java.util.ArrayList<String>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@x"));
    command.addAll(List.of(args));
    return ProcessCommandRunner.INSTANCE.run(command, null);
  }

  private static Path checkout(final Path checkouts) throws Exception {
    final var repo = checkouts.resolve("repo");
    final var src = repo.resolve("mod/src/main/java/p");
    final var config = repo.resolve("mod/config/pitest");
    Files.createDirectories(src);
    Files.createDirectories(config);
    Files.writeString(src.resolve("Widget.java"), SOURCE);
    Files.writeString(config.resolve("README.md"), README);
    Files.writeString(config.resolve("alloc-accepted.csv"), """
        !sava-hardening-baseline-schema,1
        p.Widget,size,MathMutator,SURVIVED # allocation size # line 24
        p.Widget,sum,RemoveConditionalMutator_EQUAL_IF,SURVIVED # fast-path # line 13
        p.Widget,lambda$static$0,IncrementsMutator,SURVIVED # fast-path # line 27
        p.Widget,gone,MathMutator,SURVIVED # fast-path # line 99
        p.Nowhere,x,MathMutator,SURVIVED # fast-path
        p.Widget,sum,VoidMethodCallMutator,NO_COVERAGE # untriaged # line 14
        p.Widget,sum,MathMutator,SURVIVED # no-such-label # line 15
        p.Widget,<init>,BooleanTrueReturnValsMutator,SURVIVED # killed retained # line 8
        """);
    Files.writeString(config.resolve("alloc-timeouts.csv"), "# no keys\n");
    // a worktree-like copy that git ls-files must not see
    final var stray = repo.resolve(".claude/worktrees/x/mod/config/pitest");
    Files.createDirectories(stray);
    Files.writeString(stray.resolve("alloc-accepted.csv"), "p.Widget,size,MathMutator,SURVIVED # allocation size # line 24\n");
    Files.writeString(repo.resolve(".gitignore"), ".claude/\n");
    git(repo, "init", "-q", "-b", "main");
    git(repo, "remote", "add", "origin", "git@github.com:test-org/repo.git");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "one");
    return repo;
  }

  @Test
  void corpusModeBuildsRowsFromTheIndexOnly(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    final var repo = checkout(checkouts);
    final var corpus = new HardeningCorpus("repo", repo, new GitRepo(repo, commands()));
    final var modules = corpus.modules();
    assertEquals(1, modules.size(), "the stray worktree copy is not in the index");
    assertEquals("mod", modules.getFirst().modulePath());
    assertEquals(repo.resolve("mod/src/main/java"), modules.getFirst().sourceRoot());

    final var out = dir.resolve("out");
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo,absent",
        "--out", out.toString(), "--mode", "corpus"});
    final var summary = HardeningExperiment.run(config, null, commands());
    assertEquals(2, summary.repos());
    assertEquals(1, summary.skipped());
    assertEquals(1, summary.modules());
    assertEquals(6, summary.rows(), "untriaged and undeclared labels are excluded; killed retained is kept");
    assertEquals(4, summary.scorable());
    assertEquals(Map.of("RESOLVED", 4, "MISSING_MEMBER", 1, "MISSING_TYPE", 1), summary.byStatus());
    assertNull(summary.spend());
    assertNull(summary.verdict());

    final var rows = Files.readAllLines(out.resolve("rows.tsv"));
    assertEquals(7, rows.size());
    assertEquals(List.of(
        "repo/mod#alloc#Nowhere.x#MathMutator#SURVIVED#",
        "repo/mod#alloc#Widget.<init>#BooleanTrueReturnValsMutator#SURVIVED#8",
        "repo/mod#alloc#Widget.gone#MathMutator#SURVIVED#99",
        "repo/mod#alloc#Widget.lambda$static$0#IncrementsMutator#SURVIVED#27",
        "repo/mod#alloc#Widget.size#MathMutator#SURVIVED#24",
        "repo/mod#alloc#Widget.sum#RemoveConditionalMutator_EQUAL_IF#SURVIVED#13"),
        rows.subList(1, rows.size()).stream().map(l -> l.split("\t", -1)[0]).toList(),
        "rows are written in row-id order, not in baseline order");
    assertEquals("row_id\tmodule\tsuite\tclass\tmethod\tmutator\tstatus\tlabel\tline\tmember_status\tdeclarations\tbodies_shown\tparagraph_chars\tswapped_mutator\tword_real\tword_swapped\tidentifiers_missing", rows.getFirst());
    final var size = rows.stream().filter(l -> l.startsWith("repo/mod#alloc#Widget.size#MathMutator#SURVIVED#24\t")).findFirst().orElseThrow();
    assertTrue(size.contains("\tallocation size\t24\tRESOLVED\t1\t1\t"), size);
    assertTrue(size.contains("\tRemoveConditionalMutator_EQUAL_ELSE\ttrue\tfalse\t"), "the allocation paragraph says 'arithmetic' and no conditional word: " + size);
    final var lambda = rows.stream().filter(l -> l.contains("#Widget.lambda$static$0#")).findFirst().orElseThrow();
    assertTrue(lambda.contains("\tRESOLVED\t1\t1\t"), "lambda$static$0 resolves to the static initializer of NEXT: " + lambda);
    assertTrue(rows.stream().anyMatch(l -> l.contains("#Widget.gone#") && l.contains("\tMISSING_MEMBER\t")), rows.toString());
    assertTrue(rows.stream().anyMatch(l -> l.contains("#Nowhere.x#MathMutator#SURVIVED#\t") && l.contains("\tMISSING_TYPE\t")), "no line hint leaves the key's last field empty");
    final var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("| 2 | 1 | 1 | 6 | 4 |"), report);
    assertTrue(report.contains("| repo/mod | 6 | 4 | 5 |"), "five of the six paragraphs mention the row's own family: " + report);
    assertTrue(report.contains("Not scored (corpus mode)"), report);
  }

  @Test
  void statesCarryTheParagraphFactsAndTheSwapChangesOnlyTheDescription(@TempDir final Path dir) throws Exception {
    final var repo = checkout(dir.resolve("src"));
    final var corpus = new HardeningCorpus("repo", repo, new GitRepo(repo, commands()));
    final var rows = corpus.rows();
    final var sum = rows.stream().filter(r -> r.id().contains("#Widget.sum#")).findFirst().orElseThrow();
    final var state = sum.state();
    assertEquals("removed an equality conditional (== or !=) by replacing it with true, so the if-branch always runs", state.mutatorDescription());
    assertTrue(state.memberSource().startsWith("// Widget lines 12-21\n  public int sum() {"), state.memberSource());
    assertTrue(state.memberSource().endsWith("    return total;\n  }"), state.memberSource());
    assertTrue(state.paragraph().startsWith("The fast path bullets below carry `# fast-path` rows;"), state.paragraph());
    assertTrue(state.paragraph().endsWith("- `Widget.NEXT` (`lambda$static$0`): the increment is a boundary detail."), state.paragraph());
    final var facts = state.premiseFacts().toJson();
    assertTrue(facts.contains("\"declarations\":1,\"bodies_shown\":1,\"lines_total\":10,\"paragraph_bullets\":2,\"paragraph_truncated\":false"), facts);
    assertTrue(facts.contains("\"identifiers_present\":[],\"identifiers_missing\":[\"NEXT\"]"),
        "the row's own member and the label are not identifiers; `data.length == 0` is not identifier-like; NEXT is named and absent from sum's body: " + facts);
    assertTrue(facts.contains("\"line_hint_inside_body\":true"), facts);
    final var lambda = rows.stream().filter(r -> r.id().contains("#Widget.lambda$static$0#")).findFirst().orElseThrow();
    assertEquals("RESOLVED", lambda.memberStatus());
    assertTrue(lambda.state().memberSource().contains("IntUnaryOperator NEXT = x -> x + 1;"), "a static lambda lives in a static field initializer: " + lambda.state().memberSource());
    assertTrue(lambda.state().premiseFacts().toJson().contains("\"line_hint_inside_body\":true"), lambda.state().premiseFacts().toJson());
    assertTrue(state.row().toJson().contains("\"label\":\"fast-path\",\"line\":13"), state.row().toJson());
    assertEquals("mod/src/main/java/p/Widget.java", state.filePath());
    final var swapped = sum.swappedState();
    assertEquals("replaced a return value with null", swapped.mutatorDescription(), "conditional swaps to the return family");
    assertEquals(state.paragraph(), swapped.paragraph());
    assertEquals(state.memberSource(), swapped.memberSource());
    assertEquals(state.premiseFacts(), swapped.premiseFacts());
    assertEquals(state.row(), swapped.row());
    assertTrue(sum.wordReal(), "the paragraph says 'conditional'");
    assertTrue(sum.wordSwapped(), "'empty array' carries a return-family word: the word baseline is generous by design");
    final var ctor = rows.stream().filter(r -> r.id().contains("#Widget.<init>#")).findFirst().orElseThrow();
    assertEquals("RESOLVED", ctor.memberStatus());
    assertEquals(List.of(), ctor.identifiersMissing(), "a provenance paragraph names no identifiers");
    assertEquals("killed retained", ctor.label());
  }

  @Test
  void scoredArmsFeedTheBarsTheSheetAndTheReport(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new StubClient(), dir.resolve("rec")), 2);
    // a recording of a state the corpus no longer produces: record mode prunes it
    Files.createDirectories(dir.resolve("rec"));
    Files.writeString(dir.resolve("rec/0000stale.response.json"), "{}");
    var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", out.toString(), "--recordings", dir.resolve("rec").toString(), "--labels", dir.resolve("labels.tsv").toString()});
    assertEquals("record", config.mode());
    final var summary = HardeningExperiment.run(config, runner, commands());
    assertFalse(Files.exists(dir.resolve("rec/0000stale.response.json")), "stale recording pruned in record mode");
    assertEquals(new JevRunner.Totals(8, 8, 3200, 40, 0, 8), summary.spend(), "four scorable rows, two arms each");
    final var verdict = summary.verdict();
    assertEquals(1.0, verdict.auroc(), "the stub separates the arms perfectly");
    assertEquals("value bar pending", verdict.decision());

    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals("row_id\tarm\tchoice\tp_applies\tp_does_not_apply\tp_cannot\tconfidence\tconstruct_absent\tword_baseline", jev.getFirst());
    assertEquals(9, jev.size());
    assertTrue(jev.stream().anyMatch(l -> l.contains("#Widget.sum#RemoveConditionalMutator_EQUAL_IF#SURVIVED#13\treal\tapplies\t0.850\t0.100\t")), jev.toString());
    assertTrue(jev.stream().anyMatch(l -> l.contains("#Widget.sum#RemoveConditionalMutator_EQUAL_IF#SURVIVED#13\tswapped\tdoes_not_apply\t0.050\t0.900\t")), jev.toString());
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    assertEquals("row_id\tlabel\tnotes\tmodule\trow\tmutator_description\tparagraph_head\tmember_head", sheet.getFirst());
    assertEquals(5, sheet.size(), "every scored REAL row is in the top 30");
    assertTrue(sheet.stream().noneMatch(l -> l.contains("0.100") || l.contains("0.900")), "the sheet is blind: no scores");
    assertTrue(sheet.get(1).compareTo(sheet.get(2)) < 0, "sheet rows are in id order, not score order");
    var report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("Requests 8 (8 answered), input tokens 3200, cost $0.0001; recording hits 0, misses 8; 4 rows with both arms scored."), report);
    assertTrue(report.contains("AUROC 1.000 (bootstrap 95% 1.000 to 1.000), mutator-word baseline "), report);
    assertTrue(report.contains("| **decision** | **value bar pending** | | |"), report);
    assertTrue(report.contains("| P(does_not_apply) correlates with paragraph length | 0.000 | |r| <= 0.8 | yes |"),
        "a bar that holds is reported as passing: " + report);
    assertTrue(report.contains("Choices, REAL arm: {applies=4}; SWAPPED arm: {does_not_apply=4}."), report);
    assertTrue(report.contains("## Top REAL rows by P(does_not_apply)"), report);

    // labels turn the value bar; replay needs no key
    final var labeled = new StringBuilder(sheet.getFirst()).append('\n');
    int i = 0;
    for (final var line : sheet.subList(1, sheet.size())) {
      final var cells = line.split("\t", -1);
      cells[1] = i++ < 2 ? "mis-filed" : "fine";
      labeled.append(String.join("\t", cells)).append('\n');
    }
    Files.writeString(dir.resolve("labels.tsv"), labeled.toString());
    config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", out.toString(), "--recordings", dir.resolve("rec").toString(), "--labels", dir.resolve("labels.tsv").toString(), "--mode", "replay"});
    Files.writeString(dir.resolve("rec/0000stale.response.json"), "{}");
    final var replayed = HardeningExperiment.run(config, null, commands());
    assertTrue(Files.exists(dir.resolve("rec/0000stale.response.json")), "replay mode never deletes recordings");
    assertEquals(8, replayed.spend().hits());
    assertEquals(0, replayed.spend().misses());
    assertEquals("no problem found", replayed.verdict().decision(), "two problems of the required five");
    report = Files.readString(out.resolve("report.md"));
    assertTrue(report.contains("problems confirmed among the top 30 REAL rows (4 read) | 2.000 | >= 5 | NO |"), report);
    assertTrue(report.contains("| **decision** | **no problem found** | | |"), report);
  }

  @Test
  void configValidationAndHelpers() {
    assertThrows(IllegalArgumentException.class, () -> HardeningExperiment.Config.parse(new String[]{}));
    assertThrows(IllegalArgumentException.class, () -> HardeningExperiment.Config.parse(new String[]{"--checkouts", "c"}));
    assertThrows(IllegalArgumentException.class, () -> HardeningExperiment.Config.parse(new String[]{"checkouts", "c", "--repos", "r"}));
    assertTrue(assertThrows(IllegalArgumentException.class, () -> HardeningExperiment.Config.parse(
            new String[]{"--checkouts", "c", "--repos", "r", "mode", "replay"})).getMessage().contains("got mode"),
        "an option without the dashes is rejected even when nothing required is missing");
    assertEquals("record", HardeningExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "r", "--mode"}).mode(),
        "a trailing option with no value is ignored");
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a,b", "--mode", "replay",
        "--concurrency", "2", "--visibility-cache", "v.tsv"});
    assertEquals(List.of("a", "b"), config.repos());
    assertEquals(Path.of("build/experiments/hardening"), config.out());
    assertEquals(Path.of("build/experiments/hardening/recordings"), config.recordings());
    assertEquals(2, config.concurrency());
    assertNull(config.labels());
    assertEquals(Path.of("v.tsv"), config.visibilityCache());
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, HardeningExperiment.runnerFor(config).client().mode());
    final var recording = HardeningExperiment.Config.parse(new String[]{"--checkouts", "c", "--repos", "a", "--mode", "record"});
    if (System.getenv(TypeSafeClient.API_KEY_ENV) == null) {
      assertThrows(IllegalStateException.class, () -> HardeningExperiment.runnerFor(recording),
          "any mode but replay records over the live API, which needs a key");
    } else {
      assertEquals(RecordingTypeSafeClient.Mode.RECORD, HardeningExperiment.runnerFor(recording).client().mode());
    }
    assertEquals("", HardeningExperiment.head(null, 5));
    assertEquals("ab cd", HardeningExperiment.head("ab\ncd", 5));
    assertEquals("ab cd …", HardeningExperiment.head("ab\ncdef", 5));
    assertEquals("0.500", HardeningExperiment.fmt(0.5));
  }

  @Test
  void everyReasonToSkipARepositoryIsCounted(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    Files.createDirectories(checkouts.resolve("not-a-repo"));
    Files.writeString(checkouts.resolve("a-file"), "not a checkout\n");
    // git answers for anything that gets as far as running it, except the directory that is
    // no checkout; `gh` would call every one of them public
    final CommandRunner answers = (command, directory) -> {
      if (command.getFirst().equals("gh")) {
        return "public\n";
      }
      if (command.contains(checkouts.resolve("not-a-repo").toString())) {
        throw new CommandFailedException(command, 128, "not a git repository");
      }
      return command.contains("get-url") ? "git@github.com:test-org/repo.git\n" : "";
    };
    final var out = dir.resolve("out");
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(),
        "--repos", "a-file,not-a-repo,absent", "--out", out.toString(), "--mode", "corpus"});
    final var summary = HardeningExperiment.run(config, null, answers);
    assertEquals(3, summary.repos());
    assertEquals(3, summary.skipped(), "a file, a directory that is no checkout, and a name that is not there");
    assertEquals(0, summary.modules());
    assertEquals(0, summary.rows());
    assertEquals(Map.of(), summary.byStatus());
    assertEquals(1, Files.readAllLines(out.resolve("rows.tsv")).size(), "the header alone");
  }

  @Test
  void aPrivateCheckoutIsNeverRead(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final CommandRunner privateRepo = (command, directory) -> command.getFirst().equals("gh")
        ? "private\n"
        : ProcessCommandRunner.INSTANCE.run(command, directory);
    final var out = dir.resolve("out");
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", out.toString(), "--mode", "corpus"});
    final var summary = HardeningExperiment.run(config, null, privateRepo);
    assertEquals(1, summary.skipped());
    assertEquals(0, summary.modules(), "a private checkout's modules are never even listed");
    assertEquals(0, summary.rows());
    assertEquals("test-org/repo\tPRIVATE", Files.readString(out.resolve("visibility.tsv")).strip(),
        "and the answer is cached so it is asked once");
  }

  @Test
  void theSheetIsInIdOrderEvenWhenTheRankingIsNot(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var runner = new JevRunner(RecordingTypeSafeClient.record(new RankingStub(), dir.resolve("rec")), 4);
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", out.toString(), "--recordings", dir.resolve("rec").toString()});
    assertNull(config.labels(), "no sheet has been filled in yet");
    HardeningExperiment.run(config, runner, commands());
    final var sheet = Files.readAllLines(out.resolve("labeling-sheet.tsv"));
    final var ids = sheet.subList(1, sheet.size()).stream().map(l -> l.split("\t", -1)[0]).toList();
    assertEquals(4, ids.size());
    assertEquals(ids.stream().sorted().toList(), ids, "the sheet is in id order");
    final var report = Files.readString(out.resolve("report.md"));
    final var ranked = report.substring(report.indexOf("## Top REAL rows")).lines()
        .filter(line -> line.startsWith("| repo/"))
        .map(line -> line.substring(2, line.indexOf(" | ")))
        .toList();
    assertEquals(ids.reversed(), ranked,
        "the report ranks by P(does_not_apply), which this stub makes the reverse of id order");
  }

  @Test
  void anArmWithNoAnswerDropsThePair(@TempDir final Path dir) throws Exception {
    final var checkouts = dir.resolve("src");
    checkout(checkouts);
    final var out = dir.resolve("out");
    final var stub = new StubClient();
    final var flaky = new TypeSafeClient() {

      @Override
      public String defaultModel() {
        return stub.defaultModel();
      }

      @Override
      public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
        final var state = request.state().toJson();
        // the SWAPPED arm of one row and the REAL arm of another go unanswered
        final boolean unanswered =
            state.contains("\"method\":\"size\"") && state.contains("\"mutator_description\":\"removed an equality conditional (== or !=) by replacing it with false")
                || state.contains("\"method\":\"sum\"") && state.contains("\"mutator_description\":\"removed an equality conditional (== or !=) by replacing it with true");
        return unanswered
            ? CompletableFuture.failedFuture(new IllegalStateException("no answer for this arm"))
            : stub.systemOne(request);
      }

      @Override
      public CompletableFuture<List<ModelCard>> models() {
        return stub.models();
      }
    };
    final var runner = new JevRunner(RecordingTypeSafeClient.record(flaky, dir.resolve("rec")), 2);
    final var config = HardeningExperiment.Config.parse(new String[]{"--checkouts", checkouts.toString(), "--repos", "repo",
        "--out", out.toString(), "--recordings", dir.resolve("rec").toString()});
    final var summary = HardeningExperiment.run(config, runner, commands());
    assertEquals(new JevRunner.Totals(8, 6, 2400, 30, 0, 8), summary.spend(), "two of the eight arms went unanswered");
    final var jev = Files.readAllLines(out.resolve("jev.tsv"));
    assertEquals(5, jev.size(), "two pairs, two arms each, and the header");
    assertTrue(jev.stream().noneMatch(line -> line.contains("#Widget.size#")), "a row missing its SWAPPED arm is no pair: " + jev);
    assertTrue(jev.stream().noneMatch(line -> line.contains("#Widget.sum#")), "nor is one missing its REAL arm: " + jev);
    assertTrue(Files.readString(out.resolve("report.md")).contains("2 rows with both arms scored"), "the report says so");
  }

  @Test
  void labelsReadTheSheet(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("labels.tsv");
    Files.writeString(file, "row_id\tlabel\tnotes\na\tmis-filed\t\nb\t\t\nc\t Rotted \t\nd\tfine\t\nshort\n");
    final var labels = HardeningLabels.read(file);
    assertEquals(Map.of("a", "mis-filed", "c", "rotted", "d", "fine"), labels.byKey());
    assertEquals(3, labels.size());
    Files.writeString(file, "row_id\tlabel\nx\tbogus\n");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> HardeningLabels.read(file)).getMessage().contains("line 2"));
    Files.writeString(file, "key\tlabel\nx\tfine\n");
    assertThrows(IllegalArgumentException.class, () -> HardeningLabels.read(file));
    Files.writeString(file, "row_id\tnotes\na\tsomething\n");
    assertThrows(IllegalArgumentException.class, () -> HardeningLabels.read(file), "a sheet with no label column is rejected");
    Files.writeString(file, "label\trow_id\nfine\tz\n");
    assertEquals(Map.of("z", "fine"), HardeningLabels.read(file).byKey(), "either column may come first");
    Files.writeString(file, "");
    assertThrows(IllegalArgumentException.class, () -> HardeningLabels.read(file));
    assertThrows(java.io.UncheckedIOException.class, () -> HardeningLabels.read(dir.resolve("absent.tsv")));
  }
}
