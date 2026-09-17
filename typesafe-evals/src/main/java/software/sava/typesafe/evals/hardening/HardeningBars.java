package software.sava.typesafe.evals.hardening;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/// Experiment C2's pre-registered bars and decision table over the paired arms.
public final class HardeningBars {

  public static final double SEPARATION_BAR = 0.85;
  public static final double LIFT_BAR = 0.10;
  public static final double CORRELATION_CEILING = 0.8;
  public static final int TOP_N = 30;
  public static final int VALUE_BAR = 5;
  public static final double CONFIDENT = 0.8;
  public static final int RESAMPLES = 1000;
  public static final long SEED = 7L;

  /// One scored row: both arms.
  public record Pair(HardeningRow row, HardeningScore real, HardeningScore swapped) {
  }

  public record Check(String name, double value, String required, boolean pass) {
  }

  /// @param decision one of `kill: proxy`, `kill: separation`, `no lift`, `value bar pending`, `keep`, `no problem found`
  public record Verdict(double auroc, double[] interval, double baselineAuroc, double lengthCorrelation, List<Check> checks,
                        String decision) {
  }

  private HardeningBars() {
  }

  /// Positives are SWAPPED arms (should read does_not_apply), negatives REAL arms.
  public static double auroc(final List<Pair> pairs) {
    return Metrics.auroc(pairs.stream().map(p -> p.swapped().pDoesNotApply()).toList(),
        pairs.stream().map(p -> p.real().pDoesNotApply()).toList());
  }

  public static double[] interval(final List<Pair> pairs) {
    return Metrics.aurocInterval(pairs.stream().map(p -> p.swapped().pDoesNotApply()).toList(),
        pairs.stream().map(p -> p.real().pDoesNotApply()).toList(), RESAMPLES, SEED);
  }

  /// The mutator-word baseline: "does not apply" when the paragraph lacks the shown
  /// description's family words; scored 0/1 identically in both arms.
  public static double baselineAuroc(final List<Pair> pairs) {
    return Metrics.auroc(pairs.stream().map(p -> p.row().wordSwapped() ? 0.0 : 1.0).toList(),
        pairs.stream().map(p -> p.row().wordReal() ? 0.0 : 1.0).toList());
  }

  /// Pearson r between P(does_not_apply) and paragraph length over both arms.
  public static double lengthCorrelation(final List<Pair> pairs) {
    final var p = new double[pairs.size() * 2];
    final var len = new double[pairs.size() * 2];
    for (int i = 0; i < pairs.size(); i++) {
      p[2 * i] = pairs.get(i).real().pDoesNotApply();
      p[2 * i + 1] = pairs.get(i).swapped().pDoesNotApply();
      len[2 * i] = pairs.get(i).row().paragraphChars();
      len[2 * i + 1] = pairs.get(i).row().paragraphChars();
    }
    return Metrics.pearson(p, len);
  }

  /// REAL arms ranked by P(does_not_apply), highest first, ties by id.
  public static List<Pair> ranked(final List<Pair> pairs) {
    final var out = new ArrayList<>(pairs);
    out.sort(Comparator.comparingDouble((Pair p) -> -p.real().pDoesNotApply()).thenComparing(p -> p.row().id()));
    return out;
  }

  public static List<Pair> top(final List<Pair> pairs) {
    final var ranked = ranked(pairs);
    return ranked.subList(0, Math.min(TOP_N, ranked.size()));
  }

  /// The decision table, first match wins; `labels` maps row id to `mis-filed`, `rotted`,
  /// or `fine` for the top rows a human has read (empty until then).
  public static Verdict verdict(final List<Pair> pairs, final Map<String, String> labels) {
    final double auroc = auroc(pairs);
    final double[] interval = interval(pairs);
    final double baseline = baselineAuroc(pairs);
    final double r = lengthCorrelation(pairs);
    final var checks = new ArrayList<Check>();
    final boolean proxy = Math.abs(round(r)) > CORRELATION_CEILING;
    checks.add(new Check("P(does_not_apply) correlates with paragraph length", round(r), "|r| <= " + CORRELATION_CEILING, !proxy));
    final boolean separates = round(auroc) >= SEPARATION_BAR;
    checks.add(new Check("separation AUROC, SWAPPED over REAL", round(auroc), ">= " + SEPARATION_BAR, separates));
    final boolean lift = round(auroc - baseline) >= LIFT_BAR;
    checks.add(new Check("lift over the mutator-word baseline", round(auroc - baseline), ">= " + LIFT_BAR, lift));
    final var top = top(pairs);
    int problems = 0;
    int confidentlyFine = 0;
    int labeled = 0;
    for (final var pair : top) {
      final var label = labels.get(pair.row().id());
      if (label == null) {
        continue;
      }
      labeled++;
      if (label.equals("mis-filed") || label.equals("rotted")) {
        problems++;
      } else if (pair.real().pDoesNotApply() >= CONFIDENT) {
        confidentlyFine++;
      }
    }
    final boolean value = problems >= VALUE_BAR;
    checks.add(new Check("problems confirmed among the top " + TOP_N + " REAL rows (" + labeled + " read)", problems, ">= " + VALUE_BAR, value));
    checks.add(new Check("rows at P >= " + CONFIDENT + " confirmed fine (reported, not a kill)", confidentlyFine, "reported", true));
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

  static double round(final double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }
}
