# Experiment C2: hardening evidence versus the mutant it explains

## Corpus

| repositories | skipped (private or absent) | modules | rows | scorable |
| --- | --- | --- | --- | --- |
| 6 | 0 | 19 | 651 | 644 |

Member status: {MISSING_MEMBER=7, RESOLVED=644}

| module | rows | scorable | paragraph mentions the row's family |
| --- | --- | --- | --- |
| glam-sdk-java/sdk | 18 | 18 | 0 |
| glam-sdk-java/services | 63 | 63 | 24 |
| http-servers/http-servers-core | 5 | 5 | 1 |
| http-servers/http-servers-fusionauth | 3 | 3 | 1 |
| http-servers/http-servers-jetty | 12 | 12 | 11 |
| http-servers/http-servers-sava | 10 | 10 | 5 |
| incident-client/incident-core | 12 | 12 | 12 |
| incident-client/incident-io | 6 | 6 | 6 |
| incident-client/incident-pagerduty | 28 | 28 | 28 |
| incident-client/incident-webhook | 5 | 5 | 5 |
| json-iterator/json-iterator | 9 | 9 | 6 |
| ravina/ravina-core | 43 | 43 | 34 |
| ravina/ravina-kms/core | 5 | 5 | 4 |
| ravina/ravina-kms/google | 14 | 14 | 12 |
| ravina/ravina-kms/http | 4 | 4 | 4 |
| ravina/ravina-solana | 105 | 105 | 85 |
| sava/sava-core | 120 | 120 | 73 |
| sava/sava-rpc | 189 | 182 | 71 |

## Jev

Requests 1232 (1232 answered), input tokens 1931704, cost $0.0811; recording hits 0, misses 1232; 644 rows with both arms scored.

## Bars (pre-registered decision table, first match wins)

AUROC 0.844 (bootstrap 95% 0.825 to 0.862), mutator-word baseline 0.617.

| bar | value | required | pass |
| --- | --- | --- | --- |
| P(does_not_apply) correlates with paragraph length | -0.079 | |r| <= 0.8 | yes |
| separation AUROC, SWAPPED over REAL | 0.844 | >= 0.85 | NO |
| lift over the mutator-word baseline | 0.226 | >= 0.1 | yes |
| problems confirmed among the top 30 REAL rows (0 read) | 0.000 | >= 5 | NO |
| rows at P >= 0.8 confirmed fine (reported, not a kill) | 0.000 | reported | yes |
| **decision** | **kill: separation** | | |

Choices, REAL arm: {applies=339, cannot_tell=103, does_not_apply=202}; SWAPPED arm: {applies=26, cannot_tell=100, does_not_apply=518}.

## Top REAL rows by P(does_not_apply)

| row | P(does_not_apply) | confidence | construct_absent | swapped arm P |
| --- | --- | --- | --- | --- |
| glam-sdk-java/services#services#KeyedFlatFileImpl.deleteEntry#RemoveConditionalMutator_ORDER_IF#SURVIVED#69 | 1.000 | 0.990 | 0.440 | 0.990 |
| glam-sdk-java/services#services#KeyedFlatFileImpl.deleteEntry#ConditionalsBoundaryMutator#SURVIVED#69 | 0.990 | 0.990 | 0.440 | 0.990 |
| glam-sdk-java/services#services#KeyedFlatFileImpl.deleteEntry#PrimitiveReturnsMutator#SURVIVED#57 | 0.990 | 0.990 | 0.620 | 0.040 |
| glam-sdk-java/services#services#KeyedFlatFileImpl.deleteEntry#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#56 | 0.990 | 0.980 | 0.380 | 0.990 |
| ravina/ravina-solana#alt#ScoredTableMeta.scoreTables#RemoveConditionalMutator_ORDER_ELSE#SURVIVED#49 | 0.990 | 0.980 | 0.870 | 1.000 |
| ravina/ravina-solana#catchAll#PriorityFeeRequest.serializeRecommendedParams#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#80 | 0.990 | 0.980 | 0.960 | 1.000 |
| ravina/ravina-solana#alt#ScoredTable.scoreTables#RemoveConditionalMutator_ORDER_ELSE#SURVIVED#58 | 0.980 | 0.970 | 0.450 | 1.000 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.publish#RemoveConditionalMutator_EQUAL_IF#SURVIVED#784 | 0.980 | 0.970 | 0.800 | 0.990 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#NullReturnValsMutator#NO_COVERAGE#184 | 0.970 | 0.960 | 0.160 | 0.930 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.lambda$createService$1#NullReturnValsMutator#NO_COVERAGE#127 | 0.970 | 0.960 | 0.200 | 0.940 |
| ravina/ravina-core#calls#UncheckedBalancedCall.get#RemoveConditionalMutator_EQUAL_IF#SURVIVED#53 | 0.970 | 0.960 | 0.120 | 0.990 |
| ravina/ravina-solana#epoch#SlotPerformanceStats.calculateStats#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#81 | 0.970 | 0.960 | 0.650 | 0.970 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onText#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#973 | 0.970 | 0.950 | 0.920 | 0.980 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onWholeMessage#RemoveConditionalMutator_EQUAL_IF#SURVIVED#905 | 0.970 | 0.950 | 0.840 | 0.980 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.publish#RemoveConditionalMutator_EQUAL_IF#SURVIVED#765 | 0.970 | 0.960 | 0.800 | 0.990 |
| glam-sdk-java/services#services#GlobalConfigCacheImpl.run#ConditionalsBoundaryMutator#SURVIVED#259 | 0.960 | 0.940 | 0.560 | 0.820 |
| glam-sdk-java/services#services#GlobalConfigCacheImpl.run#RemoveConditionalMutator_ORDER_ELSE#SURVIVED#259 | 0.960 | 0.940 | 0.560 | 0.800 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#RemoveConditionalMutator_EQUAL_IF#NO_COVERAGE#112 | 0.960 | 0.930 | 0.180 | 0.950 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.lambda$createService$0#NullReturnValsMutator#NO_COVERAGE#102 | 0.960 | 0.950 | 0.170 | 0.940 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onText#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#2725 | 0.960 | 0.930 | 0.910 | 0.980 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.publishGeneric#RemoveConditionalMutator_EQUAL_IF#SURVIVED#803 | 0.960 | 0.940 | 0.770 | 0.970 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#RemoveConditionalMutator_EQUAL_ELSE#NO_COVERAGE#112 | 0.950 | 0.910 | 0.180 | 0.950 |
| incident-client/incident-pagerduty#adapter#PagerDutyServiceVal.changeEvent#ConditionalsBoundaryMutator#SURVIVED#254 | 0.950 | 0.930 | 0.970 | 1.000 |
| ravina/ravina-solana#catchAll#PriorityFeeRequest.serializeParams#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#23 | 0.950 | 0.920 | 0.950 | 1.000 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onWholeMessage#RemoveConditionalMutator_EQUAL_IF#SURVIVED#2587 | 0.950 | 0.930 | 0.810 | 0.970 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#RemoveConditionalMutator_EQUAL_ELSE#NO_COVERAGE#121 | 0.940 | 0.900 | 0.210 | 0.950 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onText#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#965 | 0.940 | 0.920 | 0.920 | 0.980 |
| sava/sava-rpc#ws#SolanaJsonRpcWebsocket.onText#RemoveConditionalMutator_EQUAL_ELSE#SURVIVED#986 | 0.940 | 0.920 | 0.900 | 0.970 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#VoidMethodCallMutator#NO_COVERAGE#135 | 0.930 | 0.900 | 0.160 | 0.990 |
| glam-sdk-java/services#services#SingleAssetFulfillmentServiceEntrypoint.createService#VoidMethodCallMutator#NO_COVERAGE#182 | 0.930 | 0.900 | 0.170 | 0.990 |
