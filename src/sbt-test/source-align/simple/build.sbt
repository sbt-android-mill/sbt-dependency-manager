name := "Simple"

version := "0.1"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit")

logLevel := Level.Debug

sbt.dependency.manager.Plugin.activate
