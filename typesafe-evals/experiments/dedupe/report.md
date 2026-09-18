# Experiment B: finding dedupe between finder and refuter phases

## Corpus

| journals | findings | same-basename pairs | exact-line pairs | selected for labeling | named-workflow pairs |
| --- | --- | --- | --- | --- | --- |
| 38 | 433 | 1185 | 118 | 178 | 126 |

## Jev

Recording hits 540, misses 0; model jev-latest. Requests 540 (540 answered), input tokens 544071, cost $0.0229.

## Named case: wf_82d378e6-c04 (prose arm, pre-registered rule: level 2 at confidence >= 0.8)

- cluster of 5:
  - CONVENTIONS.md:64 A newly documented non-guessable behaviour — createTx reordering the caller's AccountMeta[] in place — was not added to the file whose stated purpose is to make that family visible in one place.
  - CONVENTIONS.md:64 The newly documented caller-array mutation was not added to CONVENTIONS.md, which the repo declares to be the one place this family is visible and which already carries a "Byte-array ownership" section for exactly this kind of retained/shared-array surprise.
  - CONVENTIONS.md:64 A newly documented caller-visible array-ownership surprise was added at the declaration but not to CONVENTIONS.md, the file whose stated purpose is to make that family visible in one place.
  - CONVENTIONS.md:64 CONVENTIONS.md's array-ownership family was not extended with the newly documented caller-array mutation, so the file's stated guarantee that the family is 'visible in one place' no longer holds for the behaviour this delta just established as a published contract.
  - CONVENTIONS.md:64 CONVENTIONS.md's "Byte-array ownership" section is the repo's stated one-place index for exactly this caller-array-ownership family, and the delta added a new member of it without indexing it there.
- cluster of 2:
  - README.md:338 The 'Result-identical routing' preamble still forward-references an InstructionRecord 'exception' as though a live acceptance exists; the section deletion changed what 'below' points at, and no InstructionRecord row remains in any baseline.
  - README.md:338 The Result-identical routing preamble still advertises a live "singleton-null exception in InstructionRecord" to look for, but the delta deleted that row from the baseline entirely.
- singletons: 19
- refuter budget: 26 findings -> 21 groups

## Named case: wf_82d378e6-c04 (prose arm, post-hoc rule: same underlying defect, P(different) <= 0.2)

- cluster of 6:
  - Transaction.java:266 The single-table overload performs the identical in-place compaction of the caller's array but is undocumented, and the newly documented overload delegates to it for exactly one lookup table.
  - Transaction.java:266 The sibling public overload `createTx(List, int, AccountMeta[], AddressLookupTable)` mutates the caller's array identically but received none of the new javadoc, so the delta documents only one of the two array-mutating entry points.
  - Transaction.java:266 The sibling single-table overload `createTx(List, int, AccountMeta[], AddressLookupTable)` compacts the caller's `sortedAccounts` in place exactly as the newly documented overload, but carries no javadoc — and the documented overload delegates to it for the one-table case.
  - Transaction.java:266 The sibling public overload `createTx(List, int, AccountMeta[], AddressLookupTable)` compacts the caller's array in place exactly as the newly documented overload does, but the delta leaves it with no javadoc at all.
  - Transaction.java:266 Only one of the two public array-taking createTx overloads got the new in-place-mutation javadoc; the AddressLookupTable overload compacts the caller's array identically and is still undocumented — and it is the code path the documented overload's own one-table promise is delegated to.
  - Transaction.java:266 The javadoc documents the in-place caller-array mutation on only one of the two public createTx overloads that perform it; the sibling AddressLookupTable overload runs the identical compaction and stays silent, and the documented overload delegates to it.
- cluster of 3:
  - Transaction.java:438 The zero-table clause is literally true but presents a destructive path as a benign rebuild: it discards the caller's fee-payer designation and every account not referenced by an instruction, and downgrades the result to a legacy message.
  - Transaction.java:438 The new zero-table sentence is true but stops short of the consequence that matters: because the fee-payer designation exists only in `sortedAccounts`, ignoring that array discards it, and the resulting transaction has a null fee payer and an arbitrary account 0.
  - Transaction.java:438 The new zero-table sentence describes that branch as a neutral rebuild, omitting that it silently downgrades the result from versioned/v0 to legacy format and discards the fee-payer designation carried only by sortedAccounts.
- cluster of 2:
  - Transaction.java:443 `tableAccountMetas` is dereferenced without a null check while every sibling overload treats null as "no lookup tables", and the new doc's "With no lookup tables" phrasing invites passing null.
  - Transaction.java:443 The new sentence "With no lookup tables, rebuilds from `instructions`" invites passing `null` for `tableAccountMetas` - which every sibling overload accepts as "no tables" - but this overload dereferences it unguarded and throws NullPointerException.
- cluster of 6:
  - CONVENTIONS.md:64 A newly documented non-guessable behaviour — createTx reordering the caller's AccountMeta[] in place — was not added to the file whose stated purpose is to make that family visible in one place.
  - CONVENTIONS.md:9 CONVENTIONS.md claims to be the one place this family of surprises is visible, but has no entry for the createTx caller-array mutation nor for the two earlier declaration-only surprises added in this same review chain.
  - CONVENTIONS.md:64 The newly documented caller-array mutation was not added to CONVENTIONS.md, which the repo declares to be the one place this family is visible and which already carries a "Byte-array ownership" section for exactly this kind of retained/shared-array surprise.
  - CONVENTIONS.md:64 A newly documented caller-visible array-ownership surprise was added at the declaration but not to CONVENTIONS.md, the file whose stated purpose is to make that family visible in one place.
  - CONVENTIONS.md:64 CONVENTIONS.md's array-ownership family was not extended with the newly documented caller-array mutation, so the file's stated guarantee that the family is 'visible in one place' no longer holds for the behaviour this delta just established as a published contract.
  - CONVENTIONS.md:64 CONVENTIONS.md's "Byte-array ownership" section is the repo's stated one-place index for exactly this caller-array-ownership family, and the delta added a new member of it without indexing it there.
- cluster of 2:
  - README.md:338 The 'Result-identical routing' preamble still forward-references an InstructionRecord 'exception' as though a live acceptance exists; the section deletion changed what 'below' points at, and no InstructionRecord row remains in any baseline.
  - README.md:338 The Result-identical routing preamble still advertises a live "singleton-null exception in InstructionRecord" to look for, but the delta deleted that row from the baseline entirely.
- singletons: 7
- refuter budget: 26 findings -> 12 groups

## Bars (prose arm, 178 labeled pairs, gold histogram {2=110, 0=17, 1=51})

| bar | value | required | pass |
| --- | --- | --- | --- |
| merge safety (gold-0 pairs merged at >= 0.8) | 0 | 0 | yes |
| suppression (recall on gold 2 at >= 0.8) | 0.400 | >= 0.700 | NO |
| jev precision at recall >= 0.7 (t=0.5) | 0.899 | >= 0.950 | NO |
| jev precision minus exact-line precision (0.653) | 0.246 | >= 0.200 | yes |
| jev precision minus jaccard precision (0.683 at t=0.25) | 0.216 | >= 0.150 | yes |
| middle recall (gold 1 as 1) | 0.608 | >= 0.500 | yes |
| pearson(P(same), jaccard) | 0.586 | <= 0.800 | yes |
| **keep** | **false** | all | |

```
gold \ predicted	0	1	2
0	15	2	0
1	7	31	13
2	1	28	81
```

### Post-hoc same-defect rule (P(different) <= 0.2, gold 1 or 2 counts as same)

| grouped | precision | recall | gold-0 pairs grouped |
| --- | --- | --- | --- |
| 134 | 1.000 | 0.832 | 0 |

## Bars (ablation arm, 178 labeled pairs)

| bar | value | required | pass |
| --- | --- | --- | --- |
| merge safety (gold-0 pairs merged at >= 0.8) | 0 | 0 | yes |
| suppression (recall on gold 2 at >= 0.8) | 0.373 | >= 0.700 | NO |
| jev precision at recall >= 0.7 (t=0.5) | 0.915 | >= 0.950 | NO |
| jev precision minus exact-line precision (0.653) | 0.263 | >= 0.200 | yes |
| jev precision minus jaccard precision (0.683 at t=0.25) | 0.233 | >= 0.150 | yes |
| middle recall (gold 1 as 1) | 0.627 | >= 0.500 | yes |
| pearson(P(same), jaccard) | 0.589 | <= 0.800 | yes |
| **keep** | **false** | all | |
