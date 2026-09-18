package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/// Experiment C1's pre-registered bars: Design 1 (swapped comments, a decision table like
/// C2's) and Design 2 (a blind-labeled sample of the real population).
public final class DocBars {

  public static final double SEPARATION_BAR = 0.85;
  public static final double LIFT_BAR = 0.10;
  public static final double CORRELATION_CEILING = 0.8;
  public static final int TOP_N = 30;
  public static final int VALUE_BAR = 5;
  public static final double SAMPLE_AUROC_BAR = 0.80;
  public static final double CONFIDENT = 0.9;
  public static final double CONFIDENT_WRONG_MAX = 0.02;
  public static final int RESAMPLES = 1000;
  public static final long SEED = 7L;

  /// A row with both arms scored.
  public record Pair(DocCorpus.Row row, DocScore real, DocScore swapped) {
  }

  /// A row with its REAL score (Design 2 needs no swap).
  public record Scored(DocCorpus.Row row, DocScore real) {
  }

  public record Check(String name, double value, String required, boolean pass) {
  }

  public record Verdict(double auroc, double[] interval, double baselineAuroc, double lengthCorrelation, List<Check> checks,
                        String decision) {
  }

  /// @param prevalence contradicted rows among labeled consistent-or-contradicted rows
  public record SampleVerdict(int labeled, int contradicted, int consistent, int notCheckable, double prevalence, double auroc,
                              double[] interval, double mismatchAuroc, double lengthAuroc, double confidentWrong, List<Check> checks) {
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

  /// The identifier-mismatch baseline: the fraction of identifiers the comment names that
  /// the member lacks, computed identically in both arms.
  public static double baselineAuroc(final List<Pair> pairs) {
    return Metrics.auroc(pairs.stream().map(p -> p.row().mismatchSwapped()).toList(),
        pairs.stream().map(p -> p.row().mismatchReal()).toList());
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

  public static List<Scored> top(final List<Scored> scored) {
    final var ranked = ranked(scored);
    return ranked.subList(0, Math.min(TOP_N, ranked.size()));
  }

  /// Design 1's decision table, first match wins. `labels` maps row id to consistent /
  /// contradicted / not_checkable for the top rows a reader has labeled.
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
    checks.add(new Check("lift over the identifier-mismatch baseline", round(auroc - baseline), ">= " + LIFT_BAR, lift));
    int problems = 0;
    int labeled = 0;
    for (final var s : top(allReal)) {
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

  /// Design 2 over the labeled sample rows: `labels` maps row id to a label; not_checkable
  /// rows are counted and left out of the ranking bars.
  public static SampleVerdict sample(final List<Scored> sample, final Map<String, String> labels) {
    final var contradicted = new ArrayList<Scored>();
    final var consistent = new ArrayList<Scored>();
    int notCheckable = 0;
    for (final var s : sample) {
      final var label = labels.get(s.row().id());
      if (label == null) {
        continue;
      }
      switch (label) {
        case DocQuestions.CONTRADICTED -> contradicted.add(s);
        case DocQuestions.CONSISTENT -> consistent.add(s);
        default -> notCheckable++;
      }
    }
    final int labeled = contradicted.size() + consistent.size() + notCheckable;
    final int decided = contradicted.size() + consistent.size();
    final double prevalence = decided == 0 ? Double.NaN : (double) contradicted.size() / decided;
    final var pos = contradicted.stream().map(s -> s.real().pContradicted()).toList();
    final var neg = consistent.stream().map(s -> s.real().pContradicted()).toList();
    final double auroc = Metrics.auroc(pos, neg);
    final double[] interval = bootstrap(pos, neg);
    final double mismatchAuroc = Metrics.auroc(contradicted.stream().map(s -> s.row().mismatchReal()).toList(),
        consistent.stream().map(s -> s.row().mismatchReal()).toList());
    final double lengthAuroc = Metrics.auroc(contradicted.stream().map(s -> (double) s.row().commentChars()).toList(),
        consistent.stream().map(s -> (double) s.row().commentChars()).toList());
    long wrong = consistent.stream().filter(s -> s.real().pContradicted() >= CONFIDENT).count();
    final double confidentWrong = consistent.isEmpty() ? 0.0 : (double) wrong / consistent.size();
    final var checks = new ArrayList<Check>();
    checks.add(new Check("pooled AUROC, contradicted over consistent", round(auroc), ">= " + SAMPLE_AUROC_BAR, round(auroc) >= SAMPLE_AUROC_BAR));
    checks.add(new Check("consistent rows at P(contradicted) >= " + CONFIDENT, round(confidentWrong), "<= " + CONFIDENT_WRONG_MAX, round(confidentWrong) <= CONFIDENT_WRONG_MAX));
    checks.add(new Check("lift over the identifier-mismatch baseline", round(auroc - mismatchAuroc), ">= " + LIFT_BAR, round(auroc - mismatchAuroc) >= LIFT_BAR));
    checks.add(new Check("lift over a comment-length predictor", round(auroc - lengthAuroc), ">= " + LIFT_BAR, round(auroc - lengthAuroc) >= LIFT_BAR));
    return new SampleVerdict(labeled, contradicted.size(), consistent.size(), notCheckable, prevalence, auroc, interval,
        mismatchAuroc, lengthAuroc, confidentWrong, List.copyOf(checks));
  }

  /// Unpaired percentile bootstrap: positives and negatives resampled independently.
  static double[] bootstrap(final List<Double> pos, final List<Double> neg) {
    if (pos.isEmpty() || neg.isEmpty()) {
      return new double[]{Double.NaN, Double.NaN};
    }
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
