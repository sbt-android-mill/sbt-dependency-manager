/**
 * sbt-dependency-manager - fetch and merge byte code and source code jars, align broken sources within jars.
 * For example, it is allow easy source code lookup for IDE while developing SBT plugins (not only).
 *
 * Copyright (c) 2012 Alexey Aksenov ezh@ezh.msk.ru
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
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException

import scala.collection.mutable.HashSet

import sbt.Artifact._
import sbt.Classpaths._
import sbt.Configurations
import sbt.Defaults._
import sbt.Keys._
import sbt._

/**
 * sbt-dependency-manager plugin entry
 */
object Plugin extends sbt.Plugin {
  lazy val dependencyPath = TaskKey[File]("dependency-path", "Target directory for dependency jars")
  lazy val dependencyFilter = TaskKey[Option[Seq[ModuleID]]]("dependency-filter", "Processing dependencies only with particular sbt.ModuleID")
  lazy val dependencyClasspathNarrow = TaskKey[Classpath]("dependency-classpath-narrow", "Union of dependencyClasspath from Compile and Test configurations")
  lazy val dependencyClasspathWide = TaskKey[Classpath]("dependency-classpath-wide", "Union of fullClasspath from Compile and Test configurations")
  lazy val dependencyTaskFetchAlign = TaskKey[UpdateReport]("dependency-fetch-align", "Fetch dependency code and source jars, merge them. Save results to target directory")
  lazy val dependencyTaskFetchWithSources = TaskKey[UpdateReport]("dependency-fetch-with-sources", "Fetch dependency code and source jars. Save results to target directory")
  lazy val dependencyTaskFetch = TaskKey[UpdateReport]("dependency-fetch", "Fetch dependency code jars. Save results to target directory")
  lazy val dependencyAddCustom = TaskKey[Boolean]("dependency-add-custom", "Add custom(unknown) libraries to results")
  lazy val dependencyIgnoreConfigurations = SettingKey[Boolean]("dependency-ignore-configurations", "Ignore configurations while lookup, 'test' for example")
  lazy val defaultSettings = Seq(
    dependencyPath <<= (target in LocalRootProject) map { _ / "deps" },
    dependencyFilter <<= (dependencyClasspathNarrow) map { cp =>
      val filter = moduleFilter(organization = "org.scala-lang")
      Some(cp.flatMap(_.get(moduleID.key)).filterNot(filter))
    },
    dependencyClasspathNarrow <<= dependencyClasspathNarrowTask,
    dependencyClasspathWide <<= dependencyClasspathWideTask,
    dependencyAddCustom <<= dependencyAddCustomTask,
    dependencyIgnoreConfigurations := true,
    dependencyTaskFetchAlign <<= dependencyTaskFetchAlignTask,
    dependencyTaskFetchWithSources <<= dependencyTaskFetchWithSourcesTask,
    dependencyTaskFetch <<= dependencyTaskFetchTask,
    // add empty classifier ""
    transitiveClassifiers in Global :== Seq("", SourceClassifier, DocClassifier))

  /** entry point for plugin in user's project */
  def activate = defaultSettings
  /**
   * Task that return union of dependencyClasspath in Compile and Test configurations
   */
  def dependencyClasspathNarrowTask = (dependencyClasspath in Compile, dependencyClasspath in Test) map ((cpA, cpB) => (cpA ++ cpB).distinct)
  /**
   * Task that return union of fullClasspath in Compile and Test configurations
   */
  def dependencyClasspathWideTask = (fullClasspath in Compile, fullClasspath in Test) map ((cpA, cpB) => (cpA ++ cpB).distinct)
  /**
   * Task that fetch custom libraries without ModuleID to dependencyPath
   * Jar files in unmanagement classpath is one of such libraries
   */
  def dependencyAddCustomTask = (dependencyClasspathWide, dependencyPath, streams) map {
    (classpath, path, s) =>
      classpath.flatMap(cp => cp.get(moduleID.key) match {
        case Some(_) => None // classpath with defined ModuleID
        case None => Some(cp.data) // classpath without ModuleID
      }).filterNot(f => f.isDirectory() || !f.exists).distinct.foreach {
        lib =>
          s.log.info("fetch custom library " + lib)
          sbt.IO.copyFile(lib, new File(path, lib.getName()), false)
      }
      true
  }
  // update-sbt-classifiers with sources align
  def dependencyTaskFetchAlignTask = (ivySbt, classifiersModule in updateSbtClassifiers, updateConfiguration,
    ivyScala, target in LocalRootProject, appConfiguration, dependencyPath, dependencyAddCustom,
    dependencyFilter, dependencyIgnoreConfigurations, dependencyClasspathWide, streams) map {
      (is, origClassifiersModule, c, ivyScala, out, app, path, dependencyAddCustom, filter, ignoreConfigurations, fullClasspath, s) =>
        commonFetchTask(is, origClassifiersModule, c, ivyScala, out, app, path, fullClasspath, filter,
          ignoreConfigurations, s, dependencyAddCustom, userFetchAlignFunction)
    }
  def userFetchAlignFunction(sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    path: File, s: TaskStreams): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          align(module.toString, codeJar, sourceJar, path, s)
        case None =>
          s.log.debug("sbt-dependency-manager: skip align for dependency " + module + " - sources not found ")
          sbt.IO.copyFile(codeJar, new File(path, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      s.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }
  def dependencyTaskFetchWithSourcesTask = (ivySbt, classifiersModule in updateSbtClassifiers, updateConfiguration,
    ivyScala, target in LocalRootProject, appConfiguration, dependencyPath, dependencyAddCustom,
    dependencyFilter, dependencyIgnoreConfigurations, dependencyClasspathWide, streams) map {
      (is, origClassifiersModule, c, ivyScala, out, app, path, dependencyAddCustom, filter, ignoreConfigurations, fullClasspath, s) =>
        commonFetchTask(is, origClassifiersModule, c, ivyScala, out, app, path, fullClasspath, filter,
          ignoreConfigurations, s, dependencyAddCustom, userFetchWithSourcesFunction)
    }
  def userFetchWithSourcesFunction(sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    path: File, s: TaskStreams): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          sbt.IO.copyFile(codeJar, new File(path, codeJar.getName()), false)
          sbt.IO.copyFile(sourceJar, new File(path, sourceJar.getName()), false)
        case None =>
          sbt.IO.copyFile(codeJar, new File(path, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      s.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }
  def dependencyTaskFetchTask = (ivySbt, classifiersModule in updateSbtClassifiers, updateConfiguration,
    ivyScala, target in LocalRootProject, appConfiguration, dependencyPath, dependencyAddCustom,
    dependencyFilter, dependencyIgnoreConfigurations, dependencyClasspathWide, streams) map {
      (is, origClassifiersModule, c, ivyScala, out, app, path, dependencyAddCustom, filter, ignoreConfigurations, fullClasspath, s) =>
        commonFetchTask(is, origClassifiersModule, c, ivyScala, out, app, path, fullClasspath, filter,
          ignoreConfigurations, s, dependencyAddCustom, userFetchFunction)
    }
  def userFetchFunction(sources: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    other: Seq[(String, sbt.ModuleID, sbt.Artifact, File)],
    path: File, s: TaskStreams): Unit = other.foreach {
    case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), codeJar) =>
      sources.find(source => source._1 == configuration && source._2 == module) match {
        case Some((_, _, _, sourceJar)) =>
          sbt.IO.copyFile(codeJar, new File(path, codeJar.getName()), false)
        case None =>
          sbt.IO.copyFile(codeJar, new File(path, codeJar.getName()), false)
      }
    case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
      s.log.debug("sbt-dependency-manager: skip align for dependency " + module + " with classifier " + classifier)
  }
  def commonFetchTask(is: sbt.IvySbt,
    origClassifiersModule: sbt.GetClassifiersModule,
    c: sbt.UpdateConfiguration,
    ivyScala: Option[sbt.IvyScala],
    out: File,
    app: xsbti.AppConfiguration,
    path: File,
    fullClasspath: Classpath,
    filter: Option[Seq[ModuleID]],
    ignoreConfigurations: Boolean,
    s: TaskStreams,
    dependencyAddCustom: Boolean,
    userFunction: (Seq[(String, sbt.ModuleID, sbt.Artifact, File)], Seq[(String, sbt.ModuleID, sbt.Artifact, File)], File, TaskStreams) => Unit) =
    withExcludes(out, origClassifiersModule.classifiers, lock(app)) { excludes =>
      import origClassifiersModule.{ id => origClassifiersModuleID, modules => origClassifiersModuleDeps }
      // do default update-sbt-classifiers with libDeps
      val libDeps = fullClasspath.flatMap(_.get(moduleID.key))
      val extClassifiersModuleDeps = {
        val result = filter match {
          case Some(filter) => (origClassifiersModuleDeps ++ libDeps).filter(filter.contains)
          case None => (origClassifiersModuleDeps ++ libDeps)
        }
        if (ignoreConfigurations)
          result.map(_.copy(configurations = None))
        else
          result
      }
      val customConfig = GetClassifiersConfiguration(origClassifiersModule, excludes, c, ivyScala)
      val customBaseModuleID = restrictedCopy(origClassifiersModuleID, true).copy(name = origClassifiersModuleID.name + "$sbt")
      val customIvySbtModule = new is.Module(InlineConfiguration(customBaseModuleID, ModuleInfo(customBaseModuleID.name), extClassifiersModuleDeps).copy(ivyScala = ivyScala))
      val customUpdateReport = IvyActions.update(customIvySbtModule, c, s.log)
      val newConfig = customConfig.copy(module = origClassifiersModule.copy(modules = customUpdateReport.allModules))
      val updateReport = IvyActions.updateClassifiers(is, newConfig, s.log)
      // process updateReport
      // get all sources
      val (sources, other) = updateReport.toSeq.partition {
        case (configuration, module, Artifact(name, kind, extension, Some("sources"), configurations, url, extraAttributes), file) => true
        case _ => false
      }
      // process all jars
      userFunction(sources, other, path, s)
      // add unprocessed modules
      if (dependencyAddCustom) {
        val unprocessed = extClassifiersModuleDeps.filterNot(other.map(_._2).contains).distinct
        fullClasspath.filter(_.get(moduleID.key).map(unprocessed.contains).getOrElse(false)).foreach {
          cp =>
            s.log.info("fetch custom library " + cp.get(moduleID.key).get)
            sbt.IO.copyFile(cp.data, new File(path, cp.data.getName()), false)
        }
      }
      updateReport
    }
  def align(moduleTag: String, code: File, sources: File, targetDirectory: File, s: TaskStreams): Unit = {
    val alignEntries = new HashSet[String]()
    if (!targetDirectory.exists())
      if (!targetDirectory.mkdirs())
        return s.log.error("unable to create " + targetDirectory)
    val target = new File(targetDirectory, code.getName)
    if (target.exists())
      if (!target.delete())
        return s.log.error("unable to delete " + target)
    s.log.info("fetch and align " + moduleTag + ", target: " + target)
    // align
    var jarCode: JarInputStream = null
    var jarSources: JarInputStream = null
    var jarTarget: JarOutputStream = null
    try {
      jarCode = new JarInputStream(new FileInputStream(code))
      jarSources = new JarInputStream(new FileInputStream(sources))
      jarTarget = try {
        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(target, true)), jarCode.getManifest())
      } catch {
        case e: NullPointerException =>
          s.log.warn(code + " has broken manifest")
          new JarOutputStream(new BufferedOutputStream(new FileOutputStream(target, true)))
      }
      // copy across all entries from the original code jar
      copy(alignEntries, jarCode, jarTarget, s)
      // copy across all entries from the original sources jar
      copy(alignEntries, jarSources, jarTarget, s)
    } catch {
      case e =>
        s.log.error("sbt-dependency-manager unable to align: " + e.getClass().getName() + " " + e.getMessage())
    } finally {
      if (jarTarget != null) {
        jarTarget.flush()
        jarTarget.close()
      }
      if (jarCode != null)
        jarCode.close()
      if (jarSources != null)
        jarSources.close()
    }
  }
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
  private def copy(alignEntries: HashSet[String], in: JarInputStream, out: JarOutputStream, s: TaskStreams) {
    var entry: ZipEntry = null
    // copy across all entries from the original code jar
    var value: Int = 0
    try {
      val buffer = new Array[Byte](2048)
      entry = in.getNextEntry()
      while (entry != null) {
        if (alignEntries(entry.getName))
          s.log.debug("skip, entry already in jar: " + entry.getName())
        else
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
      case e =>
        s.log.error("sbt-dependency-manager copy failed: " + e.getClass().getName() + " " + e.getMessage())
    }
  }
  private[this] def restrictedCopy(m: ModuleID, confs: Boolean) =
    ModuleID(m.organization, m.name, m.revision, crossVersion = m.crossVersion, extraAttributes = m.extraAttributes, configurations = if (confs) m.configurations else None)
}
