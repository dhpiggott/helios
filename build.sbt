import com.typesafe.sbt.packager.docker._

name := "helios"

Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  Seq(
    scalaVersion := "3.3.1",
    semanticdbEnabled := true
  )
)
addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fix", "scalafixAll")

lazy val helios = project
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "3.9.1",
      "co.fs2" %% "fs2-io" % "3.9.1",
      "com.luckycatlabs" % "SunriseSunsetCalculator" % "1.2",
      "dev.zio" %% "zio" % "2.0.16",
      "dev.zio" %% "zio-interop-cats" % "23.0.0.8",
      "dev.zio" %% "zio-json" % "0.6.1",
      "dev.zio" %% "zio-stacktracer" % "2.0.16",
      "dev.zio" %% "zio-streams" % "2.0.16",
      "nl.vroste" %% "rezilience" % "0.9.4",
      "org.http4s" %% "http4s-client" % "0.23.23",
      "org.http4s" %% "http4s-core" % "0.23.23",
      "org.http4s" %% "http4s-dsl" % "0.23.23",
      "org.http4s" %% "http4s-ember-client" % "0.23.23",
      "org.typelevel" %% "case-insensitive" % "1.4.0",
      "org.typelevel" %% "cats-core" % "2.10.0",
      "org.typelevel" %% "cats-effect" % "3.5.1",
      "org.typelevel" %% "cats-effect-kernel" % "3.5.1",
      "org.slf4j" % "slf4j-simple" % "2.0.7" % Runtime,
      "dev.zio" %% "zio-test" % "2.0.16" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.0.16" % Test
    ),
    run / fork := true,
    dockerBaseImage := "eclipse-temurin:17-jre-jammy"
  )
