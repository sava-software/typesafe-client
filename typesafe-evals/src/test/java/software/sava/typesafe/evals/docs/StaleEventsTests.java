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
