package software.sava.typesafe.evals.rot;

import software.sava.typesafe.evals.metrics.Metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// The pre-registered bars of Experiment A over labeled rows. Labels are `absent` (the
/// note's construct is gone or contradicted: rot), `present`, or `cannot`.
///
/// (a) recall of `absent` rows within the top 30% of the ranking by P(absent) >= 0.90;
/// (b) at least three `absent` rows Jev ranks in that top 30% that carry no control flag;
/// (c) no `absent` row answered `construct_present` at confidence >= 0.8;
/// (d) at most one `present` row in a rung-0 module answered `construct_absent`.
public final class RotBars {

  public static final double TOP_FRACTION = 0.30;
  public static final double RECALL_BAR = 0.90;
  public static final int CATCHES_BAR = 3;
  public static final double CONFIDENT = 0.8;
  public static final int FALSE_ALARM_CAP = 1;

  public record Row(String id, String gold, int rung, boolean controlFlag, RotScore score) {

    boolean absent() {
      return "absent".equals(gold);
    }

    boolean present() {
      return "present".equals(gold);
    }
  }

  /// Rows sorted by P(absent) descending, ties by id.
  public static List<Row> ranked(final List<Row> rows) {
    final var out = new ArrayList<>(rows);
    out.sort(Comparator.comparingDouble((Row r) -> -r.score().pAbsent()).thenComparing(Row::id));
    return List.copyOf(out);
  }

  public static double recallWithinTop(final List<Row> rows) {
    return Metrics.recallWithinTop(ranked(rows).stream().map(Row::absent).toList(), TOP_FRACTION);
  }

  /// `absent` rows in the top 30% that the control arm did not flag.
  public static List<Row> caughtOnlyByJev(final List<Row> rows) {
    final var ranked = ranked(rows);
    final int window = (int) Math.ceil(TOP_FRACTION * ranked.size());
    return ranked.subList(0, window).stream().filter(r -> r.absent() && !r.controlFlag()).toList();
  }

  public static List<Row> confidentlyWrong(final List<Row> rows) {
    return rows.stream().filter(r -> r.absent() && r.score().confidentlyPresent(CONFIDENT)).toList();
  }

  public static List<Row> falseAlarms(final List<Row> rows) {
    return rows.stream().filter(r -> r.present() && r.rung() == 0 && r.score().saysAbsent()).toList();
  }

  /// The control arm on the same rows: flagged rows over `absent` rows, and precision of a flag.
  public record ControlArm(int flagged, int truePositives, int absent) {

    public double recall() {
      return absent == 0 ? 0.0 : (double) truePositives / absent;
    }

    public double precision() {
      return flagged == 0 ? 0.0 : (double) truePositives / flagged;
    }
  }

  public static ControlArm controlArm(final List<Row> rows) {
    int flagged = 0;
    int truePositives = 0;
    int absent = 0;
    for (final var row : rows) {
      if (row.absent()) {
        ++absent;
      }
      if (row.controlFlag()) {
        ++flagged;
        if (row.absent()) {
          ++truePositives;
        }
      }
    }
    return new ControlArm(flagged, truePositives, absent);
  }

  public record Check(String bar, String value, String required, boolean pass) {
  }

  public static List<Check> checks(final List<Row> rows) {
    final double recall = recallWithinTop(rows);
    final var caught = caughtOnlyByJev(rows);
    final var wrong = confidentlyWrong(rows);
    final var alarms = falseAlarms(rows);
    return List.of(
        new Check("recall of rot within the top 30% by P(absent)", fmt(recall), ">= " + fmt(RECALL_BAR), recall >= RECALL_BAR),
        new Check("rot rows in the top 30% with no control flag", Integer.toString(caught.size()), ">= " + CATCHES_BAR, caught.size() >= CATCHES_BAR),
        new Check("rot rows answered construct_present at confidence >= " + CONFIDENT, Integer.toString(wrong.size()), "0", wrong.isEmpty()),
        new Check("present rows in rung-0 modules answered construct_absent", Integer.toString(alarms.size()), "<= " + FALSE_ALARM_CAP, alarms.size() <= FALSE_ALARM_CAP)
    );
  }

  public static boolean keep(final List<Row> rows) {
    return checks(rows).stream().allMatch(Check::pass);
  }

  public static Metrics.Confusion confusion(final List<Row> rows) {
    final var labels = List.of(RotQuestions.CONSTRUCT_PRESENT, RotQuestions.CONSTRUCT_ABSENT, RotQuestions.CANNOT_RESOLVE);
    return Metrics.confusion(labels,
        rows.stream().map(r -> switch (r.gold()) {
          case "absent" -> RotQuestions.CONSTRUCT_ABSENT;
          case "present" -> RotQuestions.CONSTRUCT_PRESENT;
          default -> RotQuestions.CANNOT_RESOLVE;
        }).toList(),
        rows.stream().map(r -> r.score().choice()).toList());
  }

  static String fmt(final double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  private RotBars() {
  }
}
