name := "sbt-source-align"

organization := "sbt.source.align"

version := "0.1-SNAPSHOT"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit", "-Xfatal-warnings")

sbtPlugin := true


ScriptedPlugin.scriptedSettings

sbt.source.align.Align.alignSettings

scriptedLaunchOpts ++= {
  import scala.collection.JavaConverters._
  val args = Seq("-Xmx8196M","-Xms8196M")
  management.ManagementFactory.getRuntimeMXBean().getInputArguments().asScala.filter(a => args.contains(a) || a.startsWith("-XX")).toSeq
}

sourceDirectory <<= (baseDirectory) (_ / ".." / "src")

target <<= (baseDirectory) (_ / ".." / "target")
