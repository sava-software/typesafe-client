package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

/// One entry of `GET /v1/models`. `releaseDate` is the RFC 3339 text as sent.
public record ModelCard(String name, String description, String releaseDate) {

  private static final FieldMatcher ENVELOPE_FIELDS = FieldMatcher.of("models");

  /// Parses the `{"models":[...]}` envelope.
  public static List<ModelCard> parseList(final JsonIterator ji) {
    final var holder = new Object() {
      List<ModelCard> models;
    };
    ji.testObject(ENVELOPE_FIELDS, (fieldIndex, ji1) -> {
      if (fieldIndex == 0) {
        holder.models = ji1.readList(ModelCard::parse);
      } else {
        ji1.skip();
      }
      return true;
    });
    return holder.models == null ? List.of() : holder.models;
  }

  public static ModelCard parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(Parser.FIELDS, parser);
    return new ModelCard(parser.name, parser.description, parser.releaseDate);
  }

  private static final class Parser implements FieldIndexPredicate {

    static final FieldMatcher FIELDS = FieldMatcher.of("name", "description", "release_date");

    private String name;
    private String description;
    private String releaseDate;

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> name = ji.readString();
        case 1 -> description = ji.readString();
        case 2 -> releaseDate = ji.readString();
        default -> ji.skip();
      }
      return true;
    }
  }
}
