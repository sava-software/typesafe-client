# Mutation hardening evidence

This file contains repository-specific evidence and decisions only. Run
`./gradlew :typesafe-evals:hardeningHelp` for the exact mechanics installed in this checkout,
and `./gradlew :typesafe-evals:hardeningAgentTemplate` for the version-matched agent contract.
The portable decision policy lives in sava-build's `HARDENING.md`.
Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`
(typographic `×N` is equivalent).

Suites: `text` (lexical baselines and the path scrubber, `software.sava.typesafe.evals.text`),
`corpus` (the public-repo gate, git reads, and the process runner), `report` (TSV output),
`jev` (the recording batch runner), `metrics` (the arithmetic the experiment bars use),
`rot` (Experiment A: README note parsing, the brace-matching type index, member resolution,
corpus rows, bars, and the report), `dedupe` (Experiment B: questions, corpus, pairs,
bars, and the report), `docs` (Experiment C1: doc-comment attachment, member keys, the
history miner, the stale/fresh pairing, and the miner's TSV output), `hardening`
(Experiment C2: baseline rows, README families, operator descriptions, the corpus with its
swapped arm, the bars and decision table, and the driver), and `drift` (Experiment D: the line
diff, the change corpus with its retouch filter, the pair and batched questions, the amended
decision table with its commit bootstrap, the blind sheet, and the driver).

## Untriaged debt

- None. First observations 2026-09-17: `text` 19/19, `corpus` 52/52, `report` 21/21,
  `jev` 33/33, `metrics` 138/138, `rot` 5/5 (question definitions only; the full package
  landed later the same day at 792/798 detected: 6 accepted below, 9 timeout-detected and
  audited), `dedupe` 11/11 killed. `docs` first landed at 218 killed with 35 survivors and
  11 uncovered mutants, and reached 256/256 killed with none accepted once the miner's entry
  point grew a runner seam that a temporary checkout and a scripted `gh` can drive.
  `DocComment` lost two `ArrayList` capacity hints (arithmetic with no observable effect)
  and, in the block-comment branch, an underflow check the search bound makes dead: stopping
  the search for the opening line at the first line of the file decides a close with no open
  at the same test the plain-block case already decides. `FileMembers.parameterTypes` lost an
  early return for an empty parameter list, which the general path subsumes because an empty
  list splits into one empty part whose only token is the empty string.
  The `docs` suite grew Experiment C1's corpus, question, bars, and driver and landed at 623
  killed with 71 survivors, 2 uncovered, and one minion death; it reached 684/684 killed (four
  loop-guard timeouts detected, none accepted) after dead guards came out: a blank-path test the
  suffix test subsumes, a mask check the identifier-start test subsumes, a modular sibling walk
  replaced by an explicit candidate list that never visits the member itself, a head-comment
  lookup guard a later null comparison subsumes, a non-positive sample-size return the loop
  bounds already give, and an empty-side bootstrap guard `Metrics.auroc` already answers.
  `hardening` first landed at 335 killed with 95 survivors and 13 uncovered mutants and
  reached 389/389 killed with none accepted: the triage removed dead bookkeeping in the README
  family parser (an unread bullet-line list, call-site clears that `flush` now owns, an empty
  paragraph guard the join subsumed, a loop bound rewritten over a padded copy so its mutant
  fails fast instead of looping), sentinel-free `lastIndexOf` substrings in `BaselineRow`, an
  unconditional `Path.resolve` of the module path, a `split` in place of a `$` index, and an
  unused status-count helper. It also found one real defect: the declaration spanning a row's
  `# line` hint was sorted last instead of first, so a member with more than two overloads
  could hide the relevant body from the state; the corpus rows affected were re-scored.
  `JevRunner` lost a semaphore (a synchronous
  throw leaked a permit and a removed release deadlocked into a watchdog timeout) for
  flush-when-full chunks with one failure path through `thenCompose`. `Metrics.pearson`
  lost an empty-series guard the variance check subsumes.
  `Jaccard` lost two early returns (a null-text guard and a one-side-empty guard) in favour
  of single-path code with the both-empty case decided at the division.
  `ProcessCommandRunner` lost a stderr drain thread: stderr now goes to a temp file under an
  injectable directory, which is what lets a test prove the file is removed and the
  failure message carries the whole stream without a race. `PublicRepoGate.normalize`
  validates both `owner/repo` segments instead of a blank check the slash test subsumed.
  First observation of `drift` 2026-09-18: 603 mutants, 490 killed with 104 survivors, 3
  timeout-detected, and 6 uncovered; it reached 588/603 killed with 13 accepted below and 2
  timeout-detected and audited, with no production change. The debt was almost all one-sided
  fixtures rather than missing code. `LineDiff.ops` had only ever been asked for diffs whose
  greedy reading and whose longest common subsequence agree, so it gained an addition that has
  to come before a kept line, one that has to come after, a case where pairing the sides off by
  length would cost two more edits, and one where the removed side drains on its own.
  `DriftCorpus` gained the comment-length and retouch bars at their own boundary (a comment of
  exactly the minimum length as shown, and a new comment sharing exactly nine of ten united
  tokens), a body longer than the line cap, and a comment of pure punctuation whose respacing
  only the shown-text comparison can recognise as a retouch. `DriftBars` gained one fixture per
  deterministic baseline, the correlation ceiling reached by both correlations at once and by
  each alone, a lift of exactly the bar, and the value bar read with five and with four affected
  rows in the top against a rest that loses and a rest that does not. Its commit bootstrap is
  pinned by three fixtures: one commit collapses the interval onto the AUROC, two commits put
  the separation bar inside it, and a four-commit fixture resamples finely enough at both ends
  that one resample more, one draw more per resample, or one place either way on either
  percentile index moves an end of the interval. `DriftBatch` gained the candidate bar at its own
  boundary, an inherited comment, a diff stopped exactly at the changed-line cap, a request
  holding one class, and a build over another repository's rows, a comment-only event, and a file
  whose members carry no comment. `DriftExperiment` gained a labelling sheet and a row file read
  from two repositories at once (the only way the sort and the seeded shuffle are observable when
  a commit hash leads every id), a checkout that is not a repository, one that is not public, one
  that is not a directory at all, a failed request, an answer with no usage block, and a labels
  path that names no file.
  Second observation 2026-09-18 of the suites that grew after their first pass: `corpus` 86
  mutants, `GitRepo.run` uncovered until a test read its output back, now 86/86; `docs` 685,
  `HistoryMiner.membersBefore` uncovered until the miner test read a file as it stood before a
  commit, now 685/685 (the four loop-guard timeouts unchanged); `metrics` 175 once
  `aurocInterval` arrived with Experiment C2, at 165 killed with 9 survivors and 1 timeout. Six
  of the nine died to one pinned seeded interval (a resample that keeps the previous draws,
  draws one row too many, skips the sort, or moves the lower percentile index reads a different
  pair), three are accepted below as guards whose mutated arm computes the NaN they return, and
  the loop-guard timeout is audited.

## Accepted mutants

Six `rot` rows, each an unreachable or unobservable branch in a parser; every one was
argued and then re-measured on a clean run before acceptance:

- `# empty-head-fallthrough` (`TypeIndex.member`, `RemoveConditionalMutator_EQUAL_ELSE`
  on the empty-head guard). Property: a blank declaration head is not a member. With the
  guard forced false an empty head falls through the static-initializer test, both
  constructor tests, and both `indexOf` probes to the field-name match, which fails on an
  empty string, so null is returned either way. Deleting the guard would turn `member("",
  ...)` into a constructor, so it stays. Oracle: `TypeIndexTests.declarationHeadsAreClassified`.
- `# paren-equals-sentinel` (`TypeIndex.member`, `ConditionalsBoundaryMutator` on
  `paren < equals`). `indexOf('(')` and `indexOf('=')` can only be equal at -1, which the
  preceding `paren >= 0` test excludes, so `<` and `<=` never differ. The two sibling
  boundary mutants on the line are killed by the cast-and-initializer heads in the same test.
- `# brace-is-not-keyword` (`TypeIndex.nestedIn`, `ConditionalsBoundaryMutator` on
  `open < declOffset`). One offset cannot be both a `{` and the first character of a
  `class`/`record` keyword, so equality is unreachable. Its sibling on `close < outerClose`
  is killed by `TypeIndexTests.truncatedSourcesEndAtTheirLastCharacter`.
- `# leading-dot-throws-first` (`ReadmeNotes.isExternal`, `ConditionalsBoundaryMutator`
  on `dot >= 0`). The mutant differs only at `dot == 0`, where the first segment is empty
  and `first.charAt(0)` on the previous line throws before the comparison runs; the throw
  is pinned by the `.Map` assertion in `ReadmeNotesTests`.
- `# outer-never-leads-with-dollar` (`ReadmeNotes.outer`, `ConditionalsBoundaryMutator`
  on `cut < 0`). `outer` only receives the MEMBER regex's first group, which starts with a
  letter or underscore, so `indexOf('$')` is never 0; a leading `$Inner` token is consumed
  by the CONTINUATION branch first. Private and unreachable without widening the API.
- `# self-sibling-unreachable` (`RotExperiment.run`, `RemoveConditionalMutator_EQUAL_IF`
  on the `other.id() != entry.id()` sibling filter). The mutant lists a module as its own
  sibling. Siblings are consulted by `MemberResolver` only after the module's own index
  failed to resolve a type, and the self-entry is a scan of the same directory, so it fails
  identically and can never yield `CROSS_MODULE`. The other three mutants on the line are
  killed by the multi-module fixture in `RotExperimentTests`.

- `# self-union-noop` (1 row, `Clusters.union`, `ConditionalsBoundaryMutator` on the
  first-seen-order comparison that picks which root survives). Property: the root of a
  merged component is its earliest-added member. Oracle: `ClustersAndLabelsTests` unions
  in both argument orders and asserts the root and the cluster listing. The boundary
  direction (`<` against `<=`) differs only when the two roots are the same node, and
  there both branches write that node as its own parent, which it already was. Becomes
  killable only if a self-union ever has to be observable (for example, if union() started
  counting merges); re-triage then.

Thirteen `drift` rows, each a guard whose mutated arm reaches the same value by another route,
or a boundary no corpus can land on; every one was argued and then re-measured on a clean
history-free run before acceptance:

- `# copy-at-the-cap-is-the-same-lines` (`LineDiff.limit`, `ConditionalsBoundaryMutator` on the
  input-length test). Property: input longer than MAX_LINES lines is diffed on its first
  MAX_LINES lines. The mutant differs only at exactly MAX_LINES, where it copies the array
  instead of returning it, and a full-length `Arrays.copyOf` holds the same lines in the same
  order; the only caller reads the copy element by element and never compares identities.
  Oracle: `LineDiffAndQuestionsTests.diffIsCappedWithTheCapStatedAndLongInputsAreTruncated`.
- `# nan-survives-the-two-sided-fold` (`DriftBars.baseline`, `RemoveConditionalMutator_EQUAL_ELSE`
  on the NaN test). Property: a feature with no AUROC has no two-sided strength either. With the
  guard forced through, `Math.max(a, 1 - a)` is handed NaN on both sides and returns NaN, which
  is what the guard returns; `best` filters on the same NaN test and is unmoved. Oracles:
  `DriftCorpusAndBarsTests.separationBaselinesAndRanking` for the fold and
  `DriftCorpusAndBarsTests.rank01AndBestHandleSingleRowsAndUnrankableBaselines` for the filter.
- `# nan-never-clears-the-lift-bar` (`DriftBars.verdict`, `RemoveConditionalMutator_EQUAL_IF` on
  the NaN test guarding the lift comparison). With no AUROC the mutant runs the comparison the
  guard exists to skip, and a NaN difference is neither at nor above the bar, so the lift is
  false either way. The sibling that forces the test the other way is killed by
  `DriftCorpusAndBarsTests.theLiftBarMeasuresTheScoreAgainstTheBestBaseline`.
- `# lift-value-is-nan-either-way` (`DriftBars.verdict`, `RemoveConditionalMutator_EQUAL_ELSE` on
  the NaN test in the lift check's reported value). Subtracting the best baseline's strength from
  a NaN AUROC and rounding the difference yields the NaN the guard would have written.
- `# unread-co-edits-divide-to-nan` and `# unread-body-only-divides-to-nan`
  (`DriftBars.verdict`, `RemoveConditionalMutator_EQUAL_ELSE` on the two zero-denominator tests
  behind the oracle rates). A class no reader labelled has a zero numerator as well as a zero
  denominator, because the two counters are incremented together, and 0.0 / 0 is the NaN the
  guard returns.
- `# ceiling-is-nan-either-way` (2 rows, `DriftBars.verdict`, `RemoveConditionalMutator_EQUAL_ELSE`
  and `RemoveConditionalMutator_EQUAL_IF` on the two halves of the ceiling's unread-class guard).
  Each mutant drops one half of the test; the rate that half protects is already NaN by the two
  rows above, and the ceiling is one subtraction, one division, and one addition away from it, so
  the mutant computes the NaN the guard wrote. The two siblings that force the same halves the
  other way are killed by `DriftCorpusAndBarsTests.theDecisionTableWithLabels`.
- `# no-commit-no-sample` (`DriftBars.clusterBootstrap`, `RemoveConditionalMutator_EQUAL_ELSE` on
  the empty-cluster return). With no commit to resample the mutant enters the resampling loop,
  whose inner draw loop runs over zero clusters, so every round asks for the AUROC of two empty
  score lists, gets NaN, and records nothing; the empty-sample return below it then produces the
  same pair of NaNs. The cluster count is read before it is used as a bound, so no draw is made
  against an empty population. Oracle:
  `DriftCorpusAndBarsTests.separationReadsTheCommitBootstrapAgainstTheBar`.
- `# empty-rest-has-no-upper-bound` (2 rows, `DriftBars.verdict`, `ConditionalsBoundaryMutator`
  and `RemoveConditionalMutator_ORDER_IF` on the read-count test in the value bar). A read count
  is never negative, so that conjunct's only work is short-circuiting: when nothing outside the
  top was read, the rest's Wilson bounds are NaN and the comparison the mutants let through is
  false, which is the value the conjunct was standing in for. The sibling that forces it false
  is killed by `DriftCorpusAndBarsTests.theValueBarNeedsEnoughAffectedRowsAndSeparationFromTheRest`.
- `# wilson-bounds-never-tie` (`DriftBars.verdict`, `ConditionalsBoundaryMutator` on the
  comparison between the top's Wilson lower bound and the rest's upper bound). The mutant differs
  only where the two are the same double. The only exactly representable values either bound
  takes are its clamps, 0 for a lower bound with nothing affected and 1 for an upper bound with
  everything affected, and those cannot coincide; any other tie would need two different Wilson
  expressions over integer counts to round to the same double. Becomes killable if the rule is
  ever restated as "at least as high as the rest's upper bound", which would make the tie decide.
- `# empty-mean-divides-to-nan` (`DriftBatch.meanRequestAuroc`,
  `RemoveConditionalMutator_EQUAL_ELSE` on the no-request test). With no request holding both
  classes the running sum is still 0.0, and 0.0 / 0 is the NaN the guard returns. Oracle:
  `DriftBatchTests.aurocsOverScoredCandidates`.

Three `metrics` rows, each a guard whose mutated arm reaches the NaN the guard returns; every
one was argued and then re-measured on a clean history-free run before acceptance:

- `# empty-side-divides-to-nan` (2 rows, `Metrics.auroc`, `RemoveConditionalMutator_EQUAL_ELSE`
  and `RemoveConditionalMutator_EQUAL_IF`, the two mutants that skip the empty-side return, one
  for each side). Property: with nothing to rank there is no AUROC. With the return skipped an
  empty side leaves the win count at zero and the pair count at zero, and 0.0 / 0 is the NaN the
  guard returns. Oracle: `MetricsTests.aurocIsTheMannWhitneyStatistic`, which reads NaN from an
  empty side of either kind.
- `# no-rows-resample-to-nan` (`Metrics.aurocInterval`, `RemoveConditionalMutator_EQUAL_IF`, the
  mutant that skips the early return when there are no rows). With no rows the draw loop runs
  zero times per resample, so no draw is made against an empty population, every resample is the
  AUROC of two empty lists, and the percentiles of an all-NaN array are the pair the guard
  returns. The no-resamples half of the same return is killed by
  `MetricsTests.aurocIntervalIsDeterministicAndBracketsThePointEstimate`, where an empty sample
  array would be indexed.

## Audited timeout causes

`rot-timeouts.csv` holds seven line-less keys (nine mutant instances), all `cause:liveness`:
the mutated path loses its only guarantee of finite completion, so no assertion can run
and the watchdog is the detector by construction.

- `TypeIndex.lineOf` x4 (`ConditionalsBoundaryMutator`, `RemoveConditionalMutator_ORDER_IF`,
  `MathMutator` x2): every mutation of the binary search over line starts stops the
  interval shrinking (a boundary that keeps `low == high` looping, a midpoint that no
  longer moves, or a comparison forced true), so the search never returns.
- `TypeIndex.mask` x3 (`IncrementsMutator`: the escape cursor steps backwards inside a
  string literal and re-reads the same escape forever; `MathMutator`: the delimiter-length
  addition becomes a subtraction and the cursor stops advancing past the delimiter it
  found; `RemoveConditionalMutator_EQUAL_ELSE` at two sentinel checks: an unterminated
  `/*` or `"""` that does not start at offset 0 sets the cursor to a constant below its
  current position). The last pair flips between `KILLED` and `TIMED_OUT` across seeding
  runs because `TypeIndexTests.maskingRunsToEachDelimitersEnd` kills them by assertion
  when PIT happens to run it first; either outcome is detection, and the audited row
  keeps the ratchet from reading the flip as drift.
- `ReadmeNotes.notes` x1 (`MathMutator` on the continuation cursor `j = i + 1`): the
  cursor starts one line behind the bullet and the continuation scan walks backwards
  without a lower bound; `ReadmeNotesTests.aBulletEndsWhereItsContinuationStops` kills
  it by an index exception when its line-1 fixture runs first, the README fixture times
  out otherwise.

Remedy considered and declined for now: a cycle budget inside `lineOf` and `mask` would
convert these to assertion kills but would put a fake bound in a parser whose real inputs
are whole source files; the audited set is the cheaper control while the corpus is small.

`drift-timeouts.csv` holds one line-less key (two mutant instances), `cause:liveness`:

- `DriftBars.clusterBootstrap` x2 (`RemoveConditionalMutator_ORDER_IF`): the resample loop and
  the draw loop inside it each lose their only bound. Forced true, the resample counter never
  reaches the resample budget and the draw counter never reaches the cluster count, so the
  bootstrap never returns a pair of percentiles and no assertion downstream of it can run; the
  watchdog is the detector by construction. Both are ordinary counted loops over constants, so
  there is no seam a deterministic budget could bound without putting a fake ceiling on the
  pre-registered resample count itself. The siblings that force the same two comparisons false
  leave both loops empty, which is an outcome the corpus already states, and are killed by
  `DriftCorpusAndBarsTests.separationReadsTheCommitBootstrapAgainstTheBar`.

`metrics-timeouts.csv` holds one line-less key (one mutant instance), `cause:liveness`:

- `Metrics.aurocInterval` (`RemoveConditionalMutator_ORDER_IF`): the per-resample draw loop loses
  its only bound. Forced true, the draw counter never reaches the row count, the first resample
  never completes, and no percentile is read; the watchdog is the detector by construction. It is
  an ordinary counted loop over the row count with no seam a deterministic budget could bound.
  The sibling that forces the bound false draws nothing, reads the AUROC of two empty lists, and
  is killed by `MetricsTests.aurocIntervalIsPinnedForOneSeed`.
