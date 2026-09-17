# Experiment A: acceptance-note rot detector

## Corpus

| modules | skipped (private or absent) | rows |
| --- | --- | --- |
| 18 | 4 | 118 |

Member status: {MISSING_MEMBER=1, MISSING_TYPE=8, MOVED_MEMBER=3, REMOVED_MEMBER=1, RESOLVED=105}

| module | rows | resolved | control-flagged |
| --- | --- | --- | --- |
| http-servers/http-servers-core (rung 1) | 4 | 4 | 1 |
| http-servers/http-servers-fusionauth (rung 1) | 3 | 3 | 3 |
| http-servers/http-servers-hello (rung 0) | 2 | 2 | 1 |
| http-servers/http-servers-jdk (rung 2) | 5 | 4 | 1 |
| http-servers/http-servers-jetty (rung 1) | 5 | 5 | 0 |
| http-servers/http-servers-sava (rung 1) | 3 | 3 | 1 |
| incident-client/incident-core (rung 0) | 4 | 4 | 1 |
| incident-client/incident-io (rung 2) | 4 | 4 | 1 |
| incident-client/incident-pagerduty (rung 0) | 1 | 1 | 0 |
| incident-client/incident-webhook (rung 0) | 5 | 5 | 2 |
| json-iterator/json-iterator (rung 1) | 14 | 13 | 6 |
| ravina/ravina-core (rung 1) | 16 | 16 | 5 |
| ravina/ravina-kms/google (rung 0) | 3 | 3 | 2 |
| ravina/ravina-solana (rung 2) | 7 | 7 | 0 |
| sava/sava-core (rung 3) | 27 | 24 | 8 |
| sava/sava-rpc (rung 3) | 7 | 7 | 2 |

## Jev

Requests 110 (110 answered), input tokens 161028, cost $0.0068; recording hits 0, misses 110.

## Bars against hand labels

No labeled and scored rows yet.

## Bars against PROVISIONAL survey gold hints (44 hints; not a substitute for labels)

33 labeled rows. Control arm: flagged 9, true positives 4 of 11 rot rows (recall 0.364, precision 0.444).

| bar | value | required | pass |
| --- | --- | --- | --- |
| recall of rot within the top 30% by P(absent) | 0.636 | >= 0.900 | NO |
| rot rows in the top 30% with no control flag | 3 | >= 3 | yes |
| rot rows answered construct_present at confidence >= 0.8 | 0 | 0 | yes |
| present rows in rung-0 modules answered construct_absent | 0 | <= 1 | yes |
| **keep** | **false** | all | |

```
gold \ predicted	construct_present	construct_absent	cannot_resolve
construct_present	18	4	0
construct_absent	4	7	0
cannot_resolve	0	0	0
```

## Top rows by P(construct_absent)

| row | P(absent) | confidence | control flags | gold hint |
| --- | --- | --- | --- | --- |
| http-servers/http-servers-jdk#37#JdkController.serverError | 0.990 | 0.980 | member_missing | absent |
| sava/sava-core#275#TransactionRecord.sign | 0.990 | 0.990 | member_missing | absent |
| sava/sava-core#235#TransactionRecord.setBlockHash | 0.980 | 0.970 | member_missing |  |
| sava/sava-core#248#TransactionRecord.sign | 0.980 | 0.980 | member_missing | absent |
| json-iterator/json-iterator#307#JHex.INIT_DIGITS | 0.970 | 0.950 | member_missing |  |
| sava/sava-rpc#281#JsonUtil.parseEncodedData | 0.970 | 0.950 | identifier_missing:reset identifier_missing:skipRestOfArray identifier_missing:mark2 identifier_missing:skippedValuesLeaveTheIteratorAligned |  |
| http-servers/http-servers-fusionauth#43#FusionAuthHttpServer.start | 0.960 | 0.940 | identifier_missing:startOnAnOccupiedPortThrows identifier_missing:localhost |  |
| http-servers/http-servers-fusionauth#43#FusionAuthServerBuilder.initRestServer | 0.960 | 0.930 | identifier_missing:startOnAnOccupiedPortThrows identifier_missing:localhost |  |
| ravina/ravina-core#356#CapacityStateVal.tryClaimRequest | 0.960 | 0.940 | identifier_missing:casUpdatedAt identifier_missing:WedgedClaimState identifier_missing:aClaimThatLosesTheRaceIsPutBack identifier_missing:aPacingGatedClaimReturnsFalseWithoutClaiming identifier_missing:LosingTimestampState identifier_missing:aTimestampCasThatLosesMustNotReplenish |  |
| http-servers/http-servers-jdk#37#JdkQueryHandler.lambda$handle$0 | 0.900 | 0.850 |  | absent |
| incident-client/incident-io#32#CreateIncidentRequestRecord.body | 0.890 | 0.830 | identifier_missing:bearerToken |  |
| sava/sava-core#273#TransactionRecord.lambda$static$0 | 0.890 | 0.840 |  | absent |
| sava/sava-core#260#Transaction.createTx | 0.880 | 0.820 | identifier_missing:addAccountIfExists |  |
| sava/sava-rpc#290#JsonUtil.parseEncodedData | 0.860 | 0.780 |  |  |
| http-servers/http-servers-jdk#33#JdkQueryHandler.handle | 0.840 | 0.770 |  |  |
| json-iterator/json-iterator#65#BytesJsonIterator.parse | 0.840 | 0.750 | identifier_missing:readInt identifier_missing:readLong identifier_missing:parseFieldName identifier_missing:handleEscapes |  |
| json-iterator/json-iterator#65#CharsJsonIterator.parse | 0.830 | 0.740 | identifier_missing:readInt identifier_missing:readLong identifier_missing:parseFieldName identifier_missing:handleEscapes | present |
| sava/sava-core#231#Transaction.exceedsSizeLimit | 0.750 | 0.630 | identifier_missing:TransactionRecord identifier_missing:exceedsSizeLimitBoundary | present |
| sava/sava-rpc#275#JsonUtil.parseEncodedData | 0.730 | 0.590 |  |  |
| json-iterator/json-iterator#65#FieldMatcher.match | 0.710 | 0.570 | identifier_missing:readInt identifier_missing:readLong identifier_missing:parseFieldName identifier_missing:handleEscapes |  |
| ravina/ravina-solana#42#LookupTableCacheMap.getOrFetchTables | 0.710 | 0.560 |  | absent |
| json-iterator/json-iterator#65#JIUtil.escapeQuotes* | 0.650 | 0.470 | identifier_missing:readInt identifier_missing:readLong identifier_missing:parseFieldName identifier_missing:handleEscapes |  |
| ravina/ravina-core#112#LoadBalancerConfig$Parser.parseProperties | 0.640 | 0.470 | identifier_missing:BooleanTrueReturnVals |  |
| sava/sava-rpc#317#SolanaJsonRpcWebsocket.run | 0.640 | 0.450 |  |  |
| sava/sava-core#343#SubsequenceRecord.formatCharOptions | 0.620 | 0.430 |  |  |
| ravina/ravina-core#100#CapacityStateVal.hasCapacity | 0.590 | 0.390 | identifier_missing:BooleanTrueReturnVals |  |
| ravina/ravina-core#344#CourteousBalancedCall.call | 0.580 | 0.360 | identifier_missing:aFailedClaimIsFinalUnlessTheFailoverItemIsADifferentOne identifier_missing:RacingCapacityState identifier_missing:CapacityState | absent |
| sava/sava-core#286#TransactionSkeleton.deserializeSkeleton | 0.510 | 0.270 | identifier_missing:TransactionSkeletonRecord | present |
| http-servers/http-servers-sava#52#SvmExactVerifier.verify | 0.500 | 0.250 |  | present |
| json-iterator/json-iterator#244#BytesJsonIterator.parseMultiByteString | 0.440 | 0.300 |  | absent |

