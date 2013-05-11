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

import java.util.zip.ZipEntry

import sbt._
import sbt.Keys._

object Keys {
  lazy val dependencyAddCustom = SettingKey[Boolean]("dependency-add-custom", "Add custom(unknown) libraries to results")
  lazy val dependencyBundlePath = TaskKey[File]("dependency-bundle-path", "Bundle jar location")
  lazy val dependencyClasspathFilter = TaskKey[ModuleFilter]("dependency-classpath-filter", "Filter that accept all dependency modules")
  lazy val dependencyFilter = TaskKey[Option[ModuleFilter]]("dependency-filter", "Processing dependencies only with particular sbt.ModuleID")
  lazy val dependencyIgnoreConfiguration = SettingKey[Boolean]("dependency-ignore-configurations", "Ignore configurations while lookup, 'test' for example")
  lazy val dependencyLookupClasspath = TaskKey[Classpath]("dependency-lookup-classpath", "Classpath that is used for building the dependency sequence")
  lazy val dependencyPath = TaskKey[File]("dependency-path", "Target directory for dependency jars")
  lazy val dependencyResourceFilter = SettingKey[ZipEntry => Boolean]("dependency-resource-filter", "Fuction for filtering jar content")
  lazy val dependencySkipResolved = SettingKey[Boolean]("dependency-skip-resoled", "Skip resolved dependencies with explicit artifacts which points to local resources")
  lazy val dependencyTaskBundle = TaskKey[UpdateReport]("dependency-bundle", "Fetch dependency code and source jars. Save results to bundle")
  lazy val dependencyTaskBundleWithArtifact = TaskKey[UpdateReport]("dependency-bundle-with-artifact", "Fetch dependency code and source jars, add project artefact. Save results to bundle")
  lazy val dependencyTaskFetch = TaskKey[UpdateReport]("dependency-fetch", "Fetch dependency code jars. Save results to target directory")
  lazy val dependencyTaskFetchAlign = TaskKey[UpdateReport]("dependency-fetch-align", "Fetch dependency code and source jars, merge them. Save results to target directory")
  lazy val dependencyTaskFetchWithSources = TaskKey[UpdateReport]("dependency-fetch-with-sources", "Fetch dependency code and source jars. Save results to target directory")
}
