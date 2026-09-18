# typesafe-client

Java module `software.sava.typesafe`. Implements `POST /v1/systemone` and `GET /v1/models`
of the [TypeSafe API](https://docs.typesafe.ai/api) against the live documentation as of
2026-09-17. Only the latest GA OpenJDK release is supported.

## Client

```java
final var client = TypeSafeClient.clientBuilder()
    .apiKey("...")                 // default: TYPESAFE_API_KEY
    .endpoint("https://api.typesafe.ai")   // default; or TYPESAFE_BASE_URL
    .model("jev-latest")           // default; or TYPESAFE_DEFAULT_MODEL
    .requestTimeout(Duration.ofSeconds(30))
    .httpClient(httpClient)
    .createClient();
```

`systemOne(SystemOneRequest)` returns a `CompletableFuture<SystemOneResponse>`;
`models()` returns the model cards this key can use. The client does not retry: a 408, 429,
or 5xx (529 included) fails the future with a `TypeSafeRequestException` whose
`canBeRetried()` is true, and callers own the backoff (ravina's, for fleet services);
`retryAfterMillis()` reads the `retry-after-ms` and `retry-after` headers the way the
official SDKs do, and `serverMessage()` extracts the API's error message (including a
422's field paths) while `body()` stays verbatim. A 2xx that does not parse, or parses to
something that is not a System One response, fails with `TypeSafeParseException`, cause
attached. Both carry the `HttpResponse`; the request exception also carries the
`x-typesafe-request-id` header and the full body text. Every request carries the same
identifying headers as the official SDKs (`Accept`, `User-Agent`, `X-TypeSafe-SDK`,
`X-TypeSafe-Runtime`), applied after the caller's `extendRequest` so they cannot be
replaced; the GET for models carries no content type.

## Questions

| Type | Build | Answer |
|---|---|---|
| Choice | `Question.choice(instructions, LinkedHashMap<String,String>)` or `new Choice(JsonContent, SequencedMap<String, JsonContent>)` | `ChoiceAnswer(choice, probabilities, confidence)` |
| Noul | `Question.noul(instructions[, trueDescription[, falseDescription]])` | `NoulAnswer(noul)` |
| Score | `Question.score(instructions, level0, level1, ...)` or `Question.scoreLevels(instructions, List<JsonContent>)` | `ScoreAnswer(score, probabilities, legend, confidence)` |
| Raw | `Question.raw(type, json)` for a question shape this client does not model | `UnknownAnswer(type, json)` for an answer type it does not model |

`instructions` and every Choice or Noul `criteria` entry accept a string, a JSON object, an
array, or null (`JsonContent`); a Noul side left null is omitted from the wire. Score levels
accept the same shapes but not null. Reference nested state fields with backticked paths
in the instructions, for example `` `ticket.messages[0].text` ``. A Choice takes at most
255 options (the documented API limit); include an `other` or `none` option when the list
may not cover the input. Score levels are ordered from level 0; `ScoreAnswer.normalized()`
divides by the top level so scales can be weighted together in code, and
`ScoreAnswer.legend` echoes each level's description as the JSON value it was sent as
(`legendText(level)` flattens it to text).

Five rejections exist only in this client and in neither official SDK: a blank question
id, a blank model, a blank option name, empty Choice criteria, and the 255-option cap. Code
ported from the SDKs that relied on the server for those will fail earlier here.

Question ids (the request map keys) are not sent to the model; the instructions must carry
the whole meaning. Unknown answer types parse to `UnknownAnswer` instead of failing.

## State

`SystemOneRequest.state` is required: text (`JsonContent.text`), an object built with
`JsonContent.object().put(...)`, or an array (`JsonContent.array(...)`). Pre-serialized JSON
goes in through `JsonContent.raw` and is the caller's promise of validity. Documented
limits: 64k tokens for state plus questions, 32k for state plus the longest question.
`SystemOneRequest.Builder.extraBody(key, value)` adds a top-level request field this client
does not model, for API fields that arrive before a client release.

## Recording and replay

```java
final var recording = RecordingTypeSafeClient.record(client, Path.of("build/typesafe-cache"));
// ... every exchange lands as <sha256>.request.json / .response.json / .request-id
final var replay = RecordingTypeSafeClient.replayOnly(Path.of("build/typesafe-cache"));
```

The key is the SHA-256 of the request body, so an edited question never replays a stale
answer, and a recorded evaluation re-renders byte for byte with no key and no spend.

## Live check

```
TYPESAFE_LIVE_CHECK=true ./gradlew :typesafe-client:test --tests '*LiveCheck*'
```

One real round trip plus the model list; a few hundred input tokens.
