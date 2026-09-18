package software.sava.typesafe.evals.hardening;

import software.sava.typesafe.evals.rot.ReadmeNotes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The family paragraphs of a `config/pitest/README.md`, keyed by the baseline labels they
/// declare (`baseline label `# x``, `baseline labels `# a`, `# b``, or a bullet that opens
/// with the label).
public final class ReadmeFamilies {

  /// One family: the paragraph that declares the label and the bullets beneath it.
  ///
  /// @param anchorLine 1-based line of the paragraph (or bullet) that declares the label
  public record Family(String label, String section, String paragraph, List<String> bullets, int anchorLine) {
  }

  private static final Pattern LABEL = Pattern.compile("`# ([^`]+)`");

  private final Map<String, Family> byLabel;

  private ReadmeFamilies(final Map<String, Family> byLabel) {
    this.byLabel = byLabel;
  }

  public Family family(final String label) {
    return byLabel.get(label);
  }

  public Map<String, Family> families() {
    return byLabel;
  }

  /// Labels in README order.
  public List<String> labels() {
    return List.copyOf(byLabel.keySet());
  }

  /// A label is declared by any non-bullet, non-heading paragraph that backticks it (bold
  /// family paragraphs and plain ones alike); the bullets that follow such a paragraph,
  /// up to the next family paragraph or heading, are its bullets. A bullet that declares a
  /// label no paragraph declared is a family of its own.
  public static ReadmeFamilies parse(final List<String> lines) {
    final var families = new LinkedHashMap<String, Family>();
    String section = "";
    final var paragraph = new ArrayList<String>();
    int paragraphStart = 0;
    final var declaring = new ArrayList<String>(); // labels of the paragraph the bullets below belong to
    final var bullets = new ArrayList<String>();
    final var bullet = new StringBuilder();
    // one empty line past the end closes the last bullet and the last paragraph
    final var padded = new ArrayList<>(lines);
    padded.add("");
    for (int i = 0; i < padded.size(); i++) {
      final var line = padded.get(i);
      final var stripped = line.strip();
      final boolean heading = stripped.startsWith("#");
      final boolean isBullet = stripped.startsWith("- ") || stripped.startsWith("* ");
      final boolean continuation = !bullet.isEmpty() && !stripped.isEmpty() && !isBullet && !heading && Character.isWhitespace(line.charAt(0));
      if (continuation) {
        bullet.append(' ').append(stripped);
        continue;
      }
      if (!bullet.isEmpty()) {
        bullets.add(bullet.toString());
        bullet.setLength(0);
      }
      if (isBullet) {
        // a bullet directly under a paragraph closes that paragraph
        endParagraph(families, paragraph, paragraphStart, section, declaring, bullets);
        bullet.append(stripped);
        continue;
      }
      // a non-bullet line ends the bullet run below the current paragraph
      if (stripped.isEmpty() || heading) {
        endParagraph(families, paragraph, paragraphStart, section, declaring, bullets);
        if (heading) {
          flush(families, declaring, bullets);
          section = stripped.replaceFirst("^#+\\s*", "");
        }
        continue;
      }
      // prose: starts or extends the current paragraph; bullets seen so far close the previous family
      if (paragraph.isEmpty()) {
        if (!bullets.isEmpty()) {
          flush(families, declaring, bullets);
        }
        paragraphStart = i + 1;
      }
      paragraph.add(stripped);
    }
    flush(families, declaring, bullets);
    // bullets that declare a label nobody declared are families of their own
    for (final var note : ReadmeNotes.notes(lines)) {
      for (final var label : labelsIn(note.bullet())) {
        families.putIfAbsent(label, new Family(label, note.section(), note.bullet(), List.of(), note.line()));
      }
    }
    return new ReadmeFamilies(families);
  }

  /// Closes the paragraph being read: if it declares labels it becomes the paragraph the
  /// following bullets belong to (after registering the previous one with its bullets). An
  /// empty paragraph declares nothing, so it closes to nothing.
  private static void endParagraph(final Map<String, Family> families, final List<String> paragraph, final int paragraphStart,
                                   final String section, final List<String> declaring, final List<String> bullets) {
    final var text = String.join(" ", paragraph);
    final var labels = labelsIn(text);
    if (!labels.isEmpty()) {
      flush(families, declaring, bullets);
      declaring.add(section);
      declaring.add(text);
      declaring.add(Integer.toString(paragraphStart));
      declaring.addAll(labels);
    }
    paragraph.clear();
  }

  /// Registers the paragraph in `declaring` (section, text, start line, labels...) with the
  /// bullets collected beneath it, once per label, first declaration wins; both are emptied
  /// so whatever follows starts a family of its own.
  private static void flush(final Map<String, Family> families, final List<String> declaring, final List<String> bullets) {
    if (declaring.size() >= 4) {
      final var section = declaring.get(0);
      final var text = declaring.get(1);
      final int line = Integer.parseInt(declaring.get(2));
      for (final var label : declaring.subList(3, declaring.size())) {
        families.putIfAbsent(label, new Family(label, section, text, List.copyOf(bullets), line));
      }
    }
    declaring.clear();
    bullets.clear();
  }

  /// Every `` `# label` `` mentioned anywhere in `text`.
  public static List<String> labelsIn(final String text) {
    final var labels = new ArrayList<String>();
    final Matcher matcher = LABEL.matcher(text);
    while (matcher.find()) {
      labels.add(matcher.group(1).strip());
    }
    return labels;
  }
}
