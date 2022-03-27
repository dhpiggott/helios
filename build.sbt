import com.typesafe.sbt.packager.docker._

name := "helios"

ThisBuild / scalaVersion := "3.1.1"
ThisBuild / scalafixDependencies += Dependencies.organizeImports
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
      "cache-from" -> "type=gha,ref=${{ env.DOCKER_REPOSITORY }}:buildcache",
      "cache-to" -> "type=gha,ref=${{ env.DOCKER_REPOSITORY }}:buildcache,mode=max"
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fix", "scalafixAll")

lazy val helios = project
  .in(file("helios"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Http4s.blazeClient,
      Dependencies.Http4s.dsl,
      Dependencies.Zio.core,
      Dependencies.Zio.interopCats,
      Dependencies.Zio.json,
      Dependencies.slf4jSimple,
      Dependencies.sunriseSunsetCalculator,
      Dependencies.Zio.test % Test,
      Dependencies.Zio.testSbt % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
