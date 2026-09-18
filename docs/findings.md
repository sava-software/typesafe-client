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
| Description-versus-code consistency (added after A and B ran) | Pre-registered as Experiment C after a second adversarial review | Generalises A to doc comments (C1) and to the hardening evidence itself (C2). C2 ran, cleared its separation bar by 0.005, and the value bar confirmed 8 mis-filed rows in its top 30. C1 ran in a revisited form and was killed: contradicted comments are 2.4% of the fleet and Jev does not rank them. See below. |

## How the sheets were labeled

The three labeling sheets were filled on 2026-09-18 by independent readers that never saw a
Jev answer: for each batch of about fifteen rows, two Claude Opus agents labeled blind from
the recorded request states alone (never a response file, a `jev.tsv`, or a report), and a
third decided every disagreement. Agreement before adjudication: 105 of 109 rot rows, 172 of
178 dedupe pairs, 30 of 30 hardening rows. These are model-made labels, not human ones; each
`labels.tsv` carries the reader's one-line reason beside the label, any row can be
overwritten by hand, and `--mode replay --labels <file>` re-renders the bars at no cost.

## Experiment A: acceptance-note rot detector

_Status: scored and labeled; all four bars pass, keep. Harness: `typesafe-evals` (`software.sava.typesafe.evals.rot`),
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

**Against the blind labels (109 rows: 10 absent, 86 present, 13 cannot).**

| bar | value | required | pass |
| --- | --- | --- | --- |
| recall of rot within the top 30% by P(absent) | 1.000 | >= 0.90 | yes |
| rot rows in the top 30% with no control flag | 4 | >= 3 | yes |
| rot rows answered present at confidence >= 0.8 | 0 | 0 | yes |
| present rows in unchanged (rung-0) modules answered absent | 1 | <= 1 | yes |

All ten rotted notes sit in the top 30% and none is answered "present" with confidence. The
deterministic control arm alone finds 6 of the 10 at precision 0.18. The cost of the ranking
is 12 present rows answered absent (out of 86) and 11 of the 13 "cannot" rows answered one
way or the other: Jev rarely uses `cannot_resolve` (2 of 110), so a reviewer reading the
list top-down should expect roughly one false alarm per real catch near the top. Verdict:
keep; the natural product is a `pitestAcceptanceRotReport` task in sava-build that prints
every note ranked by P(absent) beside the deterministic flags, never a gate.

## Experiment B: finding dedupe between finder and refuter phases

_Status: scored and labeled; the pre-registered rule fails two bars, the post-hoc rule holds. Harness: `typesafe-evals` (`software.sava.typesafe.evals.dedupe`),
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

**Against the blind labels (178 pairs: 17 different, 51 narrowed, 110 restated; the readers
needed the source for 4).**

| bar | value | required | pass |
| --- | --- | --- | --- |
| merge safety: gold-different pairs merged at confidence >= 0.8 | 0 | 0 | yes |
| suppression: recall on gold-restated at confidence >= 0.8 | 0.40 | >= 0.70 | no |
| precision at recall >= 0.7 | 0.899 | >= 0.95 | no |
| lift over exact-line precision (0.653) | 0.246 | >= 0.20 | yes |
| lift over Jaccard precision (0.683) | 0.216 | >= 0.15 | yes |
| middle recall (narrowed labeled as narrowed) | 0.608 | >= 0.50 | yes |
| correlation with Jaccard | 0.586 | <= 0.80 | yes |

The pre-registered rule fails where Experiment B's first run said it would: Jev separates
"different" from "same" sharply (15 of 17 different pairs at level 0, none merged) but
splits "same" between "narrowed" and "restated" (28 of 110 restated pairs land on level 1),
so a rule keyed to level 2 suppresses too little. The post-hoc "same underlying defect" rule
(P(different) <= 0.2, narrowed or restated both count as same) groups 134 pairs at precision
1.000 and recall 0.832 with no different-defect pair grouped. It was written down before the
labels existed and is reported beside the bars, not in them. Verdict: the two-way question
is the product; a three-level score was the wrong instrument. A dedupe stage between finder
and refuter phases should ask "same defect or different" and route the groups to a human.

## Experiment C: description-versus-code consistency

_Status: C2 run and labeled; the decision table lands on keep. C1 not run (no label
source). Pre-registration: the plan file's Experiment C section, written after an adversarial
review (six lenses, adjudication, two refuters per high finding, 27 agents; seven findings
stood). Harness: `software.sava.typesafe.evals.hardening` (`./gradlew :typesafe-evals:hardening`)
and `software.sava.typesafe.evals.docs` (`./gradlew :typesafe-evals:docsMine`). Outputs in
`typesafe-evals/experiments/hardening/`; recordings under `typesafe-evals/recordings/hardening/`._

**C1, doc comments versus member bodies: no natural label source; revisited below with a
constructed design and a blind-labeled sample.** The idea was to label stale
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
| problems confirmed among the top 30 REAL rows (30 read) | 8 | >= 5 | yes |
| rows at P >= 0.8 confirmed fine (reported, not a kill) | 22 | reported | |
| **decision** | **keep** | | |

The separation bar is cleared by 0.005 with the interval straddling it: a pass, but a fragile
one, and it should be read together with the split below. The value bar holds: of the 30
REAL rows with the highest P(does_not_apply), the blind readers confirmed 8 as mis-filed
(both readers agreed on all 30). The eight are concrete: four `KeyedFlatFileImpl.deleteEntry`
rows (boundary, equality, and primitive-return mutants) filed under a paragraph that argues
only that durability calls are unobservable; two `GlobalConfigCacheImpl.run` rows whose
paragraph argues about null-state rechecks while the operator mutates the loop's timing
guard; and the `scoreTables` rows in both `ScoredTable` and `ScoredTableMeta`, where the
shared paragraph does not address the `remainingAccounts.size() < 2` break it is filed
against. The other 22 rows at the top are fine, most of them provenance paragraphs that
argue nothing, so the ranked list's top runs at about one real mis-filing per three rows.
Verdict: keep; propose `baselineNotesReport` in sava-build's plugin as a ranked review list
beside the existing label-presence check, and pre-register the reasoning-versus-provenance
split for the next run.

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

## Experiment C1 revisited: doc comments versus member bodies

_Status: Design 1 (swapped comments) run and killed by its separation bar; Design 2 (a
blind-labeled sample of the real population) run and labeled. Pre-registration: the plan file's
"C1 revisited" section and its amendments, written after a three-lens review (15 findings, 14
stood) and before the first request. Harness: `software.sava.typesafe.evals.docs`
(`./gradlew :typesafe-evals:docsExperiment`). Outputs in `typesafe-evals/experiments/docs/`;
recordings under `typesafe-evals/recordings/docs/`._

**What the review changed before the run.** Over half the first frame was IDL-generated code
under `/gen/`; a third of the swapped comments were byte-identical to their real twin after
masking (overloads sharing a comment, tag-only comments emptied by tag removal); the state
handed Jev the identifier facts the baseline measures; the criteria did not partition; and the
name-echo channel (a comment paraphrasing its member's name in prose) leaked the swap at AUROC
0.75 while the pre-registered baseline saw 0.52. All of it was fixed in the pre-registration
and the harness: generated sources excluded, swaps restricted to differently named members with
different shown comments, block tags removed with their continuations, names masked as prose,
the identifier channel moved out of the state and into a baseline that also carries name echo,
one partitioning Choice, and Design 2 re-scoped to a prevalence study because a 150-row sample
cannot carry a ranking bar at this fleet's prevalence.

**Corpus.** 461 documented members across the six public repositories (sava 216, http-servers
96, json-iterator 66, incident-client 35, ravina 29, glam-sdk-java 19 once its generated
clients are out), 373 with a swap, one row per distinct shown comment; 37 members whose comment
predates their last body change (the stale candidates) plus 113 random members form the
150-row sample.

**Design 1 run.** 834 requests, 677,206 input tokens, $0.028, all answered.

| bar | value | required | pass |
| --- | --- | --- | --- |
| P(contradicted) correlates with comment length | r = -0.090 | abs(r) <= 0.8 | yes |
| separation AUROC, SWAPPED over REAL (bootstrap 95% 0.626 to 0.696) | 0.663 | >= 0.85 | no |
| lift over the deterministic baseline (0.620) | 0.043 | >= 0.10 | no |
| **decision** | **kill: separation** | | |

**Why it failed, and what the model actually did.** The design assumed a sibling's comment over
this member's body would read as "contradicted". Jev read it as "not checkable": in the SWAPPED
arm 239 of 373 answers are not_checkable, 94 contradicted, 40 consistent; in the REAL arm 259
consistent, 87 not_checkable, 27 contradicted. That is the literal-reading model behaving as
documented: a comment about another method makes no claim this body can settle, so it is not
checkable rather than false. Post-hoc, and not in the bars: scoring the arms by one minus
P(consistent) separates them at AUROC 0.901, and the swapped arm is less consistent than its
real twin in 339 of 373 pairs. The instrument was wrong, not the model; a re-run would
pre-register "does this comment belong to this code" as the question and P(consistent) as the
score. It was not re-run here, because moving the score after seeing the data is the thing the
bars exist to prevent.

**Design 2, the real population.** The 150-row sample was labeled blind by two readers per
row with a third deciding disagreements (agreement 144 of 150; the 30-row top sheet 23 of 23
on the rows not already in the sample). Contradicted comments are rare in this fleet:

| stratum | labeled | contradicted | consistent | not checkable | prevalence (Wilson 95%) |
| --- | --- | --- | --- | --- | --- |
| stale candidates (body changed under an untouched comment) | 37 | 1 | 31 | 5 | 1 of 32 = 3.1% (0.6 to 15.7) |
| random documented members | 113 | 2 | 90 | 21 | 2 of 92 = 2.2% (0.6 to 7.6) |
| pooled | 150 | 3 | 121 | 26 | 3 of 124 = 2.4% (0.8 to 6.9) |

The three contradicted comments are real and subtle: `FieldMatcher.hash` says spans of eight
bytes or more use two word loads while the branch tests `len > Long.BYTES`; `SolanaJsonRpcWebsocket.escalateUnanswered`
says the connection is aborted here while the member only marks the lifecycle and returns;
`SolanaJsonRpcWebsocket.staleSingletonId` attributes to the caller a guard the member itself
holds. Jev ranked them 96th, 143rd, and 150th of 461 by P(contradicted) and called all three
"consistent". Of its top 20 REAL rows by P(contradicted), the readers confirmed none as
contradicted (0 of 20, Wilson upper bound 16%); no consistent row scored at or above 0.90.
Across the sample Jev's choice matched the label on 110 of 150 rows; its errors were 23
"not checkable" calls on rows the readers could check, 11 "contradicted" calls on consistent
rows, and the 3 misses above.

**Verdict: kill, and a finding about the fleet rather than the model.** About one documented
member in forty carries a comment its body contradicts, and the ones that do are subtle enough
that a literal-reading model does not see them. The signal Jev does carry, "this comment does
not belong to this code" (AUROC 0.90 post hoc), is not the question anyone needs answered
here, because comments in this fleet are fixed in the same commit as the code. There is no
product to propose for doc comments; the deterministic checks (parameter names, thrown types,
referenced identifiers) remain the right tool for the part of the problem that exists.

## Experiment D: doc drift at change time

_Status: run and labeled; the pre-registered decision table lands on kill at the separation
bar, and the labels show why. Pre-registration: the plan file's Experiment D section, its
batched arm, and the amendments after a three-lens review (15 findings, 14 stood), all written
before the first request. Harness: `software.sava.typesafe.evals.drift`
(`./gradlew :typesafe-evals:drift`). Outputs in `typesafe-evals/experiments/drift/`; recordings
under `typesafe-evals/recordings/drift/`._

**The question.** The static question (is this comment wrong at HEAD) was killed in C1. The
question that matters to the fleet is the one asked at change time: when a member's body
changes, does its comment need to change too? History labels it for free, or seems to: in
each commit that changed a documented member's body, the author either also changed the
comment (CO_EDIT) or left it byte-identical (BODY_ONLY). One Choice per row over the comment
before the change, the lines removed and added, and the member after the change, with four
options that partition: contradicted_by_change, needs_addition, unaffected, not_checkable;
the score is the sum of the first two. A batched arm sends one request per changed file with
one Noul per documented comment in it, the shape a pull-request mode would use.

**What the review changed before the run.** Classes split on the comment as shown rather than
the raw text (tag-only edits are not co-edits); one row per member, its latest change, so one
commit cannot supply a cluster; line counts out of the state (member length is a baseline, not
an input); seven two-sided baselines including the one-bit "abstract member gained or lost a
body" flag, which the reviewers predicted would carry the signal; a bootstrap that resamples
commits rather than rows; a single shuffled blind sheet of every row in both classes, from
which an oracle ceiling is computed before the value bar is read; and a value bar with a
control (the affected rate in the top 30 BODY_ONLY rows against the rate in the rest).

**Corpus.** 76 rows in 37 commits across the six public repositories: 29 CO_EDIT and 47
BODY_ONLY (sava 25 and 30, incident-client 3 and 2, json-iterator 1 and 7, ravina 0 and 4,
http-servers 0 and 2, glam-sdk-java 0 and 2). The per-member rule removed 45 earlier changes;
60 events had comments under 40 characters as shown, 16 were retouches, 7 lost their comment.
13 rows are abstract-toggle rows, all CO_EDIT, all from sava's `Transaction` and
`TransactionSkeleton` interfaces gaining default bodies. The batched arm has 42 (commit, file)
requests over 385 candidate comments, 25 of them positives.

**Run.** 118 requests (76 pair, 42 batched), 244,890 input tokens, $0.0103, all answered.
Pair-arm choices: CO_EDIT contradicted 8, needs_addition 7, unaffected 13, not_checkable 1;
BODY_ONLY contradicted 3, needs_addition 21, unaffected 23.

**Bars (first match wins).**

| bar | value | required | pass |
| --- | --- | --- | --- |
| score correlates with diff size (Pearson -0.060, Spearman 0.017) | 0.060 | both <= 0.8 | yes |
| separation AUROC, CO_EDIT over BODY_ONLY (commit bootstrap 95% 0.370 to 0.710) | 0.594 | lower bound >= 0.75 | no |
| lift over the best deterministic baseline (member lines before, 0.773) | -0.179 | >= 0.10 | no |
| affected rows among the top 30 BODY_ONLY (30 read; the other 17 hold 0) | 6 | >= 5, and Wilson lower bound above the rest's upper bound | no (0.095 against 0.184) |
| **decision** | **kill: separation** | | |

Without the 13 abstract-toggle rows the AUROC is 0.420. The strongest baseline is the length
of the member before the change, two-sided 0.773: short interface stubs are the co-edits.

**Why it failed: the label, measured.** The blind sheet (76 rows, two readers each, 70 agreed,
6 adjudicated) says the author's behaviour is not the drift label. Readers marked 13 of the 29
co-edits affected (6 contradicted, 7 needs_addition) and 16 unaffected: the comment was touched
in the same commit for reasons the body change does not require (a visibility widened, a
`volatile` added, a delegation extracted, a default body supplied under a still-true comment).
In BODY_ONLY, 6 of 47 were marked affected. A judge that agreed with the readers on every row
would separate the two classes at 0.5 + (0.448 - 0.128) / 2 = 0.660, below the 0.75 bar.
Jev's 0.594 is a miss against a target that could not have been hit; the bar was set on the
assumption, stated in the pre-registration, that the noise would be smaller.

**Against the readers' labels, post hoc and not in the bars.** With the readers' affected /
unaffected as the gold (19 against 57), the same scores rank at AUROC 0.799: 0.853 within the
co-edits, 0.699 within the body-only rows; P(contradicted_by_change) against the readers'
8 contradicted rows ranks at 0.841. The four-way choice matches the readers' label on 39 of 76
rows and the two-way split on 46: the systematic error is over-calling needs_addition (20 of
the 57 unaffected rows), which is the literal reading of "adds or removes a behaviour the
comment would need to describe" applied to any added line. All six body-only rows the readers
marked affected sit in Jev's top 30 of 47, at ranks 1, 5, 11, 22, 25, and 30, and none in the
remaining 17. The pre-registered bound rule fails on 17 control rows (the rest's Wilson upper
bound is 0.184); the chance of that placement under a random ranking is 0.055.

**The six missed updates are real.** They are what the experiment was looking for: comments
whose authors changed the code and not the words. `SolanaJsonRpcWebsocket.escalateUnanswered`
still says the connection is aborted here after the `abort()` call left the member (the same
comment C1's readers found at HEAD); `SolanaAccounts.stakeConfig` says it is deprecated after
the annotation was removed; `BaseJsonIterator.skipLiteral` lost its refill path;
`EpochInfoServiceImpl.checkCycle` gained a null-sample return the comment's enumeration of
outcomes does not list; `SolanaJsonRpcWebsocket.deferredBuild` and `startBuild` gained
behaviour (an in-flight registration, a precondition throw) under comments written for the
previous body. Fixes are one line each.

**Batched arm (reported, no bar).** 42 requests, 154,166 input tokens: 400 tokens and 0.11
requests per judged comment against 1,193 tokens and one request in the pair arm. Pooled AUROC
of the Noul over all negatives 0.875, mean within-request 0.915, but against changed-member
negatives only 0.631, and the changed-member negatives are themselves separated from the
untouched ones at 0.872. The batched Noul mostly reads which member's name appears in
`changes`, a fact the diff already states. A file mode that ships would filter candidates to
the changed members deterministically first and ask Jev only about those, at which point it is
the pair arm's question again and the pair arm's numbers apply.

**Verdict: kill under the table; the label is the finding.** Co-edit history cannot serve as
the gold for drift in this fleet: half of the co-edits are not about the body change, and the
ceiling that leaves (0.66) is below any bar worth setting. The signal Jev showed against blind
reader labels (0.80 pooled, all six missed updates in the top 30) is post hoc and rests on 19
positives; it is a reason to pre-register a follow-up with reader labels as the gold from the
start and a larger corpus than six repositories' history provides, not a reason to ship.
Until then the change-time product is the same deterministic one as at HEAD: parameter names,
thrown types, and referenced identifiers checked against the diff.
