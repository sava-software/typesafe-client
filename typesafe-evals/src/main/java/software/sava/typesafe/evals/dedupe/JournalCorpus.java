package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.evals.corpus.JsonTree;
import software.sava.typesafe.evals.text.PathScrubber;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Reads finder findings out of Claude Code workflow journals. Attribution is by result
/// shape, because only 168 of 901 journal records in the corpus carry a phase label: a
/// result object with a non-empty list of objects under `findings`, `nonConflictConcerns`,
/// or `breakages` is a finder result. Excluded: the merge stage's re-emissions (a finding
/// with both `id` and `sources`), differential-test `results`, and all-clears (an `info`
/// claim that starts with "Checked:").
public final class JournalCorpus {

  static final List<String> LIST_KEYS = List.of("findings", "nonConflictConcerns", "breakages");
  static final List<String> TEXT_KEYS = List.of("summary", "claim", "claim_or_defect", "title", "problem");
  static final List<String> SCENARIO_KEYS = List.of("failure_scenario", "why_it_matters", "detail");
  static final List<String> LOCATION_KEYS = List.of("where", "location");

  private static final Pattern PATH_TOKEN = Pattern.compile(
      "[\\w./~<>:-]*[\\w-]+\\.(?:java|kt|kts|md|tsv|csv|txt|json|yml|yaml|toml|rs|sh|properties)\\b"
  );

  /// Every `journal.jsonl` under `<projectDir>/*/subagents/workflows/wf_*/`.
  public static List<Path> journals(final List<Path> projectDirs) {
    final var out = new ArrayList<Path>();
    for (final var project : projectDirs) {
      if (!Files.isDirectory(project)) {
        continue;
      }
      try (final var sessions = Files.list(project)) {
        for (final var session : sessions.sorted().toList()) {
          final var workflows = session.resolve("subagents").resolve("workflows");
          if (!Files.isDirectory(workflows)) {
            continue;
          }
          try (final Stream<Path> runs = Files.list(workflows)) {
            for (final var run : runs.sorted().toList()) {
              final var journal = run.resolve("journal.jsonl");
              if (run.getFileName().toString().startsWith("wf_") && Files.isRegularFile(journal)) {
                out.add(journal);
              }
            }
          }
        }
      } catch (final IOException e) {
        throw new UncheckedIOException("failed to list " + project, e);
      }
    }
    return out;
  }

  public static List<CorpusFinding> read(final List<Path> journals) {
    final var out = new ArrayList<CorpusFinding>();
    for (final var journal : journals) {
      out.addAll(readJournal(journal));
    }
    return out;
  }

  public static List<CorpusFinding> readJournal(final Path journal) {
    final var workflow = journal.getParent().getFileName().toString();
    final var out = new ArrayList<CorpusFinding>();
    final List<String> lines;
    try {
      lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to read " + journal, e);
    }
    for (final var line : lines) {
      if (line.isBlank()) {
        continue;
      }
      final var record = JsonTree.parse(line);
      if (!"result".equals(JsonTree.string(record, "type"))) {
        continue;
      }
      // a bare-string result has no lists; objectList and string both answer null for it
      final var result = JsonTree.get(record, "result");
      final var agentId = JsonTree.string(record, "agentId");
      for (final var listKey : LIST_KEYS) {
        final var items = JsonTree.objectList(result, listKey);
        if (items == null) {
          continue;
        }
        final var carrierFile = JsonTree.string(result, "file");
        for (int i = 0; i < items.size(); i++) {
          final var finding = normalize(workflow, agentId, listKey, i, items.get(i), carrierFile);
          if (finding != null) {
            out.add(finding);
          }
        }
      }
    }
    return out;
  }

  /// Null when the item is excluded: a merge re-emission, an all-clear, or a record with no text.
  static CorpusFinding normalize(final String workflow,
                                 final String agentId,
                                 final String listKey,
                                 final int index,
                                 final Map<?, ?> item,
                                 final String carrierFile) {
    if (JsonTree.get(item, "id") != null && JsonTree.get(item, "sources") != null) {
      return null;
    }
    final var text = first(item, TEXT_KEYS);
    if (text == null) {
      return null;
    }
    final var severity = JsonTree.string(item, "severity");
    if ("info".equals(severity) && text.startsWith("Checked:")) {
      return null;
    }
    final var rawFile = JsonTree.string(item, "file");
    final var location = rawFile != null ? rawFile : firstPathToken(first(item, LOCATION_KEYS));
    final var file = PathScrubber.scrub(location != null ? location : carrierFile);
    return new CorpusFinding(
        workflow + '#' + agentId + '#' + listKey + '#' + index,
        workflow,
        agentId,
        listKey,
        PathScrubber.scrub(text),
        PathScrubber.scrub(first(item, SCENARIO_KEYS)),
        PathScrubber.scrub(JsonTree.string(item, "evidence")),
        file,
        basename(file),
        JsonTree.integer(item, "line"),
        severity,
        JsonTree.string(item, "category")
    );
  }

  static String first(final Map<?, ?> item, final List<String> keys) {
    for (final var key : keys) {
      final var value = JsonTree.string(item, key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /// The first token that looks like a source path inside prose, without a trailing `:line`.
  static String firstPathToken(final String prose) {
    if (prose == null) {
      return null;
    }
    final var matcher = PATH_TOKEN.matcher(prose);
    return matcher.find() ? matcher.group() : null;
  }

  static String basename(final String file) {
    if (file == null) {
      return null;
    }
    final var trimmed = file.endsWith("/") ? file.substring(0, file.length() - 1) : file;
    // lastIndexOf answers -1 for no slash, and substring(0) is the whole string
    final var name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
    return name.isEmpty() ? null : name;
  }

  private JournalCorpus() {
  }
}
