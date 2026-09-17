package software.sava.typesafe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RecordingTypeSafeClientTests {

  /// A delegate that answers every request with the smoke body and counts calls.
  private static final class StubClient implements TypeSafeClient {

    final AtomicInteger calls = new AtomicInteger();
    final String requestId;
    String lastBody;

    StubClient(final String requestId) {
      this.requestId = requestId;
    }

    @Override
    public String defaultModel() {
      return "jev-stub";
    }

    @Override
    public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
      calls.incrementAndGet();
      lastBody = request.body();
      return CompletableFuture.completedFuture(
          SystemOneResponse.parse(TestBodies.SMOKE.getBytes(StandardCharsets.UTF_8), requestId));
    }

    @Override
    public CompletableFuture<List<ModelCard>> models() {
      return CompletableFuture.completedFuture(List.of(new ModelCard("jev-stub", "d", "r")));
    }
  }

  private static SystemOneRequest request() {
    return SystemOneRequest.builder()
        .state("s")
        .question("still_holds", Question.choice("q", "still_holds", "no_longer_holds", "cannot_tell"))
        .build();
  }

  @Test
  void aMissRecordsThreeFilesAndTheNextCallReplays(@TempDir final Path dir) throws Exception {
    final var stub = new StubClient(TestBodies.SMOKE_REQUEST_ID);
    final var recording = RecordingTypeSafeClient.record(stub, dir.resolve("cache"));
    assertEquals(RecordingTypeSafeClient.Mode.RECORD, recording.mode());
    assertEquals("jev-stub", recording.defaultModel());

    final var first = recording.systemOne(request()).join();
    assertEquals(1, stub.calls.get());
    assertEquals(0, recording.hits());
    assertEquals(1, recording.misses());
    assertTrue(stub.lastBody.contains("\"model\":\"jev-stub\""), "the delegate's default model is resolved before hashing");

    final var key = RecordingTypeSafeClient.key(request().withDefaultModel("jev-stub").body());
    final var cache = recording.directory();
    assertEquals(stub.lastBody, Files.readString(cache.resolve(key + ".request.json")));
    assertEquals(TestBodies.SMOKE, Files.readString(cache.resolve(key + ".response.json")));
    assertEquals(TestBodies.SMOKE_REQUEST_ID + "\n", Files.readString(cache.resolve(key + ".request-id")));
    try (final var files = Files.list(cache)) {
      assertEquals(4, files.count(), "three exchange files plus default-model, no temp files left behind");
    }
    assertEquals("jev-stub\n", Files.readString(cache.resolve(RecordingTypeSafeClient.DEFAULT_MODEL_FILE)));

    final var second = recording.systemOne(request()).join();
    assertEquals(1, stub.calls.get(), "a hit never reaches the delegate");
    assertEquals(1, recording.hits());
    assertEquals(first.answers(), second.answers());
    assertEquals(first.requestId(), second.requestId());
    assertEquals(first.raw(), second.raw());

    final var replay = RecordingTypeSafeClient.replayOnly(cache);
    assertEquals("jev-stub", replay.defaultModel(), "the recording remembers its model");
    assertEquals(second, replay.systemOne(request()).join(), "a null-model request resolves to the recorded model and replays");
    assertEquals(second, replay.systemOne(request().withDefaultModel("jev-stub")).join());
    assertEquals(2, replay.hits());
  }

  @Test
  void aMissingRequestIdIsNotRecorded(@TempDir final Path dir) throws Exception {
    final var recording = RecordingTypeSafeClient.record(new StubClient(null), dir);
    final var response = recording.systemOne(request()).join();
    assertNull(response.requestId());
    try (final var files = Files.list(dir)) {
      assertEquals(3, files.count(), "request, response, default-model; no request-id file");
    }
    assertNull(recording.systemOne(request()).join().requestId());
  }

  @Test
  void aDifferentQuestionIsADifferentKey(@TempDir final Path dir) {
    final var stub = new StubClient("r");
    final var recording = RecordingTypeSafeClient.record(stub, dir);
    recording.systemOne(request()).join();
    final var reworded = SystemOneRequest.builder()
        .state("s")
        .question("still_holds", Question.choice("q2", "still_holds", "no_longer_holds", "cannot_tell"))
        .build();
    recording.systemOne(reworded).join();
    assertEquals(2, stub.calls.get());
    assertNotEquals(RecordingTypeSafeClient.key(request().withDefaultModel("jev-stub").body()),
        RecordingTypeSafeClient.key(reworded.withDefaultModel("jev-stub").body()));
  }

  @Test
  void replayOnlyFailsAMissWithoutCallingAnything(@TempDir final Path dir) throws Exception {
    final var replay = RecordingTypeSafeClient.replayOnly(dir);
    assertEquals(RecordingTypeSafeClient.Mode.REPLAY_ONLY, replay.mode());
    assertEquals(TypeSafeClient.DEFAULT_MODEL, replay.defaultModel(), "no recording yet: the client default");
    Files.writeString(dir.resolve(RecordingTypeSafeClient.DEFAULT_MODEL_FILE), " \n");
    assertEquals(TypeSafeClient.DEFAULT_MODEL, RecordingTypeSafeClient.replayOnly(dir).defaultModel(), "a blank model file is ignored");
    Files.writeString(dir.resolve(RecordingTypeSafeClient.DEFAULT_MODEL_FILE), "jev-recorded\n");
    assertEquals("jev-recorded", RecordingTypeSafeClient.replayOnly(dir).defaultModel());
    final var failure = assertThrows(CompletionException.class, () -> replay.systemOne(request()).join());
    final var missing = assertInstanceOf(NoSuchElementException.class, failure.getCause());
    assertTrue(missing.getMessage().contains(RecordingTypeSafeClient.key(request().withDefaultModel(TypeSafeClient.DEFAULT_MODEL).body())));
    assertEquals(1, replay.misses());
    final var models = assertThrows(CompletionException.class, () -> replay.models().join());
    assertInstanceOf(UnsupportedOperationException.class, models.getCause());
  }

  @Test
  void modelsPassThroughToTheDelegate(@TempDir final Path dir) {
    final var recording = RecordingTypeSafeClient.record(new StubClient("r"), dir);
    assertEquals("jev-stub", recording.models().join().getFirst().name());
  }

  @Test
  void keyIsTheLowerCaseHexSha256OfTheBody() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", RecordingTypeSafeClient.key(""));
    assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", RecordingTypeSafeClient.key("test"));
  }

  @Test
  void nullArgumentsAreRejected() {
    assertThrows(NullPointerException.class, () -> RecordingTypeSafeClient.record(null, Path.of(".")));
    assertThrows(NullPointerException.class, () -> RecordingTypeSafeClient.record(new StubClient("r"), null));
    assertThrows(NullPointerException.class, () -> RecordingTypeSafeClient.replayOnly(null));
  }
}
