package software.sava.typesafe.evals.text;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class JaccardTests {

  @Test
  void tokensAreLowerCasedDistinctRunsOfLettersAndDigits() {
    assertEquals(Set.of("transaction", "java", "435", "two", "defects"),
        Jaccard.tokens("Transaction.java:435 -- two DEFECTS, two defects"));
    assertEquals(Set.of(), Jaccard.tokens(null));
    assertEquals(Set.of(), Jaccard.tokens(" -- ... "));
    assertEquals(Set.of("café", "naïve"), Jaccard.tokens("Café naïve"));
  }

  @Test
  void identicalTextsScoreOneAndDisjointTextsScoreZero() {
    assertEquals(1.0, Jaccard.similarity("the guard is dead", "The guard IS dead."));
    assertEquals(0.0, Jaccard.similarity("alpha beta", "gamma delta"));
  }

  @Test
  void partialOverlapIsIntersectionOverUnion() {
    // {a, b, c} vs {b, c, d}: 2 shared of 4 distinct
    assertEquals(0.5, Jaccard.similarity("a b c", "b c d"));
    // {a} vs {a, b, c, d}: 1 of 4
    assertEquals(0.25, Jaccard.similarity("a a a", "a b c d"));
    assertEquals(Jaccard.similarity("a b c", "b c d"), Jaccard.similarity("b c d", "a b c"), "symmetric");
  }

  @Test
  void anEmptySideScoresZeroNotOne() {
    assertEquals(0.0, Jaccard.similarity("", ""));
    assertEquals(0.0, Jaccard.similarity("a", ""));
    assertEquals(0.0, Jaccard.similarity(null, "a"));
  }
}
