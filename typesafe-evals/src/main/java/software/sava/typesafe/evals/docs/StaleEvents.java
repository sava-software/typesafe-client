package software.sava.typesafe.evals.docs;

import software.sava.typesafe.evals.text.Jaccard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/// Two natural label sources for stale doc comments.
///
/// `later-reconciliation`: a body-only edit followed, in a later commit, by a comment-only
/// edit of the same member. The comment as it stood just before that reconciliation is
/// `stale`; the reconciled comment with the same body is `fresh`.
///
/// `co-edit`: one commit changes both the body and the comment of a member, and the words
/// that changed in the comment name identifiers that changed in the body. The old comment
/// paired with the new body is `stale`; the new comment with the new body is `fresh`.
///
/// Edits that fail the content filter are excluded rather than labeled.
public final class StaleEvents {

  /// A labeled (comment, body) pair.
  ///
  /// @param reconcilingCommit the comment-only commit that produced this pair
  /// @param staleSince        the body-only commit after which the comment went stale (null for fresh)
  public record Row(String label,
                    String source,
                    String reconcilingCommit,
                    String staleSince,
                    String path,
                    FileMembers.Key key,
                    String kind,
                    String signature,
                    String comment,
                    String body,
                    double commentJaccard,
                    String overlap,
                    String strictOverlap) {

    /// A word the old comment used and the new one dropped names an identifier the body
    /// also dropped (in the same commit for a co-edit, in the pending body-only edits for
    /// a later reconciliation): the comment referred to code that is gone.
    public boolean strict() {
      return !strictOverlap.isEmpty();
    }
  }

  /// Why a comment-only edit was excluded, for the summary.
  public record Excluded(String commit, FileMembers.Key key, String reason) {
  }

  public record Result(List<Row> rows, List<Excluded> excluded, int bodyOnlyEvents, int commentOnlyEvents, int coEditedEvents) {

    public long count(final String label, final String source) {
      return rows.stream().filter(r -> r.label().equals(label) && r.source().equals(source)).count();
    }

    public long strictCount(final String label) {
      return rows.stream().filter(r -> r.label().equals(label) && r.strict()).count();
    }
  }

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w]*");

  /// Comment token sets more similar than this are treated as retouches, not reconciliations.
  public static final double MAX_COMMENT_JACCARD = 0.9;

  private StaleEvents() {
  }

  public static Result pair(final List<HistoryMiner.Event> events) {
    final var rows = new ArrayList<Row>();
    final var excluded = new ArrayList<Excluded>();
    int bodyOnly = 0;
    int commentOnly = 0;
    int coEdited = 0;
    for (final var group : HistoryMiner.byMember(events).values()) {
      String staleSince = null;
      final var pendingRemoved = new HashSet<String>(); // identifiers the pending body-only edits dropped
      for (final var event : group) {
        if (event.bodyChanged() && !event.commentChanged()) {
          ++bodyOnly;
          if (event.oldComment() != null) {
            staleSince = staleSince == null ? event.commit() : staleSince;
            pendingRemoved.addAll(removedIdentifiers(event));
          }
        } else if (event.commentChanged() && !event.bodyChanged()) {
          ++commentOnly;
          if (staleSince != null && event.oldComment() != null && event.newComment() != null) {
            final var code = identifiers(event.newBody() + '\n' + event.newSignature());
            code.addAll(pendingRemoved);
            final var verdict = filter(event, code, pendingRemoved);
            emit(rows, excluded, event, verdict, "later-reconciliation", staleSince);
          }
          staleSince = null;
          pendingRemoved.clear();
        } else {
          ++coEdited;
          if (event.oldComment() != null && event.newComment() != null) {
            final var verdict = filter(event, changedIdentifiers(event), removedIdentifiers(event));
            emit(rows, excluded, event, verdict, "co-edit", event.commit());
          }
          staleSince = null;
          pendingRemoved.clear();
        }
      }
    }
    return new Result(rows, excluded, bodyOnly, commentOnly, coEdited);
  }

  private static void emit(final List<Row> rows, final List<Excluded> excluded, final HistoryMiner.Event event,
                           final Verdict verdict, final String source, final String staleSince) {
    if (verdict.reason() == null) {
      rows.add(new Row("stale", source, event.commit(), staleSince, event.path(), event.key(), event.kind(),
          event.newSignature(), event.oldComment(), event.newBody(), verdict.jaccard(), verdict.overlap(), verdict.strictOverlap()));
      rows.add(new Row("fresh", source, event.commit(), null, event.path(), event.key(), event.kind(),
          event.newSignature(), event.newComment(), event.newBody(), verdict.jaccard(), verdict.overlap(), verdict.strictOverlap()));
    } else {
      excluded.add(new Excluded(event.commit(), event.key(), source + ": " + verdict.reason()));
    }
  }

  record Verdict(double jaccard, String overlap, String strictOverlap, String reason) {
  }

  /// Identifiers of the old body-plus-signature that the new one no longer has.
  static Set<String> removedIdentifiers(final HistoryMiner.Event event) {
    final var removed = identifiers(event.oldBody() + '\n' + event.oldSignature());
    removed.removeAll(identifiers(event.newBody() + '\n' + event.newSignature()));
    return removed;
  }

  /// Identifiers that appear in exactly one of the old and new body-plus-signature texts.
  static Set<String> changedIdentifiers(final HistoryMiner.Event event) {
    final var before = identifiers(event.oldBody() + '\n' + event.oldSignature());
    final var after = identifiers(event.newBody() + '\n' + event.newSignature());
    final var changed = new HashSet<String>();
    for (final var id : before) {
      if (!after.contains(id)) {
        changed.add(id);
      }
    }
    for (final var id : after) {
      if (!before.contains(id)) {
        changed.add(id);
      }
    }
    return changed;
  }

  /// The comment edit must change content: token-set Jaccard below the cap, and at least
  /// one added or removed comment token must be one of `code`'s identifiers.
  static Verdict filter(final HistoryMiner.Event event, final Set<String> code, final Set<String> removedCode) {
    final var before = Jaccard.tokens(event.oldComment());
    final var after = Jaccard.tokens(event.newComment());
    final double jaccard = Jaccard.similarity(before, after);
    final var changed = new HashSet<String>();
    final var strict = new ArrayList<String>();
    for (final var token : before) {
      if (!after.contains(token)) {
        changed.add(token);
        if (removedCode.contains(token)) {
          strict.add(token);
        }
      }
    }
    strict.sort(null);
    for (final var token : after) {
      if (!before.contains(token)) {
        changed.add(token);
      }
    }
    final var overlap = new ArrayList<String>();
    for (final var token : changed) {
      if (code.contains(token)) {
        overlap.add(token);
      }
    }
    overlap.sort(null);
    if (jaccard >= MAX_COMMENT_JACCARD) {
      return new Verdict(jaccard, String.join(" ", overlap), String.join(" ", strict), "retouch: comment jaccard " + String.format(java.util.Locale.ROOT, "%.2f", jaccard));
    }
    if (overlap.isEmpty()) {
      return new Verdict(jaccard, "", "", "no changed token names code");
    }
    return new Verdict(jaccard, String.join(" ", overlap), String.join(" ", strict), null);
  }

  /// Lower-cased identifiers of a code fragment.
  static Set<String> identifiers(final String code) {
    final var out = new HashSet<String>();
    final var matcher = IDENTIFIER.matcher(code);
    while (matcher.find()) {
      out.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
    }
    return out;
  }
}
