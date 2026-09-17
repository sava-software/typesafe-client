# Mutation hardening evidence

This file contains repository-specific evidence and decisions only. Run
`./gradlew :typesafe-client:hardeningHelp` for the exact mechanics installed in this checkout,
and `./gradlew :typesafe-client:hardeningAgentTemplate` for the version-matched agent contract.
The portable decision policy lives in sava-build's `HARDENING.md`.
Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`
(typographic `×N` is equivalent).

Three suites: `request` (question and request serialization: `JsonContent`, `Question`,
`Choice`, `Noul`, `NoulCriteria`, `Score`, `SystemOneRequest`), `response` (answer and
envelope parsing: `Answer`, the answer records, `Usage`, `ModelCard`,
`SystemOneResponse`), and `client` (`TypeSafeClient` builder, `TypeSafeClientImpl`,
`RecordingTypeSafeClient`, `exceptions`). Tests, the fuzz target, and the live check are
excluded from every suite. `mutationOwnershipAudit` is clean.

## Untriaged debt

- None. The first observation (2026-09-17, sava-build 21.5.37, open-source PIT) seeded
  no rows: `request` 116/116, `response` 66/66, `client` 103/103 killed, after a
  same-day triage of the initial 33 survivors -- 12 removed by deleting capacity hints
  and a redundant emptiness check, the rest killed by tests that pin null option keys and
  ids, absent probability and legend maps, the `null` value paths of the convenience
  factories, comma placement between two questions, the status gate at 299/300 on the
  wire and at 100/199 through a fake response (a 1xx cannot be served as a final response
  by `jdk.httpserver`, so `TypeSafeClientImpl.gate` is package-private for that test),
  the environment fallback through the builder's `environment` seam, and the exception
  message at exactly the quoting limit.

## Accepted mutants

- None.

## Audited timeout causes

- None observed.
