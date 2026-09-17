package software.sava.typesafe.evals.corpus;

import systems.comodal.jsoniter.JsonIterator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A generic JSON reader for records whose shape is not known in advance (workflow journals
/// carry whatever schema each script declared). Objects become insertion-ordered maps,
/// arrays lists, numbers `Long` when integral and `Double` otherwise, and JSON null a Java
/// null.
public final class JsonTree {

  public static Object parse(final String json) {
    return read(JsonIterator.parse(json));
  }

  public static Object read(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case STRING -> ji.readString();
      case NUMBER -> number(ji.readNumberAsString());
      case BOOLEAN -> ji.readBoolean();
      case NULL -> {
        ji.readNull();
        yield null;
      }
      case ARRAY -> {
        final var list = new ArrayList<>();
        while (ji.readArray()) {
          list.add(read(ji));
        }
        yield list;
      }
      case OBJECT -> {
        final var map = new LinkedHashMap<String, Object>();
        ji.testObject((buf, offset, len, ji1) -> {
          map.put(new String(buf, offset, len), read(ji1));
          return true;
        });
        yield map;
      }
      case INVALID -> throw new IllegalArgumentException("invalid JSON");
    };
  }

  /// A literal without a fractional part that fits a long is a `Long`; anything else a `Double`.
  static Number number(final String literal) {
    final var decimal = new BigDecimal(literal);
    if (decimal.scale() <= 0) {
      try {
        return decimal.longValueExact();
      } catch (final ArithmeticException tooBig) {
        return decimal.doubleValue();
      }
    }
    return decimal.doubleValue();
  }

  /// The value at `key` when `node` is an object, else null.
  public static Object get(final Object node, final String key) {
    return node instanceof Map<?, ?> map ? map.get(key) : null;
  }

  /// The string at `key` when it is a non-blank string, else null.
  public static String string(final Object node, final String key) {
    return get(node, key) instanceof String s && !s.isBlank() ? s : null;
  }

  /// The integer at `key` when it is an integral number (or a string holding one), else null.
  public static Integer integer(final Object node, final String key) {
    return switch (get(node, key)) {
      case Long l -> l.intValue();
      case Double d when d == Math.rint(d) -> d.intValue();
      case String s -> {
        try {
          yield Integer.valueOf(s.strip());
        } catch (final NumberFormatException e) {
          yield null;
        }
      }
      case null, default -> null;
    };
  }

  /// The value at `key` as a list of objects, or null when it is anything else (an empty
  /// list, a string that looks like a list, or a list holding non-objects all count as
  /// "anything else").
  public static List<Map<?, ?>> objectList(final Object node, final String key) {
    if (!(get(node, key) instanceof List<?> list) || list.isEmpty()) {
      return null;
    }
    final var out = new ArrayList<Map<?, ?>>(list.size());
    for (final var item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        return null;
      }
      out.add(map);
    }
    return out;
  }

  private JsonTree() {
  }
}
