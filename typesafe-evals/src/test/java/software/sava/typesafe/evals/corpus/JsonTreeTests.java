package software.sava.typesafe.evals.corpus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class JsonTreeTests {

  @Test
  void readsEveryValueKindIntoPlainJavaValues() {
    final var tree = JsonTree.parse("""
        {"s":"x","i":42,"big":123456789012345678,"d":1.5,"e":1e3,"t":true,"f":false,"n":null,"a":[1,"two",{"k":[]}],"o":{"z":1,"a":2}}""");
    final var map = assertInstanceOf(Map.class, tree);
    assertEquals("x", map.get("s"));
    assertEquals(42L, map.get("i"));
    assertEquals(123456789012345678L, map.get("big"));
    assertEquals(1.5, map.get("d"));
    assertEquals(1000L, map.get("e"), "an integral literal in exponent notation is still integral");
    assertEquals(true, map.get("t"));
    assertEquals(false, map.get("f"));
    assertNull(map.get("n"));
    assertTrue(map.containsKey("n"));
    assertEquals(List.of(1L, "two", Map.of("k", List.of())), map.get("a"));
    assertEquals(List.of("z", "a"), List.copyOf(((Map<?, ?>) map.get("o")).keySet()), "insertion order kept");
    assertEquals("bare", JsonTree.parse("\"bare\""));
    assertEquals(7L, JsonTree.parse("7"));
    assertEquals(-0.25, JsonTree.parse("-0.25"));
  }

  @Test
  void numbersBeyondLongPrecisionBecomeDoubles() {
    assertEquals(1e19, JsonTree.number("10000000000000000000"));
    assertEquals(9223372036854775807L, JsonTree.number("9223372036854775807"));
    assertEquals(2.0, JsonTree.number("2.0"));
    assertEquals(-3L, JsonTree.number("-3"));
  }

  @Test
  void accessorsAreNullSafeAndTyped() {
    final var tree = JsonTree.parse("""
        {"file":"A.java","blank":"  ","line":12,"dline":13.0,"sline":" 14 ","bad":"x","frac":1.5,"list":[{"a":1},{"b":2}],"empty":[],"mixed":[{"a":1},2],"str":"[]"}""");
    assertEquals("A.java", JsonTree.string(tree, "file"));
    assertNull(JsonTree.string(tree, "blank"));
    assertNull(JsonTree.string(tree, "line"));
    assertNull(JsonTree.string(tree, "missing"));
    assertNull(JsonTree.string("not an object", "file"));
    assertEquals(12, JsonTree.integer(tree, "line"));
    assertEquals(13, JsonTree.integer(tree, "dline"));
    assertEquals(14, JsonTree.integer(tree, "sline"));
    assertNull(JsonTree.integer(tree, "bad"));
    assertNull(JsonTree.integer(tree, "frac"));
    assertNull(JsonTree.integer(tree, "missing"));
    assertEquals(2, JsonTree.objectList(tree, "list").size());
    assertNull(JsonTree.objectList(tree, "empty"));
    assertNull(JsonTree.objectList(tree, "mixed"));
    assertNull(JsonTree.objectList(tree, "str"));
    assertNull(JsonTree.objectList(tree, "missing"));
    assertNull(JsonTree.get(List.of(), "x"));
  }

  @Test
  void malformedInputIsARuntimeException() {
    assertThrows(RuntimeException.class, () -> JsonTree.parse("{\"a\":"));
    assertThrows(RuntimeException.class, () -> JsonTree.parse("nope"));
  }
}
