# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. Machine-specific
information (local clone paths of reference repositories) belongs in a git-ignored
`AGENTS.local.md`, never here.

## What this repository is

A Java client for the TypeSafe System One API (`POST /v1/systemone`, `GET /v1/models`),
with no dependencies beyond the JDK, json-iterator, and sava-rpc's `JsonHttpClient` base.
JPMS modules built with the shared sava-build convention plugins; published to GitHub
Packages only (Maven Central is not used for this artifact).

- `typesafe-client/` — the client: `TypeSafeClient` + builder, `TypeSafeClientImpl`
  (transport), `Question` (`Choice` / `Noul` / `Score`) and `JsonContent` (hand-written
  JSON out), `Answer` (`ChoiceAnswer` / `NoulAnswer` / `ScoreAnswer` / `UnknownAnswer`),
  `SystemOneRequest` / `SystemOneResponse` / `Usage` / `ModelCard` (json-iterator in),
  `RecordingTypeSafeClient` (SHA-256-keyed record/replay), and `exceptions`.
- `typesafe-examples/` — runnable usage; not published.
- `typesafe-evals/` — the evaluation harness for the experiments recorded in
  `docs/findings.md`; not published. Reads only content from public repositories.

## Canonical API reference

The wire shapes here are hand-written; the live documentation is the ground truth. Start
from the index at `https://docs.typesafe.ai/llms.txt`; Mintlify serves any page as
Markdown by appending `.md`. Verify against `https://docs.typesafe.ai/api.md`, the
primitive pages under `/primitives/`, and the model-jaggedness page for the current model's
limits. TypeSafe publishes no OpenAPI document and no Java SDK; the Python and JavaScript
SDKs are the closest reference implementations.

## JSON conventions

- Serialization is hand-rolled through `JsonContent`; every caller-supplied string is
  escaped with json-iterator's `JIUtil.escapeJson` on its way out, map keys included.
  `JsonContent.raw` is the one unescaped path and is documented as the caller's promise.
- Optional fields are omitted when null (`instructions`, `criteria` on a Noul); a null
  `criteria` *value* inside a Choice or Score writes as `null`, which the API accepts.
- Parsing skips unknown fields, keeps unknown answer types, and fails malformed input with
  a `RuntimeException` only (the fuzz target enforces this). Field order on the wire is not
  assumed: an answer's `type` may follow the fields it interprets.
- Exact-string tests in `QuestionSerializationTests` pin the documented examples byte for
  byte; when a serialized field changes, extend them and the parser tests together.

## House rules for using the API from this fleet

- Jev proposes, code and humans dispose: nothing built on this client auto-accepts,
  auto-merges, or gates a build. Outputs land in reports a person reads.
- Only content from public repositories may be sent as request state. Corpus builders in
  `typesafe-evals` verify visibility with `gh repo view <owner>/<repo> --json visibility`
  and fail closed.
- Keep the API key out of every file under a repository. The live check reads
  `TYPESAFE_API_KEY` from the environment and is off unless `TYPESAFE_LIVE_CHECK=true`.
- Never add assistant attribution to commits or PRs.

## Build, test, and hardening

```
./gradlew check                                   # build + unit, wire, and replay tests
./gradlew :typesafe-client:pitestRequest|pitestResponse|pitestClient
./gradlew :typesafe-evals:pitestText
./gradlew :typesafe-client:fuzzResponse -PmaxFuzzTime=<seconds>
./gradlew :typesafe-client:hardeningHelp          # installed-version authority; run it
TYPESAFE_LIVE_CHECK=true ./gradlew :typesafe-client:test --tests '*LiveCheck*'
```

Records live per module under `config/pitest/`: `<suite>-accepted.csv`, the acceptance
arguments in `README.md`, and the bound toolchain provenance sidecars. CI runs `check`
only. `:typesafe-client:hardeningCertify` plus a local `:typesafe-client:fuzzAll
-PmaxFuzzTime=<seconds>` campaign are release-checklist items, run locally with both
budgets recorded in the release notes.

Conventions to preserve:

- Every mutation suite sets `excludedClasses` to keep PIT off tests, fuzz targets, and the
  live check; keep those exclusions when adding a suite.
- Fuzz targets live in test sources, are free of Jazzer imports (a public static
  `fuzzerTestOneInput(byte[])`), and follow "garbage in → RuntimeException out". Seed
  corpora live under `src/test/resources/fuzz/<target>/` with their provenance in the
  README beside the corpus directory, never inside it.
- Tests never hit the network except the explicitly gated live check; wire tests serve real
  bodies from `jdk.httpserver` on the loopback address.

<!-- hardening-template block:start -->
- Iterate with the module's `test` task. Before handoff, run each `pitest<Suite>`
  whose mutated code the change can reach, including suites in dependent modules,
  and `mutationOwnershipAudit` when production classes or target/exclusion rules
  change. `hardeningCertify` (or `:hardeningCertifyAll`) is the pre-release check
  this repo's notes assign an owner to, not the inner loop.
- Iterate on one cluster with `-PmutateOnly=<class-glob>`. Before any record
  decision, re-run unscoped with `-PnoMutationHistory`: a `[history]` report cannot
  support adding, removing, or relabelling records.
- An unkilled mutant has three outcomes: kill it with a test that asserts the
  property it breaks, refactor it out of existence, or accept it with a written
  reason in `config/pitest/README.md` and a family label on the row. Refreshes seed
  rows `# untriaged`; triage replaces that label. Never accept a `NO_COVERAGE`
  mutant as equivalent; it is an untested line.
- A mutant is a question, not a specification. State the intended property and an
  oracle independent of the implementation before writing the killing test. If they
  contradict current behaviour, prove the bug with a failing regression test first,
  then fix production; never lock a bug in with a passing assertion.
- Write records only through the installed writer tasks: `BaselineUnion` adds
  reviewed rows, `BaselineRetag` refreshes `# line` metadata, `BaselinePrune` deletes
  only after two matching fresh history-free previews, `BaselineUpdate` is for a
  first seed or a reviewed complete rewrite, and `pitest<Suite>BaselineRebase`
  follows a PIT, PIT-plugin/tool-artifact, ArcMutate-base, or certificate change.
  Never hand-edit baseline
  rows or provenance stamps.
- Baseline keys are line-less (`class,method,mutator,STATUS`); `# line` tags are
  review metadata. Identical rows are sibling mutants and the comparison is a
  multiset: never hand-dedupe.
- A new `TIMED_OUT` mutant is a reviewer stop, never detection. Record it in
  `config/pitest/<suite>-timeouts.csv` with a cause and argue it in the README; only
  `cause:liveness` certifies. A member whose coordinate has left the population is
  removed by hand after one fresh history-free run with valid committed provenance
  omits it; while provenance is invalid, repair or rebase it first.
- Tests are deterministic: fixed seeds, no sleeps, a clock with a non-zero origin,
  stubs that return distinguishable non-default values, and the subject built inside
  the test body. Exclusions must cover the test source set, not a naming convention.
- Verify by the absence of failures: trust the exit code and the `.running`
  sentinel, not a summary. `MINION_DIED` and `RUN_ERROR` are not results; re-run. A
  suite that got faster without getting narrower is a bug report.
- Fuzz findings become a committed seed input and a named regression test. Run
  `fuzzAll` locally with an explicit `-PmaxFuzzTime` and `-PmaxParallelFuzzTargets`
  before a release. Where one thing has two representations, fuzz the differential.
- `./gradlew :module:hardeningHelp` lists the installed tasks and options;
  sava-build's HARDENING.md holds the argument behind every rule above.
<!-- hardening-template block:end -->
