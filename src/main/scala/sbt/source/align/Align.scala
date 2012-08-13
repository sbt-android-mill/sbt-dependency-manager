/**
 * sbt-source-align - merge code and source jars, also align broken scala source files.
 * For example, it is allow easy source code lookup for IDE while developing SBT plugins (not only).
 *
 * Copyright (c) 2012, Alexey Aksenov ezh@ezh.msk.ru. All rights reserved.
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 or any later
 * version, as published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package sbt.source.align

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

object Align extends Plugin {
  val alignPath = TaskKey[File]("update-align-path")
  val alignFullTask = TaskKey[UpdateReport]("update-align-sbt-classifiers")
  val alignUserTask = TaskKey[UpdateReport]("update-align-classifiers")
  val alignSettings = Seq(
    alignPath <<= (target in LocalRootProject) map { _ / "align" },
    alignFullTask <<= updateSbtClassifiersAndFixBrokenAlign,
    alignUserTask <<= updateClassifiersAndFixBrokenAlign,
    // add empty classifier ""
    transitiveClassifiers in Global :== Seq("", SourceClassifier, DocClassifier))
  // update-sbt-classifiers with sources align
  def updateSbtClassifiersAndFixBrokenAlign = (ivySbt, classifiersModule in updateSbtClassifiers, updateConfiguration,
    ivyScala, target in LocalRootProject, appConfiguration, alignPath, streams) map {
      (is, mod, c, ivyScala, out, app, path, s) =>
        withExcludes(out, mod.classifiers, lock(app)) { excludes =>
          // do default update-sbt-classifiers
          val report = IvyActions.transitiveScratch(is, "sbt", GetClassifiersConfiguration(mod, excludes, c, ivyScala), s.log)
          // process UpdateReport
          // get all sources
          val (sources, other) = report.toSeq.partition {
            case (configuration, module, Artifact(name, kind, extension, Some("sources"), configurations, url, extraAttributes), file) => true
            case _ => false
          }
          // process all jars
          other.foreach {
            case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), file) =>
              sources.find(source => source._1 == configuration && source._2 == module) match {
                case Some((_, _, _, sourceFile)) =>
                  align(module.toString, file, sourceFile, path, s)
                case None =>
                  s.log.debug("sbt-source-align: skip align for dependency " + module + " - sources not found ")
              }
            case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
              s.log.debug("sbt-source-align: skip align for dependency " + module + " with classifier " + classifier)
          }
          report
        }
    }
  // update-classifiers with sources align
  def updateClassifiersAndFixBrokenAlign = (ivySbt, classifiersModule in updateClassifiers, updateConfiguration,
    ivyScala, target in LocalRootProject, appConfiguration, alignPath, streams) map {
      (is, mod, c, ivyScala, out, app, path, s) =>
        withExcludes(out, mod.classifiers, lock(app)) { excludes =>
          // do default update-sbt-classifiers
          val report = IvyActions.updateClassifiers(is, GetClassifiersConfiguration(mod, excludes, c, ivyScala), s.log)
          // process UpdateReport
          // get all sources
          val (sources, other) = report.toSeq.partition {
            case (configuration, module, Artifact(name, kind, extension, Some("sources"), configurations, url, extraAttributes), file) => true
            case _ => false
          }
          // process all jars
          other.foreach {
            case (configuration, module, Artifact(name, kind, extension, Some(""), configurations, url, extraAttributes), file) =>
              sources.find(source => source._1 == configuration && source._2 == module) match {
                case Some((_, _, _, sourceFile)) =>
                  align(module.toString, file, sourceFile, path, s)
                case None =>
                  s.log.debug("sbt-source-align: skip align for dependency " + module + " - sources not found ")
              }
            case (configuration, module, Artifact(name, kind, extension, classifier, configurations, url, extraAttributes), file) =>
              s.log.debug("sbt-source-align: skip align for dependency " + module + " with classifier " + classifier)
          }
          report
        }
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
    s.log.info("align " + moduleTag + ", target: " + target)
    // align
    var jarCode: JarInputStream = null
    var jarSources: JarInputStream = null
    var jarTarget: JarOutputStream = null
    try {
      jarCode = new JarInputStream(new FileInputStream(code))
      jarSources = new JarInputStream(new FileInputStream(sources))
      jarTarget = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(target, true)), jarCode.getManifest())
      // copy across all entries from the original code jar
      copy(alignEntries, jarCode, jarTarget, s)
      // copy across all entries from the original sources jar
      copy(alignEntries, jarSources, jarTarget, s)
    } catch {
      case e =>
        s.log.error("sbt-source-align: align " + e.getMessage())
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
              s.log.error("sbt-source-align: align " + e.getMessage())
          }
        entry = in.getNextEntry()
      }
    } catch {
      case e =>
        s.log.error("sbt-source-align: align " + e.getMessage())
    }
  }
}