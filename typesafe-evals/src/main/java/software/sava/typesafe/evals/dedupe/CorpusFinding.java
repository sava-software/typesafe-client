package software.sava.typesafe.evals.dedupe;

import software.sava.typesafe.evals.text.Jaccard;

import java.util.Set;

/// One finding from a finder-phase result, normalized across the finder schemas.
///
/// @param id        `<workflow>#<agent>#<list key>#<index>`, unique within the corpus
/// @param workflow  the `wf_...` directory name
/// @param text      summary / claim / title / problem, whichever the schema carried
/// @param scenario  failure_scenario / why_it_matters / detail, or null
/// @param evidence  the evidence field, or null
/// @param file      the scrubbed file path or the first path-like token of a prose location, or null
/// @param basename  the last segment of `file`, or null
/// @param line      the line number, or null when the schema carried none
public record CorpusFinding(String id,
                            String workflow,
                            String agentId,
                            String listKey,
                            String text,
                            String scenario,
                            String evidence,
                            String file,
                            String basename,
                            Integer line,
                            String severity,
                            String category) {

  /// The prose a lexical baseline compares: text plus scenario.
  public String prose() {
    return scenario == null ? text : text + ' ' + scenario;
  }

  public Set<String> tokens() {
    return Jaccard.tokens(prose());
  }
}
