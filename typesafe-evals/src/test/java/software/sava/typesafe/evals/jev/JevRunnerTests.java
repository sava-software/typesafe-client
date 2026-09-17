package software.sava.typesafe.evals.jev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.typesafe.ModelCard;
import software.sava.typesafe.Question;
import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class JevRunnerTests {

  private static final String BODY = """
      {"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.7}},"usage":{"input_tokens":100,"output_tokens":3}}""";

  /// Answers from a script keyed by state text: a body, or "FAIL" to fail the future, or
  /// "THROW" to throw before a future exists. Tracks the peak number of requests in flight.
  private static final class StubClient implements TypeSafeClient {

    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      final var state = request.state().toJson();
      if (state.contains("THROW")) {
        throw new IllegalStateException("thrown synchronously");
      }
      final int now = inFlight.incrementAndGet();
      peak.accumulateAndGet(now, Math::max);
      return CompletableFuture.supplyAsync(() -> {
        try {
          Thread.sleep(20);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        inFlight.decrementAndGet();
        if (state.contains("FAIL")) {
          throw new IllegalArgumentException("failed asynchronously");
        }
        return SystemOneResponse.parse(BODY.getBytes(StandardCharsets.UTF_8), "req_" + state.length());
      });
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static SystemOneRequest request(final String state) {
    return SystemOneRequest.builder().state(state).question("q", Question.noul("n")).build();
  }

  @Test
  void runsInOrderWithBoundedConcurrencyAndTotals(@TempDir final Path dir) {
    final var stub = new StubClient();
    final var runner = new JevRunner(RecordingTypeSafeClient.record(stub, dir), 2);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    for (int i = 0; i < 6; i++) {
      requests.put("r" + i, request("state " + i));
    }
    requests.put("bad", request("FAIL here"));
    requests.put("worse", request("THROW here"));

    final var outcomes = runner.run(requests);
    assertEquals(List.of("r0", "r1", "r2", "r3", "r4", "r5", "bad", "worse"),
        outcomes.stream().map(JevRunner.Outcome::id).toList());
    // in-flight is counted synchronously on submit, so a chunk fills to exactly the bound
    assertEquals(2, stub.peak.get(), "peak in flight");
    assertTrue(outcomes.get(0).succeeded());
    assertEquals(0.7, outcomes.get(0).response().noul("q").noul());
    assertSame(requests.get("r0"), outcomes.get(0).request());

    final var failures = JevRunner.failures(outcomes);
    assertEquals(List.of("bad", "worse"), List.copyOf(failures.keySet()));
    assertInstanceOf(IllegalArgumentException.class, failures.get("bad"));
    assertInstanceOf(IllegalStateException.class, failures.get("worse"));
    assertNull(outcomes.get(6).response());

    final var totals = runner.totals(outcomes);
    assertEquals(new JevRunner.Totals(8, 6, 600, 18, 0, 8), totals);
    assertEquals(600 * 0.042 / 1_000_000, totals.dollars(), 1e-15);

    // a second run replays every success from the recording and re-attempts the failures
    final var again = runner.run(requests);
    assertEquals(6, again.stream().filter(JevRunner.Outcome::succeeded).count());
    assertEquals(6, runner.client().hits());
  }

  @Test
  void aTrailingPartialChunkAndASynchronousThrowFirst(@TempDir final Path dir) {
    final var stub = new StubClient();
    final var runner = new JevRunner(RecordingTypeSafeClient.record(stub, dir), 3);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    requests.put("worse", request("THROW first"));
    for (int i = 0; i < 4; i++) {
      requests.put("r" + i, request("state " + i));
    }
    final var outcomes = runner.run(requests);
    assertEquals(List.of("worse", "r0", "r1", "r2", "r3"), outcomes.stream().map(JevRunner.Outcome::id).toList());
    assertFalse(outcomes.getFirst().succeeded());
    assertEquals("thrown synchronously", outcomes.getFirst().failure().getMessage());
    assertEquals(4, outcomes.stream().filter(JevRunner.Outcome::succeeded).count());
    // chunks: [worse r0 r1] then [r2 r3]; the throw never submits, so the first chunk peaks at 2
    assertEquals(2, stub.peak.get());
    assertEquals(new JevRunner.Totals(5, 4, 400, 12, 0, 5), runner.totals(outcomes));
    assertEquals(List.of(), runner.run(new LinkedHashMap<>()));
  }

  @Test
  void theLiveFactoriesNeedAKeyOnlyToRecord(@TempDir final Path dir) {
    if (System.getenv(TypeSafeClient.API_KEY_ENV) == null) {
      assertThrows(IllegalStateException.class, () -> JevRunner.recording(dir));
    } else {
      final var recording = JevRunner.recording(dir);
      assertEquals(RecordingTypeSafeClient.Mode.RECORD, recording.mode());
      assertEquals(dir, recording.directory());
    }
    final var replay = JevRunner.replayOnly(dir);
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, replay.mode());
    assertEquals(dir, replay.directory());
  }

  @Test
  void replayOnlyNeedsNoKeyAndFailsMissesAsOutcomes(@TempDir final Path dir) {
    final var runner = new JevRunner(JevRunner.replayOnly(dir), 1);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    requests.put("x", request("never recorded"));
    final var outcomes = runner.run(requests);
    assertFalse(outcomes.getFirst().succeeded());
    assertInstanceOf(java.util.NoSuchElementException.class, outcomes.getFirst().failure());
    assertEquals(new JevRunner.Totals(1, 0, 0, 0, 0, 1), runner.totals(outcomes));
  }

  @Test
  void aMissingUsageCountsAsZero(@TempDir final Path dir) {
    final var noUsage = new TypeSafeClient() {
      @Override
      public String defaultModel() {
        return "m";
      }

      @Override
      public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
        return CompletableFuture.completedFuture(SystemOneResponse.parse("{\"model\":\"m\"}".getBytes(StandardCharsets.UTF_8), null));
      }

      @Override
      public CompletableFuture<List<ModelCard>> models() {
        return CompletableFuture.completedFuture(List.of());
      }
    };
    final var runner = new JevRunner(RecordingTypeSafeClient.record(noUsage, dir), 1);
    final var requests = new LinkedHashMap<String, SystemOneRequest>();
    requests.put("x", request("s"));
    assertEquals(new JevRunner.Totals(1, 1, 0, 0, 0, 1), runner.totals(runner.run(requests)));
    assertThrows(IllegalArgumentException.class, () -> new JevRunner(RecordingTypeSafeClient.record(noUsage, dir), 0));
  }
}
