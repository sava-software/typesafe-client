package software.sava.typesafe.evals.rot;

import java.util.List;

/// One (note, member) pair ready to score: the state Jev sees, the deterministic control
/// arm's flags on the same evidence, and the provisional gold hint when the survey named
/// this member.
///
/// @param rung         0 (no source drift since the snapshot) to 3 (dozens of changed files)
/// @param controlFlags what code alone could see: `member_missing`, `identifier_missing:x`,
///                     `only_implementation_contradicted`, `test_missing:x`
/// @param goldHint     `absent`, `present`, or null
public record RotRow(String id,
                     Manifest.Entry entry,
                     int rung,
                     ReadmeNotes.MemberRef ref,
                     MemberResolver.Resolution resolution,
                     RotQuestions.State state,
                     List<String> controlFlags,
                     String goldHint) {

  public boolean controlFlag() {
    return !controlFlags.isEmpty();
  }

  public String module() {
    return entry.id();
  }
}
