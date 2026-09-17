package software.sava.typesafe.evals.hardening;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class HardeningBarsTests {

  static HardeningScore score(final double pDoesNotApply, final double confidence) {
    final double rest = 1.0 - pDoesNotApply;
    final var choice = pDoesNotApply >= rest * 0.8 ? HardeningQuestions.DOES_NOT_APPLY : HardeningQuestions.APPLIES_YES;
    return new HardeningScore(choice, rest * 0.8, pDoesNotApply, rest * 0.2, confidence, 0.1);
  }

  static HardeningRow row(final String id, final int paragraphChars, final boolean wordReal, final boolean wordSwapped) {
    final var base = BaselineRow.parse("s", "p.C,m,MathMutator,SURVIVED # x # line 1");
    final var family = new ReadmeFamilies.Family("x", "s", "para", List.of(), 1);
    return new HardeningRow(id, "r/m", base, family, "RESOLVED", 1, 1, paragraphChars, HardeningQuestionsTests.state(),
        "RemoveConditionalMutator_EQUAL_ELSE", wordReal, wordSwapped, List.of());
  }

  static HardeningBars.Pair pair(final String id, final double real, final double swapped, final int chars, final boolean wordReal, final boolean wordSwapped) {
    return new HardeningBars.Pair(row(id, chars, wordReal, wordSwapped), score(real, 0.9), score(swapped, 0.9));
  }

  @Test
  void aurocRanksSwappedOverRealAndTheBaselineUsesTheWordFlags() {
    final var pairs = List.of(
        pair("a", 0.1, 0.9, 100, true, false),
        pair("b", 0.2, 0.8, 200, true, false),
        pair("c", 0.3, 0.7, 300, true, true),
        pair("d", 0.6, 0.4, 400, false, false)
    );
    // swapped {0.9,0.8,0.7,0.4} vs real {0.1,0.2,0.3,0.6}: 0.4 loses to 0.6 only -> 15 of 16
    assertEquals(0.9375, HardeningBars.auroc(pairs));
    // baseline: swapped arm "does not apply" = 1 when the paragraph lacks the swapped family word: a,b,d -> 1, c -> 0
    // real arm: a,b,c -> 0, d -> 1; wins 9 (three 1s over three 0s), ties 6 (three 1-1, three 0-0) -> 12 of 16
    assertEquals(0.75, HardeningBars.baselineAuroc(pairs));
    final var interval = HardeningBars.interval(pairs);
    assertTrue(interval[0] <= 0.9375 && 0.9375 <= interval[1]);
    assertEquals(List.of("d", "c", "b", "a"), HardeningBars.ranked(pairs).stream().map(p -> p.row().id()).toList());
    assertEquals(4, HardeningBars.top(pairs).size(), "fewer rows than TOP_N: all of them");
    // P(does_not_apply) over both arms against paragraph length
    final double r = HardeningBars.lengthCorrelation(pairs);
    assertEquals(0.0, r, 1e-9, "real rises with length exactly as swapped falls, so the arms cancel");
  }

  @Test
  void theDecisionTableFiresInPrecedenceOrder() {
    final var separating = List.of(
        pair("a", 0.1, 0.9, 100, false, false),
        pair("b", 0.2, 0.8, 100, false, false),
        pair("c", 0.3, 0.7, 100, false, false)
    );
    var verdict = HardeningBars.verdict(separating, Map.of());
    assertEquals(1.0, verdict.auroc());
    assertEquals(0.5, verdict.baselineAuroc(), "identical word flags in both arms: the baseline is a coin");
    assertEquals(0.0, verdict.lengthCorrelation(), "constant length has no variance");
    assertEquals("value bar pending", verdict.decision());
    assertEquals(5, verdict.checks().size());
    assertTrue(verdict.checks().get(0).pass());
    assertTrue(verdict.checks().get(1).pass());
    assertTrue(verdict.checks().get(2).pass());
    assertFalse(verdict.checks().get(3).pass(), "no problems confirmed yet");
    assertEquals("problems confirmed among the top 30 REAL rows (0 read)", verdict.checks().get(3).name());

    // labels: five problems among the top rows -> keep
    final var five = List.of(
        pair("a", 0.9, 0.95, 100, false, false), pair("b", 0.85, 0.95, 100, false, false), pair("c", 0.8, 0.95, 100, false, false),
        pair("d", 0.75, 0.95, 100, false, false), pair("e", 0.7, 0.95, 100, false, false), pair("f", 0.1, 0.95, 100, false, false)
    );
    verdict = HardeningBars.verdict(five, Map.of("a", "mis-filed", "b", "rotted", "c", "mis-filed", "d", "rotted", "e", "mis-filed", "f", "fine"));
    assertEquals("keep", verdict.decision());
    assertEquals(5.0, verdict.checks().get(3).value());
    assertEquals(0.0, verdict.checks().get(4).value(), "f is fine but below the confidence bar");
    verdict = HardeningBars.verdict(five, Map.of("a", "fine", "b", "fine", "c", "fine", "d", "rotted"));
    assertEquals("no problem found", verdict.decision());
    assertEquals(3.0, verdict.checks().get(4).value(), "a, b, c are confidently wrong: reported, not a kill");
    assertTrue(verdict.checks().get(4).pass());

    // no lift: the baseline separates as well as Jev
    final var wordy = List.of(
        pair("a", 0.1, 0.9, 100, true, false),
        pair("b", 0.2, 0.8, 100, true, false)
    );
    verdict = HardeningBars.verdict(wordy, Map.of());
    assertEquals(1.0, verdict.auroc());
    assertEquals(1.0, verdict.baselineAuroc());
    assertEquals("no lift", verdict.decision());
    assertFalse(verdict.checks().get(2).pass());

    // separation fails
    final var flat = List.of(pair("a", 0.5, 0.5, 100, false, false), pair("b", 0.6, 0.4, 100, false, false));
    verdict = HardeningBars.verdict(flat, Map.of());
    assertEquals("kill: separation", verdict.decision());
    assertFalse(verdict.checks().get(1).pass());

    // proxy: P tracks length in both arms
    final var proxy = List.of(
        pair("a", 0.1, 0.15, 100, false, false),
        pair("b", 0.5, 0.55, 500, false, false),
        pair("c", 0.9, 0.95, 900, false, false)
    );
    verdict = HardeningBars.verdict(proxy, Map.of());
    assertTrue(verdict.lengthCorrelation() > 0.99, "" + verdict.lengthCorrelation());
    assertEquals("kill: proxy", verdict.decision(), "the proxy rule overrides separation");
    assertFalse(verdict.checks().get(0).pass());
  }

  @Test
  void boundariesRoundToFourDecimals() {
    // separation exactly at the bar passes; a hair below rounds and fails
    assertEquals(0.85, HardeningBars.round(0.85));
    assertEquals(0.8499, HardeningBars.round(0.84994));
    assertEquals(0.85, HardeningBars.round(0.84996));
    assertEquals(0.85, HardeningBars.SEPARATION_BAR);
    assertEquals(0.10, HardeningBars.LIFT_BAR);
    assertEquals(0.8, HardeningBars.CORRELATION_CEILING);
    assertEquals(30, HardeningBars.TOP_N);
    assertEquals(5, HardeningBars.VALUE_BAR);
    assertEquals(0.8, HardeningBars.CONFIDENT);
    final var empty = HardeningBars.verdict(List.of(), Map.of());
    assertTrue(Double.isNaN(empty.auroc()));
    assertEquals("kill: separation", empty.decision(), "NaN never clears the bar");
    assertEquals(0.0, empty.lengthCorrelation());
  }

  @Test
  void topIsCappedAtThirty() {
    final var many = new java.util.ArrayList<HardeningBars.Pair>();
    for (int i = 0; i < 40; i++) {
      many.add(pair(String.format("%02d", i), i / 40.0, 0.5, 100, false, false));
    }
    final var top = HardeningBars.top(many);
    assertEquals(30, top.size());
    assertEquals("39", top.getFirst().row().id());
    assertEquals("10", top.getLast().row().id());
  }
}
