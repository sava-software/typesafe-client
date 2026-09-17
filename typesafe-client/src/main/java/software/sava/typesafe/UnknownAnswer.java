package software.sava.typesafe;

/// An answer whose wire `type` this client does not know. Kept so a request with one new
/// answer type still delivers the others.
public record UnknownAnswer(String type) implements Answer {
}
