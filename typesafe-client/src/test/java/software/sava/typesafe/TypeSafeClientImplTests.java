package software.sava.typesafe;

import org.junit.jupiter.api.Test;
import software.sava.typesafe.exceptions.TypeSafeRequestException;
import systems.comodal.jsoniter.JsonIterator;

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

  /// fixtures-12: the gate admits every 2xx, so a 204 -- or any empty-bodied success -- reaches
  /// the parsers with zero bytes and fails there. `jdk.httpserver` cannot serve that body, so
  /// the gate is driven directly, as above.
  @Test
  void anEmptyBodiedSuccessReachesTheParsersAndFails() {
    final byte[] empty = new byte[0];
    final var httpResponse = new FakeHttpResponse(204, "req_204", empty);
    assertSame(empty, TypeSafeClientImpl.gate(httpResponse));
    final var systemOne = assertThrows(RuntimeException.class, () -> SystemOneResponse.parse(httpResponse, empty));
    assertTrue(systemOne.getMessage().contains("unexpected end"), systemOne.getMessage());
    final var models = assertThrows(RuntimeException.class,
        () -> ModelCard.parseList(JsonIterator.parse(empty)));
    assertTrue(models.getMessage().contains("unexpected end"), models.getMessage());
  }
}
