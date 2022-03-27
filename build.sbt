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
    List("test"),
    name = Some("Build project")
  )
)
ThisBuild / githubWorkflowPublishTargetBranches := Seq()

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fix", "scalafixAll")

lazy val helios = project
  .in(file("helios"))
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
  // TODO: Configure publishing
  .enablePlugins(JavaAppPackaging, DockerPlugin)
