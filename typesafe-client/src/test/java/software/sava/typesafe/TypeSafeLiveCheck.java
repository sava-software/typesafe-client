package software.sava.typesafe;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// One real round trip, off by default. Run with
/// `TYPESAFE_LIVE_CHECK=true ./gradlew :typesafe-client:test --tests '*LiveCheck*'` and a
/// `TYPESAFE_API_KEY` in the environment. Costs a few hundred input tokens.
final class TypeSafeLiveCheck {

  @Test
  void oneRequestAndTheModelList() {
    assumeTrue("true".equals(System.getenv("TYPESAFE_LIVE_CHECK")), "TYPESAFE_LIVE_CHECK != true");
    assumeTrue(System.getenv(TypeSafeClient.API_KEY_ENV) != null, "no TYPESAFE_API_KEY");
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = TypeSafeClient.clientBuilder().httpClient(httpClient).createClient();

      final var models = client.models().join();
      assertFalse(models.isEmpty());
      assertTrue(models.stream().anyMatch(m -> TypeSafeClient.DEFAULT_MODEL.equals(m.name())));

      final var request = SystemOneRequest.builder()
          .state("My running shoes arrived in the wrong size.")
          .question("department", Question.choice("Which team should handle this?", "returns", "shipping", "billing"))
          .question("refund", Question.noul("Does the customer want money back?"))
          .question("tone", Question.score("How upset is the customer?", "Calm.", "Annoyed.", "Furious."))
          .build();
      final var response = client.systemOne(request).join();

      assertNotNull(response.model());
      assertTrue(response.requestId() != null && response.requestId().startsWith("req_"), String.valueOf(response.requestId()));
      assertEquals(3, response.answers().size());
      final var department = response.choice("department");
      assertEquals(3, department.probabilities().size());
      assertTrue(department.confidence() >= 0 && department.confidence() <= 1);
      final var refund = response.noul("refund").noul();
      assertTrue(refund >= 0 && refund <= 1);
      final var tone = response.score("tone");
      assertEquals(2, tone.topLevel());
      assertTrue(tone.score() >= 0 && tone.score() <= 2);
      assertTrue(response.usage().inputTokens() > 0);
    }
  }
}
