// Published to GitHub Packages only: the Maven Central feature is deliberately not applied,
// so no Central staging or upload task exists in this build. publish-gh.yml runs the task below.
val publishedModules = setOf("typesafe-client")

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  val publishTasks = publishedModules.map { ":$it:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository" }
  dependsOn(publishTasks)
}
