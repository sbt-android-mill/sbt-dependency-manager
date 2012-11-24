name := "Simple"

version := "0.1"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit")

logLevel := Level.Debug

sbt.dependency.manager.Plugin.activate

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.1"
