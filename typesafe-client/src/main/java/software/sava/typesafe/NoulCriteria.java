package software.sava.typesafe;

/// Optional descriptions of what a yes and a no mean for a [Noul]. Either side may be null.
public record NoulCriteria(JsonContent trueDescription, JsonContent falseDescription) {

  public static NoulCriteria of(final String trueDescription, final String falseDescription) {
    return new NoulCriteria(
        trueDescription == null ? null : JsonContent.text(trueDescription),
        falseDescription == null ? null : JsonContent.text(falseDescription)
    );
  }

  void writeTo(final StringBuilder out) {
    out.append("{\"true\":");
    JsonContent.write(out, trueDescription);
    out.append(",\"false\":");
    JsonContent.write(out, falseDescription);
    out.append('}');
  }
}
