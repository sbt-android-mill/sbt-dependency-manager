import com.jsuereth.sbtsite.SiteKeys

ScriptedPlugin.scriptedSettings

name := "sbt-source-align"

organization := "sbt.source.align"

version := "0.1-SNAPSHOT"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit", "-Xfatal-warnings")

sbtPlugin := true