# Mutation hardening evidence

This file contains repository-specific evidence and decisions only. Run
`./gradlew :typesafe-evals:hardeningHelp` for the exact mechanics installed in this checkout,
and `./gradlew :typesafe-evals:hardeningAgentTemplate` for the version-matched agent contract.
The portable decision policy lives in sava-build's `HARDENING.md`.
Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`
(typographic `×N` is equivalent).

One suite, `text`, over the lexical baselines the experiments compare against
(`software.sava.typesafe.evals.text`). Experiment code gets its own suite when it lands.

## Untriaged debt

- None. First observation 2026-09-17: `text` 15/15 killed after replacing two early
  returns in `Jaccard` (a null-text guard and a one-side-empty guard) with single-path
  code; the both-empty case is decided at the division.

## Accepted mutants

- None.

## Audited timeout causes

- None observed.
