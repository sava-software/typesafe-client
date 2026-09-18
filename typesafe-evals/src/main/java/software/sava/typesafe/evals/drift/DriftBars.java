package software.sava.typesafe.evals.drift;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/// Experiment D's pre-registered bars and decision table.
public final class DriftBars {

  public static final double SEPARATION_BAR = 0.75;
  public static final double LIFT_BAR = 0.10;
  public static final double CORRELATION_CEILING = 0.8;
  public static final int TOP_N = 30;
  public static final int VALUE_BAR = 5;
  public static final int NOISE_SAMPLE = 30;
  public static final int RESAMPLES = 1000;
  public static final long SEED = 7L;

  public record Scored(DriftCorpus.Row row, DriftScore score) {
  }

  public record Check(String name, double value, String required, boolean pass) {
  }

  /// @param noiseRelated   of the noise-sample CO_EDIT rows read, how many were related comment edits (-1 unread)
  /// @param noiseRead      how many of the noise sample were read
  /// @param ceiling        the separation a perfect judge could reach given the unrelated fraction; NaN unread
  public record Verdict(double auroc, double[] interval, double baselineAuroc, double sizeCorrelation, int noiseRelated,
                        int noiseRead, double ceiling, List<Check> checks, String decision) {
  }

  private DriftBars() {
  }

  static List<Double> scores(final List<Scored> rows, final String klass) {
    return rows.stream().filter(s -> s.row().klass().equals(klass)).map(s -> s.score().pAffected()).toList();
  }

  public static double auroc(final List<Scored> rows) {
    return Metrics.auroc(scores(rows, DriftCorpus.CO_EDIT), scores(rows, DriftCorpus.BODY_ONLY));
  }

  /// Rank-scaled to 0..1 within the corpus: the larger of diff size and comment-diff overlap.
  public static Map<String, Double> baselineScores(final List<Scored> rows) {
    final var bySize = rank01(rows, s -> (double) s.row().diffSize());
    final var byOverlap = rank01(rows, s -> s.row().overlap());
    final var out = new java.util.LinkedHashMap<String, Double>();
    for (final var s : rows) {
      out.put(s.row().id(), Math.max(bySize.get(s.row().id()), byOverlap.get(s.row().id())));
    }
    return out;
  }

  /// Average rank in 0..1 (ties share their mean rank), 0.5 for a single row.
  static Map<String, Double> rank01(final List<Scored> rows, final java.util.function.ToDoubleFunction<Scored> value) {
    final var sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparingDouble(value));
    final var out = new java.util.LinkedHashMap<String, Double>();
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

  public static double baselineAuroc(final List<Scored> rows) {
    final var scores = baselineScores(rows);
    return Metrics.auroc(
        rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.CO_EDIT)).map(s -> scores.get(s.row().id())).toList(),
        rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.BODY_ONLY)).map(s -> scores.get(s.row().id())).toList());
  }

  public static double sizeCorrelation(final List<Scored> rows) {
    final var p = new double[rows.size()];
    final var size = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      p[i] = rows.get(i).score().pAffected();
      size[i] = rows.get(i).row().diffSize();
    }
    return Metrics.pearson(p, size);
  }

  /// BODY_ONLY rows ranked by P(affected), highest first, ties by id.
  public static List<Scored> topBodyOnly(final List<Scored> rows) {
    final var out = new ArrayList<>(rows.stream().filter(s -> s.row().klass().equals(DriftCorpus.BODY_ONLY)).toList());
    out.sort(Comparator.comparingDouble((Scored s) -> -s.score().pAffected()).thenComparing(s -> s.row().id()));
    return out.subList(0, Math.min(TOP_N, out.size()));
  }

  /// A seeded sample of CO_EDIT rows for the noise estimate, in id order.
  public static List<DriftCorpus.Row> noiseSample(final List<DriftCorpus.Row> rows) {
    final var coEdits = new ArrayList<>(rows.stream().filter(r -> r.klass().equals(DriftCorpus.CO_EDIT)).toList());
    coEdits.sort(Comparator.comparing(DriftCorpus.Row::id));
    final var random = new Random(SEED);
    final var picked = new ArrayList<DriftCorpus.Row>();
    while (picked.size() < NOISE_SAMPLE && !coEdits.isEmpty()) {
      picked.add(coEdits.remove(random.nextInt(coEdits.size())));
    }
    picked.sort(Comparator.comparing(DriftCorpus.Row::id));
    return picked;
  }

  /// The decision table, first match wins. `topLabels` maps BODY_ONLY row ids to
  /// needed_update / no_update_needed / cannot_tell; `noiseLabels` maps CO_EDIT row ids to
  /// related / unrelated.
  public static Verdict verdict(final List<Scored> rows, final Map<String, String> topLabels, final Map<String, String> noiseLabels) {
    final double auroc = auroc(rows);
    final double[] interval = bootstrap(scores(rows, DriftCorpus.CO_EDIT), scores(rows, DriftCorpus.BODY_ONLY));
    final double baseline = baselineAuroc(rows);
    final double r = sizeCorrelation(rows);
    final var checks = new ArrayList<Check>();
    final boolean proxy = Math.abs(round(r)) > CORRELATION_CEILING;
    checks.add(new Check("P(affected) correlates with diff size", round(r), "|r| <= " + CORRELATION_CEILING, !proxy));
    final boolean separates = round(auroc) >= SEPARATION_BAR;
    checks.add(new Check("separation AUROC, CO_EDIT over BODY_ONLY", round(auroc), ">= " + SEPARATION_BAR, separates));
    final boolean lift = round(auroc - baseline) >= LIFT_BAR;
    checks.add(new Check("lift over the best deterministic baseline", round(auroc - baseline), ">= " + LIFT_BAR, lift));
    int needed = 0;
    int read = 0;
    for (final var s : topBodyOnly(rows)) {
      final var label = topLabels.get(s.row().id());
      if (label == null) {
        continue;
      }
      read++;
      if (label.equals("needed_update")) {
        needed++;
      }
    }
    final boolean value = needed >= VALUE_BAR;
    checks.add(new Check("BODY_ONLY rows in the top " + TOP_N + " confirmed as missed updates (" + read + " read)", needed, ">= " + VALUE_BAR, value));
    int related = 0;
    int noiseRead = 0;
    for (final var entry : noiseLabels.entrySet()) {
      noiseRead++;
      if (entry.getValue().equals("related")) {
        related++;
      }
    }
    final double ceiling = noiseRead == 0 ? Double.NaN : 1.0 - 0.5 * (1.0 - (double) related / noiseRead);
    final String decision;
    if (proxy) {
      decision = "kill: proxy";
    } else if (!separates) {
      decision = "kill: separation";
    } else if (!lift) {
      decision = "no lift";
    } else if (read == 0) {
      decision = "value bar pending";
    } else if (value) {
      decision = "keep";
    } else {
      decision = "nothing missed";
    }
    return new Verdict(auroc, interval, baseline, r, noiseRead == 0 ? -1 : related, noiseRead, ceiling, List.copyOf(checks), decision);
  }

  /// Unpaired percentile bootstrap.
  static double[] bootstrap(final List<Double> pos, final List<Double> neg) {
    final var random = new Random(SEED);
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
