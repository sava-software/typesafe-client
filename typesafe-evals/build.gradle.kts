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
}

dependencyAnalysis {
  issues {
    onAny {
      severity("ignore")
    }
  }
}
