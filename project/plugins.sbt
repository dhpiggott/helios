addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.4")
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.17.2")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.2.16")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.13")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"