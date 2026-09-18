package software.sava.typesafe;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.List;

/// One entry of `GET /v1/models`. `releaseDate` is the RFC 3339 text as sent.
///
/// Unknown card fields are skipped; a card that carries only `name` parses with the other
/// fields null. The envelope itself is required to be an object with a `models` array, as it
/// is in both reference SDKs: a body that is not an object, or one whose `models` is absent,
/// `null` or not an array, fails the parse with an [IllegalStateException] rather than reading
/// back as "this account has no models".
public record ModelCard(String name, String description, String releaseDate) {

  private static final FieldMatcher ENVELOPE_FIELDS = FieldMatcher.of("models");

  /// Parses the `{"models":[...]}` envelope.
  ///
  /// @throws IllegalStateException when the body is not an object, or `models` is absent or
  ///                               not an array
  public static List<ModelCard> parseList(final JsonIterator ji) {
    final var bodyType = ji.whatIsNext();
    if (bodyType != ValueType.OBJECT) {
      throw new IllegalStateException("models response must be a JSON object, not " + bodyType);
    }
    final var holder = new Object() {
      List<ModelCard> models;
    };
    ji.testObject(ENVELOPE_FIELDS, (fieldIndex, ji1) -> {
      if (fieldIndex == 0) {
        final var modelsType = ji1.whatIsNext();
        if (modelsType != ValueType.ARRAY) {
          throw new IllegalStateException("models response 'models' must be a JSON array, not " + modelsType);
        }
        holder.models = ji1.readList(ModelCard::parse);
      } else {
        ji1.skip();
      }
      return true;
    });
    if (holder.models == null) {
      throw new IllegalStateException("models response without a 'models' array");
    }
    return holder.models;
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
