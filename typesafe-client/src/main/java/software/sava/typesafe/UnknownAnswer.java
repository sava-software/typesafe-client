package software.sava.typesafe;

/// An answer whose wire `type` this client does not know. Kept so a request with one new
/// answer type still delivers the others.
///
/// @param type the wire `type` value
/// @param json the answer object as JSON text, so a caller can read a type this client does
///             not model yet without re-parsing [SystemOneResponse#raw()]. It is the parsed
///             value written back out, not a byte-for-byte slice of the response: strings and
///             field names are re-escaped and insignificant whitespace is dropped.
public record UnknownAnswer(String type, String json) implements Answer {
}
