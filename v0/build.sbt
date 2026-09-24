// v0 工程唯一的构建定义。
// 平时不需要改这里，只有加依赖或升版本时才动。

ThisBuild / scalaVersion := "2.13.18"

// chisel 和 chisel-plugin 必须完全同版本
val chiselVersion = "7.15.0"

lazy val root = (project in file("."))
  .settings(
    name := "cpu-v0",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"    % chiselVersion,
      "org.scalatest"     %% "scalatest" % "3.2.20" % Test,
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
  )
