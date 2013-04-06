name := "sbt-dependency-manager"

organization := "sbt.dependency.manager"

version := "0.4"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit", "-Xfatal-warnings")

sbtPlugin := true

ScriptedPlugin.scriptedSettings

sbt.dependency.manager.Plugin.activate

scriptedLaunchOpts ++= {
  import scala.collection.JavaConverters._
  val args = Seq("-Xmx8196M","-Xms8196M")
  management.ManagementFactory.getRuntimeMXBean().getInputArguments().asScala.filter(a => args.contains(a) || a.startsWith("-XX")).toSeq
}

sourceDirectory <<= (baseDirectory) (_ / ".." / "src")

target <<= (baseDirectory) (_ / ".." / "target")

dependencyPath <<= (baseDirectory) map (_ / "deps")
