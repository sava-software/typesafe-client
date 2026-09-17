package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

/// Token accounting for one request. Only input tokens are billed.
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

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> inputTokens = ji.readLong();
        case 1 -> outputTokens = ji.readLong();
        default -> ji.skip();
      }
      return true;
    }
  }
}
