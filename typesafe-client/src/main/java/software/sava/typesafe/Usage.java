package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

/// Token accounting for one request. Only input tokens are billed.
///
/// A count of 0 also means "not reported": the API may send `"usage":{}` or omit one count,
/// and both read back as 0 here. The Python SDK widens the same fields to `int | None` for
/// exactly that case (`_core/response_types.py:65-73`, "or `None` when the API did not report
/// it"), so code metering spend cannot tell a free request from an unreported one. An absent
/// `usage` object is still distinguishable: [SystemOneResponse#usage()] is then null.
///
/// A count must be a JSON number or `null`; a quoted number is rejected, as it is by the
/// Python SDK's strict models.
public record Usage(long inputTokens, long outputTokens) {

  public static Usage parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(Parser.FIELDS, parser);
    return new Usage(parser.inputTokens, parser.outputTokens);
  }

  private static final class Parser implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of("input_tokens", "output_tokens");

    private long inputTokens;
    private long outputTokens;

    private static long readCount(final JsonIterator ji, final String field) {
      final var valueType = ji.whatIsNext();
      if (valueType == ValueType.NULL) {
        ji.skip();
        return 0L;
      }
      if (valueType != ValueType.NUMBER) {
        throw new IllegalStateException("usage field '" + field + "' must be a JSON number, not " + valueType);
      }
      return ji.readLong();
    }

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> inputTokens = readCount(ji, "input_tokens");
        case 1 -> outputTokens = readCount(ji, "output_tokens");
        default -> ji.skip();
      }
      return true;
    }
  }
}
