package software.sava.typesafe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

/// Records every System One exchange under a directory, keyed by the SHA-256 of the request
/// body, and replays a recorded response instead of calling the API when the same body comes
/// back. Three files per exchange, all plain text:
///
/// - `<key>.request.json`: the request body verbatim
/// - `<key>.response.json`: the response body verbatim
/// - `<key>.request-id`: the `x-typesafe-request-id` header, when the API sent one
///
/// plus one `default-model` file naming the model the recording was made with, so a
/// replay-only client resolves a request that left its model null to the same body.
///
/// A recorded evaluation therefore re-renders byte for byte with no key and no spend, and the
/// request file beside each response is its provenance. Editing a question changes the body,
/// so a stale recording is never replayed by accident.
public final class RecordingTypeSafeClient implements TypeSafeClient {

  public enum Mode {
    /// Call the delegate on a miss and record what comes back.
    RECORD,
    /// Never call anything; a miss fails the returned future with [NoSuchElementException].
    REPLAY_ONLY
  }

  static final String DEFAULT_MODEL_FILE = "default-model";

  private final TypeSafeClient delegate;
  private final Path directory;
  private final Mode mode;
  private final String defaultModel;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();

  private RecordingTypeSafeClient(final TypeSafeClient delegate, final Path directory, final Mode mode) {
    this.delegate = delegate;
    this.directory = directory;
    this.mode = mode;
    this.defaultModel = delegate != null ? delegate.defaultModel() : recordedModel(directory);
  }

  /// The model a replay-only client answers `defaultModel()` with: the recording's, else the
  /// client default.
  private static String recordedModel(final Path directory) {
    final var file = directory.resolve(DEFAULT_MODEL_FILE);
    if (!Files.isRegularFile(file)) {
      return DEFAULT_MODEL;
    }
    try {
      final var model = Files.readString(file, StandardCharsets.UTF_8).strip();
      return model.isEmpty() ? DEFAULT_MODEL : model;
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
  }

  public static RecordingTypeSafeClient record(final TypeSafeClient delegate, final Path directory) {
    return new RecordingTypeSafeClient(requireNonNull(delegate, "delegate"), requireNonNull(directory, "directory"), Mode.RECORD);
  }

  public static RecordingTypeSafeClient replayOnly(final Path directory) {
    return new RecordingTypeSafeClient(null, requireNonNull(directory, "directory"), Mode.REPLAY_ONLY);
  }

  public Mode mode() {
    return mode;
  }

  public Path directory() {
    return directory;
  }

  public long hits() {
    return hits.get();
  }

  public long misses() {
    return misses.get();
  }

  @Override
  public String defaultModel() {
    return defaultModel;
  }

  /// The recording key for `body`: lower-case hex SHA-256 of its UTF-8 bytes.
  public static String key(final String body) {
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOne(final SystemOneRequest request) {
    final var resolved = request.withDefaultModel(defaultModel());
    final var body = resolved.body();
    final var key = key(body);
    final var responseFile = directory.resolve(key + ".response.json");
    if (Files.isRegularFile(responseFile)) {
      hits.incrementAndGet();
      return CompletableFuture.completedFuture(replay(key, responseFile));
    }
    misses.incrementAndGet();
    if (mode == Mode.REPLAY_ONLY) {
      return CompletableFuture.failedFuture(new NoSuchElementException(
          "no recorded response " + responseFile + " for request " + key));
    }
    return delegate.systemOne(resolved).thenApply(response -> {
      store(key, body, response);
      return response;
    });
  }

  private SystemOneResponse replay(final String key, final Path responseFile) {
    try {
      final var requestIdFile = directory.resolve(key + ".request-id");
      final var requestId = Files.isRegularFile(requestIdFile)
          ? Files.readString(requestIdFile).strip()
          : null;
      return SystemOneResponse.parse(Files.readAllBytes(responseFile), requestId);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to replay " + responseFile, e);
    }
  }

  private void store(final String key, final String requestBody, final SystemOneResponse response) {
    try {
      Files.createDirectories(directory);
      // the same content every time, so rewriting it is idempotent and needs no guard
      write(directory.resolve(DEFAULT_MODEL_FILE), defaultModel + '\n');
      write(directory.resolve(key + ".request.json"), requestBody);
      if (response.requestId() != null) {
        write(directory.resolve(key + ".request-id"), response.requestId() + '\n');
      }
      // the response file lands last so a partial recording never replays
      write(directory.resolve(key + ".response.json"), response.raw());
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to record " + key + " under " + directory, e);
    }
  }

  /// Writes through a temp file in the same directory and moves it into place, so a reader
  /// never sees a half-written recording.
  private static void write(final Path file, final String content) throws IOException {
    final var temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Override
  public CompletableFuture<List<ModelCard>> models() {
    if (delegate == null) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("replay-only client has no delegate"));
    }
    return delegate.models();
  }
}
