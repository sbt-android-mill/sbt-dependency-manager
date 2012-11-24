sbt-source-align
================

fetch [SBT](https://github.com/harrah/xsbt "Simple Build Tool") project artifacts, compose jars with source code, align sources inside jars for your favorite IDE

* allow fetch __all dependency jars (include simple-build-tool itself)__ to target folder
* allow fetch __all dependency jars with sources (include simple-build-tool itself)__ to target folder
* allow fetch __all dependency jars with sources, merge them (include simple-build-tool itself)__ and save to target folder

If you want to improve it, please send mail to sbt-android-mill at digimead.org. You will be added to the group. Please, feel free to add yourself to authors.

It is less than 400 lines. SBT source code is really simple to read and simple to extend :-)

This readme cover all plugin functionality, even if it is written in broken english (would you have preferred well written russian :-) Please, correct it, if you find something inappropriate.

## Adding to your project ##

Create a

 * _project/plugins/project/Build.scala_ - for older simple-build-tool
 * _project/project/Build.scala_ - for newer simple-build-tool

file that looks like the following:

```scala
    import sbt._
    object PluginDef extends Build {
      override def projects = Seq(root)
      lazy val root = Project("plugins", file(".")) dependsOn(dm)
      lazy val dm = uri("git://github.com/sbt-android-mill/sbt-source-align.git#0.3")
    }
```

You may find more information about Build.scala at [https://github.com/harrah/xsbt/wiki/Plugins](https://github.com/harrah/xsbt/wiki/Plugins)

Then in your _build.sbt_ file, simply add:

``` scala
    sbt.dependency.manager.Plugin.activate
```

You may find sample project at _src/sbt-test/source-align/simple_

## Usage ##

By default aligned jars saved to _target/deps_ Change _dependenciesPath_ or add to your project something like

``` scala
    dependenciesPath <<= (target in LocalRootProject) map { _ / "my-align-dir" }
```

or

``` scala
    dependenciesPath <<= (baseDirectory) (_ / "my-aling-dir")
```


You may skip dependencies with _ssa-skip-organization_. If you got something like this

```
[trace] Stack trace suppressed: run last *:update-align for the full output.
[error] (*:update-align) java.lang.IllegalArgumentException: Cannot add dependency 'org.scala-lang#scala-library;2.9.2' to configuration 'provided' of module sbt.android.mill#sbt-android-mill$sbt;0.1-SNAPSHOT because this configuration doesn't exist!
```

add an exception to alignSkipOrganizationin your _build.sbt_

``` scala
    ssaSkipOrganization += "org.scala-lang"
```

By default ssaSkipOrganization contains "org.scala-lang" and "org.scala-sbt". This setting affects library-dependencies only.

### Align project dependencies ###

1. Download all project __and SBT__ dependencies, sources, javadocs

2. Merge code jars with sources

3. Align sources inside jars

SBT task name

```
> dependency-fetch-align
```

It is very useful to develop simple-build-tool plugins. Most SBT source code are unaligned. Original sources saved in root directory of jar, but it binded to different packages. This situation prevent source code lookup in most common situations. This is very annoying. SBT _*-sources.jar_ was mostly useless in development before sbt-source-align ;-)

### Fetch project dependencies with sources to '123' directory

```
> set ssaPath <<= baseDirectory map {(f) => f / "123" }
> dependency-fetch-with-sources
```
 
Internals
---------

### Options ###

* __dependency-path__ (dependencyPath) - Target directory for dependency jars
* __dependency-filter__ (dependencyFilter) - Processing dependencies only with particular sbt.ModuleID
* __dependency-add-custom__ (dependencyAddCustom) - Add custom(unknown) libraries to results
* __dependency-classpath-narrow__ (dependencyClasspathNarrow) - Union of dependencyClasspath from Compile and Test configurations
* __dependency-classpath-wide__ (dependencyClasspathWide) - Union of fullClasspath from Compile and Test configurations
* __dependency-ignore-configurations__ (dependencyIgnoreConfigurations) - Ignore configurations while lookup, 'test' for example

### Tasks ###

* __dependency-fetch__ - Fetch project jars. Save result to target directory
* __dependency-fetch-align__ - Fetch project jars, merge them with source code. Save result to target directory
* __dependency-fetch-with-sources__ - Fetch project jars, fetch source jars. Save result to target directory

Demonstration
-------------

[Simple-build-tool plugin with Eclipse in 5 Minutes](http://youtu.be/3K8knvkVAyc) on Youtube

HD quality [Simple-build-tool plugin with Eclipse in 5 Minutes](https://github.com/downloads/sbt-android-mill/sbt-android-mill-extra/EclipseSBT.mp4) - 60,5Mb

Developing simple SBT plugin in Eclipse IDE with

* autocomplete
* __sources lookup__
* __debug SBT tasks in Eclipse debugger__ (I asked about debugging in SBT mailing list, but no one can answer. I suspect that others people for debug sbt plugins used print or s.log.debug. lol ;-) )
* implicit lookup
* types lookup
* refactoring support

... and bunch of other standard features

_PS sbt-dependency-manager obsoletes capabilities provided by sbt deliver-local + IvyDE or sbteclipse plugin_

Authors
-------

* Alexey Aksenov

License
-------

The sbt-source-align is licensed to you under the terms of
the Apache License, version 2.0, a copy of which has been
included in the LICENSE file.

Copyright
---------

Copyright © 2012 Alexey B. Aksenov/Ezh. All rights reserved.
