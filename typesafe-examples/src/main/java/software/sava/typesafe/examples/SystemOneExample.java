package software.sava.typesafe.examples;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.Question;
import software.sava.typesafe.SystemOneRequest;
import software.sava.typesafe.TypeSafeClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;

/// Asks three independent questions about one support message in a single request. Needs
/// `TYPESAFE_API_KEY` in the environment.
public final class SystemOneExample {

  public static void main(final String[] args) {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = TypeSafeClient.clientBuilder()
          .httpClient(httpClient)
          .createClient();

      final var criteria = new LinkedHashMap<String, String>();
      criteria.put("billing", "Payments, invoicing, refunds");
      criteria.put("technical", "Bugs, outages, integrations");
      criteria.put("sales", "Pricing, upgrades, new accounts");

      final var request = SystemOneRequest.builder()
          .state(JsonContent.object()
              .put("message", "I was charged twice for last month and the export button crashes Safari.")
              .put("plan", "team")
              .build())
          .question("department", Question.choice("Which team should handle `message`?", criteria))
          .question("refund_requested", Question.noul("Does `message` ask for money back?"))
          .question("frustration", Question.score("How frustrated is the writer of `message`?",
              "Calm and neutral.", "Concerned but civil.", "Very angry or using strong language."))
          .build();

      final var response = client.systemOne(request).join();

      final var department = response.choice("department");
      System.out.printf("department: %s (confidence %.2f, %s)%n",
          department.choice(), department.confidence(), department.probabilities());
      System.out.printf("refund requested: %.2f%n", response.noul("refund_requested").noul());
      final var frustration = response.score("frustration");
      System.out.printf("frustration: %.2f of %d (confidence %.2f)%n",
          frustration.score(), frustration.topLevel(), frustration.confidence());
      System.out.printf("model %s, %d input tokens, request %s%n",
          response.model(), response.usage().inputTokens(), response.requestId());
    }
  }

  private SystemOneExample() {
  }
}
