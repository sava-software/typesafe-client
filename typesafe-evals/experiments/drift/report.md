# Experiment D: doc drift at change time

## Corpus

| repositories | skipped | rows | CO_EDIT | BODY_ONLY | commits |
| --- | --- | --- | --- | --- | --- |
| 6 | 0 | 76 | 29 | 47 | 37 |

Excluded events: {comment removed=7, comment retouched (shown text equal or jaccard >= 0.9)=16, comment shorter than 40 as shown=60, comment-only event=233, earlier change of the same member=45, no comment before the change=134, whitespace-only body change=2}

| repository | CO_EDIT | BODY_ONLY |
| --- | --- | --- |
| glam-sdk-java | 0 | 2 |
| http-servers | 0 | 2 |
| incident-client | 3 | 2 |
| json-iterator | 1 | 7 |
| ravina | 0 | 4 |
| sava | 25 | 30 |

Abstract-toggle rows (a member gained or lost a body): 13.

## Jev

Requests 118 (118 answered), input tokens 244890, cost $0.0103; recording hits 118, misses 0; 76 rows scored.

## Bars (pre-registered decision table as amended, first match wins)

AUROC 0.594 (commit bootstrap 95% 0.370 to 0.710; separation kill); without abstract-toggle rows 0.420.

| deterministic baseline | AUROC | two-sided |
| --- | --- | --- |
| diff size | 0.401 | 0.599 |
| comment-to-diff overlap | 0.479 | 0.521 |
| rank-max of size and overlap | 0.410 | 0.590 |
| member lines before | 0.227 | 0.773 |
| member lines after | 0.322 | 0.678 |
| shown comment length | 0.607 | 0.607 |
| abstract member gained or lost a body | 0.724 | 0.724 |

| bar | value | required | pass |
| --- | --- | --- | --- |
| score correlates with diff size (Pearson -0.0599, Spearman 0.0173) | 0.060 | both <= 0.8 | yes |
| separation AUROC, CO_EDIT over BODY_ONLY (commit bootstrap 0.3704 to 0.7104) | 0.594 | interval lower bound >= 0.75 | NO |
| lift over the best deterministic baseline (member lines before, 0.7729) | -0.179 | >= 0.1 | NO |
| BODY_ONLY rows in the top 30 readers marked affected (30 read; rest 0 of 17) | 6.000 | >= 5 and Wilson lower bound above the rest's upper bound | NO |
| **decision** | **kill: separation** | | |

Oracle ceiling: readers marked 0.448 of CO_EDIT rows and 0.128 of BODY_ONLY rows affected, so a perfect judge of the readers' labels would separate the classes at about 0.660.

Value bar: top 30 BODY_ONLY rows 6 of 30 = 0.200 (0.095 to 0.373) affected; the rest 0 of 17 = 0.000 (0.000 to 0.184).

Choices, CO_EDIT: {contradicted_by_change=8, needs_addition=7, not_checkable=1, unaffected=13}; BODY_ONLY: {contradicted_by_change=3, needs_addition=21, unaffected=23}.

## Top BODY_ONLY rows by score

| row | score | confidence | diff lines | overlap |
| --- | --- | --- | --- | --- |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.escalateUnanswered(Connection, long) | 0.830 | 0.240 | 5 | 0.000 |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.retainedRegistrations() | 0.820 | 0.760 | 1 | 0.000 |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.releaseChannelSlot(Subscription<?>) | 0.800 | 0.710 | 8 | 0.000 |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.checkCycle(long) | 0.750 | 0.220 | 32 | 0.000 |
| json-iterator#2f07ba7#json-iterator/src/main/java/systems/comodal/jsoniter/BaseJsonIterator.java#BaseJsonIterator.skipLiteral(String, String, String) | 0.730 | 0.470 | 2 | 0.000 |
| sava#0d4f07a#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.sendSubscription(Connection, Subscription<?>) | 0.730 | 0.170 | 17 | 0.000 |
| incident-client#61c84ba#incident-core/src/main/java/software/sava/incident/core/api/IncidentClients.java#IncidentClients.loadFactory(String) | 0.700 | 0.410 | 17 | 1.000 |
| ravina#5578c86#ravina-solana/src/main/java/software/sava/services/solana/epoch/EpochInfoServiceImpl.java#EpochInfoServiceImpl.start() | 0.700 | 0.200 | 4 | 0.000 |
| sava#05a4294#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.sendUnSubscriptionLockHeld(Connection, String, BigInteger) | 0.680 | 0.330 | 13 | 0.500 |
| sava#82987fe#sava-core/src/main/java/software/sava/core/tx/TransactionRecord.java#TransactionRecord.priorityFeeLamportsToComputeUnitPrice(long, int) | 0.680 | 0.480 | 4 | 0.333 |
| sava#b4c0afe#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.handlePendingSubscriptions(Connection) | 0.670 | 0.270 | 3 | 0.000 |
| ravina#5578c86#ravina-solana/src/main/java/software/sava/services/solana/epoch/EpochInfoServiceImpl.java#EpochInfoServiceImpl.checkCycle(Cycle, boolean) | 0.670 | 0.460 | 8 | 0.000 |
| glam-sdk-java#bb830ff#services/src/main/java/systems/glam/services/db/sql/BatchSqlExecutorImpl.java#BatchSqlExecutorImpl.run() | 0.660 | 0.520 | 4 | 0.000 |
| ravina#5578c86#ravina-solana/src/main/java/software/sava/services/solana/epoch/SlotPerformanceStats.java#SlotPerformanceStats.calculateStats(List<PerfSample>, int, int) | 0.660 | 0.540 | 6 | 0.000 |
| sava#b24a296#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.claimPingDeadlineTransition(PingDeadlineTransition) | 0.650 | 0.390 | 3 | 0.000 |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.ownBuild(CompletableFuture<WebSocket>, long) | 0.620 | 0.310 | 4 | 0.000 |
| sava#b4c0afe#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.adopt(WebSocket, long) | 0.610 | 0.420 | 33 | 0.000 |
| sava#442addf#sava-core/src/main/java/software/sava/core/tx/Transaction.java#Transaction.exceedsSizeLimit() | 0.600 | 0.260 | 2 | 0.000 |
| sava#6bdbfb1#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.flushPendingUnSubscriptions(Connection, long) | 0.600 | 0.320 | 2 | 0.000 |
| sava#00348f3#sava-core/src/main/java/software/sava/core/tx/Transaction.java#Transaction.exceedsInstructionLimit() | 0.590 | 0.110 | 2 | 0.000 |
| sava#442addf#sava-core/src/main/java/software/sava/core/tx/Transaction.java#Transaction.sign(Signer, byte[]) | 0.580 | 0.150 | 14 | 1.000 |
| sava#05a4294#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.deferredBuild(long, AttemptListener) | 0.520 | 0.350 | 4 | 1.000 |
| sava#0f6e808#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.consumerThrew(String, RuntimeException) | 0.520 | 0.290 | 4 | 0.000 |
| glam-sdk-java#9278933#services/src/main/java/systems/glam/services/rpc/AccountFetcherImpl.java#AccountFetcherImpl.failCurrentBatches(RuntimeException) | 0.510 | 0.320 | 29 | 0.000 |
| sava#b4c0afe#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.startBuild(long, CompletableFuture<WebSocket>) | 0.510 | 0.260 | 4 | 0.333 |
| json-iterator#eb87b90#systems.comodal.json_iterator/src/main/java/systems/comodal/jsoniter/JsonIterator.java#JsonIterator.applyChars(CharBufferFunction<R>) | 0.500 | 0.180 | 2 | 0.000 |
| json-iterator#db15a1b#json-iterator/src/main/java/systems/comodal/jsoniter/JsonIterator.java#JsonIterator.readByteArray() | 0.430 | 0.350 | 10 | 0.500 |
| sava#52dce85#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.connect() | 0.420 | 0.400 | 116 | 1.000 |
| sava#b24a296#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.buildReservedAttempt(long, CompletableFuture<WebSocket>) | 0.420 | 0.250 | 5 | 0.000 |
| sava#2c70f78#sava-core/src/main/java/software/sava/core/accounts/SolanaAccounts.java#SolanaAccounts.stakeConfig() | 0.390 | 0.090 | 1 | 0.000 |

## Batched arm: one request per changed file, one Noul per candidate comment (reported, no bar)

| batches | requests answered | candidates judged | positives | requests with both classes | input tokens |
| --- | --- | --- | --- | --- | --- |
| 42 | 42 | 385 | 25 | 12 | 154166 |

AUROC pooled, positives over all negatives: 0.875; over changed-member negatives only (like the pair arm): 0.631; mean within-request AUROC: 0.915.

Cost per judged comment: batched 400 input tokens in 0.11 requests; pair arm 1193 input tokens in 1 request.

