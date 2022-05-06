import com.typesafe.sbt.packager.docker._

name := "helios"

ThisBuild / scalaVersion := "3.1.2"
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.3.1-RC3"
ThisBuild / semanticdbEnabled := true

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
      "dev.zio" %% "zio" % "1.0.14",
      "dev.zio" %% "zio-interop-cats" % "3.2.9.1",
      "dev.zio" %% "zio-json" % "0.2.0-M4",
      "nl.vroste" %% "rezilience" % "0.7.0",
      "org.slf4j" % "slf4j-simple" % "1.7.36",
      "com.luckycatlabs" % "SunriseSunsetCalculator" % "1.2",
      "dev.zio" %% "zio-test" % "1.0.14" % Test,
      "dev.zio" %% "zio-test-sbt" % "1.0.14" % Test
    ),
    run / fork := true,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    dockerBaseImage := "openjdk:17"
  )
