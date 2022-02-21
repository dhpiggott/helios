import sbt.Keys.*
import sbt.*
import scalafix.sbt.ScalafixPlugin.autoImport.*

object ProjectPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  override def globalSettings: Seq[Setting[?]] =
    addCommandAlias(
      "fix",
      "scalafixAll"
    ) ++ addCommandAlias(
      "fixCheck",
      "scalafixAll --check"
    ) ++ addCommandAlias(
      "fmt",
      "all scalafmtSbt scalafmtAll"
    ) ++ addCommandAlias(
      "fmtCheck",
      "all scalafmtSbtCheck scalafmtCheckAll"
    )

  override def projectSettings: Seq[Setting[?]] =
    scalaSettings ++
      scalafixSettings

  private lazy val scalaSettings = Seq(
    scalaVersion := "3.1.1"
  )

  private lazy val scalafixSettings =
    inThisBuild(
      Seq(
        scalafixDependencies += Dependencies.organizeImports,
        semanticdbEnabled := true
      )
    )

}
