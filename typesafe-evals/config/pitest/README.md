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
`rot` and `dedupe` (each experiment's question definitions; the corpus builders join them).

## Untriaged debt

- None. First observations 2026-09-17: `text` 19/19, `corpus` 52/52, `report` 21/21,
  `jev` 33/33, `metrics` 138/138, `rot` 5/5, `dedupe` 11/11 killed. `JevRunner` lost a semaphore (a synchronous
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

- `# self-union-noop` (1 row, `Clusters.union`, `ConditionalsBoundaryMutator` on the
  first-seen-order comparison that picks which root survives). Property: the root of a
  merged component is its earliest-added member. Oracle: `ClustersAndLabelsTests` unions
  in both argument orders and asserts the root and the cluster listing. The boundary
  direction (`<` against `<=`) differs only when the two roots are the same node, and
  there both branches write that node as its own parent, which it already was. Becomes
  killable only if a self-union ever has to be observable (for example, if union() started
  counting merges); re-triage then.

## Audited timeout causes

- None observed.
