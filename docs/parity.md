# Client parity review against the official SDKs

Run 2026-09-18 before the first release. The Java client was written from the vendor docs; the official Python SDK (v0.7.0) and TypeScript SDK (v0.6.0) are the reference implementations. Five read-only reviewers each took one lens over the Java sources and both SDKs, and one refuter per lens re-read every citation; the full per-lens record with evidence is under `parity/`. Three implementation passes then applied the confirmed findings, one per area (response parsing, transport and errors, request side), each in its own worktree with its PIT suite kept at 100%.

Counts: 51 findings; 35 confirmed, 15 partly (true but overstated), 1 refuted.

## What changed

- **Response parsing.** Score legend values are JSON values, not strings (an object- or array-valued legend used to throw away the whole response); answers missing their payload field fail the parse instead of reading as zero; a 200 without a `model`, and a models envelope without an array, are parse failures; `usage:{}` and null counts read as 0; quoted numbers are rejected as both SDKs do; `UnknownAnswer` keeps the answer's JSON. Nine SDK response bodies are pinned verbatim with their provenance.
- **Transport and errors.** A base URL with a path prefix is kept; `retryAfterMillis()` and `serverMessage()` on `TypeSafeRequestException`; 408 counts as retryable; environment values are trimmed; the SDKs' identifying headers are sent and, with the bearer, applied after the caller's `extendRequest`; the models GET carries no content type; connection refusal and timeout causes are pinned; non-positive timeouts are rejected at build time.
- **Request side.** One-sided noul criteria; `extraBody` for unmodelled top-level fields; `Question.raw` for unmodelled question shapes; null state and null score levels rejected; lone surrogates escaped rather than replaced; `Question.scoreLevels` for rich levels; the Java-only validations documented as such.

## Deferred

- A `models()` envelope carrying the request id and raw body (public API change; the failed-call path already carries both).
- Per-request extra headers and a per-attempt `X-TypeSafe-Retry-Count`; both need a per-request field and are documented as not modelled.

## Every finding

| id | severity | refuter | finding | outcome |
| --- | --- | --- | --- | --- |
| request-1 | medium | confirmed | Noul criteria always writes both `true` and `false`; the one-sided shape both SDKs send and live-test is unreachable | implemented (request) |
| request-2 | medium | partly | No Accept, User-Agent, X-TypeSafe-SDK or X-TypeSafe-Runtime header on any request | implemented (transport) |
| request-3 | medium | confirmed | No way to send extra top-level body fields; both SDKs support and pin this forward-compat escape hatch | implemented (request) |
| request-4 | medium | confirmed | Question is a sealed interface, so unknown question types and unknown question fields cannot be sent | implemented (request) |
| request-5 | medium | partly | A request with no state silently posts `"state":null`, which the published schema marks required and non-nullable | implemented (request) |
| request-6 | low | confirmed | extendRequest runs after the bearer header, so a caller can overwrite Authorization and Content-Type | implemented (transport) |
| request-7 | low | partly | Null score levels are handled three different ways and the API schema says they are not allowed | implemented (request) |
| request-8 | low | confirmed | GET /v1/models sends Content-Type: application/json on a bodyless request | implemented (transport) |
| request-9 | low | partly | Choice enforces a non-empty criteria map, non-blank option names and a 255-option cap that neither SDK enforces | implemented (request) |
| request-10 | low | confirmed | A lone surrogate in state or instructions is silently replaced with `?` on the wire | implemented (request) |
| request-11 | low | partly | Default request timeout is 30s where both SDKs default to 10s, and there is no per-call header override | implemented (transport) |
| response-1 | high | confirmed | Score legend values are parsed as strings, but the wire echoes back object/array criteria | implemented (parse) |
| response-2 | high | confirmed | Missing required answer fields silently default to 0.0 / null instead of failing | implemented (parse) |
| response-3 | medium | partly | A 200 body that is not a System One response parses into an empty response with a null model | implemented (parse) |
| response-4 | medium | confirmed | A malformed /v1/models envelope is reported as an empty model list, not an error | implemented (parse) |
| response-5 | medium | partly | Usage conflates "not reported" with zero, and would throw outright on a null token count | implemented (parse) |
| response-6 | medium | confirmed | No response fixtures from either SDK are pinned in the Java tests | implemented (parse) |
| response-7 | low | confirmed | UnknownAnswer keeps only the type string and discards the payload | implemented (parse) |
| response-8 | low | confirmed | Java accepts stringified numbers that both SDKs reject | implemented (parse) |
| response-9 | low | confirmed | GET /v1/models discards the request id and the raw body | deferred: a request-id-carrying models() envelope (public API change; deferred) |
| errors-1 | high | confirmed | A base URL with a path prefix is silently discarded: URI.resolve replaces the whole path | implemented (transport) |
| errors-2 | high | confirmed | Retry-After and retry-after-ms are never parsed or exposed, so the documented "callers own the backoff" contract is not reproducible | implemented (transport) |
| errors-3 | medium | confirmed | canBeRetried() misses 408, which both SDKs retry by default | implemented (transport) |
| errors-4 | medium | confirmed | The error envelope is never parsed: no message, detail, or 422 validation-path extraction | implemented (transport) |
| errors-5 | medium | partly | Environment values are not trimmed: a padded TYPESAFE_BASE_URL throws and a padded TYPESAFE_API_KEY sends a broken bearer token | implemented (transport) |
| errors-6 | medium | confirmed | Connection failures and timeouts surface as raw java.net.http exceptions, with no Java test covering either | implemented (transport) |
| errors-7 | medium | confirmed | None of the SDKs' identifying request headers are sent: no Accept, no User-Agent, no X-TypeSafe-SDK, no X-TypeSafe-Runtime | implemented (transport) |
| errors-8 | medium | refuted | The wire test writes the request-id header through the constant it is meant to verify, so a wrong header name would still pass | refuted |
| errors-9 | low | confirmed | Content-Type: application/json is sent on the bodyless GET /v1/models, which both SDKs deliberately omit | implemented (transport) |
| errors-10 | low | confirmed | extendRequest runs after the bearer token and can silently replace Authorization | implemented (transport) |
| errors-11 | low | confirmed | models() discards the response request id | implemented (transport) |
| errors-12 | low | confirmed | X-TypeSafe-Retry-Count is neither sent nor documented, so caller-owned retries are invisible to the server | implemented (transport) |
| validation-1 | high | partly | Java accepts a one-level Score; the JS SDK rejects anything under two levels, at the type level and at runtime | implemented (request) |
| validation-2 | medium | partly | No test pins the Java client against the two reference SDKs' shared pre-send validation contract | deferred: a separate SDK-parity validation test class (the cases are pinned in place instead) |
| validation-3 | medium | confirmed | A 422 request-validation body is quoted verbatim; both SDKs extract the field path and message | implemented (transport) |
| validation-4 | low | confirmed | Four pre-send rejections exist only in Java: blank question id, blank model, blank option name, empty choice criteria, and a hard 255-option cap | implemented (request) |
| validation-5 | low | confirmed | Timeout Durations are not validated at build time; both SDKs reject a non-positive timeout eagerly | implemented (transport) |
| validation-6 | low | partly | No upper bound on score levels, although Java enforces the matching documented cap for choice options | implemented (request) |
| fixtures-1 | high | confirmed | Score legend values can be JSON objects or arrays; Java reads them as strings | implemented (parse) |
| fixtures-2 | high | confirmed | Answers missing their payload field parse to silent zeros instead of failing | implemented (parse) |
| fixtures-3 | medium | confirmed | Both SDKs retry HTTP 408; Java's canBeRetried() says no, and no test covers 408 | implemented (transport) |
| fixtures-4 | medium | partly | The 422 fixture is an invented body shape, not the one the API sends | implemented (transport) |
| fixtures-5 | medium | confirmed | No test pins how models() behaves on the SDKs' unrecognized-envelope table | implemented (parse) |
| fixtures-6 | medium | confirmed | Error-body edge cases: non-UTF-8 bytes, bare `null`, `[]` and `42` are unpinned | implemented (transport) |
| fixtures-7 | medium | partly | The wire tests assert two request headers; both SDKs pin the full outgoing set | implemented (transport) |
| fixtures-8 | medium | partly | The cross-SDK "duplicate charge" ticket request is the one fixture all three clients could share | deferred: live-check half not added (the request-side golden is) |
| fixtures-9 | medium | confirmed | `"usage":{}` and partially-reported token counts are untested | implemented (parse) |
| fixtures-10 | low | partly | Forward-compatible extra fields are pinned only for model cards, not answers or usage | implemented (parse) |
| fixtures-11 | low | confirmed | Rich object score levels and array instructions are unpinned on the request side | implemented (request) |
| fixtures-12 | low | confirmed | A 2xx with no body (204) is accepted by the status gate but never exercised | implemented (parse) |
| fixtures-13 | low | confirmed | One-level score criteria: JS rejects, Python and the schema allow — Java's choice is right but uncited | implemented (request) |
