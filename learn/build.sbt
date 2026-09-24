// learn 工程的构建定义。和 v0 用同一套版本，方便互相拷贝写法。

ThisBuild / scalaVersion := "2.13.18"

// chisel 与 chisel-plugin 必须完全同版本
val chiselVersion = "7.15.0"

lazy val root = (project in file("."))
  .settings(
    name := "cpu-learn",
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
