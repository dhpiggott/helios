import com.typesafe.sbt.packager.docker._

name := "helios"

inThisBuild(
  List(
    scalaVersion := "3.1.1",
    scalafixDependencies += Dependencies.organizeImports,
    semanticdbEnabled := true
  )
)

addCommandAlias("fix", "scalafixAll")
addCommandAlias("fixCheck", "scalafixAll --check")
addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fmtCheck", "all scalafmtSbtCheck scalafmtCheckAll")

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
