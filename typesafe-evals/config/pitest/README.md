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
corpus rows, bars, and the report) and `dedupe` (Experiment B: questions, corpus, pairs,
bars, and the report).

## Untriaged debt

- None. First observations 2026-09-17: `text` 19/19, `corpus` 52/52, `report` 21/21,
  `jev` 33/33, `metrics` 138/138, `rot` 5/5 (question definitions only; the full package
  landed later the same day at 792/798 detected: 6 accepted below, 9 timeout-detected and
  audited), `dedupe` 11/11 killed. `JevRunner` lost a semaphore (a synchronous
  throw leaked a permit and a removed release deadlocked into a watchdog timeout) for
  flush-when-full chunks with one failure path through `thenCompose`. `Metrics.pearson`
  lost an empty-series guard the variance check subsumes.
  `Jaccard` lost two early returns (a null-text guard and a one-side-empty guard) in favour
  of single-path code with the both-empty case decided at the division.
  `ProcessCommandRunner` lost a stderr drain thread: stderr now goes to a temp file under an
  injectable directory, which is what lets a test prove the file is removed and the
  failure message carries the whole stream without a race. `PublicRepoGate.normalize`
  validates both `owner/repo` segments instead of a blank check the slash test subsumed.

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
