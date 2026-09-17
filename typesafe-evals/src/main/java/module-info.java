/// Evaluation harness for the two experiments in `docs/findings.md`. Not published.
module software.sava.typesafe_evals {
  requires java.net.http;

  requires transitive software.sava.typesafe;

  exports software.sava.typesafe.evals.corpus;
  exports software.sava.typesafe.evals.dedupe;
  exports software.sava.typesafe.evals.jev;
  exports software.sava.typesafe.evals.metrics;
  exports software.sava.typesafe.evals.report;
  exports software.sava.typesafe.evals.rot;
  exports software.sava.typesafe.evals.text;
}
