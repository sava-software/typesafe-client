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
| Description-versus-code consistency (added after A and B ran) | Pre-registered as Experiment C after a second adversarial review | Generalises A to doc comments (C1) and to the hardening evidence itself (C2). C1 had no label source on this fleet; C2 ran, cleared its separation bar by 0.005, and waits on the human value bar. See below. |

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

## Experiment C: description-versus-code consistency

_Status: C2 run; its pre-registered table lands on the human value bar. C1 not run (no label
source). Pre-registration: the plan file's Experiment C section, written after an adversarial
review (six lenses, adjudication, two refuters per high finding, 27 agents; seven findings
stood). Harness: `software.sava.typesafe.evals.hardening` (`./gradlew :typesafe-evals:hardening`)
and `software.sava.typesafe.evals.docs` (`./gradlew :typesafe-evals:docsMine`). Outputs in
`typesafe-evals/experiments/hardening/`; recordings under `typesafe-evals/recordings/hardening/`._

**C1, doc comments versus member bodies: no label source.** The idea was to label stale
comments from git history: a body-only edit later reconciled by a comment-only edit of the
same member. A member-level miner over the six public checkouts (1,066 Java commits) found
3 such members, plus 38 co-edits whose comment change names identifiers the body change
touched. Sampled pairs are additions (a deprecation note, a paragraph about new v1 handling),
not contradictions; only 3 pass the strict test (a word dropped from the comment names an
identifier dropped from the body). The review's refuters reproduced the counts. C1 can only
run on hand labels (frame and bars are pre-registered in the plan file) and was not run.

**C2, hardening evidence versus the mutant it explains.** Unit: one labeled accepted-baseline
row at HEAD (`class,method,mutator,status # label # line`) whose label a README family
paragraph declares, joined to the member at HEAD and to PIT's description of the operator.
Files come from `git ls-files` (a filesystem walk had counted worktree copies four times
over). 19 modules, 651 rows, 644 scorable (7 members missing at HEAD). The SWAPPED arm keeps
row, paragraph, member source, and facts byte-identical and substitutes the description of
an operator from another family, so neither the label nor the member name can separate the
arms, which the review had shown string matching alone could do (AUROC 0.84) under the
first design.

**Run.** 1,232 distinct requests (28 rows share a key with another label and so a request),
1,969,192 input tokens, $0.083, all answered. Choices in the REAL arm: applies 363,
does_not_apply 178, cannot_tell 103; SWAPPED arm: does_not_apply 517, cannot_tell 100,
applies 27. The swapped arm scored higher than its real twin in 530 of 616 unique rows.

A harness defect surfaced between the first and the final run, found by the mutation triage
of the harness itself: the declaration spanning a row's `# line` hint was sorted last instead
of first, so for the 102 rows whose member has more than two declarations the relevant body
could be cut from the state. The first run, with that defect, scored AUROC 0.844 and would
have been killed; 164 rows were re-scored after the fix. Both numbers are recorded here
because the bar sits between them.

**Bars (first match wins).**

| bar | value | required | pass |
| --- | --- | --- | --- |
| P(does_not_apply) correlates with paragraph length | r = -0.082 | abs(r) <= 0.8 | yes |
| separation AUROC, SWAPPED over REAL (bootstrap 95% 0.836 to 0.874) | 0.855 | >= 0.85 | yes |
| lift over the mutator-word baseline (0.617) | 0.238 | >= 0.10 | yes |
| problems confirmed among the top 30 REAL rows | pending | >= 5 | |
| **decision** | **value bar pending** | | |

The separation bar is cleared by 0.005 with the interval straddling it: a pass, but a fragile
one, and it should be read together with the split below. The decision now rests on the
human value bar: `labeling-sheet.tsv` holds the 30 REAL rows with the highest
P(does_not_apply), in id order and without scores (labels mis-filed / rotted / fine). The
ranking is led by `KeyedFlatFileImpl.deleteEntry` in glam-sdk-java/services (four rows at
P 0.99 to 1.00), `ScoredTable.scoreTables` and `ScoredTableMeta.scoreTables` in
ravina-solana, and the `SolanaJsonRpcWebsocket` rows in sava-rpc whose labels record
provenance; 17 of the 30 come from glam-sdk-java/services and 9 from sava-rpc. Five or more
confirmed problems keep the candidate and propose `baselineNotesReport` in sava-build's
plugin as a ranked review list; fewer records "no problem found at the top" and stops.

**Post-hoc, reported beside the bars and not in them.** 95 rows are `cannot_tell` in both
arms (the paragraph records history rather than reasoning, so no description applies);
without them the AUROC is 0.899. Splitting by label kind: the 80 provenance-labeled rows
(`killed retained`, `retired implementation retained`, flip-insurance families) score 0.596,
the other 536 score 0.887. Per module: sava-rpc 0.718 and glam-sdk-java/services 0.714 carry
most of the provenance rows; sava-core 0.938, ravina-solana 0.952, ravina-core 0.940,
incident-pagerduty 1.000. The review had argued, and the pre-registration accepted, that
provenance rows stay in because they are the rows most likely to be stale; a follow-up that
pre-registers the split (reasoning paragraphs versus provenance paragraphs, with a "records
history" option) is the natural next design.

**What did work.** Jev's `cannot_tell` lands on the provenance paragraphs, which is the right
answer for text that argues nothing. Cost and speed were as before: eight cents for 1,232
judgments. The leakage controls held: the length proxy is absent and the word baseline is
beaten by 0.24. And the mutation triage of the harness caught a state-assembly bug that a
green test suite had not.
