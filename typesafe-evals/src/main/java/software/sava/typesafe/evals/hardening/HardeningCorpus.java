package software.sava.typesafe.evals.hardening;

import software.sava.typesafe.JsonContent;
import software.sava.typesafe.evals.corpus.GitRepo;
import software.sava.typesafe.evals.rot.TypeIndex;
import software.sava.typesafe.evals.text.PathScrubber;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// Builds Experiment C2's rows for one public checkout: every labeled accepted-baseline row
/// whose label a README family paragraph declares, joined to the member at HEAD.
public final class HardeningCorpus {

  static final int BODY_CAP = 2;
  static final int BODY_LINE_CAP = 200;
  static final int PARAGRAPH_CHARS = 6000;

  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)*");

  /// One `config/pitest` directory and the module that owns it.
  public record Module(String modulePath, Path configDir, Path sourceRoot) {
  }

  private final String repoName;
  private final Path checkout;
  private final GitRepo git;

  public HardeningCorpus(final String repoName, final Path checkout, final GitRepo git) {
    this.repoName = repoName;
    this.checkout = checkout;
    this.git = git;
  }

  /// Modules with a `config/pitest/README.md` and at least one `*-accepted.csv`, from the
  /// index of the checkout (`git ls-files`), so worktree and build copies are never seen.
  public List<Module> modules() {
    final var byDir = new TreeMap<String, List<String>>();
    for (final var line : git.run("ls-files", "--", "*config/pitest/*-accepted.csv").lines().toList()) {
      final var path = line.strip();
      if (path.isEmpty()) {
        continue;
      }
      final var dir = path.substring(0, path.lastIndexOf('/'));
      byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(path);
    }
    final var modules = new ArrayList<Module>();
    for (final var dir : byDir.keySet()) {
      final var configDir = checkout.resolve(dir);
      if (!Files.isRegularFile(configDir.resolve("README.md"))) {
        continue;
      }
      final var modulePath = dir.endsWith("/config/pitest") ? dir.substring(0, dir.length() - "/config/pitest".length()) : "";
      final var moduleRoot = modulePath.isEmpty() ? checkout : checkout.resolve(modulePath);
      modules.add(new Module(modulePath, configDir, moduleRoot.resolve("src/main/java")));
    }
    return modules;
  }

  public List<HardeningRow> rows() {
    final var rows = new ArrayList<HardeningRow>();
    for (final var module : modules()) {
      rows.addAll(rows(module));
    }
    return rows;
  }

  public List<HardeningRow> rows(final Module module) {
    final List<String> readme;
    try {
      readme = Files.readAllLines(module.configDir().resolve("README.md"));
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + module.configDir(), e);
    }
    final var families = ReadmeFamilies.parse(readme);
    final var index = TypeIndex.scan(module.sourceRoot());
    final var moduleId = repoName + (module.modulePath().isEmpty() ? "" : "/" + module.modulePath());
    final var rows = new ArrayList<HardeningRow>();
    final List<Path> csvs;
    try (final var files = Files.list(module.configDir())) {
      csvs = files.filter(f -> BaselineRow.suiteOf(f) != null).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to list " + module.configDir(), e);
    }
    for (final var csv : csvs) {
      for (final var row : BaselineRow.read(csv)) {
        final var label = row.label();
        if (label == null) {
          continue;
        }
        final var family = families.family(label);
        if (family == null) {
          continue;
        }
        rows.add(row(moduleId, module, row, family, index));
      }
    }
    return rows;
  }

  HardeningRow row(final String moduleId, final Module module, final BaselineRow row, final ReadmeFamilies.Family family,
                   final TypeIndex index) {
    final var id = moduleId + '#' + row.suite() + '#' + row.binaryClassName() + '.' + row.method() + '#' + row.mutator()
        + '#' + row.status() + '#' + (row.lineHint() == null ? "" : row.lineHint());
    final var type = index.byBinaryName(row.binaryClassName());
    final var paragraphFull = paragraph(family);
    final var paragraphText = paragraphFull.length() > PARAGRAPH_CHARS ? paragraphFull.substring(0, PARAGRAPH_CHARS) + " …" : paragraphFull;
    final var operator = MutatorDescriptions.describe(row.mutator());
    final var swapped = MutatorDescriptions.swapFor(row.mutator());
    final boolean wordReal = MutatorDescriptions.mentionsFamily(paragraphText, operator.family());
    final boolean wordSwapped = MutatorDescriptions.mentionsFamily(paragraphText, MutatorDescriptions.describe(swapped).family());
    if (type == null) {
      return new HardeningRow(id, moduleId, row, family, "MISSING_TYPE", 0, 0, paragraphText.length(), null, swapped,
          wordReal, wordSwapped, List.of());
    }
    final var members = members(index, type, row.method());
    if (members.isEmpty()) {
      return new HardeningRow(id, moduleId, row, family, "MISSING_MEMBER", 0, 0, paragraphText.length(), null, swapped,
          wordReal, wordSwapped, List.of());
    }
    // a declaration that spans the row's line hint is shown first
    final var ordered = new ArrayList<>(members);
    if (row.lineHint() != null) {
      ordered.sort((a, b) -> Boolean.compare(!spans(b, row.lineHint()), !spans(a, row.lineHint())));
    }
    final var source = new StringBuilder();
    int shown = 0;
    long linesTotal = 0;
    for (final var member : ordered) {
      linesTotal += member.length();
      if (shown < BODY_CAP) {
        if (!source.isEmpty()) {
          source.append("\n\n");
        }
        source.append("// ").append(type.simpleName()).append(" lines ").append(member.startLine()).append('-').append(member.endLine()).append('\n');
        final int end = Math.min(member.endLine(), member.startLine() + BODY_LINE_CAP - 1);
        source.append(type.slice(member.startLine(), end));
        if (end < member.endLine()) {
          source.append("\n// … ").append(member.endLine() - end).append(" more lines not shown");
        }
        shown++;
      } else {
        source.append("\n// also declared: ").append(member.signature());
      }
    }
    final var present = new ArrayList<String>();
    final var missing = new ArrayList<String>();
    for (final var identifier : identifiers(paragraphText, row)) {
      (Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(source).find() ? present : missing).add(identifier);
    }
    Boolean lineInside = null;
    if (row.lineHint() != null) {
      lineInside = ordered.stream().anyMatch(m -> spans(m, row.lineHint()));
    }
    final var facts = JsonContent.object()
        .put("declarations", (long) members.size())
        .put("bodies_shown", (long) shown)
        .put("lines_total", linesTotal)
        .put("paragraph_bullets", (long) family.bullets().size())
        .put("paragraph_truncated", paragraphFull.length() > PARAGRAPH_CHARS)
        .put("identifiers_present", JsonContent.array(present.toArray(String[]::new)))
        .put("identifiers_missing", JsonContent.array(missing.toArray(String[]::new)))
        .put("line_hint_inside_body", lineInside == null ? null : JsonContent.bool(lineInside))
        .build();
    final var rowJson = JsonContent.object()
        .put("class", row.className())
        .put("method", row.method())
        .put("mutator", row.mutator())
        .put("status", row.status())
        .put("label", label(row))
        .put("line", row.lineHint() == null ? null : JsonContent.number(row.lineHint()))
        .build();
    final var filePath = PathScrubber.scrub(checkout.relativize(type.file()).toString());
    final var state = new HardeningQuestions.State(rowJson, operator.description(), source.toString(), paragraphText, facts, filePath);
    return new HardeningRow(id, moduleId, row, family, "RESOLVED", members.size(), shown, paragraphText.length(), state, swapped,
        wordReal, wordSwapped, List.copyOf(missing));
  }

  private static String label(final BaselineRow row) {
    return String.join(" # ", row.labels());
  }

  private static boolean spans(final TypeIndex.Member member, final int line) {
    return member.startLine() <= line && line <= member.endLine();
  }

  /// The family paragraph and every bullet beneath it, in README order.
  static String paragraph(final ReadmeFamilies.Family family) {
    final var out = new StringBuilder(family.paragraph());
    for (final var bullet : family.bullets()) {
      out.append('\n').append(bullet);
    }
    return out.toString();
  }

  /// Members of `type` a baseline `method` cell can refer to: the name itself, the method
  /// a `lambda$name$N` lives in, the constructors for `<init>`, and for `<clinit>` the
  /// static initializer blocks plus the static fields with initializers, which is where a
  /// `lambda$static$N` is declared in source.
  static List<TypeIndex.Member> members(final TypeIndex index, final TypeIndex.TypeDecl type, final String method) {
    var name = method;
    if (name.startsWith("lambda$")) {
      final var rest = name.substring("lambda$".length());
      final int dollar = rest.indexOf('$');
      name = dollar < 0 ? rest : rest.substring(0, dollar);
      if (name.equals("static")) {
        name = "<clinit>";
      } else if (name.equals("new")) {
        name = "<init>";
      }
    }
    if (name.equals("<clinit>")) {
      final var statics = new ArrayList<>(index.members(type, "<clinit>"));
      for (final var member : index.members(type)) {
        if (member.kind().equals("field") && member.signature().contains("static ") && member.signature().contains("=")) {
          statics.add(member);
        }
      }
      return statics;
    }
    return index.members(type, name);
  }

  /// Backticked identifier-like spans of the paragraph, excluding labels, mutator names,
  /// file names, and the row's own class and method (identical across arms, but pointless).
  static List<String> identifiers(final String paragraph, final BaselineRow row) {
    final var out = new LinkedHashMap<String, Boolean>();
    final var matcher = BACKTICKED.matcher(paragraph);
    while (matcher.find()) {
      final var span = matcher.group(1).strip();
      if (span.startsWith("#") || span.endsWith("Mutator") || span.contains(".csv") || span.contains(".md")
          || !IDENTIFIER.matcher(span).matches() || span.contains("$")) {
        continue;
      }
      final var last = span.substring(span.lastIndexOf('.') + 1);
      if (last.equals(row.simpleClassName()) || last.equals(row.method())) {
        continue;
      }
      out.putIfAbsent(last, Boolean.TRUE);
    }
    return List.copyOf(out.keySet());
  }

  public Map<String, Integer> statusCounts(final List<HardeningRow> rows) {
    final var counts = new TreeMap<String, Integer>();
    for (final var row : rows) {
      counts.merge(row.memberStatus(), 1, Integer::sum);
    }
    return counts;
  }
}
