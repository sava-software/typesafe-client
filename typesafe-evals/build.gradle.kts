plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

dependencies {
  project(":typesafe-client")
}

hardening {
  mutation.register("text") {
    targetClasses = listOf("software.sava.typesafe.evals.text.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.text.*Test*"
  }
  mutation.register("corpus") {
    targetClasses = listOf("software.sava.typesafe.evals.corpus.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.corpus.*Test*"
  }
  mutation.register("jev") {
    targetClasses = listOf("software.sava.typesafe.evals.jev.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.jev.*Test*"
  }
  mutation.register("metrics") {
    targetClasses = listOf("software.sava.typesafe.evals.metrics.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.metrics.*Test*"
  }
  mutation.register("report") {
    targetClasses = listOf("software.sava.typesafe.evals.report.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.report.*Test*"
  }
}

dependencyAnalysis {
  issues {
    onAny {
      severity("ignore")
    }
  }
}
