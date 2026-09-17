package software.sava.typesafe;

/// A yes/no judgment. The answer is the probability of yes: near 1 a strong yes, near 0 a
/// strong no, near 0.5 similar probability either way -- not "medium". There is no separate
/// confidence. Ask one Noul per label when several may apply.
///
/// @param criteria optional descriptions of the `true` and `false` outcomes; omitted when null
public record Noul(JsonContent instructions, NoulCriteria criteria) implements Question {

  public static final String TYPE = "noul";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void writeTo(final StringBuilder out) {
    Question.writeHead(out, this);
    if (criteria != null) {
      out.append(",\"criteria\":");
      criteria.writeTo(out);
    }
    out.append('}');
  }
}
