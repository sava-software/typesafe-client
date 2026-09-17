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
  mutation.register("rot") {
    targetClasses = listOf("software.sava.typesafe.evals.rot.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.rot.*Test*"
  }
  mutation.register("dedupe") {
    targetClasses = listOf("software.sava.typesafe.evals.dedupe.*")
    excludedClasses = listOf("*Test*")
    targetTests = "software.sava.typesafe.evals.dedupe.*Test*"
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

// ./gradlew :typesafe-evals:dedupe -PevalArgs="--projects <dir,dir> --out <dir> --mode record|replay|corpus"
tasks.register<JavaExec>("dedupe") {
  group = "experiments"
  description = "Experiment B: finding dedupe between finder and refuter phases"
  mainModule.set("software.sava.typesafe_evals")
  mainClass.set("software.sava.typesafe.evals.dedupe.DedupeExperiment")
  classpath = sourceSets.main.get().runtimeClasspath
  args = (project.findProperty("evalArgs") as String?)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
}

// ./gradlew :typesafe-evals:rot -PevalArgs="--manifest <file> --golden-fleet <dir> --checkouts <dir> --out <dir> --mode record|replay|corpus"
tasks.register<JavaExec>("rot") {
  group = "experiments"
  description = "Experiment A: acceptance-note rot detector"
  mainModule.set("software.sava.typesafe_evals")
  mainClass.set("software.sava.typesafe.evals.rot.RotExperiment")
  classpath = sourceSets.main.get().runtimeClasspath
  args = (project.findProperty("evalArgs") as String?)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
}
