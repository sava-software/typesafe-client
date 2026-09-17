/// Java client for the TypeSafe System One API (`POST /v1/systemone`): typed questions in,
/// calibrated probabilities out. Transport is sava-rpc's `JsonHttpClient`; JSON is written by
/// hand and read with json-iterator.
module software.sava.typesafe {
  requires java.net.http;

  requires transitive systems.comodal.json_iterator;
  requires software.sava.rpc;

  exports software.sava.typesafe;
  exports software.sava.typesafe.exceptions;
}
