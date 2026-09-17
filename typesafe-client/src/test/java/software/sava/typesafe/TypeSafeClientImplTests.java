package software.sava.typesafe;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.exceptions.TypeSafeRequestException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/// The status gate at its edges. A 1xx cannot be served as a final response by
/// `jdk.httpserver`, so the lower bound is pinned here, without a socket.
final class TypeSafeClientImplTests {

  private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

  @Test
  void theGateAcceptsExactlyTheTwoHundreds() {
    assertSame(BODY, TypeSafeClientImpl.gate(new FakeHttpResponse(200, null, BODY)));
    assertSame(BODY, TypeSafeClientImpl.gate(new FakeHttpResponse(299, null, BODY)));
    for (final int status : new int[]{100, 199, 300, 404, 529}) {
      final var exception = assertThrows(TypeSafeRequestException.class,
          () -> TypeSafeClientImpl.gate(new FakeHttpResponse(status, "req_g", BODY)), "status " + status);
      assertEquals(status, exception.statusCode());
      assertEquals("req_g", exception.requestId());
      assertEquals("{}", exception.body());
    }
  }
}
