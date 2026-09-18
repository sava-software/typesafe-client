package software.sava.typesafe;

/// Response bodies pinned from the wire: the first live call this client made (2026-09-17,
/// request `req_01a0b00eba887f499299419d8edcb72a`) and the documented examples.
///
/// The `SDK_` bodies are the canonical fixtures the reference SDKs drive their own response
/// tests off, copied verbatim (Python dict literals rendered as the JSON they serialize to,
/// key order preserved) with their source path and line. They are what makes the three
/// clients comparable; when an SDK bumps one after an API change, diff against it here.
/// Paths are relative to the reference checkouts `typesafe-sdk-python` (v0.7.0) and
/// `typesafe-sdk-js` (v0.6.0).
final class TestBodies {

  static final String SMOKE_REQUEST_ID = "req_01a0b00eba887f499299419d8edcb72a";

  static final String SMOKE = """
      {"model":"jev-1.13.0","answers":{"still_holds":{"type":"choice","choice":"still_holds","confidence":0.85,"probabilities":{"still_holds":0.9,"cannot_tell":0.0,"no_longer_holds":0.1}},"names_escape":{"type":"noul","noul":0.04},"severity":{"type":"score","score":1.82,"confidence":0.73,"legend":{"0":"The note is about the method's contract only.","1":"The note names a specific branch or guard.","2":"The note names exact expressions, constants, or ordering."},"probabilities":{"0":0.01,"1":0.17,"2":0.82}}},"usage":{"input_tokens":619,"output_tokens":81}}""";

  static final String MODELS = """
      {"models":[{"name":"jev-latest","description":"The latest iteration of TypeSafe's System One Model: Jev","release_date":"2026-09-10T18:38:01.391457+00:00"},{"name":"jev-preview","description":"A preview version of `jev-latest`: should be better in most ways","release_date":"2026-09-10T18:39:06.057655+00:00"}]}""";

  /// `RESULT`, the SDK's all-three-answer-types response: tests/test_clients.py:42-56.
  static final String SDK_RESULT = """
      {"model":"jev-latest","usage":{"input_tokens":12,"output_tokens":3},"answers":{"spam":{"type":"noul","noul":0.98},"tone":{"type":"choice","choice":"friendly","confidence":0.9,"probabilities":{"friendly":0.9,"hostile":0.1}},"quality":{"type":"score","score":1.7,"confidence":0.8,"legend":{"0":"bad","1":"ok","2":"great"},"probabilities":{"0":0.1,"1":0.1,"2":0.8}}}}""";

  /// `SYSTEM_ONE_RESPONSE`, the JS SDK's canonical body: test/client.test.ts:29-33.
  static final String SDK_JS_RESULT = """
      {"model":"m","answers":{"q1":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}""";

  /// `test_rich_descriptions`: the legend echoes back the object criterion that was sent --
  /// tests/test_clients.py:215-224, asserted at :243 as `result.scores["risk"].legend[0]`.
  static final String SDK_RICH_LEGEND = """
      {"model":"custom","usage":{"input_tokens":1,"output_tokens":1},"answers":{"risk":{"type":"score","score":0,"confidence":1,"legend":{"0":{"summary":"duplicated","examples":["charged twice"]}},"probabilities":{"0":1}}}}""";

  /// The legend of `test_response_preserves_nested_json`, tests/test_responses.py:178-201, as
  /// a wire answer. That test round-trips a Python `ScoreAnswer` rather than decoding HTTP, so
  /// the body is the shape its `model_dump()` asserts, not a recorded response.
  static final String SDK_NESTED_LEGEND = """
      {"type":"score","score":0.0,"confidence":1.0,"legend":{"0":{"examples":["a",{"note":null}]}},"probabilities":{"0":1.0}}""";

  /// `test_unknown_answer_type_ignored`: tests/test_responses.py:157-166.
  static final String SDK_UNKNOWN_ANSWER_TYPE = """
      {"model":"test","usage":{"input_tokens":1,"output_tokens":1},"answers":{"spam":{"type":"noul","noul":0.9},"mystery":{"type":"aurora","value":3}}}""";

  /// `test_unknown_extra_fields_tolerated`: tests/test_responses.py:141-145.
  static final String SDK_EXTRA_FIELDS = """
      {"model":"test","usage":{"input_tokens":1,"output_tokens":1,"reasoning_tokens":9,"billing_units":1},"answers":{"spam":{"type":"noul","noul":0.9,"explanation":"spammy"}}}""";

  /// tests/test_responses.py:141-145 with one unknown key added at the response top level,
  /// the one level neither SDK fixture covers.
  static final String SDK_EXTRA_FIELDS_AND_TOP_LEVEL = """
      {"model":"test","trace_id":"t-1","usage":{"input_tokens":1,"output_tokens":1,"reasoning_tokens":9,"billing_units":1},"answers":{"spam":{"type":"noul","noul":0.9,"explanation":"spammy"}}}""";

  /// The empty usage object the SDK's own handlers return: tests/test_types.py:66.
  static final String SDK_EMPTY_USAGE = """
      {"model":"jev-latest","usage":{},"answers":{}}""";

  /// `CARD` in the `{"models":[...]}` envelope `test_models_shape` serves:
  /// tests/test_clients.py:57 and :251.
  static final String SDK_MODELS_CARD = """
      {"models":[{"name":"jev-latest","description":"Fast model","release_date":"2026-08-01"}]}""";

  /// `test_models_ignore_unknown_fields`: the same card plus unmodelled fields --
  /// tests/test_clients.py:257.
  static final String SDK_MODELS_CARD_EXTRA_FIELDS = """
      {"models":[{"name":"jev-latest","description":"Fast model","release_date":"2026-08-01","context_window":128000,"pricing":null}]}""";

  /// "preserves employee-only model fields through raw access": test/client.test.ts:187-189.
  static final String SDK_JS_MODELS_TAGS = """
      {"models":[{"name":"m","description":"d","release_date":"2026","tags":["internal"]}]}""";

  private TestBodies() {
  }
}
