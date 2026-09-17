package software.sava.typesafe;

/// Jazzer entry point for the System One response parser. Malformed-input contract:
/// "garbage in -> RuntimeException out" -- Jazzer hunts hangs, memory exhaustion, and any
/// non-RuntimeException throwable.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :typesafe-client:fuzzResponse [-PmaxFuzzTime=<seconds>]`.
public final class SystemOneResponseFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    try {
      SystemOneResponse.parse(data, null);
    } catch (final RuntimeException expected) {
      // malformed responses must fail with a RuntimeException, never anything else
    }
  }

  private SystemOneResponseFuzz() {
  }
}
