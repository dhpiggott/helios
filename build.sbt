import com.typesafe.sbt.packager.docker._

name := "helios"

ThisBuild / scalaVersion := "3.1.1"
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.3.1-RC3"
ThisBuild / semanticdbEnabled := true
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(
    List("all scalafmtSbtCheck scalafmtCheckAll"),
    name = Some("Check that scalafmt has been run")
  ),
  WorkflowStep.Sbt(
    List("scalafixAll --check"),
    name = Some("Check that scalafix has been run")
  ),
  WorkflowStep.Sbt(
    List("test", "helios/Docker/stage"),
    name = Some("Build project")
  )
)
ThisBuild / githubWorkflowEnv += "DOCKER_REPOSITORY" -> "dhpiggott/helios"
// TODO: Dependabot?
ThisBuild / githubWorkflowPublishPreamble := Seq(
  WorkflowStep.Use(
    UseRef.Public("docker", "setup-qemu-action", "v1"),
    name = Some("Setup QEMU")
  ),
  WorkflowStep.Use(
    UseRef.Public("docker", "setup-buildx-action", "v1"),
    name = Some("Setup Docker Buildx")
  ),
  WorkflowStep.Use(
    UseRef.Public("docker", "login-action", "v1.10.0"),
    params = Map(
      "username" -> "${{ secrets.DOCKERHUB_USERNAME }}",
      "password" -> "${{ secrets.DOCKERHUB_TOKEN }}"
    )
  ),
  WorkflowStep.Use(
    UseRef.Public("docker", "metadata-action", "v3"),
    params = Map("images" -> "${{ env.DOCKER_REPOSITORY }}"),
    id = Some("meta")
  )
)
ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Use(
    UseRef.Public("docker", "build-push-action", "v2"),
    name = Some("Build and push"),
    params = Map(
      "context" -> "helios/target/docker/stage",
      "platforms" -> "linux/amd64, linux/arm64",
      "push" -> "true",
      "tags" -> "${{ steps.meta.outputs.tags }}",
      "cache-from" -> "type=gha",
      "cache-to" -> "type=gha,mode=max"
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fix", "scalafixAll")

lazy val helios = project
  .in(file("helios"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    // TODO: Scala Steward
    libraryDependencies ++= Seq(
      // TODO: Try Ember
      "org.http4s" %% "http4s-blaze-client" % "0.23.10",
      "org.http4s" %% "http4s-dsl" % "0.23.10",
      // TODO: Try 2.0?
      "dev.zio" %% "zio" % "1.0.13",
      "dev.zio" %% "zio-interop-cats" % "3.2.9.1",
      "dev.zio" %% "zio-json" % "0.2.0-M3",
      "org.slf4j" % "slf4j-simple" % "1.7.35",
      "com.luckycatlabs" % "SunriseSunsetCalculator" % "1.2",
      "dev.zio" %% "zio-test" % "1.0.13" % Test,
      "dev.zio" %% "zio-test-sbt" % "1.0.13" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    dockerBaseImage := "openjdk:17"
  )
