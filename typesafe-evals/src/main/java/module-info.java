/// Evaluation harness for the two experiments in `docs/findings.md`. Not published.
module software.sava.typesafe_evals {
  requires java.net.http;

  requires transitive software.sava.typesafe;

  exports software.sava.typesafe.evals.text;
}
