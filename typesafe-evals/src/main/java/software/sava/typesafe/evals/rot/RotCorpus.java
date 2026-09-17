package software.sava.typesafe.evals.rot;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.corpus.CommandRunner;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.text.PathScrubber;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// Builds the rows of one module: every class-qualified member a snapshot note names,
/// resolved against HEAD, with the bounded state the plan specifies (note window <= 60
/// lines, at most two member bodies of <= 200 lines each, signatures for the rest) and the
/// code-computed premise facts that double as the control arm.
public final class RotCorpus {

  static final int NOTE_WINDOW_CHARS = 6_000;
  static final int BODY_LINE_CAP = 200;
  static final int BODY_CAP = 2;

  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w]*");
  private static final Pattern ONLY_IMPLEMENTATION = Pattern.compile(
      "\\b(?:the )?(?:only|sole) (?:implementation|implementor|implementer|subclass)\\b", Pattern.CASE_INSENSITIVE);
  private static final Set<String> NOT_CODE = Set.of(
      "SURVIVED", "KILLED", "NO_COVERAGE", "TIMED_OUT", "RUN_ERROR", "MEMORY_ERROR", "NON_VIABLE",
      "null", "true", "false", "this", "return", "if", "else", "for", "while", "new", "void", "int", "long",
      "byte", "boolean", "final", "static", "public", "private", "default", "throws", "catch", "finally",
      "STRONGER", "DEFAULTS", "check", "test", "main");

  private final Manifest.Entry entry;
  private final Path checkout;
  private final Path snapshotDir;
  private final TypeIndex main;
  private final MemberResolver resolver;
  private final int rung;
  private final Set<String> suiteNames;
  private final Map<String, String> goldHints;

  public RotCorpus(final Manifest.Entry entry,
                   final Path checkout,
                   final Path snapshotDir,
                   final Map<String, TypeIndex> siblingModules,
                   final CommandRunner runner,
                   final Map<String, String> goldHints) {
    this.entry = entry;
    this.checkout = checkout;
    this.snapshotDir = snapshotDir;
    this.main = TypeIndex.scan(checkout.resolve(entry.sourceRoot()));
    final var test = TypeIndex.scan(checkout.resolve(entry.testRoot()));
    this.resolver = new MemberResolver(entry, checkout, main, test, siblingModules, runner);
    this.rung = rung(new GitRepo(checkout, runner).changedJavaFiles(entry.commit(), entry.sourceRoot()).size());
    this.suiteNames = suiteNames(snapshotDir);
    this.goldHints = goldHints;
  }

  public int rung() {
    return rung;
  }

  public TypeIndex mainIndex() {
    return main;
  }

  /// 0: no changed .java files; 1: one or two; 2: three to twelve; 3: more.
  static int rung(final int changedFiles) {
    if (changedFiles == 0) {
      return 0;
    }
    if (changedFiles <= 2) {
      return 1;
    }
    return changedFiles <= 12 ? 2 : 3;
  }

  /// `<suite>` from every `<suite>-accepted.csv` and `<suite>-timeouts.csv` in the snapshot.
  static Set<String> suiteNames(final Path snapshotDir) {
    final var names = new LinkedHashSet<String>();
    if (!Files.isDirectory(snapshotDir)) {
      return names;
    }
    try (final var files = Files.list(snapshotDir)) {
      for (final var file : files.map(f -> f.getFileName().toString()).sorted().toList()) {
        final int cut = file.indexOf("-accepted.csv") >= 0 ? file.indexOf("-accepted.csv") : file.indexOf("-timeouts.csv");
        if (cut > 0) {
          names.add(file.substring(0, cut));
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to list " + snapshotDir, e);
    }
    return names;
  }

  public List<RotRow> rows() {
    final List<String> lines;
    try {
      lines = Files.readAllLines(snapshotDir.resolve("README.md"), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + snapshotDir.resolve("README.md"), e);
    }
    final var rows = new ArrayList<RotRow>();
    for (final var note : ReadmeNotes.notes(lines)) {
      final var refs = ReadmeNotes.members(note);
      final var resolutions = new LinkedHashMap<ReadmeNotes.MemberRef, MemberResolver.Resolution>();
      for (final var ref : refs) {
        resolutions.put(ref, resolver.resolve(ref));
      }
      for (final var ref : refs) {
        final var resolution = resolutions.get(ref);
        if (resolution.status() == MemberResolver.Status.TEST_CLASS || resolution.status() == MemberResolver.Status.CROSS_MODULE) {
          continue;
        }
        rows.add(row(note, ref, resolution, resolutions));
      }
    }
    return rows;
  }

  RotRow row(final ReadmeNotes.Note note,
             final ReadmeNotes.MemberRef ref,
             final MemberResolver.Resolution resolution,
             final Map<ReadmeNotes.MemberRef, MemberResolver.Resolution> siblings) {
    final var methodSource = methodSource(ref, resolution);
    final var siblingSource = siblingSource(ref, siblings);
    final var visible = (methodSource == null ? "" : methodSource) + '\n' + (siblingSource == null ? "" : siblingSource);
    final var flags = new ArrayList<String>();
    final var identifiers = identifiers(note, ref);
    final var present = new ArrayList<String>();
    final var missing = new ArrayList<String>();
    if (resolution.resolved()) {
      for (final var identifier : identifiers) {
        (Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(visible).find() ? present : missing).add(identifier);
      }
    } else {
      flags.add("member_missing");
    }
    for (final var identifier : missing) {
      flags.add("identifier_missing:" + identifier);
    }
    final boolean onlyImplementation = ONLY_IMPLEMENTATION.matcher(note.bullet()).find();
    Integer implementors = null;
    if (onlyImplementation && resolution.type() != null) {
      implementors = implementors(resolution.type());
      if (implementors > 1) {
        flags.add("only_implementation_contradicted");
      }
    }
    final var testsPresent = new ArrayList<String>();
    final var testsMissing = new ArrayList<String>();
    for (final var entry : siblings.entrySet()) {
      if (entry.getValue().status() == MemberResolver.Status.TEST_CLASS) {
        testsPresent.add(entry.getKey().display());
      }
    }
    final var facts = JsonContent.object()
        .put("member_status", resolution.status().name())
        .put("member_status_detail", resolution.detail())
        .put("resolved_type", resolution.type() == null ? null : resolution.type().binaryName())
        .put("declarations", (long) resolution.members().size())
        .put("bodies_shown", (long) Math.min(BODY_CAP, resolution.members().size()))
        .put("identifiers_present", JsonContent.array(present.toArray(String[]::new)))
        .put("identifiers_missing", JsonContent.array(missing.toArray(String[]::new)))
        .put("claims_only_implementation", onlyImplementation)
        .put("implementor_count", implementors == null ? null : JsonContent.number(implementors))
        .put("line_hints_inside_body", lineHintsInside(ref, resolution) == null ? null : JsonContent.bool(lineHintsInside(ref, resolution)))
        .put("covering_tests_named", JsonContent.array(testsPresent.toArray(String[]::new)))
        .build();
    final var filePath = resolution.type() == null ? null : PathScrubber.scrub(checkout.relativize(resolution.type().file()).toString());
    final var state = new RotQuestions.State(noteWindow(note), methodSource, siblingSource, facts, filePath);
    final var id = entry.id() + '#' + note.line() + '#' + ref.display();
    return new RotRow(id, entry, rung, ref, resolution, state, List.copyOf(flags), goldHints.get(goldKey(entry, ref)));
  }

  static String goldKey(final Manifest.Entry entry, final ReadmeNotes.MemberRef ref) {
    return entry.id() + '#' + ref.simpleClassName() + '.' + ref.memberPart();
  }

  /// Section title, family paragraph, and bullet, capped.
  static String noteWindow(final ReadmeNotes.Note note) {
    final var window = new StringBuilder();
    if (!note.section().isEmpty()) {
      window.append("## ").append(note.section()).append('\n');
    }
    if (!note.family().isEmpty()) {
      window.append(note.family()).append('\n');
    }
    window.append(note.bullet());
    if (window.length() > NOTE_WINDOW_CHARS) {
      window.setLength(NOTE_WINDOW_CHARS);
      window.append(" …");
    }
    return window.toString();
  }

  /// At most two bodies (each capped, sliced around a line hint when too long), then a
  /// signature line per remaining overload. A member that is gone gets the type's member
  /// index instead, so the absence is visible.
  String methodSource(final ReadmeNotes.MemberRef ref, final MemberResolver.Resolution resolution) {
    final var type = resolution.type();
    if (type == null) {
      return null;
    }
    final var out = new StringBuilder();
    if (!resolution.resolved()) {
      out.append("// `").append(ref.memberPart()).append("` is not declared in ").append(type.binaryName())
          .append(" at HEAD (").append(resolution.detail()).append("). Members declared now:\n");
      for (final var member : main.members(type)) {
        out.append("//   ").append(member.signature()).append('\n');
      }
      return out.toString();
    }
    final var members = resolution.members();
    for (int i = 0; i < members.size(); i++) {
      final var member = members.get(i);
      if (i < BODY_CAP) {
        out.append(body(type, member, ref)).append("\n\n");
      } else {
        out.append("// also declared: ").append(member.signature()).append('\n');
      }
    }
    return out.toString().strip();
  }

  static String body(final TypeIndex.TypeDecl type, final TypeIndex.Member member, final ReadmeNotes.MemberRef ref) {
    int from = member.startLine();
    int to = member.endLine();
    if (member.length() > BODY_LINE_CAP) {
      final int anchor = firstLineHint(ref, member);
      from = Math.max(member.startLine(), anchor - BODY_LINE_CAP / 2);
      to = Math.min(member.endLine(), from + BODY_LINE_CAP - 1);
      return "// " + type.binaryName() + " lines " + from + "-" + to + " of " + member.startLine() + "-" + member.endLine()
          + " (truncated around line " + anchor + ")\n" + type.slice(from, to);
    }
    return "// " + type.binaryName() + " lines " + from + "-" + to + "\n" + type.slice(from, to);
  }

  /// The first line hint that falls inside the member, else the member's first line.
  static int firstLineHint(final ReadmeNotes.MemberRef ref, final TypeIndex.Member member) {
    if (ref.lineHints() != null) {
      for (final var hint : ref.lineHints().split("[/,–-]")) {
        try {
          final int line = Integer.parseInt(hint);
          if (line >= member.startLine() && line <= member.endLine()) {
            return line;
          }
        } catch (final NumberFormatException ignored) {
          // not a line
        }
      }
    }
    return member.startLine();
  }

  /// Bodies of the other resolved members the same note names, at most two, then signatures.
  String siblingSource(final ReadmeNotes.MemberRef ref, final Map<ReadmeNotes.MemberRef, MemberResolver.Resolution> siblings) {
    final var out = new StringBuilder();
    int bodies = 0;
    for (final var entry : siblings.entrySet()) {
      if (entry.getKey().equals(ref) || !entry.getValue().resolved()) {
        continue;
      }
      final var member = entry.getValue().members().getFirst();
      if (bodies < BODY_CAP) {
        out.append(body(entry.getValue().type(), member, entry.getKey())).append("\n\n");
        bodies++;
      } else {
        out.append("// also named: ").append(entry.getValue().type().binaryName()).append(' ').append(member.signature()).append('\n');
      }
    }
    return out.isEmpty() ? null : out.toString().strip();
  }

  /// True when every line hint on the reference falls inside a matched body; null without hints
  /// or a resolution.
  static Boolean lineHintsInside(final ReadmeNotes.MemberRef ref, final MemberResolver.Resolution resolution) {
    if (ref.lineHints() == null || !resolution.resolved()) {
      return null;
    }
    for (final var hint : ref.lineHints().split("[/,–-]")) {
      final int line;
      try {
        line = Integer.parseInt(hint);
      } catch (final NumberFormatException e) {
        continue;
      }
      if (resolution.members().stream().noneMatch(m -> line >= m.startLine() && line <= m.endLine())) {
        return false;
      }
    }
    return true;
  }

  /// Backticked identifiers in the bullet that look like code and are not the subject, a
  /// suite name, a mutator, a status, or a keyword.
  List<String> identifiers(final ReadmeNotes.Note note, final ReadmeNotes.MemberRef ref) {
    final var out = new LinkedHashSet<String>();
    final var spans = BACKTICKED.matcher(note.bullet());
    while (spans.find()) {
      final var span = spans.group(1).strip();
      if (!IDENTIFIER.matcher(span).matches() || span.length() < 2) {
        continue;
      }
      if (NOT_CODE.contains(span) || span.endsWith("Mutator") || suiteNames.contains(span)
          || span.equals(ref.memberPart()) || span.equals(ref.simpleClassName()) || span.startsWith("EQUAL_") || span.startsWith("ORDER_")) {
        continue;
      }
      out.add(span);
    }
    return List.copyOf(out);
  }

  /// Types in this module whose header extends or implements `type`'s simple name.
  int implementors(final TypeIndex.TypeDecl type) {
    final var pattern = Pattern.compile("\\b(?:extends|implements)\\b[^{]*\\b" + Pattern.quote(type.simpleName()) + "\\b");
    int count = 0;
    for (final var candidate : main.types()) {
      if (!candidate.binaryName().equals(type.binaryName()) && pattern.matcher(candidate.header()).find()) {
        count++;
      }
    }
    return count;
  }
}
