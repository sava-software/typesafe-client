dependencies {
  project(":typesafe-client")
}

dependencyAnalysis {
  issues {
    onAny {
      severity("ignore")
    }
  }
}
