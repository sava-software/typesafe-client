package software.sava.typesafe;

import systems.comodal.jsoniter.JIUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static java.util.Objects.requireNonNull;

/// A JSON value on its way to the TypeSafe API: the request `state`, a question's
/// `instructions`, and each `criteria` entry all accept "string, object, array, or null".
/// Values are written by hand into a [StringBuilder]; there is no reflection and no databind.
///
/// [Raw] carries pre-serialized JSON verbatim and is the caller's promise that it is valid.
/// Every other variant escapes what it holds.
///
/// Strings are escaped by [#writeString(StringBuilder,String)]: json-iterator's escaping for
/// `"`, `\` and the C0 controls, plus a backslash-u escape for any unpaired surrogate. A well
/// formed surrogate pair is left alone and goes out as UTF-8. Escaping the lone half matches
/// the JS SDK, whose `JSON.stringify` has been well-formed since ES2019; without it the JDK's
/// UTF-8 encoder silently substitutes `?` for the unpaired code unit.
public sealed interface JsonContent
    permits JsonContent.Text, JsonContent.Num, JsonContent.Bool, JsonContent.Raw, JsonContent.Obj, JsonContent.Arr {

  void writeTo(final StringBuilder out);

  default String toJson() {
    final var out = new StringBuilder(64);
    writeTo(out);
    return out.toString();
  }

  /// Writes `content`, or the JSON literal `null` when it is null.
  static void write(final StringBuilder out, final JsonContent content) {
    if (content == null) {
      out.append("null");
    } else {
      content.writeTo(out);
    }
  }

  /// Writes `value` as a quoted JSON string: escaped with json-iterator, except that an
  /// unpaired surrogate is written as a backslash-u escape instead of being handed to the UTF-8
  /// encoder, which would replace it with `?`. Used for every string on the wire, map keys and
  /// the request's own `model` and question ids included.
  static void writeString(final StringBuilder out, final String value) {
    out.append('"');
    final int length = value.length();
    // the span since the last escape; unguarded, since an empty span escapes to an empty
    // string and `value.substring(0)` is the same instance, so a guard would only be an
    // optimization -- and an equivalent mutant
    int from = 0;
    for (int i = 0; i < length; i++) {
      final char c = value.charAt(i);
      if (!Character.isSurrogate(c) || isPaired(value, i, c)) {
        continue;
      }
      out.append(JIUtil.escapeJson(value.substring(from, i)));
      appendUnicodeEscape(out, c);
      from = i + 1;
    }
    out.append(JIUtil.escapeJson(value.substring(from)));
    out.append('"');
  }

  /// True when the surrogate `c` at `i` is one half of a well-formed pair, which UTF-8 encodes
  /// as it stands and which therefore needs no escape.
  private static boolean isPaired(final String value, final int i, final char c) {
    return Character.isHighSurrogate(c)
        ? i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))
        : i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
  }

  private static void appendUnicodeEscape(final StringBuilder out, final char c) {
    out.append("\\u")
        .append(Character.forDigit((c >> 12) & 0xF, 16))
        .append(Character.forDigit((c >> 8) & 0xF, 16))
        .append(Character.forDigit((c >> 4) & 0xF, 16))
        .append(Character.forDigit(c & 0xF, 16));
  }

  static Text text(final String value) {
    return new Text(value);
  }

  static Num number(final long value) {
    return new Num(Long.toString(value));
  }

  static Num number(final double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("JSON has no representation for " + value);
    }
    return new Num(Double.toString(value));
  }

  static Bool bool(final boolean value) {
    return value ? Bool.TRUE : Bool.FALSE;
  }

  static Raw raw(final String json) {
    return new Raw(json);
  }

  static Obj.Builder object() {
    return new Obj.Builder();
  }

  static Obj object(final SequencedMap<String, ? extends JsonContent> fields) {
    return new Obj(fields);
  }

  static Arr array(final List<? extends JsonContent> items) {
    return new Arr(items);
  }

  /// An array of strings, each escaped.
  static Arr array(final String... items) {
    final var list = new ArrayList<JsonContent>(items.length);
    for (final var item : items) {
      list.add(new Text(item));
    }
    return new Arr(list);
  }

  /// A JSON string; escaped per RFC 8259 on write.
  record Text(String value) implements JsonContent {

    public Text {
      requireNonNull(value, "value");
    }

    @Override
    public void writeTo(final StringBuilder out) {
      writeString(out, value);
    }
  }

  /// A JSON number, held as its canonical decimal text.
  record Num(String literal) implements JsonContent {

    public Num {
      requireNonNull(literal, "literal");
      if (literal.isBlank()) {
        throw new IllegalArgumentException("number literal must not be blank");
      }
    }

    @Override
    public void writeTo(final StringBuilder out) {
      out.append(literal);
    }
  }

  record Bool(boolean value) implements JsonContent {

    static final Bool TRUE = new Bool(true);
    static final Bool FALSE = new Bool(false);

    @Override
    public void writeTo(final StringBuilder out) {
      out.append(value);
    }
  }

  /// Pre-serialized JSON, written verbatim. The caller guarantees validity; nothing here
  /// checks it, and an invalid fragment surfaces as a 422 from the API.
  record Raw(String json) implements JsonContent {

    public Raw {
      requireNonNull(json, "json");
      if (json.isBlank()) {
        throw new IllegalArgumentException("raw JSON must not be blank");
      }
    }

    @Override
    public void writeTo(final StringBuilder out) {
      out.append(json);
    }
  }

  /// A JSON object with insertion-ordered fields. A null field value writes as `null`.
  record Obj(SequencedMap<String, ? extends JsonContent> fields) implements JsonContent {

    public Obj {
      requireNonNull(fields, "fields");
      final var copy = new LinkedHashMap<String, JsonContent>();
      for (final var entry : fields.entrySet()) {
        copy.put(requireNonNull(entry.getKey(), "field name"), entry.getValue());
      }
      fields = Collections.unmodifiableSequencedMap(copy);
    }

    @Override
    public void writeTo(final StringBuilder out) {
      out.append('{');
      var first = true;
      for (final var entry : fields.entrySet()) {
        if (first) {
          first = false;
        } else {
          out.append(',');
        }
        writeString(out, entry.getKey());
        out.append(':');
        write(out, entry.getValue());
      }
      out.append('}');
    }

    public static final class Builder {

      private final LinkedHashMap<String, JsonContent> fields = new LinkedHashMap<>();

      private Builder() {
      }

      public Builder put(final String name, final JsonContent value) {
        fields.put(requireNonNull(name, "field name"), value);
        return this;
      }

      public Builder put(final String name, final String value) {
        return put(name, value == null ? null : new Text(value));
      }

      public Builder put(final String name, final long value) {
        return put(name, number(value));
      }

      public Builder put(final String name, final double value) {
        return put(name, number(value));
      }

      public Builder put(final String name, final boolean value) {
        return put(name, bool(value));
      }

      public Builder putAll(final Map<String, ? extends JsonContent> values) {
        for (final var entry : values.entrySet()) {
          put(entry.getKey(), entry.getValue());
        }
        return this;
      }

      public Obj build() {
        return new Obj(fields);
      }
    }
  }

  /// A JSON array. A null item writes as `null`.
  record Arr(List<? extends JsonContent> items) implements JsonContent {

    public Arr {
      requireNonNull(items, "items");
      items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Override
    public void writeTo(final StringBuilder out) {
      out.append('[');
      var first = true;
      for (final var item : items) {
        if (first) {
          first = false;
        } else {
          out.append(',');
        }
        write(out, item);
      }
      out.append(']');
    }
  }
}
