package software.sava.typesafe.evals.drift;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/// Experiment D's pre-registered bars and decision table, as amended after review: two-sided
/// deterministic baselines, a commit-clustered bootstrap read as a three-way rule, a value
/// bar with a base-rate control, and an oracle ceiling from blind labels on both classes.
public final class DriftBars {

  public static final double SEPARATION_BAR = 0.75;
  public static final double LIFT_BAR = 0.10;
  public static final double CORRELATION_CEILING = 0.8;
  public static final int TOP_N = 30;
  public static final int VALUE_BAR = 5;
  public static final int RESAMPLES = 1000;
  public static final long SEED = 7L;

  public record Scored(DriftCorpus.Row row, DriftScore score) {

    double affected() {
      return score.affected();
    }
  }

  public record Check(String name, double value, String required, boolean pass) {
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

  /// One deterministic baseline: its raw AUROC and its two-sided strength.
  public record Baseline(String name, double auroc, double twoSided) {
  }

  /// @param aurocNoToggle   AUROC with the abstract-toggle rows removed
  /// @param separation      pass / kill / undetermined, from the interval
  /// @param oracleRho       fraction of CO_EDIT rows readers marked affected; NaN unread
  /// @param oracleBeta      the same fraction among BODY_ONLY rows
  /// @param ceiling         0.5 + (rho - beta) / 2; NaN unread
  /// @param topRate         affected rate readers found in the top TOP_N BODY_ONLY rows
  /// @param restRate        the same in the remaining BODY_ONLY rows
  public record Verdict(double auroc, double[] interval, double aurocNoToggle, List<Baseline> baselines, Baseline best,
                        double pearson, double spearman, String separation, double oracleRho, double oracleBeta, double ceiling,
                        Rate topRate, Rate restRate, List<Check> checks, String decision) {
  }

  private DriftBars() {
  }

  static List<Double> scores(final List<Scored> rows, final String klass) {
    return rows.stream().filter(s -> s.row().klass().equals(klass)).map(Scored::affected).toList();
  }

  public static double auroc(final List<Scored> rows) {
    return Metrics.auroc(scores(rows, DriftCorpus.CO_EDIT), scores(rows, DriftCorpus.BODY_ONLY));
  }

  /// AUROC of a per-row feature, CO_EDIT over BODY_ONLY.
  static double featureAuroc(final List<Scored> rows, final ToDoubleFunction<Scored> feature) {
    return Metrics.auroc(rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.CO_EDIT)).map(feature::applyAsDouble).toList(),
        rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.BODY_ONLY)).map(feature::applyAsDouble).toList());
  }

  /// Average rank in 0..1 (ties share their mean rank), 0.5 for a single row.
  static Map<String, Double> rank01(final List<Scored> rows, final ToDoubleFunction<Scored> value) {
    final var sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparingDouble(value));
    final var out = new LinkedHashMap<String, Double>();
    final int n = sorted.size();
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && value.applyAsDouble(sorted.get(j + 1)) == value.applyAsDouble(sorted.get(i))) {
        j++;
      }
      final double meanRank = (i + j) / 2.0;
      for (int k = i; k <= j; k++) {
        out.put(sorted.get(k).row().id(), n == 1 ? 0.5 : meanRank / (n - 1));
      }
      i = j + 1;
    }
    return out;
  }

  /// Every pre-registered deterministic baseline, raw and two-sided.
  public static List<Baseline> baselines(final List<Scored> rows) {
    final var bySize = rank01(rows, s -> (double) s.row().diffSize());
    final var byOverlap = rank01(rows, s -> s.row().overlap());
    final var out = new ArrayList<Baseline>();
    out.add(baseline("diff size", rows, s -> (double) s.row().diffSize()));
    out.add(baseline("comment-to-diff overlap", rows, s -> s.row().overlap()));
    out.add(baseline("rank-max of size and overlap", rows, s -> Math.max(bySize.get(s.row().id()), byOverlap.get(s.row().id()))));
    out.add(baseline("member lines before", rows, s -> (double) s.row().linesBefore()));
    out.add(baseline("member lines after", rows, s -> (double) s.row().linesAfter()));
    out.add(baseline("shown comment length", rows, s -> (double) s.row().commentChars()));
    out.add(baseline("abstract member gained or lost a body", rows, s -> s.row().abstractToggle() ? 1.0 : 0.0));
    return out;
  }

  static Baseline baseline(final String name, final List<Scored> rows, final ToDoubleFunction<Scored> feature) {
    final double a = featureAuroc(rows, feature);
    return new Baseline(name, a, Double.isNaN(a) ? Double.NaN : Math.max(a, 1 - a));
  }

  public static Baseline best(final List<Baseline> baselines) {
    Baseline best = null;
    for (final var b : baselines) {
      if (!Double.isNaN(b.twoSided()) && (best == null || b.twoSided() > best.twoSided())) {
        best = b;
      }
    }
    return best == null ? new Baseline("none", Double.NaN, Double.NaN) : best;
  }

  public static double pearson(final List<Scored> rows) {
    final var p = new double[rows.size()];
    final var size = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      p[i] = rows.get(i).affected();
      size[i] = rows.get(i).row().diffSize();
    }
    return Metrics.pearson(p, size);
  }

  /// Spearman rank correlation between the score and diff size: Pearson over average ranks.
  public static double spearman(final List<Scored> rows) {
    final var byScore = rank01(rows, Scored::affected);
    final var bySize = rank01(rows, s -> (double) s.row().diffSize());
    final var a = new double[rows.size()];
    final var b = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      a[i] = byScore.get(rows.get(i).row().id());
      b[i] = bySize.get(rows.get(i).row().id());
    }
    return Metrics.pearson(a, b);
  }

  /// BODY_ONLY rows ranked by the score, highest first, ties by id.
  public static List<Scored> rankedBodyOnly(final List<Scored> rows) {
    final var out = new ArrayList<>(rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.BODY_ONLY)).toList());
    out.sort(Comparator.comparingDouble((Scored s) -> -s.affected()).thenComparing(s -> s.row().id()));
    return out;
  }

  static boolean affectedLabel(final String label) {
    return DriftQuestions.CONTRADICTED.equals(label) || DriftQuestions.NEEDS_ADDITION.equals(label);
  }

  /// The decision table, first match wins. `labels` maps row ids of either class to one of
  /// the question's four options, from the blind sheet.
  public static Verdict verdict(final List<Scored> rows, final Map<String, String> labels) {
    final double auroc = auroc(rows);
    final double[] interval = clusterBootstrap(rows);
    final var noToggle = rows.stream().filter(s -> !s.row().abstractToggle()).toList();
    final double aurocNoToggle = auroc(noToggle);
    final var baselines = baselines(rows);
    final var best = best(baselines);
    final double r = pearson(rows);
    final double rho = spearman(rows);
    final var checks = new ArrayList<Check>();
    final boolean proxy = Math.abs(round(r)) > CORRELATION_CEILING || Math.abs(round(rho)) > CORRELATION_CEILING;
    checks.add(new Check("score correlates with diff size (Pearson " + round(r) + ", Spearman " + round(rho) + ")",
        Math.max(Math.abs(round(r)), Math.abs(round(rho))), "both <= " + CORRELATION_CEILING, !proxy));
    final String separation;
    if (Double.isNaN(auroc)) {
      separation = "kill";
    } else if (round(interval[0]) >= SEPARATION_BAR) {
      separation = "pass";
    } else if (round(interval[1]) < SEPARATION_BAR) {
      separation = "kill";
    } else {
      separation = "undetermined";
    }
    checks.add(new Check("separation AUROC, CO_EDIT over BODY_ONLY (commit bootstrap " + round(interval[0]) + " to " + round(interval[1]) + ")",
        round(auroc), "interval lower bound >= " + SEPARATION_BAR, separation.equals("pass")));
    final boolean lift = !Double.isNaN(auroc) && round(auroc - best.twoSided()) >= LIFT_BAR;
    checks.add(new Check("lift over the best deterministic baseline (" + best.name() + ", " + round(best.twoSided()) + ")",
        Double.isNaN(auroc) ? Double.NaN : round(auroc - best.twoSided()), ">= " + LIFT_BAR, lift));
    // oracle ceiling and value bar from the blind labels
    int coRead = 0;
    int coAffected = 0;
    int bodyRead = 0;
    int bodyAffected = 0;
    for (final var s : rows) {
      final var label = labels.get(s.row().id());
      if (label == null) {
        continue;
      }
      if (s.row().klass().equals(DriftCorpus.CO_EDIT)) {
        coRead++;
        if (affectedLabel(label)) {
          coAffected++;
        }
      } else {
        bodyRead++;
        if (affectedLabel(label)) {
          bodyAffected++;
        }
      }
    }
    final double oracleRho = coRead == 0 ? Double.NaN : (double) coAffected / coRead;
    final double oracleBeta = bodyRead == 0 ? Double.NaN : (double) bodyAffected / bodyRead;
    final double ceiling = coRead == 0 || bodyRead == 0 ? Double.NaN : 0.5 + (oracleRho - oracleBeta) / 2;
    final var ranked = rankedBodyOnly(rows);
    int topRead = 0;
    int topAffected = 0;
    int restRead = 0;
    int restAffected = 0;
    for (int i = 0; i < ranked.size(); i++) {
      final var label = labels.get(ranked.get(i).row().id());
      if (label == null) {
        continue;
      }
      if (i < TOP_N) {
        topRead++;
        if (affectedLabel(label)) {
          topAffected++;
        }
      } else {
        restRead++;
        if (affectedLabel(label)) {
          restAffected++;
        }
      }
    }
    final var topRate = Rate.of(topAffected, topRead);
    final var restRate = Rate.of(restAffected, restRead);
    final boolean enough = topAffected >= VALUE_BAR;
    final boolean beatsRest = enough && restRead > 0 && topRate.lower() > restRate.upper();
    checks.add(new Check("BODY_ONLY rows in the top " + TOP_N + " readers marked affected (" + topRead + " read; rest "
        + restAffected + " of " + restRead + ")", topAffected, ">= " + VALUE_BAR + " and Wilson lower bound above the rest's upper bound", beatsRest));
    final String decision;
    if (proxy) {
      decision = "kill: proxy";
    } else if (separation.equals("kill")) {
      decision = "kill: separation";
    } else if (!lift) {
      decision = "no lift";
    } else if (topRead == 0) {
      decision = "value bar pending";
    } else if (beatsRest) {
      decision = separation.equals("pass") ? "keep" : "keep (separation undetermined)";
    } else if (enough) {
      decision = "ranking not shown";
    } else {
      decision = "nothing missed";
    }
    return new Verdict(auroc, interval, aurocNoToggle, List.copyOf(baselines), best, r, rho, separation, oracleRho, oracleBeta, ceiling,
        topRate, restRate, List.copyOf(checks), decision);
  }

  /// Percentile bootstrap that resamples commits and takes all of their rows.
  static double[] clusterBootstrap(final List<Scored> rows) {
    final var byCommit = new LinkedHashMap<String, List<Scored>>();
    for (final var s : rows) {
      byCommit.computeIfAbsent(s.row().repo() + '#' + s.row().commit(), k -> new ArrayList<>()).add(s);
    }
    final var clusters = new ArrayList<>(byCommit.values());
    if (clusters.isEmpty()) {
      return new double[]{Double.NaN, Double.NaN};
    }
    final var random = new Random(SEED);
    final var samples = new ArrayList<Double>(RESAMPLES);
    for (int r = 0; r < RESAMPLES; r++) {
      final var pos = new ArrayList<Double>();
      final var neg = new ArrayList<Double>();
      for (int i = 0; i < clusters.size(); i++) {
        for (final var s : clusters.get(random.nextInt(clusters.size()))) {
          (s.row().klass().equals(DriftCorpus.CO_EDIT) ? pos : neg).add(s.affected());
        }
      }
      final double sample = Metrics.auroc(pos, neg);
      if (!Double.isNaN(sample)) {
        // a resample holding one class only has no AUROC and does not count
        samples.add(sample);
      }
    }
    if (samples.isEmpty()) {
      return new double[]{Double.NaN, Double.NaN};
    }
    samples.sort(null);
    final int n = samples.size();
    return new double[]{samples.get((int) Math.floor(0.025 * (n - 1))), samples.get((int) Math.ceil(0.975 * (n - 1)))};
  }

  static double round(final double value) {
    return Double.isNaN(value) ? Double.NaN : Math.round(value * 10000.0) / 10000.0;
  }
}
