# Fuzz seed corpora

`response/` bootstraps `SystemOneResponseFuzz` with the shapes a from-scratch mutator would
take a long time to assemble: nested `answers` objects with per-option probability maps and
score legends.

- `smoke-2026-09-17.bin` — the body of this client's first live call, request
  `req_01a0b00eba887f499299419d8edcb72a`, verbatim.
- `docs-choice.bin` — the documented Choice response example.
- `unknown-type.bin` — an answer type this client does not know beside one it does, so the
  keep-unknown path is seeded.
- `rich-legend.bin` — a score whose legend echoes back an object criterion, from the Python
  SDK's `test_rich_descriptions` (tests/test_clients.py:215-224), so the legend value walk is
  seeded with a nested object and array.

A finding lands here as a new seed plus a named regression test.
