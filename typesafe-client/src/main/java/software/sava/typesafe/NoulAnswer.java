package software.sava.typesafe;

/// The answer to a [Noul]: the probability, in 0..1, that the answer is yes.
public record NoulAnswer(double noul) implements Answer {

  public static final String TYPE = "noul";

  @Override
  public String type() {
    return TYPE;
  }
}
