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

_Status: harness pending._

## Experiment B: finding dedupe between finder and refuter phases

_Status: harness pending._
