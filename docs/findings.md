# Findings: TypeSafe (Jev) for sava and GLAM development and testing

Working record of what was tried, what was rejected, and why. Written so the next person
does not re-run the same review. Dates are 2026-09-17 unless stated.

## The model in one paragraph

Jev 1.13 is a "System One" model: it takes a state (text or JSON) plus narrow typed
questions and returns calibrated probabilities in about 0.3 s, at $0.042 per million input
tokens. Three primitives: Choice (≤ 255 options; choice, probabilities, confidence), Score
(ordered described levels; score, probabilities, confidence), Noul (yes/no probability). It
reads instructions literally, does not reason across steps, is weak at counting, arithmetic,
and dates, is distracted by irrelevant state, and does not treat state as hostile. Many
questions over one state run in parallel in one request. Limits: 64k tokens combined,
32k for state plus the longest question.

## House rules

- Jev proposes, code and humans dispose. Nothing auto-accepts, auto-merges, or gates a build.
- Only content from public repositories is sent as request state.
- Every experiment records its exchanges (`RecordingTypeSafeClient`) and states its bar
  before the first API call.

## Round 1: eight obvious candidates, all rejected

Each candidate was assessed by three read-only reviewers (fit, data, risk) and a refuter;
every rejection cites files. The pattern across all eight: in these repositories the label
is usually computable from structure, the volume is too small to need a model, the gold
does not measure the concept, or the judgment needs cross-file reasoning.

| Candidate | Decisive reason |
| --- | --- |
| Mutation-survivor family classifier | The mutator column plus one regex reproduces the two largest families at 94–96%. HARDENING accepts on observed evidence, not a family name, and the casebook forbids treating a label as authorization for the next similar mutant. Gold is 137 free-text labels, 80 of them singletons. |
| Killing-test locator | PIT records only the first killing test (`fullMutationMatrix` is off everywhere), so the gold cannot separate "where a test belongs" from PIT's coverage ordering. |
| Gate and verify the `detect_*` LLM audits | Grep leaves 5–33 candidates per vulnerability class, so there is no fan-out to filter; no `detect_*` output was ever recorded, so a verifier has zero rows. |
| Forwarded-seat classification (V/O/P/D/G/T) | A 15-line pass over `Signer<'info>` field types reproduced every published O index with no false positives; the output would feed a mainnet signer allowance. |
| glam-actions `ActionFailure` classification | The signal is an HTTP status the repo itself formatted into the string; ONCHAIN comes from hex codes; the nine reasons are call-site facts. |
| IDL-delta adjudication | Every clause of the breaking-change taxonomy keys on IDL structure; doc-only rewrites arrived with Rust diffs in 38 of 39 commits. |
| IDL instruction → permission mapping | Gold is 12 Drift rows with three constant fields; the permission enums have no doc comments to describe options with; no consumer of the output exists any more. |
| Semantic writing lint | 599 of 1,062 findings are generated boilerplate that `--allow-term` removes; the checker is byte-pinned to an external standard. |

Side findings from that round, each a deterministic fix with no model:

- `ActionFailure.unexpected()` in glam-actions defaults every non-`IllegalArgumentException`
  to INFRA and retryable, so an unrecognized permanent venue error retries.
- A deterministic seat deriver over `build_remaining_accounts` plus an IDL flattener for
  kamino_lending's nested account structs is what the GLAM-1247 classification test needs.
- An `idl-taxonomy.py` beside `anchor_v1/tools/idl-compare.py` would surface the case the
  current gap report hides: error code 6002 repurposed from one name to another.
- Pass `--allow-term` for the nine generated boilerplate phrases in glam-next's writing check.
- Setting `fullMutationMatrix=true` for one PIT module would show whether killing-test
  ambiguity is real; today it is unobservable.

## Round 2: four adjacent candidates, two survive

| Candidate | Verdict | Decisive evidence |
| --- | --- | --- |
| Acceptance-note rot detector | Keep | About half of the acceptance notes name constructs in English ("the swap fast path", "the single-table shortcut") that no regex maps to code. A verified rotted note (`Transaction.exceedsSizeLimit`, "the only implementation"; HEAD has three) is grep-invisible. The plugin's `BaselineNotes` only checks that the label string is present. |
| Finding dedupe before refuters | Keep | In one sava workflow, 30 of 78 refuter agents re-verified two defects. Exact file:line over-merges and over-splits; 72% of same-line pairs sit in a Jaccard band holding both duplicates and distinct defects. All 463 findings across 12 finder schemas carry a summary-like field, so no schema change is needed. |
| Cross-round convergence / stop signal | Reject | The one multi-round trajectory is in a private repository; public PRs top out at 3 comments; HARDENING already assigns the stop judgment to a human. |
| Dependabot release-note screening | Reject | The one bump that cost real work had a one-line PR body with no notes; the crisp prose positives cost one line each. No label exists that is not circular. |

## Experiment A: acceptance-note rot detector

_Status: scored; awaiting hand labels. Harness: `typesafe-evals` (`software.sava.typesafe.evals.rot`),
run with `./gradlew :typesafe-evals:rot -PevalArgs="..."`. Outputs in `typesafe-evals/experiments/rot/`;
every API exchange recorded under `typesafe-evals/recordings/rot/`._

**Corpus.** The golden-fleet snapshot READMEs pinned by sava-build's manifest, read at their
snapshot commits, paired with the named member's source at HEAD of the public checkouts
(sava, json-iterator, ravina, http-servers, incident-client). 18 modules produced rows; 4
manifest entries were skipped as private or absent. 118 class-qualified member references
became rows: 105 resolve at HEAD, 3 moved to another type, 1 was removed since the
snapshot, 1 was never declared, and 8 name external types (Jetty, the JDK, sava-core from
a dependent) and are not scored. Each row's state is the note window (section, family
paragraph, bullet), up to two member bodies of at most 200 lines, sibling members the same
section names, a `premise_facts` block computed from the code (member status, declaration
count, backticked identifiers present and missing, "only implementation" claims with the
implementor count, whether cited line numbers fall inside the body, covering tests named),
and the file path. The deterministic control arm flags a row when the member is missing or
a backticked identifier is absent from the body; it flagged 34 of the 110 scorable rows.

**Run.** 110 requests, 161,028 input tokens, $0.0068, all answered. Answers: 30
`construct_absent`, 78 `construct_present`, 2 `cannot_resolve`.

**Provisional bars.** 33 rows carry a hint transcribed from the round-2 survey (11 absent,
22 present); hints are not labels, and 16 of the 44 hints match no row because the snapshot
README names those members in forms the parser does not take (a bare method under a class
heading, a family paragraph, a CSV-only member).

| bar | value | required | pass |
| --- | --- | --- | --- |
| recall of rot within the top 30% by P(absent) | 0.636 | >= 0.90 | no |
| rot rows in the top 30% with no control flag | 3 | >= 3 | yes |
| rot rows answered present at confidence >= 0.8 | 0 | 0 | yes |
| present rows in unchanged (rung-0) modules answered absent | 0 | <= 1 | yes |

The control arm alone: recall 0.364, precision 0.444 on the same 33 rows.

The four hinted-absent rows outside the top 30% are three `CourteousBalancedCall.call`
notes and `BytesJsonIterator.parseMultiByteString`. The survey marked them rotted because
their cited line numbers no longer point at the construct; the constructs themselves
(`hasCapacity` operands, `++i`, the `i >= maxTry` break, the `<= 0` wait branch,
`buf[head++]` and the `head == tail` guard) are still in the bodies, and the question asks
about constructs, not lines, by design: line drift is the deterministic
`line_hints_inside_body` fact's job. Read by the construct definition those four are
"present", every remaining hinted-absent row sits in the top 9 of the 33, and the recall bar
would pass. That is a reading, not a label; the labeling sheet decides.

**Catches the control arm missed** (no identifier flag, P(absent) shown): `JdkQueryHandler.handle`
(0.84; the note names `process(exchange)` and `executor.execute(...)`, neither in HEAD's handler),
`TransactionRecord.lambda$static$0` (0.89; the `Map.merge` lambda left the class),
`SolanaJsonRpcWebsocket.run` (0.64; the while loop the note cites moved into `runLoop`),
`LookupTableCacheMap.getOrFetchTables` (0.71; hinted absent). One high-ranked row is a harness
artefact: `JsonUtil.parseEncodedData` (0.86) has four overloads and the state showed two; the
logging call the note names lives in the core overload that was cut. Jev put 0.92 on
`depends_on_unseen` for it, and `premise_facts.bodies_shown` (2 of 4) is the deterministic tell.
A second reader, blind to Jev's answers, re-read these eight rows against HEAD and agreed
on every one.

**What did not work.** The `depends_on_unseen` Noul is uninformative here: 99 of 110 rows score
it above 0.5, because every mutation-triage note depends on a test or a PIT run that is not
shown. If kept it needs rewording to "depends on source code not shown". The `contradicted` Noul
fired above 0.5 on three rows only, all moved or missing members, which the control arm already
flags.

**Next.** Label `labeling-sheet.tsv` (110 rows: `label` absent/present/cannot, by the
construct definition above, before looking at `jev.tsv`), then
`--mode replay --labels <file>` re-renders the bars from the recordings at no cost.

## Experiment B: finding dedupe between finder and refuter phases

_Status: scored; awaiting hand labels. Harness: `typesafe-evals` (`software.sava.typesafe.evals.dedupe`),
run with `./gradlew :typesafe-evals:dedupe -PevalArgs="..."`. Outputs in `typesafe-evals/experiments/dedupe/`;
every API exchange recorded under `typesafe-evals/recordings/dedupe/`._

**Corpus.** 38 workflow journals from the public sava and sava-openjdk projects; 433 finder
findings after excluding the merge stage's re-emissions, differential-test outcomes, and
all-clears; 1,185 same-workflow, same-basename pairs, of which 118 share an exact line.
Attribution is by result shape, since only 168 of 901 journal records carry a phase label.

**Run.** 270 pairs scored (the 178 selected for labeling plus every pair of the named
workflow) in two arms, prose only and prose plus code-computed facts: 540 requests,
544,071 input tokens, $0.023, all answered. Level distribution in the prose arm: 110
different, 66 narrowed, 94 restated; 46 of the 94 restated at confidence >= 0.8.

**Named case (`wf_82d378e6-c04`, 26 findings, 78 refuters spent originally).**

| rule | six at Transaction.java:266 | five at CONVENTIONS.md:64 | two distinct at Transaction.java:435 | groups |
| --- | --- | --- | --- | --- |
| pre-registered: level 2 at confidence >= 0.8 | six singletons | one group | separate | 21 |
| post-hoc: same underlying defect, P(different) <= 0.2 | one group | one group (plus the line-9 finding) | separate | 12 |

The pre-registered rule misses the six because Jev puts almost no mass on "different"
for them (0.05 to 0.2) but splits the rest between "narrowed" and "restated": each of the
six stresses a different aspect of one defect, which the literal reading of level 1
absorbs. The distinct pair at 435 scores level 0 at confidence 0.98 under both rules. The
post-hoc rule also grouped three findings at line 438 and two at 443 that read as the
same defect each. It is reported beside the pre-registered bars, not in them; the labels
decide whether it holds up (merge safety on gold-0 pairs is computed for it too).

**Next.** Label `labeling-sheet.tsv` (178 rows: `label` 0/1/2, `needed_source` y/n),
then `--mode replay --labels <file>` re-renders the bars from the recordings at no cost.
