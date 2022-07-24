import com.typesafe.sbt.packager.docker._

name := "helios"

ThisBuild / resolvers += Resolver.sonatypeRepo("snapshots")
ThisBuild / scalaVersion := "3.1.3"
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.3.1-RC3"
ThisBuild / semanticdbEnabled := true

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fix", "scalafixAll")

lazy val helios = project
  .in(file("helios"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % "0.23.13",
      "org.http4s" %% "http4s-dsl" % "0.23.13",
      "dev.zio" %% "zio" % "2.0.0",
      "dev.zio" %% "zio-interop-cats" % "3.3.0",
      "dev.zio" %% "zio-json" % "0.3.0-RC10",
      "nl.vroste" %% "rezilience" % "0.9.0",
      "org.slf4j" % "slf4j-simple" % "1.7.36",
      "com.luckycatlabs" % "SunriseSunsetCalculator" % "1.2",
      "dev.zio" %% "zio-test" % "2.0.0" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.0.0" % Test
    ),
    run / fork := true,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    dockerBaseImage := "openjdk:17"
  )
