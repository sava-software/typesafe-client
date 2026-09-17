package software.sava.typesafe.evals.jev;

import software.sava.typesafe.RecordingTypeSafeClient;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.SystemOneResponse;
import software.sava.typesafe.TypeSafeClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.util.Objects.requireNonNullElse;

/// Runs a batch of requests through a recording client with bounded concurrency and
/// totals what it cost. Every exchange lands under the recording directory, so a rerun
/// with the same questions replays for free and a report can be re-rendered with no key.
public final class JevRunner {

  /// One request's outcome: exactly one of `response` and `failure` is set.
  public record Outcome(String id, SystemOneRequest request, SystemOneResponse response, Throwable failure) {

    public boolean succeeded() {
      return response != null;
    }
  }

  public record Totals(int requests, int succeeded, long inputTokens, long outputTokens, long hits, long misses) {

    /// Dollars at the documented $0.042 per million input tokens; output is free.
    public double dollars() {
      return inputTokens * 0.042 / 1_000_000.0;
    }
  }

  private final RecordingTypeSafeClient client;
  private final int concurrency;

  public JevRunner(final RecordingTypeSafeClient client, final int concurrency) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be at least 1");
    }
    this.client = client;
    this.concurrency = concurrency;
  }

  /// A recording client over the live API, keyed from the environment.
  public static RecordingTypeSafeClient recording(final Path directory) {
    return RecordingTypeSafeClient.record(TypeSafeClient.clientBuilder().createClient(), directory);
  }

  /// A client that only replays what `directory` already holds; needs no key.
  public static RecordingTypeSafeClient replayOnly(final Path directory) {
    return RecordingTypeSafeClient.replayOnly(directory);
  }

  public RecordingTypeSafeClient client() {
    return client;
  }

  /// Runs every request in chunks of `concurrency` (a chunk is joined before the next one
  /// starts) and returns outcomes in the input order. A failed request is an outcome, not an
  /// exception: the rest of the batch runs. A client that throws before returning a future
  /// fails that one outcome the same way.
  public List<Outcome> run(final SequencedMap<String, SystemOneRequest> requests) {
    final var outcomes = new ArrayList<Outcome>(requests.size());
    final var pending = new ArrayList<Map.Entry<String, SystemOneRequest>>(concurrency);
    for (final var entry : requests.entrySet()) {
      pending.add(entry);
      if (pending.size() == concurrency) {
        flush(pending, outcomes);
      }
    }
    flush(pending, outcomes);
    return outcomes;
  }

  private void flush(final List<Map.Entry<String, SystemOneRequest>> pending, final List<Outcome> outcomes) {
    final var futures = new ArrayList<CompletableFuture<SystemOneResponse>>(pending.size());
    for (final var entry : pending) {
      // thenCompose turns a synchronous throw into a failed future, so both failure shapes
      // reach the join below the same way
      futures.add(CompletableFuture.completedFuture(null).thenCompose(_ -> client.systemOne(entry.getValue())));
    }
    for (int i = 0; i < pending.size(); i++) {
      final var id = pending.get(i).getKey();
      final var request = pending.get(i).getValue();
      try {
        outcomes.add(new Outcome(id, request, futures.get(i).join(), null));
      } catch (final CompletionException e) {
        outcomes.add(new Outcome(id, request, null, requireNonNullElse(e.getCause(), e)));
      }
    }
    pending.clear();
  }

  public Totals totals(final List<Outcome> outcomes) {
    int succeeded = 0;
    long input = 0;
    long output = 0;
    for (final var outcome : outcomes) {
      if (outcome.succeeded()) {
        ++succeeded;
        final var usage = outcome.response().usage();
        if (usage != null) {
          input += usage.inputTokens();
          output += usage.outputTokens();
        }
      }
    }
    return new Totals(outcomes.size(), succeeded, input, output, client.hits(), client.misses());
  }

  /// The outcomes that failed, keyed by id, for the report's "not scored" section.
  public static Map<String, Throwable> failures(final List<Outcome> outcomes) {
    final var failures = new LinkedHashMap<String, Throwable>();
    for (final var outcome : outcomes) {
      if (!outcome.succeeded()) {
        failures.put(outcome.id(), outcome.failure());
      }
    }
    return failures;
  }
}
