package software.sava.typesafe.evals.docs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class StaleEventsTests {

  private static final FileMembers.Key KEY = new FileMembers.Key("T", "m", "int");

  private static HistoryMiner.Event event(final String commit, final int ordinal, final String oldComment, final String newComment,
                                          final String oldBody, final String newBody) {
    final boolean commentChanged = !java.util.Objects.equals(oldComment, newComment);
    final boolean bodyChanged = !oldBody.equals(newBody);
    return new HistoryMiner.Event(commit, ordinal, "T.java", KEY, "method", commentChanged, bodyChanged, oldComment, newComment,
        "void m(int x)", "void m(int x)", oldBody, newBody);
  }

  /// An event with the change flags set explicitly, for inputs the miner itself never
  /// produces but `pair` has to classify.
  private static HistoryMiner.Event flagged(final String commit, final int ordinal, final boolean commentChanged,
                                            final boolean bodyChanged, final String oldComment, final String newComment,
                                            final String oldBody, final String newBody) {
    return new HistoryMiner.Event(commit, ordinal, "T.java", KEY, "method", commentChanged, bodyChanged, oldComment, newComment,
        "void m(int x)", "void m(int x)", oldBody, newBody);
  }

  @Test
  void laterReconciliationPairsTheBodyOnlyEditWithTheCommentOnlyEdit() {
    final var events = List.of(
        event("c1", 0, "Reads the cache.", "Reads the cache.", "{ return cache; }", "{ return recompute(); }"),
        event("c2", 1, "Reads the cache.", "Calls recompute every time.", "{ return recompute(); }", "{ return recompute(); }")
    );
    final var result = StaleEvents.pair(events);
    assertEquals(1, result.bodyOnlyEvents());
    assertEquals(1, result.commentOnlyEvents());
    assertEquals(0, result.coEditedEvents());
    assertEquals(2, result.rows().size());
    final var stale = result.rows().get(0);
    assertEquals("stale", stale.label());
    assertEquals("later-reconciliation", stale.source());
    assertEquals("c2", stale.reconcilingCommit());
    assertEquals("c1", stale.staleSince());
    assertEquals("Reads the cache.", stale.comment());
    assertEquals("{ return recompute(); }", stale.body(), "the stale comment is paired with the body that outlived it");
    assertEquals("cache recompute", stale.overlap(), "the identifier the pending body edit dropped counts as code");
    assertEquals("cache", stale.strictOverlap());
    assertTrue(stale.strict(), "the comment dropped the word for the identifier the body dropped");
    final var fresh = result.rows().get(1);
    assertEquals("fresh", fresh.label());
    assertNull(fresh.staleSince());
    assertEquals("Calls recompute every time.", fresh.comment());
    assertEquals(1, result.count("stale", "later-reconciliation"));
    assertEquals(0, result.count("stale", "co-edit"));
    assertTrue(result.excluded().isEmpty());
  }

  @Test
  void coEditsPairTheOldCommentWithTheNewBody() {
    final var events = List.of(
        event("c3", 0, "Uses the cache.", "Uses the registry.", "{ return cache.get(k); }", "{ return registry.get(k); }")
    );
    final var result = StaleEvents.pair(events);
    assertEquals(1, result.coEditedEvents());
    assertEquals(2, result.rows().size());
    final var stale = result.rows().getFirst();
    assertEquals("co-edit", stale.source());
    assertEquals("c3", stale.staleSince());
    assertEquals("Uses the cache.", stale.comment());
    assertEquals("{ return registry.get(k); }", stale.body());
    assertEquals("cache registry", stale.overlap());
    assertEquals("cache", stale.strictOverlap(), "a word the comment dropped names an identifier the body dropped");
    assertTrue(stale.strict());
    assertEquals(1, result.strictCount("stale"));
    assertEquals(1, result.strictCount("fresh"));
  }

  @Test
  void retouchesAndUnrelatedEditsAreExcludedNotLabeled() {
    final var events = List.of(
        // a body-only edit, then a typo fix: token sets barely differ
        event("c1", 0, "Returns the cached total of all the items.", "Returns the cached total of all the items.", "{ a; }", "{ b; }"),
        event("c2", 1, "Returns the cached total of all the items.", "Returns the cached total of all the items!", "{ b; }", "{ b; }"),
        // a body-only edit, then a rewrite that names nothing in the code
        event("c3", 2, "Alpha beta.", "Alpha beta.", "{ b; }", "{ c; }"),
        event("c4", 3, "Alpha beta.", "Gamma delta.", "{ c; }", "{ c; }"),
        // a co-edit whose comment change names nothing that changed in the body
        event("c5", 4, "Old words here.", "New words there.", "{ c; }", "{ d; }"),
        // a comment-only edit with no prior body edit: nothing to reconcile
        event("c6", 5, "New words there.", "Newer words c.", "{ d; }", "{ d; }"),
        // a comment added where none was
        event("c7", 6, null, "Now documented.", "{ d; }", "{ e; }")
    );
    final var result = StaleEvents.pair(events);
    assertTrue(result.rows().isEmpty(), result.rows().toString());
    assertEquals(List.of("later-reconciliation: retouch: comment jaccard 1.00", "later-reconciliation: no changed token names code",
            "co-edit: no changed token names code"),
        result.excluded().stream().map(StaleEvents.Excluded::reason).toList());
    assertEquals("c2", result.excluded().getFirst().commit());
    assertEquals(KEY, result.excluded().getFirst().key());
    assertEquals(2, result.bodyOnlyEvents(), "c1 and c3");
    assertEquals(3, result.commentOnlyEvents(), "c2, c4, c6");
    assertEquals(2, result.coEditedEvents(), "c5 and the comment-added c7");
  }

  @Test
  void aCoEditResetsThePendingBodyOnlyEdit() {
    final var events = List.of(
        event("c1", 0, "Uses cache.", "Uses cache.", "{ cache; }", "{ cache; x; }"),
        event("c2", 1, "Uses cache.", "Uses cache and y.", "{ cache; x; }", "{ cache; x; y; }"),
        event("c3", 2, "Uses cache and y.", "Uses cache, x and y.", "{ cache; x; y; }", "{ cache; x; y; }")
    );
    final var result = StaleEvents.pair(events);
    final var sources = result.rows().stream().map(r -> r.label() + ':' + r.source() + ':' + r.reconcilingCommit()).toList();
    assertEquals(List.of("stale:co-edit:c2", "fresh:co-edit:c2"), sources,
        "c3 follows a co-edit, not a body-only edit, so it is a plain comment edit; c2's overlap is y");
    assertEquals("y", result.rows().getFirst().overlap());
    assertTrue(result.excluded().isEmpty(), "c3 is not even a candidate");
  }

  @Test
  void staleSinceIsTheFirstUnreconciledBodyEdit() {
    final var events = List.of(
        event("c1", 0, "Reads alpha.", "Reads alpha.", "{ alpha; }", "{ beta; }"),
        event("c2", 1, "Reads alpha.", "Reads alpha.", "{ beta; }", "{ gamma; }"),
        event("c3", 2, "Reads alpha.", "Reads gamma.", "{ gamma; }", "{ gamma; }")
    );
    final var result = StaleEvents.pair(events);
    assertEquals(2, result.bodyOnlyEvents());
    assertEquals(2, result.rows().size());
    final var stale = result.rows().getFirst();
    assertEquals("c1", stale.staleSince(), "the comment went stale at the first body edit it failed to follow");
    assertEquals("c3", stale.reconcilingCommit());
    assertEquals("alpha gamma", stale.overlap(), "the identifiers of every pending body edit count as code");
    assertEquals("alpha", stale.strictOverlap());
  }

  @Test
  void pendingIdentifiersResetAtEachReconciliation() {
    final var events = List.of(
        event("c1", 0, "Reads alpha.", "Reads alpha.", "{ alpha; }", "{ beta; }"),
        event("c2", 1, "Reads alpha.", "Reads beta and alpha.", "{ beta; }", "{ beta; }"),
        event("c3", 2, "Reads beta and alpha.", "Reads beta and alpha.", "{ beta; }", "{ gamma; }"),
        event("c4", 3, "Reads beta and alpha.", "Reads gamma.", "{ gamma; }", "{ gamma; }")
    );
    final var result = StaleEvents.pair(events);
    assertEquals(4, result.rows().size(), result.rows().toString());
    assertEquals("c2", result.rows().getFirst().reconcilingCommit());
    assertEquals("beta", result.rows().getFirst().overlap());
    final var later = result.rows().get(2);
    assertEquals("c4", later.reconcilingCommit());
    assertEquals("c3", later.staleSince());
    assertEquals("beta gamma", later.overlap(),
        "alpha was dropped before the earlier reconciliation, so it is no longer pending code");
    assertEquals("beta", later.strictOverlap());
  }

  @Test
  void pendingIdentifiersResetAtACoEdit() {
    final var events = List.of(
        event("c1", 0, "Reads alpha.", "Reads alpha.", "{ alpha; }", "{ beta; }"),
        event("c2", 1, "Reads alpha.", "Reads gamma.", "{ beta; }", "{ gamma; }"),
        event("c3", 2, "Reads gamma.", "Reads gamma.", "{ gamma; }", "{ delta; }"),
        event("c4", 3, "Reads gamma.", "Reads delta, not alpha.", "{ delta; }", "{ delta; }")
    );
    final var result = StaleEvents.pair(events);
    assertEquals(4, result.rows().size(), result.rows().toString());
    assertEquals("co-edit", result.rows().getFirst().source());
    assertEquals("gamma", result.rows().getFirst().overlap());
    final var later = result.rows().get(2);
    assertEquals("later-reconciliation", later.source());
    assertEquals("c3", later.staleSince());
    assertEquals("delta gamma", later.overlap(), "the co-edit cleared what the first body edit had dropped");
    assertEquals("gamma", later.strictOverlap());
  }

  @Test
  void bothHalvesOfAPairMustExist() {
    // a body-only edit of a member with no comment at all cannot start a stale window
    final var undocumented = StaleEvents.pair(List.of(
        flagged("c1", 0, false, true, null, null, "{ cache.get(k); }", "{ registry.get(k); }"),
        event("c2", 1, "Reads the cache.", "Reads the registry.", "{ registry.get(k); }", "{ registry.get(k); }")));
    assertEquals(1, undocumented.bodyOnlyEvents());
    assertEquals(1, undocumented.commentOnlyEvents());
    assertTrue(undocumented.rows().isEmpty(), "there was no comment to go stale");
    assertTrue(undocumented.excluded().isEmpty(), "and so nothing to exclude either");

    // a comment that first appears at the reconciling commit has no stale half
    final var added = StaleEvents.pair(List.of(
        event("c1", 0, "Reads the cache.", "Reads the cache.", "{ return cache.get(k); }", "{ return recompute(); }"),
        event("c2", 1, null, "Now it recomputes.", "{ return recompute(); }", "{ return recompute(); }")));
    assertTrue(added.rows().isEmpty(), "no comment before the edit, so no stale comment");
    assertTrue(added.excluded().isEmpty());

    // a comment deleted at the reconciling commit has no fresh half
    final var deleted = StaleEvents.pair(List.of(
        event("c1", 0, "Reads the cache.", "Reads the cache.", "{ return cache.get(k); }", "{ return registry.get(k); }"),
        event("c2", 1, "Reads the cache.", null, "{ return registry.get(k); }", "{ return registry.get(k); }")));
    assertTrue(deleted.rows().isEmpty(), "no comment after the edit, so no fresh comment");
    assertTrue(deleted.excluded().isEmpty());

    // nor does a co-edit that deletes the comment
    final var coEditDeleting = StaleEvents.pair(List.of(
        event("c1", 0, "Uses the cache.", null, "{ cache.get(k); }", "{ registry.get(k); }")));
    assertEquals(1, coEditDeleting.coEditedEvents());
    assertTrue(coEditDeleting.rows().isEmpty());
    assertTrue(coEditDeleting.excluded().isEmpty());

    // an event that reports no change at all is neither a body-only nor a comment-only edit
    final var unchanged = StaleEvents.pair(List.of(flagged("c1", 0, false, false, null, null, "{ a; }", "{ a; }")));
    assertEquals(0, unchanged.bodyOnlyEvents());
    assertEquals(0, unchanged.commentOnlyEvents());
    assertEquals(1, unchanged.coEditedEvents());
    assertTrue(unchanged.rows().isEmpty());
  }

  @Test
  void aPairIsStrictOnlyWhenACommentWordNamesRemovedCode() {
    final var result = StaleEvents.pair(List.of(event("c1", 0, "Uses the cache.", "Uses the cache and the registry.",
        "{ cache.get(k); }", "{ cache.get(k); registry.get(k); }")));
    assertEquals(2, result.rows().size());
    final var stale = result.rows().getFirst();
    assertEquals("registry", stale.overlap(), "the word the comment added names the identifier the body added");
    assertEquals("", stale.strictOverlap(), "the comment dropped no word and the body dropped no identifier");
    assertFalse(stale.strict());
    assertFalse(result.rows().get(1).strict());
    assertEquals(1, result.count("stale", "co-edit"));
    assertEquals(0, result.strictCount("stale"), "a labeled pair need not be strict");
    assertEquals(0, result.strictCount("fresh"));
  }

  @Test
  void onlyTokensOneCommentLacksAreChangedTokens() {
    final var verdict = StaleEvents.filter(event("c", 0, "keep old", "keep new", "{ a; }", "{ a; }"),
        Set.of("keep", "old", "new"), Set.of("old"));
    assertNull(verdict.reason());
    assertEquals("new old", verdict.overlap(), "a token both comments keep is not changed, even when it names code");
    assertEquals("old", verdict.strictOverlap());
  }

  @Test
  void overlapsAreReportedInSortedOrder() {
    final var verdict = StaleEvents.filter(event("c", 0, "p a keep", "keep", "{ a; }", "{ a; }"),
        Set.of("a", "p"), Set.of("a", "p"));
    assertEquals("a p", verdict.overlap());
    assertEquals("a p", verdict.strictOverlap(), "sorted, not in token-set iteration order");
  }

  @Test
  void aCommentEditExactlyAtTheCapIsARetouch() {
    final var verdict = StaleEvents.filter(event("c", 0, "one two three four five six seven eight nine",
        "one two three four five six seven eight nine ten", "{ a; }", "{ a; }"), Set.of("ten"), Set.of());
    assertEquals(StaleEvents.MAX_COMMENT_JACCARD, verdict.jaccard(), "nine tokens shared out of ten");
    assertEquals("retouch: comment jaccard 0.90", verdict.reason(), "the cap itself counts as a retouch");
  }

  @Test
  void helpers() {
    assertEquals(Set.of("a", "b_c", "d1"), StaleEvents.identifiers("A + b_c(d1) - 9"));
    assertEquals(Set.of(), StaleEvents.identifiers(""));
    final var e = event("c", 0, "x", "y", "{ old(); keep(); }", "{ NEW(); keep(); }");
    assertEquals(Set.of("old", "new"), StaleEvents.changedIdentifiers(e));
    assertEquals(Set.of("old"), StaleEvents.removedIdentifiers(e));
    final var verdict = StaleEvents.filter(e, Set.of("x", "y"), Set.of("x"));
    assertNull(verdict.reason());
    assertEquals(0.0, verdict.jaccard());
    assertEquals("x y", verdict.overlap());
    assertEquals("x", verdict.strictOverlap());
    assertEquals(0.9, StaleEvents.MAX_COMMENT_JACCARD);
  }
}
