package software.sava.typesafe;

/// Optional descriptions of what a yes and a no mean for a [Noul]. Either side may be null.
///
/// A null side is omitted from the object rather than written as `null`: both reference SDKs
/// send only the keys the caller supplied (the JS SDK hands the caller's object to
/// `JSON.stringify`, and the Python SDK's `NoulCriteria` is a `TypedDict(total=False)`), so a
/// one-sided noul goes out as `{"true":"..."}`. Two null sides write `{}`, which the Python
/// SDK also sends. Under the published schema an omitted key and an explicit `null` decode to
/// the same value; the bytes, and whatever the server renders to the model, are what differ.
public record NoulCriteria(JsonContent trueDescription, JsonContent falseDescription) {

  public static NoulCriteria of(final String trueDescription, final String falseDescription) {
    return new NoulCriteria(
        trueDescription == null ? null : JsonContent.text(trueDescription),
        falseDescription == null ? null : JsonContent.text(falseDescription)
    );
  }

  void writeTo(final StringBuilder out) {
    out.append('{');
    if (trueDescription != null) {
      out.append("\"true\":");
      trueDescription.writeTo(out);
    }
    if (falseDescription != null) {
      if (trueDescription != null) {
        out.append(',');
      }
      out.append("\"false\":");
      falseDescription.writeTo(out);
    }
    out.append('}');
  }
}
