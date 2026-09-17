package software.sava.typesafe.evals.hardening;

import java.util.List;
import java.util.Map;

/// PIT's mutation operators in plain words, and the coarse family each belongs to. The
/// baseline CSV records only the operator name, so the description is per operator, not
/// per mutant.
public final class MutatorDescriptions {

  public record Operator(String name, String family, String description) {
  }

  private static final Map<String, Operator> OPERATORS = Map.ofEntries(
      entry("ConditionalsBoundaryMutator", "conditional",
          "changed a conditional boundary: < became <= or <= became <, > became >= or >= became >"),
      entry("RemoveConditionalMutator_EQUAL_IF", "conditional",
          "removed an equality conditional (== or !=) by replacing it with true, so the if-branch always runs"),
      entry("RemoveConditionalMutator_EQUAL_ELSE", "conditional",
          "removed an equality conditional (== or !=) by replacing it with false, so the if-branch never runs"),
      entry("RemoveConditionalMutator_ORDER_IF", "conditional",
          "removed an ordering conditional (<, <=, >, >=) by replacing it with true, so the if-branch always runs"),
      entry("RemoveConditionalMutator_ORDER_ELSE", "conditional",
          "removed an ordering conditional (<, <=, >, >=) by replacing it with false, so the if-branch never runs"),
      entry("MathMutator", "arithmetic",
          "replaced an arithmetic or bitwise operator with its counterpart: + with -, * with /, % with *, << with >>, & with |, ^ with &"),
      entry("IncrementsMutator", "arithmetic",
          "replaced an increment with a decrement or a decrement with an increment on a local variable"),
      entry("InvertNegsMutator", "arithmetic",
          "removed the negation of a numeric value"),
      entry("VoidMethodCallMutator", "call",
          "removed a call to a void method"),
      entry("NakedReceiverMutator", "call",
          "replaced a method call with its receiver: the call is dropped and the object it was called on is used instead"),
      entry("BooleanTrueReturnValsMutator", "return",
          "replaced a boolean return value with true"),
      entry("BooleanFalseReturnValsMutator", "return",
          "replaced a boolean return value with false"),
      entry("NullReturnValsMutator", "return",
          "replaced a return value with null"),
      entry("EmptyObjectReturnValsMutator", "return",
          "replaced a return value with an empty value: an empty string, collection, array, or Optional"),
      entry("PrimitiveReturnsMutator", "return",
          "replaced a primitive return value with 0")
  );

  /// Words a README paragraph uses when it argues about a family's kind of change; the
  /// mutator-word baseline asks whether the paragraph contains any of them.
  private static final Map<String, List<String>> FAMILY_WORDS = Map.of(
      "conditional", List.of("conditional", "boundary", "comparison", "branch", "guard", "condition"),
      "arithmetic", List.of("arithmetic", "operator", "addition", "subtraction", "multiplication", "increment", "decrement", "negation", "math"),
      "call", List.of("call", "void", "invocation", "invoke"),
      "return", List.of("return", "null", "empty", "boolean", "primitive")
  );

  /// The family whose description the SWAPPED arm shows for a row of `family`: the next
  /// family in a fixed cycle, so every row's swap crosses a family boundary.
  private static final Map<String, String> SWAP_TARGET = Map.of(
      "conditional", "return",
      "return", "call",
      "call", "arithmetic",
      "arithmetic", "conditional",
      "other", "conditional"
  );

  /// One representative operator per family for the SWAPPED arm.
  private static final Map<String, String> REPRESENTATIVE = Map.of(
      "conditional", "RemoveConditionalMutator_EQUAL_ELSE",
      "return", "NullReturnValsMutator",
      "call", "VoidMethodCallMutator",
      "arithmetic", "MathMutator"
  );

  private MutatorDescriptions() {
  }

  public static List<String> familyWords(final String family) {
    return FAMILY_WORDS.getOrDefault(family, List.of());
  }

  /// Whether `text` (lower-cased by the caller or not) contains a family word of `family`.
  public static boolean mentionsFamily(final String text, final String family) {
    final var lower = text.toLowerCase(java.util.Locale.ROOT);
    for (final var word : familyWords(family)) {
      if (lower.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /// The operator the SWAPPED arm describes for a row mutated by `mutator`.
  public static String swapFor(final String mutator) {
    return REPRESENTATIVE.get(SWAP_TARGET.get(describe(mutator).family()));
  }

  private static Map.Entry<String, Operator> entry(final String name, final String family, final String description) {
    return Map.entry(name, new Operator(name, family, description));
  }

  /// The operator, or a generic entry for a name this table does not know.
  public static Operator describe(final String mutator) {
    final var known = OPERATORS.get(mutator);
    return known != null ? known : new Operator(mutator, "other", "mutation operator " + mutator);
  }

  public static boolean known(final String mutator) {
    return OPERATORS.containsKey(mutator);
  }
}
