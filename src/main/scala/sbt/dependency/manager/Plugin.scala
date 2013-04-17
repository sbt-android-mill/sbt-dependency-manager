/**
 * sbt-dependency-manager - fetch and merge byte code and source code jars, align broken sources within jars.
 * For example, it is allow easy source code lookup for IDE while developing SBT plugins (not only).
 *
 * Copyright (c) 2012-2013 Alexey Aksenov ezh@ezh.msk.ru
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbt.dependency.manager

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException

import sbt.Artifact._
import sbt.Classpaths._
import sbt.Configurations
import sbt.Defaults._
import sbt.Keys._
import sbt._

import scala.collection.mutable.HashSet

import xsbti.AppConfiguration

/**
 * sbt-dependency-manager plugin entry
 */
object Plugin extends sbt.Plugin {
  lazy val dependencyPath = TaskKey[File]("dependency-path", "Target directory for dependency jars")
  lazy val dependencyBundlePath = TaskKey[File]("dependency-bundle-path", "Bundle jar location")
  lazy val dependencyFilter = TaskKey[Option[ModuleFilter]]("dependency-filter", "Processing dependencies only with particular sbt.ModuleID")
  lazy val dependencyResourceFilter = SettingKey[ZipEntry => Boolean]("dependency-resource-filter", "Fuction for filtering jar content")
  lazy val dependencyClasspathFilter = TaskKey[ModuleFilter]("dependency-classpath-filter", "Filter that accept all dependency modules")
  lazy val dependencyLookupClasspath = TaskKey[Classpath]("dependency-lookup-classpath", "Classpath that is used for building the dependency sequence")
  lazy val dependencyTaskBundle = TaskKey[UpdateReport]("dependency-bundle", "Fetch dependency code and source jars. Save results to bundle")
  lazy val dependencyTaskBundleWithArtifact = TaskKey[UpdateReport]("dependency-bundle-with-artifact", "Fetch dependency code and source jars, add project artefact. Save results to bundle")
  lazy val dependencyTaskFetchAlign = TaskKey[UpdateReport]("dependency-fetch-align", "Fetch dependency code and source jars, merge them. Save results to target directory")
  lazy val dependencyTaskFetchWithSources = TaskKey[UpdateReport]("dependency-fetch-with-sources", "Fetch dependency code and source jars. Save results to target directory")
  lazy val dependencyTaskFetch = TaskKey[UpdateReport]("dependency-fetch", "Fetch dependency code jars. Save results to target directory")
  lazy val dependencyAddCustom = SettingKey[Boolean]("dependency-add-custom", "Add custom(unknown) libraries to results")
  lazy val dependencyIgnoreConfiguration = SettingKey[Boolean]("dependency-ignore-configurations", "Ignore configurations while lookup, 'test' for example")
  lazy val defaultSettings = Seq(
    dependencyAddCustom := true,
    dependencyBundlePath <<= (target, normalizedName) map { (target, name) => target / (name + "-development-bundle.jar") },
    dependencyClasspathFilter <<= (dependencyLookupClasspathTask) map (cp =>
      cp.flatMap(_.get(moduleID.key)).foldLeft(moduleFilter(NothingFilter, NothingFilter, NothingFilter))((acc, m) => acc |
        moduleFilter(GlobFilter(m.organization), GlobFilter(m.name), GlobFilter(m.revision)))),
    dependencyFilter <<= dependencyClasspathFilter map (dcf => Some(dcf -
      moduleFilter(organization = GlobFilter("org.scala-lang"), name = GlobFilter("scala-library")))),
    dependencyIgnoreConfiguration := true,
    dependencyLookupClasspath <<= dependencyLookupClasspathTask,
    dependencyPath <<= (target in LocalRootProject) map { _ / "deps" },
    dependencyResourceFilter := resourceFilter,
    dependencyTaskBundle <<= dependencyTaskBundleTask,
    dependencyTaskBundleWithArtifact <<= dependencyTaskBundleWithArtifactTask,
    dependencyTaskFetch <<= dependencyTaskFetchTask,
    dependencyTaskFetchAlign <<= dependencyTaskFetchAlignTask,
    dependencyTaskFetchWithSources <<= dependencyTaskFetchWithSourcesTask,
    // add the empty classifier ""
    transitiveClassifiers in Global :== Seq("", SourceClassifier, DocClassifier))

  /** Entry point for plugin in user's project */
  def activate = defaultSettings
  /** Implementation of dependency-bundle */
  def dependencyTaskBundleTask = (classifiersModule in updateSbtClassifiers, dependencyBundlePath, dependencyPath,
    dependencyFilter, dependencyLookupClasspath, ivySbt, state, streams, thisProjectRef) map {
      (origClassifiersModule, pathBundle, pathDependency, dependencyFilter, dependencyClasspath,
      ivySbt, state, streams, thisProjectRef) =>
        val extracted: Extracted = Project.extract(state)
        val thisScope = Load.projectScope(thisProjectRef)
        val result = for {
          appConfiguration <- appConfiguration in thisScope get extracted.structure.data
          ivyLoggingLevel <- ivyLoggingLevel in thisScope get extracted.structure.data
          ivyScala <- ivyScala in thisScope get extracted.structure.data
          name <- name in thisScope get extracted.structure.data
          pathTarget <- target in thisScope get extracted.structure.data
          updateConfiguration <- updateConfiguration in thisScope get extracted.structure.data
          dependencyAddCustom <- dependencyAddCustom in thisScope get extracted.structure.data
          dependencyIgnoreConfiguration <- dependencyIgnoreConfiguration in thisScope get extracted.structure.data
          dependencyResourceFilter <- dependencyResourceFilter in thisScope get extracted.structure.data
        } yield {
          streams.log.info("fetch and align dependencies to bundle for " + name)
          val argument = TaskArgument(appConfiguration, ivyLoggingLevel, ivySbt, ivyScala,
            origClassifiersModule, new UpdateConfiguration(updateConfiguration.retrieve, true, ivyLoggingLevel),
            pathBundle, pathDependency, pathTarget, streams,
            dependencyAddCustom, None, true, dependencyClasspath,
            dependencyFilter, dependencyIgnoreConfiguration, dependencyResourceFilter)
          commonFetchTask(argument, doFetchAlign)
        }
        result.get
    }
  /** Implementation of dependency-bundle-with-artifact */
  def dependencyTaskBundleWithArtifactTask = (classifiersModule in updateSbtClassifiers, dependencyBundlePath, dependencyPath,
    dependencyFilter, dependencyLookupClasspath, ivySbt, packageBin in Compile, state, streams, thisProjectRef) map {
      (origClassifiersModule, pathBundle, pathDependency, dependencyFilter, dependencyClasspath,
      ivySbt, packageBin, state, streams, thisProjectRef) =>
        val extracted: Extracted = Project.extract(state)
        val thisScope = Load.projectScope(thisProjectRef)
        val result = for {
          appConfiguration <- appConfiguration in thisScope get extracted.structure.data
          ivyLoggingLevel <- ivyLoggingLevel in thisScope get extracted.structure.data
          ivyScala <- ivyScala in thisScope get extracted.structure.data
          name <- name in thisScope get extracted.structure.data
          pathTarget <- target in thisScope get extracted.structure.data
          updateConfiguration <- updateConfiguration in thisScope get extracted.structure.data
          dependencyAddCustom <- dependencyAddCustom in thisScope get extracted.structure.data
          dependencyIgnoreConfiguration <- dependencyIgnoreConfiguration in thisScope get extracted.structure.data
          dependencyResourceFilter <- dependencyResourceFilter in thisScope get extracted.structure.data
        } yield {
          streams.log.info("fetch and align dependencies with artifact to bundle for " + name)
          val argument = TaskArgument(appConfiguration, ivyLoggingLevel, ivySbt, ivyScala,
            origClassifiersModule, new UpdateConfiguration(updateConfiguration.retrieve, true, ivyLoggingLevel),
            pathBundle, pathDependency, pathTarget, streams,
            dependencyAddCustom, Some(packageBin), true, dependencyClasspath,
            dependencyFilter, dependencyIgnoreConfiguration, dependencyResourceFilter)
          commonFetchTask(argument, doFetchAlign)
        }
        result.get
    }
  /** Implementation of dependency-fetch-align */
  def dependencyTaskFetchAlignTask = (classifiersModule in updateSbtClassifiers, dependencyBundlePath, dependencyPath,
    dependencyFilter, dependencyLookupClasspath, ivySbt, state, streams, thisProjectRef) map {
      (origClassifiersModule, pathBundle, pathDependency, dependencyFilter, dependencyClasspath,
      ivySbt, state, streams, thisProjectRef) =>
        val extracted: Extracted = Project.extract(state)
        val thisScope = Load.projectScope(thisProjectRef)
        val result = for {
          appConfiguration <- appConfiguration in thisScope get extracted.structure.data
          ivyLoggingLevel <- ivyLoggingLevel in thisScope get extracted.structure.data
          ivyScala <- ivyScala in thisScope get extracted.structure.data
          name <- name in thisScope get extracted.structure.data
          pathTarget <- target in thisScope get extracted.structure.data
          updateConfiguration <- updateConfiguration in thisScope get extracted.structure.data
          dependencyAddCustom <- dependencyAddCustom in thisScope get extracted.structure.data
          dependencyIgnoreConfiguration <- dependencyIgnoreConfiguration in thisScope get extracted.structure.data
          dependencyResourceFilter <- dependencyResourceFilter in thisScope get extracted.structure.data
        } yield {
          streams.log.info("fetch and align dependencies for " + name)
          val argument = TaskArgument(appConfiguration, ivyLoggingLevel, ivySbt, ivyScala,
            origClassifiersModule, new UpdateConfiguration(updateConfiguration.retrieve, true, ivyLoggingLevel),
            pathBundle, pathDependency, pathTarget, streams,
            dependencyAddCustom, None, false, dependencyClasspath,
            dependencyFilter, dependencyIgnoreConfiguration, dependencyResourceFilter)
          commonFetchTask(argument, doFetchAlign)
        }
        result.get
    }
  /** Implementation of dependency-fetch-with-sources */
  def dependencyTaskFetchWithSourcesTask = (classifiersModule in updateSbtClassifiers, dependencyBundlePath, dependencyPath,
    dependencyFilter, dependencyLookupClasspath, ivySbt, state, streams, thisProjectRef) map {
      (origClassifiersModule, pathBundle, pathDependency, dependencyFilter, dependencyClasspath,
      ivySbt, state, streams, thisProjectRef) =>
        val extracted: Extracted = Project.extract(state)
        val thisScope = Load.projectScope(thisProjectRef)
        val result = for {
          appConfiguration <- appConfiguration in thisScope get extracted.structure.data
          ivyLoggingLevel <- ivyLoggingLevel in thisScope get extracted.structure.data
          ivyScala <- ivyScala in thisScope get extracted.structure.data
          name <- name in thisScope get extracted.structure.data
          pathTarget <- target in thisScope get extracted.structure.data
          updateConfiguration <- updateConfiguration in thisScope get extracted.structure.data
          dependencyAddCustom <- dependencyAddCustom in thisScope get extracted.structure.data
          dependencyIgnoreConfiguration <- dependencyIgnoreConfiguration in thisScope get extracted.structure.data
          dependencyResourceFilter <- dependencyResourceFilter in thisScope get extracted.structure.data
        } yield {
          streams.log.info("fetch dependencies with source code for " + name)
          val argument = TaskArgument(appConfiguration, ivyLoggingLevel, ivySbt, ivyScala,
            origClassifiersModule, new UpdateConfiguration(updateConfiguration.retrieve, true, ivyLoggingLevel),
            pathBundle, pathDependency, pathTarget, streams,
            dependencyAddCustom, None, false, dependencyClasspath,
            dependencyFilter, dependencyIgnoreConfiguration, dependencyResourceFilter)
          commonFetchTask(argument, doFetchWithSources)
        }
        result.get
    }
  /** Implementation of dependency-fetch */
  def dependencyTaskFetchTask = (classifiersModule in updateSbtClassifiers, dependencyBundlePath, dependencyPath,
    dependencyFilter, dependencyLookupClasspath, ivySbt, state, streams, thisProjectRef) map {
      (origClassifiersModule, pathBundle, pathDependency, dependencyFilter, dependencyClasspath,
      ivySbt, state, streams, thisProjectRef) =>
        val extracted: Extracted = Project.extract(state)
        val thisScope = Load.projectScope(thisProjectRef)
        val result = for {
          appConfiguration <- appConfiguration in thisScope get extracted.structure.data
          ivyLoggingLevel <- ivyLoggingLevel in thisScope get extracted.structure.data
          ivyScala <- ivyScala in thisScope get extracted.structure.data
          name <- name in thisScope get extracted.structure.data
          pathTarget <- target in thisScope get extracted.structure.data
          updateConfiguration <- updateConfiguration in thisScope get extracted.structure.data
          dependencyAddCustom <- dependencyAddCustom in thisScope get extracted.structure.data
          dependencyIgnoreConfiguration <- dependencyIgnoreConfiguration in thisScope get extracted.structure.data
          dependencyResourceFilter <- dependencyResourceFilter in thisScope get extracted.structure.data
        } yield {
          streams.log.info("fetch dependencies for " + name)
          val argument = TaskArgument(appConfiguration, ivyLoggingLevel, ivySbt, ivyScala,
            origClassifiersModule, new UpdateConfiguration(updateConfiguration.retrieve, true, ivyLoggingLevel),
            pathBundle, pathDependency, pathTarget, streams,
            dependencyAddCustom, None, false, dependencyClasspath,
            dependencyFilter, dependencyIgnoreConfiguration, dependencyResourceFilter)
          commonFetchTask(argument, doFetch)
        }
        result.get
    }
  /**
   * Task that returns the union of fullClasspath in Compile and Test configurations
   */
  def dependencyLookupClasspathTask =
    (externalDependencyClasspath in Compile, externalDependencyClasspath in Test) map ((cpA, cpB) => (cpA ++ cpB).distinct)
  /**
   * Dependency resource filter
   * It drops META-INF/ .SF .DSA .RSA files by default
   */
  def resourceFilter(entry: ZipEntry): Boolean =
    Seq("META-INF/.*\\.SF", "META-INF/.*\\.DSA", "META-INF/.*\\.RSA").find(entry.getName().toUpperCase().matches).nonEmpty

  /** Repack sequence of jar artifacts */
  protected def align(moduleTag: String, code: File, sources: File, targetDirectory: File, resourceFilter: ZipEntry => Boolean, s: TaskStreams,
    alignEntries: HashSet[String] = HashSet[String](), output: JarOutputStream = null): Unit = {
    if (!targetDirectory.exists())
      if (!targetDirectory.mkdirs())
        return s.log.error("unable to create " + targetDirectory)
    val target = new File(targetDirectory, code.getName)
    if (output == null)
      s.log.info("fetch and align " + moduleTag + ", target: " + target)
    else
      s.log.info("fetch and align " + moduleTag + ", target: bundle")
    // align
    var jarCode: JarInputStream = null
    var jarSources: JarInputStream = null
    var jarTarget: JarOutputStream = Option(output) getOrElse null
    try {
      jarCode = new JarInputStream(new FileInputStream(code))
      jarSources = new JarInputStream(new FileInputStream(sources))
      if (jarTarget == null && output == null) {
        if (target.exists())
          if (!target.delete()) {
            try {
              jarCode.close
              jarSources.close
            } catch {
              case e: Throwable =>
            }
            return s.log.error("unable to delete " + target)
          }
        jarTarget = try {
          new JarOutputStream(new BufferedOutputStream(new FileOutputStream(target, true)), jarCode.getManifest())
        } catch {
          case e: NullPointerException =>
            s.log.warn(code + " has broken manifest")
            new JarOutputStream(new BufferedOutputStream(new FileOutputStream(target, true)))
        }
      }
      // copy across all entries from the original code jar
      copy(alignEntries, jarCode, jarTarget, resourceFilter, s)
      // copy across all entries from the original sources jar
      copy(alignEntries, jarSources, jarTarget, resourceFilter, s)
    } catch {
      case e: Throwable =>
        s.log.error("sbt-dependency-manager unable to align: " + e.getClass().getName() + " " + e.getMessage())
    } finally {
      if (jarTarget != null && output == null) {
        jarTarget.flush()
        jarTarget.close()
      }
      if (jarCode != null)
        jarCode.close()
      if (jarSources != null)
        jarSources.close()
    }
  }
  /** Common part for all sbt-dependency-manager tasks */
  protected def commonFetchTask(arg: TaskArgument, userFunction: (TaskArgument, Seq[(String, sbt.ModuleID, sbt.Artifact, File)], Seq[(String, sbt.ModuleID, sbt.Artifact, File)]) => Unit): UpdateReport =
    synchronized {
      withExcludes(arg.pathTarget, arg.origClassifiersModule.classifiers, lock(arg.appConfiguration)) { excludes =>
        import arg.origClassifiersModule.{ id => origClassifiersModuleID, modules => origClassifiersModuleDeps }
        if (arg.dependencyBundle)
          arg.streams.log.info("create bundle " + arg.pathBundle)
        // do default update-sbt-classifiers with libDeps
        val libDeps = arg.dependencyClasspath.flatMap(_.get(moduleID.key))
        val extClassifiersModuleDeps = {
          val result = arg.dependencyFilter match {
            case Some(filter) => (origClassifiersModuleDeps ++ libDeps).filter(filter)
            case None => (origClassifiersModuleDeps ++ libDeps)
          }
          if (arg.dependencyIgnoreConfiguration)
            result.map(_.copy(configurations = None))
          else
            result
        }
        val customConfig = GetClassifiersConfiguration(arg.origClassifiersModule, excludes, arg.updateConfiguration, arg.ivyScala)
        val customBaseModuleID = restrictedCopy(origClassifiersModuleID, true).copy(name = origClassifiersModuleID.name + "$sbt")
        val customIvySbtModule = new arg.ivySbt.Module(InlineConfiguration(customBaseModuleID, ModuleInfo(customBaseModuleID.name), extClassifiersModuleDeps).copy(ivyScala = arg.ivyScala))
        val customUpdateReport = IvyActions.update(customIvySbtModule, arg.updateConfiguration, arg.streams.log)
        val newConfig = customConfig.copy(module = arg.origClassifiersModule.copy(modules = customUpdateReport.allModules))
        val updateReport = IvyActions.updateClassifiers(arg.ivySbt, newConfig, arg.streams.log)
        // process updateReport
        // get all sources
        val (sources, other) = updateReport.toSeq.partition {
          case (configuration, module, Artifact(name, kind, extension, Some("sources"), configurations, url, extraAttributes), file) => true
          case _ => false
        }
        // process all jars
        other.sortBy(_._2.toString).foreach { module => arg.streams.log.debug("add " + module._2) }
        userFunction(arg, sources, other)
        // add unprocessed modules
        if (arg.dependencyAddCustom) {
          // get all unprocessed dependencies with ModuleID
          val unprocessedModules = arg.dependencyFilter match {
            case Some(filter) =>
              extClassifiersModuleDeps.filterNot(other.map(_._2).contains).distinct.filter(filter)
            case None =>
              extClassifiersModuleDeps.filterNot(other.map(_._2).contains).distinct
          }
          unprocessedModules.sortBy(_.toString).foreach { module => arg.streams.log.debug("add unprocessed " + module) }
          // get all unprocessed dependencies or dependencies without ModuleID
          val unprocessed = arg.dependencyClasspath.sortBy(_.toString).filter(cp => cp.get(moduleID.key).isEmpty ||
            cp.get(moduleID.key).forall(m => unprocessedModules.contains(m)))
          if (arg.dependencyBundle)
            unprocessed.foreach {
              cp =>
                arg.streams.log.info("fetch custom library " + cp.data.getName())
                // copy across all entries from the original code jar
                val jarCode = new JarInputStream(new FileInputStream(cp.data))
                try {
                  copy(arg.bundleEntries, jarCode, arg.bundleJar, resourceFilter, arg.streams)
                  arg.bundleResources += cp.data.getAbsolutePath()
                } catch {
                  case e: Throwable =>
                    arg.streams.log.error("sbt-dependency-manager unable to align: " + e.getClass().getName() + " " + e.getMessage())
                } finally {
                  if (jarCode != null)
                    jarCode.close()
                }
            }
          else
            unprocessed.foreach {
              cp =>
                arg.streams.log.info("fetch custom library " + cp.data.getName())
                sbt.IO.copyFile(cp.data, new File(arg.pathDependency, cp.data.getName()), false)
            }
        }
        if (arg.dependencyBundle) {
          // add artifact
          arg.dependencyArtifact.foreach { artifact =>
            // copy across all entries from the original code jar
            val jarCode = new JarInputStream(new FileInputStream(artifact))
            try {
              copy(arg.bundleEntries, jarCode, arg.bundleJar, resourceFilter, arg.streams)
              arg.bundleResources += artifact.getAbsolutePath()
            } catch {
              case e: Throwable =>
                arg.streams.log.error("sbt-dependency-manager unable to align: " + e.getClass().getName() + " " + e.getMessage())
            } finally {
              if (jarCode != null)
                jarCode.close()
            }
          }
          arg.bundleJar.flush()
          arg.bundleJar.close()
          // create bundle description
          val directory = arg.pathBundle.getParentFile()
          val file = arg.pathBundle.getName() + ".description"
          val descriptionFile = new File(directory, file)
          Some(new PrintWriter(descriptionFile)).foreach { writer =>
            try {
              writer.write(arg.bundleResources.toList.sorted.mkString("\n"))
            } catch {
              case e: Throwable =>
                arg.streams.log.error("unable to create bundle description " + descriptionFile.getAbsolutePath() + " " + e)
            } finally {
              try { writer.close } catch { case e: Throwable => }
            }
          }
        }
        updateReport
      }
    }
  /** Specific part for tasks dependency-fetch-align, dependency-bundle, dependency-bundle-with-artifact */
  protected def doFetchAlign(arg: TaskArgument, sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)]): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          if (arg.dependencyBundle) {
            align(module.toString, codeJar, sourceJar, arg.pathDependency, resourceFilter, arg.streams, arg.bundleEntries, arg.bundleJar)
            arg.bundleResources += codeJar.getAbsolutePath()
          } else
            align(module.toString, codeJar, sourceJar, arg.pathDependency, resourceFilter, arg.streams)
        case None =>
          arg.streams.log.debug("sbt-dependency-manager: skip align for dependency " + module + " - sources not found ")
          if (arg.dependencyBundle) {
            // copy across all entries from the original code jar
            val jarCode = new JarInputStream(new FileInputStream(codeJar))
            try {
              copy(arg.bundleEntries, jarCode, arg.bundleJar, resourceFilter, arg.streams)
              arg.bundleResources += codeJar.getAbsolutePath()
            } catch {
              case e: Throwable =>
                arg.streams.log.error("sbt-dependency-manager unable to align: " + e.getClass().getName() + " " + e.getMessage())
            } finally {
              if (jarCode != null)
                jarCode.close()
            }
          } else
            sbt.IO.copyFile(codeJar, new File(arg.pathDependency, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      arg.streams.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }
  /** Specific part for task dependency-fetch-with-sources */
  protected def doFetchWithSources(arg: TaskArgument, sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)]): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          sbt.IO.copyFile(codeJar, new File(arg.pathDependency, codeJar.getName()), false)
          sbt.IO.copyFile(sourceJar, new File(arg.pathDependency, sourceJar.getName()), false)
        case None =>
          sbt.IO.copyFile(codeJar, new File(arg.pathDependency, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      arg.streams.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }
  /** Specific part for task dependency-fetch */
  protected def doFetch(arg: TaskArgument, sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)]): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          sbt.IO.copyFile(codeJar, new File(arg.pathDependency, codeJar.getName()), false)
        case None =>
          sbt.IO.copyFile(codeJar, new File(arg.pathDependency, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      arg.streams.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }

  /** Repack content of jar artifact */
  private def alignScalaSource(alignEntries: HashSet[String], entry: ZipEntry, content: String, s: TaskStreams): Option[ZipEntry] = {
    val searchFor = "/" + entry.getName.takeWhile(_ != '.')
    val distance = alignEntries.toSeq.map(path => (path.indexOf(searchFor), path)).filter(_._1 > 1).sortBy(_._1).headOption
    distance match {
      case Some((idx, entryPath)) =>
        val newEntry = new ZipEntry(entryPath.substring(0, idx) + searchFor + ".scala")
        s.log.debug("align " + entry.getName + " to " + newEntry.getName())
        newEntry.setComment(entry.getComment())
        newEntry.setCompressedSize(entry.getCompressedSize())
        newEntry.setCrc(entry.getCrc())
        newEntry.setExtra(entry.getExtra())
        newEntry.setMethod(entry.getMethod())
        newEntry.setSize(entry.getSize())
        newEntry.setTime(entry.getTime())
        Some(newEntry)
      case None =>
        var path = Seq[String]()
        val pattern = """\s*package\s+([a-z\\._$-]+).*""".r
        content.split("\n").foreach {
          case pattern(packageName) =>
            path = path :+ packageName.replaceAll("\\.", "/")
          case line =>
        }
        if (path.nonEmpty) {
          val prefix = path.mkString("/") + "/"
          alignEntries.toSeq.find(_.startsWith(prefix)) match {
            case Some(path) =>
              val newEntry = new ZipEntry(prefix + entry.getName())
              s.log.debug("align " + entry.getName + " to " + newEntry.getName())
              newEntry.setComment(entry.getComment())
              newEntry.setCompressedSize(entry.getCompressedSize())
              newEntry.setCrc(entry.getCrc())
              newEntry.setExtra(entry.getExtra())
              newEntry.setMethod(entry.getMethod())
              newEntry.setSize(entry.getSize())
              newEntry.setTime(entry.getTime())
              Some(newEntry)
            case None =>
              s.log.warn("failed to align source " + entry.getName())
              None
          }
        } else
          None
    }
  }
  /** Copy content of jar artifact */
  private def copy(alignEntries: HashSet[String], in: JarInputStream, out: JarOutputStream, resourceFilter: ZipEntry => Boolean, s: TaskStreams) {
    var entry: ZipEntry = null
    // copy across all entries from the original code jar
    var value: Int = 0
    try {
      val buffer = new Array[Byte](2048)
      entry = in.getNextEntry()
      while (entry != null) {
        if (alignEntries(entry.getName))
          s.log.debug("skip, entry already in jar: " + entry.getName())
        else if (resourceFilter(entry)) {
          s.log.debug("skip, filtered " + entry)
        } else
          try {
            alignEntries(entry.getName) = true
            val bos = new ByteArrayOutputStream()
            value = in.read(buffer)
            while (value > 0) {
              bos.write(buffer, 0, value)
              value = in.read(buffer)
            }
            val destEntry = new ZipEntry(entry.getName)
            out.putNextEntry(destEntry)
            out.write(bos.toByteArray())
            // adjust root scala sources
            if (entry.getName.endsWith(".scala") && entry.getName.indexOf("/") == -1)
              alignScalaSource(alignEntries, entry, bos.toString, s).foreach {
                entry =>
                  if (alignEntries(entry.getName))
                    s.log.debug("skip, entry already in jar: " + entry.getName())
                  else {
                    out.putNextEntry(entry)
                    out.write(bos.toByteArray())
                  }
              }
          } catch {
            case e: ZipException =>
              s.log.error("sbt-dependency-manager zip failed: " + e.getMessage())
          }
        entry = in.getNextEntry()
      }
    } catch {
      case e: Throwable =>
        s.log.error("sbt-dependency-manager copy failed: " + e.getClass().getName() + " " + e.getMessage())
    }
  }
  private[this] def restrictedCopy(m: ModuleID, confs: Boolean) =
    ModuleID(m.organization, m.name, m.revision, crossVersion = m.crossVersion, extraAttributes = m.extraAttributes, configurations = if (confs) m.configurations else None)

  /** Consolidated argument with all required information */
  case class TaskArgument(
    /** Application configuration that provides information about SBT process */
    appConfiguration: AppConfiguration,
    /** The property representing Ivy process log level */
    ivyLogLevel: UpdateLogging.Value,
    /** Ivy wrapper that contains org.apache.ivy.Ivy and org.apache.ivy.core.settings.IvySettings */
    ivySbt: IvySbt,
    /** Ivy scala artifacts description */
    ivyScala: Option[IvyScala],
    /** GetClassifiersModule */
    origClassifiersModule: GetClassifiersModule,
    /** Update configuration */
    updateConfiguration: UpdateConfiguration,
    /** Bundle path with jar name */
    pathBundle: java.io.File,
    /** Path to Fetched artifacts */
    pathDependency: java.io.File,
    /** Target path */
    pathTarget: java.io.File,
    /** SBT task streams for logging */
    streams: TaskStreams,
    /** Flag indicating whether custom libraries without ModuleID should be fetched */
    dependencyAddCustom: Boolean,
    /** The property representing artifact location */
    dependencyArtifact: Option[java.io.File],
    /** Flag indicating whether plugin should create bundle */
    dependencyBundle: Boolean,
    /** Classpath that is used to build dependency sequence */
    dependencyClasspath: Classpath,
    /** Fetch filter */
    dependencyFilter: Option[ModuleFilter],
    /** Flag indicating whether plugin should ignore a dependency configuration while lookup ('test' for example) */
    dependencyIgnoreConfiguration: Boolean,
    /** Function that filters jar content */
    dependencyResourceFilter: ZipEntry => Boolean) {
    val bundleJar: JarOutputStream = if (dependencyBundle) {
      pathBundle.delete() // remove old bundle
      new JarOutputStream(new BufferedOutputStream(new FileOutputStream(pathBundle, true)))
    } else
      null
    val bundleEntries = HashSet[String]()
    val bundleResources = HashSet[String]()
  }
}
