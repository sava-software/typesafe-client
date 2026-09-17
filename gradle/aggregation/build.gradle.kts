plugins {
  id("software.sava.build.feature.publish-maven-central")
}

val publishedModules = setOf("typesafe-client")

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  val publishTasks = publishedModules.map { ":$it:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository" }
  dependsOn(publishTasks)
}
