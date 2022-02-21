import com.typesafe.sbt.packager.docker._

lazy val helios = project
  .in(file("helios"))
  .settings(
    name := "helios"
  )
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Http4s.blazeClient,
      Dependencies.Http4s.dsl,
      Dependencies.Zio.core,
      Dependencies.Zio.interopCats,
      Dependencies.Zio.json,
      Dependencies.slf4jSimple
    )
  )
  // TODO: Configure publishing
  .enablePlugins(JavaAppPackaging, DockerPlugin)
