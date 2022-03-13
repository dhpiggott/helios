import sbt.*

object Dependencies {

  object Http4s {
    // TOOD: Try Ember
    val blazeClient: ModuleID =
      "org.http4s" %% "http4s-blaze-client" % "0.23.10"

    val dsl: ModuleID = "org.http4s" %% "http4s-dsl" % "0.23.10"
  }

  object Zio {
    // TODO: Try 2.0?
    val core: ModuleID = "dev.zio" %% "zio" % "1.0.13"

    val interopCats: ModuleID = "dev.zio" %% "zio-interop-cats" % "3.2.9.1"

    val json: ModuleID = "dev.zio" %% "zio-json" % "0.2.0-M3"

    val test: ModuleID = "dev.zio" %% "zio-test" % "1.0.13"

    val testSbt: ModuleID = "dev.zio" %% "zio-test-sbt" % "1.0.13"
  }

  val organizeImports: ModuleID =
    "com.github.liancheng" %% "organize-imports" % "0.3.1-RC3"

  // TODO: Do we need this?
  val slf4jSimple: ModuleID = "org.slf4j" % "slf4j-simple" % "1.7.35"

  val sunriseSunsetCalculator: ModuleID =
    "com.luckycatlabs" % "SunriseSunsetCalculator" % "1.2"

}
