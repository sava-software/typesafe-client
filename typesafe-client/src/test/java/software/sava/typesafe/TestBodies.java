package software.sava.typesafe;

/// Response bodies pinned from the wire: the first live call this client made (2026-09-17,
/// request `req_01a0b00eba887f499299419d8edcb72a`) and the documented examples.
final class TestBodies {

  static final String SMOKE_REQUEST_ID = "req_01a0b00eba887f499299419d8edcb72a";

  static final String SMOKE = """
      {"model":"jev-1.13.0","answers":{"still_holds":{"type":"choice","choice":"still_holds","confidence":0.85,"probabilities":{"still_holds":0.9,"cannot_tell":0.0,"no_longer_holds":0.1}},"names_escape":{"type":"noul","noul":0.04},"severity":{"type":"score","score":1.82,"confidence":0.73,"legend":{"0":"The note is about the method's contract only.","1":"The note names a specific branch or guard.","2":"The note names exact expressions, constants, or ordering."},"probabilities":{"0":0.01,"1":0.17,"2":0.82}}},"usage":{"input_tokens":619,"output_tokens":81}}""";

  static final String MODELS = """
      {"models":[{"name":"jev-latest","description":"The latest iteration of TypeSafe's System One Model: Jev","release_date":"2026-09-10T18:38:01.391457+00:00"},{"name":"jev-preview","description":"A preview version of `jev-latest`: should be better in most ways","release_date":"2026-09-10T18:39:06.057655+00:00"}]}""";

  private TestBodies() {
  }
}
