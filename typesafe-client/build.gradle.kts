plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  // the wire tests serve real responses to the client's own transport
  requires("jdk.httpserver")
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  // the recompiled root includes test sources; keep PIT off the tests themselves
  mutation.register("request") {
    targetClasses = listOf(
      "software.sava.typesafe.JsonContent",
      "software.sava.typesafe.JsonContent\$*",
      "software.sava.typesafe.Question",
      "software.sava.typesafe.Question\$*",
      "software.sava.typesafe.Choice",
      "software.sava.typesafe.Noul",
      "software.sava.typesafe.NoulCriteria",
      "software.sava.typesafe.Score",
      "software.sava.typesafe.SystemOneRequest",
      "software.sava.typesafe.SystemOneRequest\$*"
    )
    excludedClasses = listOf("*Test*", "*Fuzz*", "*LiveCheck*")
    targetTests = "software.sava.typesafe.*Test*"
  }
  mutation.register("response") {
    targetClasses = listOf(
      "software.sava.typesafe.Answer",
      "software.sava.typesafe.Answer\$*",
      "software.sava.typesafe.ChoiceAnswer",
      "software.sava.typesafe.NoulAnswer",
      "software.sava.typesafe.ScoreAnswer",
      "software.sava.typesafe.UnknownAnswer",
      "software.sava.typesafe.Usage",
      "software.sava.typesafe.Usage\$*",
      "software.sava.typesafe.ModelCard",
      "software.sava.typesafe.ModelCard\$*",
      "software.sava.typesafe.SystemOneResponse",
      "software.sava.typesafe.SystemOneResponse\$*"
    )
    excludedClasses = listOf("*Test*", "*Fuzz*", "*LiveCheck*")
    targetTests = "software.sava.typesafe.*Test*"
  }
  mutation.register("client") {
    targetClasses = listOf(
      "software.sava.typesafe.TypeSafeClient",
      "software.sava.typesafe.TypeSafeClient\$*",
      "software.sava.typesafe.TypeSafeClientImpl",
      "software.sava.typesafe.TypeSafeClientImpl\$*",
      "software.sava.typesafe.RecordingTypeSafeClient",
      "software.sava.typesafe.RecordingTypeSafeClient\$*",
      "software.sava.typesafe.exceptions.*"
    )
    excludedClasses = listOf("*Test*", "*Fuzz*", "*LiveCheck*")
    targetTests = "software.sava.typesafe.*Test*"
  }
  fuzz.register("response") {
    targetClass = "software.sava.typesafe.SystemOneResponseFuzz"
    // a full answers envelope: nested maps of probabilities and legends that a
    // from-scratch mutator would take a long time to assemble
    maxLen = 4096
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/response")
  }
}
