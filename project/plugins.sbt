resolvers ++= Seq(
  Resolver.url("typesafe-ivy-releases-for-online-crossbuild", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.defaultIvyPatterns),
  Resolver.url("typesafe-ivy-snapshots-for-online-crossbuild", url("http://repo.typesafe.com/typesafe/ivy-snapshots/"))(Resolver.defaultIvyPatterns),
  Resolver.url("typesafe-repository-for-online-crossbuild", url("http://typesafe.artifactoryonline.com/typesafe/ivy-releases/"))(Resolver.defaultIvyPatterns),
  Resolver.url("typesafe-shapshots-for-online-crossbuild", url("http://typesafe.artifactoryonline.com/typesafe/ivy-snapshots/"))(Resolver.defaultIvyPatterns))

libraryDependencies <+= (sbtVersion)((v) =>
      v.split('.') match {
        case Array("0", "11", "3") =>
          "org.scala-sbt" %% "scripted-plugin" % v
        case Array("0", "11", _) =>
          "org.scala-tools.sbt" %% "scripted-plugin" % v
        case Array("0", n, _) if n.toInt < 11 =>
          "org.scala-tools.sbt" %% "scripted-plugin" % v
        case _ =>
          "org.scala-sbt" % "scripted-plugin" % v
      })
