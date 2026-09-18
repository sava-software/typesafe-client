package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/// Experiment C1's pre-registered bars: Design 1 (swapped comments, a decision table like
/// C2's) and Design 2 (a blind-labeled sample of the real population, reported as a
/// prevalence study).
public final class DocBars {

  public static final double SEPARATION_BAR = 0.85;
  public static final double LIFT_BAR = 0.10;
  public static final double CORRELATION_CEILING = 0.8;
  public static final int TOP_N = 30;
  public static final int VALUE_BAR = 5;
  public static final int PRECISION_TOP = 20;
  public static final double CONFIDENT = 0.9;
  public static final double CONFIDENT_WRONG_RATE = 0.02;
  public static final int AUROC_MIN_POSITIVES = 20;
  public static final int RESAMPLES = 1000;
  public static final long SEED = 7L;

  /// A row with both arms scored.
  public record Pair(DocCorpus.Row row, DocScore real, DocScore swapped) {
  }

  /// A row with its REAL score.
  public record Scored(DocCorpus.Row row, DocScore real) {
  }

  public record Check(String name, double value, String required, boolean pass) {
  }

  public record Verdict(double auroc, double[] interval, double baselineAuroc, double lengthCorrelation, List<Check> checks,
                        String decision) {
  }

  /// A rate with its Wilson 95% interval.
  public record Rate(int count, int of, double rate, double lower, double upper) {

    public static Rate of(final int count, final int of) {
      if (of == 0) {
        return new Rate(0, 0, Double.NaN, Double.NaN, Double.NaN);
      }
      final double z = 1.96;
      final double p = (double) count / of;
      final double denominator = 1 + z * z / of;
      final double centre = (p + z * z / (2.0 * of)) / denominator;
      final double half = z * Math.sqrt(p * (1 - p) / of + z * z / (4.0 * of * of)) / denominator;
      return new Rate(count, of, p, Math.max(0, centre - half), Math.min(1, centre + half));
    }
  }

  /// One stratum's prevalence.
  public record Stratum(String name, int labeled, int contradicted, int consistent, int notCheckable, Rate prevalence) {
  }

  /// @param topPrecision   contradicted rows among the labeled rows of the top PRECISION_TOP by P(contradicted)
  /// @param confidentWrong consistent rows at P(contradicted) >= CONFIDENT, of all consistent rows
  /// @param binomialP      exact one-sided P(X >= k) for k confident-wrong of n at CONFIDENT_WRONG_RATE
  /// @param auroc          NaN unless at least AUROC_MIN_POSITIVES rows are labeled contradicted
  public record SampleVerdict(List<Stratum> strata, Stratum pooled, Rate topPrecision, Rate confidentWrong, double binomialP,
                              double auroc, double[] interval) {
  }

  private DocBars() {
  }

  public static double auroc(final List<Pair> pairs) {
    return Metrics.auroc(pairs.stream().map(p -> p.swapped().pContradicted()).toList(),
        pairs.stream().map(p -> p.real().pContradicted()).toList());
  }

  public static double[] interval(final List<Pair> pairs) {
    return Metrics.aurocInterval(pairs.stream().map(p -> p.swapped().pContradicted()).toList(),
        pairs.stream().map(p -> p.real().pContradicted()).toList(), RESAMPLES, SEED);
  }

  /// The deterministic baseline (identifier mismatch or missing name echo), identically in
  /// both arms.
  public static double baselineAuroc(final List<Pair> pairs) {
    return Metrics.auroc(pairs.stream().map(p -> p.row().baselineSwapped()).toList(),
        pairs.stream().map(p -> p.row().baselineReal()).toList());
  }

  /// Pearson r between P(contradicted) and comment length over both arms.
  public static double lengthCorrelation(final List<Pair> pairs) {
    final var p = new double[pairs.size() * 2];
    final var len = new double[pairs.size() * 2];
    for (int i = 0; i < pairs.size(); i++) {
      p[2 * i] = pairs.get(i).real().pContradicted();
      p[2 * i + 1] = pairs.get(i).swapped().pContradicted();
      len[2 * i] = pairs.get(i).row().real().comment().length();
      len[2 * i + 1] = pairs.get(i).row().swapped().comment().length();
    }
    return Metrics.pearson(p, len);
  }

  /// REAL scores ranked by P(contradicted), highest first, ties by id.
  public static List<Scored> ranked(final List<Scored> scored) {
    final var out = new ArrayList<>(scored);
    out.sort(Comparator.comparingDouble((Scored s) -> -s.real().pContradicted()).thenComparing(s -> s.row().id()));
    return out;
  }

  public static List<Scored> top(final List<Scored> scored, final int n) {
    final var ranked = ranked(scored);
    return ranked.subList(0, Math.min(n, ranked.size()));
  }

  /// Design 1's decision table, first match wins. `labels` maps row id to a label for the
  /// top rows a reader has labeled.
  public static Verdict verdict(final List<Pair> pairs, final List<Scored> allReal, final Map<String, String> labels) {
    final double auroc = auroc(pairs);
    final double[] interval = interval(pairs);
    final double baseline = baselineAuroc(pairs);
    final double r = lengthCorrelation(pairs);
    final var checks = new ArrayList<Check>();
    final boolean proxy = Math.abs(round(r)) > CORRELATION_CEILING;
    checks.add(new Check("P(contradicted) correlates with comment length", round(r), "|r| <= " + CORRELATION_CEILING, !proxy));
    final boolean separates = round(auroc) >= SEPARATION_BAR;
    checks.add(new Check("separation AUROC, SWAPPED over REAL", round(auroc), ">= " + SEPARATION_BAR, separates));
    final boolean lift = round(auroc - baseline) >= LIFT_BAR;
    checks.add(new Check("lift over the deterministic baseline", round(auroc - baseline), ">= " + LIFT_BAR, lift));
    int problems = 0;
    int labeled = 0;
    for (final var s : top(allReal, TOP_N)) {
      final var label = labels.get(s.row().id());
      if (label == null) {
        continue;
      }
      labeled++;
      if (label.equals(DocQuestions.CONTRADICTED)) {
        problems++;
      }
    }
    final boolean value = problems >= VALUE_BAR;
    checks.add(new Check("contradicted comments confirmed among the top " + TOP_N + " REAL rows (" + labeled + " read)", problems, ">= " + VALUE_BAR, value));
    final String decision;
    if (proxy) {
      decision = "kill: proxy";
    } else if (!separates) {
      decision = "kill: separation";
    } else if (!lift) {
      decision = "no lift";
    } else if (labeled == 0) {
      decision = "value bar pending";
    } else if (value) {
      decision = "keep";
    } else {
      decision = "no problem found";
    }
    return new Verdict(auroc, interval, baseline, r, List.copyOf(checks), decision);
  }

  /// Design 2 over the labeled sample: prevalence per stratum and pooled, precision of the
  /// top rows, the confident-wrong rate with its exact p, and an AUROC only when enough rows
  /// are contradicted. `stratumOf` names each row's stratum.
  public static SampleVerdict sample(final List<Scored> sample, final Map<String, String> labels, final Map<String, String> stratumOf) {
    final var byStratum = new java.util.LinkedHashMap<String, List<Scored>>();
    for (final var s : sample) {
      byStratum.computeIfAbsent(stratumOf.getOrDefault(s.row().id(), "random"), k -> new ArrayList<>()).add(s);
    }
    final var strata = new ArrayList<Stratum>();
    for (final var entry : byStratum.entrySet()) {
      strata.add(stratum(entry.getKey(), entry.getValue(), labels));
    }
    final var pooled = stratum("pooled", sample, labels);
    final var contradicted = new ArrayList<Scored>();
    final var consistent = new ArrayList<Scored>();
    for (final var s : sample) {
      final var label = labels.get(s.row().id());
      if (DocQuestions.CONTRADICTED.equals(label)) {
        contradicted.add(s);
      } else if (DocQuestions.CONSISTENT.equals(label)) {
        consistent.add(s);
      }
    }
    int topLabeled = 0;
    int topContradicted = 0;
    for (final var s : top(sample, PRECISION_TOP)) {
      final var label = labels.get(s.row().id());
      if (label == null) {
        continue;
      }
      topLabeled++;
      if (label.equals(DocQuestions.CONTRADICTED)) {
        topContradicted++;
      }
    }
    final int wrong = (int) consistent.stream().filter(s -> s.real().pContradicted() >= CONFIDENT).count();
    final var confidentWrong = Rate.of(wrong, consistent.size());
    final double binomialP = consistent.isEmpty() ? Double.NaN : binomialTail(wrong, consistent.size(), CONFIDENT_WRONG_RATE);
    double auroc = Double.NaN;
    double[] interval = {Double.NaN, Double.NaN};
    if (contradicted.size() >= AUROC_MIN_POSITIVES) {
      final var pos = contradicted.stream().map(s -> s.real().pContradicted()).toList();
      final var neg = consistent.stream().map(s -> s.real().pContradicted()).toList();
      auroc = Metrics.auroc(pos, neg);
      interval = bootstrap(pos, neg);
    }
    return new SampleVerdict(List.copyOf(strata), pooled, Rate.of(topContradicted, topLabeled), confidentWrong, binomialP, auroc, interval);
  }

  static Stratum stratum(final String name, final List<Scored> rows, final Map<String, String> labels) {
    int contradicted = 0;
    int consistent = 0;
    int notCheckable = 0;
    for (final var s : rows) {
      final var label = labels.get(s.row().id());
      if (label == null) {
        continue;
      }
      switch (label) {
        case DocQuestions.CONTRADICTED -> contradicted++;
        case DocQuestions.CONSISTENT -> consistent++;
        default -> notCheckable++;
      }
    }
    return new Stratum(name, contradicted + consistent + notCheckable, contradicted, consistent, notCheckable,
        Rate.of(contradicted, contradicted + consistent));
  }

  /// P(X >= k) for X ~ Binomial(n, rate): the chance of seeing at least this many confident
  /// wrong answers if the true rate were `rate`.
  static double binomialTail(final int k, final int n, final double rate) {
    double tail = 0;
    for (int i = k; i <= n; i++) {
      tail += Math.exp(logChoose(n, i) + i * Math.log(rate) + (n - i) * Math.log(1 - rate));
    }
    return Math.min(1.0, tail);
  }

  private static double logChoose(final int n, final int k) {
    double v = 0;
    for (int i = 1; i <= k; i++) {
      v += Math.log(n - k + i) - Math.log(i);
    }
    return v;
  }

  /// Unpaired percentile bootstrap: positives and negatives resampled independently. A side
  /// with nothing in it needs no case of its own: every resample is then a NaN AUROC, so both
  /// percentiles of the sorted samples are NaN too.
  static double[] bootstrap(final List<Double> pos, final List<Double> neg) {
    final var random = new java.util.Random(SEED);
    final var samples = new double[RESAMPLES];
    final var p = new ArrayList<Double>(pos.size());
    final var n = new ArrayList<Double>(neg.size());
    for (int r = 0; r < RESAMPLES; r++) {
      p.clear();
      n.clear();
      for (int i = 0; i < pos.size(); i++) {
        p.add(pos.get(random.nextInt(pos.size())));
      }
      for (int i = 0; i < neg.size(); i++) {
        n.add(neg.get(random.nextInt(neg.size())));
      }
      samples[r] = Metrics.auroc(p, n);
    }
    java.util.Arrays.sort(samples);
    return new double[]{samples[(int) Math.floor(0.025 * (RESAMPLES - 1))], samples[(int) Math.ceil(0.975 * (RESAMPLES - 1))]};
  }

  static double round(final double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }
}
