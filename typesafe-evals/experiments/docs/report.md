# Experiment C1: doc comments versus member bodies

## Corpus

| repositories | skipped | documented members | with a swap | stale candidates | sampled |
| --- | --- | --- | --- | --- | --- |
| 6 | 0 | 461 | 373 | 37 | 150 |

| repository | members | with a swap |
| --- | --- | --- |
| glam-sdk-java | 19 | 5 |
| http-servers | 96 | 80 |
| incident-client | 35 | 25 |
| json-iterator | 66 | 62 |
| ravina | 29 | 18 |
| sava | 216 | 183 |

## Jev

Requests 834 (834 answered), input tokens 677206, cost $0.0284; recording hits 834, misses 0; 373 rows with both arms scored, 461 REAL rows scored.

## Design 1: swapped comments (pre-registered decision table, first match wins)

AUROC 0.663 (bootstrap 95% 0.626 to 0.696), deterministic baseline (identifier mismatch or missing name echo) 0.620.

| bar | value | required | pass |
| --- | --- | --- | --- |
| P(contradicted) correlates with comment length | -0.090 | |r| <= 0.8 | yes |
| separation AUROC, SWAPPED over REAL | 0.663 | >= 0.85 | NO |
| lift over the deterministic baseline | 0.043 | >= 0.1 | NO |
| contradicted comments confirmed among the top 30 REAL rows (30 read) | 0.000 | >= 5 | NO |
| **decision** | **kill: separation** | | |

Choices, REAL arm: {consistent=316, contradicted=36, not_checkable=109}; SWAPPED arm: {consistent=40, contradicted=94, not_checkable=239}.

## Top REAL rows by P(contradicted)

| row | P(contradicted) | confidence | baseline |
| --- | --- | --- | --- |
| sava#sava-core/src/main/java/software/sava/core/accounts/PublicKey.java#PublicKey.fromBase58Encoded(byte[], int, int) | 0.960 | 0.940 | 1.000 |
| http-servers#http-servers-netty/src/main/java/software/sava/http_servers/netty/NettyRequestGate.java#NettyRequestGate.begin(HttpRequest) | 0.940 | 0.900 | 1.000 |
| sava#sava-core/src/main/java/software/sava/core/tx/V1Transaction.java#V1Transaction.setPriorityFeeLamportsFromComputeUnitPrice(long, int) | 0.930 | 0.900 | 0.500 |
| json-iterator#json-iterator/src/main/java/systems/comodal/jsoniter/JsonIterator.java#JsonIterator.readByteArray(byte[]) | 0.840 | 0.750 | 0.500 |
| http-servers#http-servers-soak/src/main/java/software/sava/http_servers/soak/Abuser.java#Abuser.CYCLE | 0.830 | 0.750 | 1.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.requestFingerprint(String) | 0.770 | 0.660 | 0.500 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaRpcWebsocket.java#SolanaRpcWebsocket$Builder.subscriptionResendDelay(long) | 0.710 | 0.570 | 1.000 |
| ravina#ravina-solana/src/main/java/software/sava/services/solana/epoch/EpochInfoService.java#EpochInfoService.numSamples(Duration) | 0.690 | 0.540 | 0.333 |
| incident-client#incident-webhook/src/main/java/software/sava/incident/webhook/WebhookClient.java#WebhookClient$Builder.header(String, String) | 0.660 | 0.490 | 1.000 |
| http-servers#http-servers-netty/src/main/java/software/sava/http_servers/netty/NettyRequestGate.java#NettyRequestGate.refusal(HttpRequest) | 0.650 | 0.480 | 1.000 |
| sava#sava-examples/src/main/java/software/sava/examples/SubscribeToLookupTables.java#SubscribeToLookupTables.connect(SolanaRpcWebsocket) | 0.620 | 0.440 | 1.000 |
| sava#sava-core/src/main/java/software/sava/core/tx/TransactionSkeleton.java#TransactionSkeleton.deserializeSkeleton(byte[]) | 0.610 | 0.420 | 0.500 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaRpcWebsocket.java#SolanaRpcWebsocket$Builder.keepAliveDelay(long) | 0.580 | 0.370 | 1.000 |
| sava#sava-core/src/main/java/software/sava/core/accounts/token/TokenAccount.java#TokenAccount.parseState(byte) | 0.560 | 0.340 | 0.500 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/Subscription.java#Subscription.NEVER | 0.560 | 0.340 | 1.000 |
| json-iterator#json-iterator/src/main/java/systems/comodal/jsoniter/ValueType.java#ValueType.of(char) | 0.540 | 0.310 | 1.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.preparePingDeadlineTransition() | 0.530 | 0.300 | 1.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/Timings.java#Timings.resendDelayFor(long, long) | 0.530 | 0.300 | 1.000 |
| incident-client#incident-webhook/src/main/java/software/sava/incident/webhook/config/WebhookConfig.java#WebhookConfig.headers() | 0.510 | 0.260 | 1.000 |
| incident-client#incident-io/src/main/java/software/sava/incident/io/exceptions/IncidentIoRequestException.java#IncidentIoRequestException.errorCode() | 0.500 | 0.260 | 1.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaRpcWebsocketBuilder.java#SolanaRpcWebsocketBuilder.DEFAULT_CONNECT_TIMEOUT | 0.500 | 0.250 | 1.000 |
| http-servers#http-servers-soak/src/main/java/software/sava/http_servers/soak/Pipeliner.java#Pipeliner.closeIndex(int) | 0.490 | 0.240 | 0.000 |
| incident-client#incident-io/src/main/java/software/sava/incident/io/CreateIncidentResponseRecord.java#CreateIncidentResponseRecord$Parser.parseIdName(JsonIterator) | 0.490 | 0.230 | 0.500 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.handlePendingSubscriptions(Connection) | 0.490 | 0.230 | 1.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.recordFailedPing(Connection, PingProbe, Throwable) | 0.490 | 0.230 | 0.000 |
| sava#sava-rpc/src/main/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocket.java#SolanaJsonRpcWebsocket.scheduleBuild(long, long, CompletableFuture<WebSocket>, CompletableFuture<Void>) | 0.480 | 0.220 | 0.000 |
| json-iterator#json-iterator/src/main/java/systems/comodal/jsoniter/BaseJsonIterator.java#BaseJsonIterator.intDigit(int) | 0.470 | 0.220 | 0.000 |
| ravina#ravina-solana/src/main/java/software/sava/services/solana/epoch/EpochInfoServiceImpl.java#EpochInfoServiceImpl.newCycle(Epoch, long) | 0.470 | 0.210 | 1.000 |
| incident-client#incident-webhook/src/main/java/software/sava/incident/webhook/exceptions/WebhookRequestException.java#WebhookRequestException.body() | 0.440 | 0.170 | 1.000 |
| sava#sava-core/src/main/java/software/sava/core/accounts/token/Token2022.java#Token2022.padLengthForMultisig(byte[], int, int) | 0.440 | 0.330 | 1.000 |

## Design 2: the real population (blind-labeled sample, a prevalence study)

| stratum | labeled | contradicted | consistent | not checkable | prevalence (Wilson 95%) |
| --- | --- | --- | --- | --- | --- |
| random | 113 | 2 | 90 | 21 | 2 of 92 = 0.022 (0.006 to 0.076) |
| stale-candidate | 37 | 1 | 31 | 5 | 1 of 32 = 0.031 (0.006 to 0.157) |
| pooled | 150 | 3 | 121 | 26 | 3 of 124 = 0.024 (0.008 to 0.069) |

Precision of the top 20 REAL rows by P(contradicted): 0 of 20 = 0.000 (0.000 to 0.161).
Consistent rows at P(contradicted) >= 0.9: 0 of 121 = 0.000 (0.000 to 0.031); exact one-sided p against a 0.02 rate: 1.000.
AUROC not reported: fewer than 20 rows are labeled contradicted, so a ranking statistic would be underpowered.
