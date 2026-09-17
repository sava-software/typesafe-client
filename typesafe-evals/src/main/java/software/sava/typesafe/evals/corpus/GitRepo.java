package software.sava.typesafe.evals.corpus;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/// The few git reads a corpus builder needs, over a local checkout.
public record GitRepo(Path root, CommandRunner runner) {

  private static final Pattern ORIGIN = Pattern.compile(
      "^(?:git@github\\.com:|https://github\\.com/|ssh://git@github\\.com/)([^/\\s]+/[^/\\s]+?)(?:\\.git)?/?$"
  );

  public GitRepo(final Path root) {
    this(root, ProcessCommandRunner.INSTANCE);
  }

  /// `owner/repo` parsed from the `origin` remote; ssh, https, and ssh:// forms are accepted.
  public String originOwnerRepo() {
    final var url = git("remote", "get-url", "origin").strip();
    final var matcher = ORIGIN.matcher(url);
    if (!matcher.matches()) {
      throw new IllegalStateException("cannot read owner/repo from origin '" + url + "' of " + root);
    }
    return matcher.group(1);
  }

  public String head() {
    return git("rev-parse", "HEAD").strip();
  }

  /// The file `path` (repository-relative) as it was at `commit`.
  public String show(final String commit, final String path) {
    return git("show", commit + ':' + path);
  }

  /// Repository-relative paths of `.java` files under `subdir` that differ between `from`
  /// and HEAD.
  public List<String> changedJavaFiles(final String from, final String subdir) {
    final var out = git("diff", "--name-only", from + "..HEAD", "--", subdir);
    return out.lines().map(String::strip).filter(line -> line.endsWith(".java")).toList();
  }

  private String git(final String... args) {
    final var command = new java.util.ArrayList<String>(args.length + 3);
    command.add("git");
    command.add("-C");
    command.add(root.toString());
    command.addAll(List.of(args));
    return runner.run(command, null);
  }
}
