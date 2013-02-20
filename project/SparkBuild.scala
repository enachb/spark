import sbt._
import sbt.Classpaths.publishTask
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import twirl.sbt.TwirlPlugin._
// For Sonatype publishing
//import com.jsuereth.pgp.sbtplugin.PgpKeys._

object SparkBuild extends Build {
  // Hadoop version to build against. For example, "0.20.2", "0.20.205.0", or
  // "1.0.3" for Apache releases, or "0.20.2-cdh3u5" for Cloudera Hadoop.
  val HADOOP_VERSION = "1.0.3"
  val HADOOP_MAJOR_VERSION = "1"

  // For Hadoop 2 versions such as "2.0.0-mr1-cdh4.1.1", set the HADOOP_MAJOR_VERSION to "2"
  //val HADOOP_VERSION = "2.0.0-mr1-cdh4.1.1"
  //val HADOOP_MAJOR_VERSION = "2"

  lazy val root = Project("root", file("."), settings = rootSettings) aggregate(core, /*repl,*/ examples, bagel)

  lazy val core = Project("core", file("core"), settings = coreSettings)

//  lazy val repl = Project("repl", file("repl"), settings = replSettings) dependsOn (core)

  lazy val examples = Project("examples", file("examples"), settings = examplesSettings) dependsOn (core)

  lazy val bagel = Project("bagel", file("bagel"), settings = bagelSettings) dependsOn (core)

  // A configuration to set an alternative publishLocalConfiguration
  val qf = "http://repo.quantifind.com/content/repositories/"
  lazy val MavenCompile = config("m2r") extend(Compile)
  lazy val publishLocalBoth = TaskKey[Unit]("publish-local", "publish local for m2 and ivy")

  def sharedSettings = Defaults.defaultSettings ++ Seq(
    organization       := "org.spark-project",
    version            := "0.7.0-SNAPSHOT",
    scalaVersion       := "2.10.0",
    scalacOptions      := Seq(/*"-deprecation",*/ "-unchecked", "-optimize"), // -deprecation is too noisy due to usage of old Hadoop API, enable it once that's no longer an issue
    unmanagedJars in Compile <<= baseDirectory map { base => (base / "lib" ** "*.jar").classpath },
    retrieveManaged := true,
    retrievePattern := "[type]s/[artifact](-[revision])(-[classifier]).[ext]",
    transitiveClassifiers in Scope.GlobalScope := Seq("sources"),
    testListeners <<= target.map(t => Seq(new eu.henkelmann.sbt.JUnitXmlTestsListener(t.getAbsolutePath))),

    publishTo <<= version {
      (v: String) =>
        Some("snapshots" at qf + "ext-snapshots")
    },


    libraryDependencies ++= Seq(
    "org.eclipse.jetty" % "jetty-server"    % "7.5.3.v20111011",
    "org.scalatest"    %% "scalatest"       % "1.9.1"  % "test",
    "org.scalacheck"   %% "scalacheck"      % "1.10.0" % "test",
    "com.novocode"      % "junit-interface" % "0.8"    % "test"
  ),
    parallelExecution := false,
    /* Workaround for issue #206 (fixed after SBT 0.11.0) */
    watchTransitiveSources <<= Defaults.inDependencies[Task[Seq[File]]](watchSources.task,
      const(std.TaskExtra.constant(Nil)), aggregate = true, includeRoot = true) apply { _.join.map(_.flatten) }
  )

  val slf4jVersion = "1.6.1"

  def coreSettings = sharedSettings ++ Seq(
    name := "spark-core",
    resolvers ++= Seq(
      "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      "JBoss Repository"    at "http://repository.jboss.org/nexus/content/repositories/releases/",
      "Spray Repository"    at "http://repo.spray.cc/",
      "Cloudera Repository" at "https://repository.cloudera.com/artifactory/cloudera-repos/"
    ),

    libraryDependencies ++= Seq(
        "com.google.guava"    % "guava"            % "11.0.1",
        "log4j"               % "log4j"            % "1.2.16",
        "org.slf4j"           % "slf4j-api"        % slf4jVersion,
        "org.slf4j"           % "slf4j-log4j12"    % slf4jVersion,
        "com.ning"            % "compress-lzf"     % "0.8.4",
        "org.apache.hadoop"   % "hadoop-core"      % HADOOP_VERSION,
        "asm"                 % "asm-all"          % "3.3.1",
        "com.google.protobuf" % "protobuf-java"    % "2.4.1",
        "de.javakaffee"       % "kryo-serializers" % "0.20",
        "com.typesafe.akka"  %% "akka-remote"      % "2.1.0",
        "com.typesafe.akka"  %% "akka-slf4j"       % "2.1.0",
        "it.unimi.dsi"        % "fastutil"         % "6.4.4",
        "io.spray"            % "spray-can"        % "1.1-M7",
        "io.spray"            % "spray-io"         % "1.1-M7",
        "io.spray"            % "spray-routing"    % "1.1-M7",
        "io.spray"           %% "spray-json"       % "1.2.3",
        "colt"                % "colt"             % "1.2.0",
        "org.apache.mesos"    % "mesos"            % "0.9.0-incubating",
        "org.scala-lang"      % "scala-actors"     % "2.10.0"
      ) ++ (if (HADOOP_MAJOR_VERSION == "2")
        Some("org.apache.hadoop" % "hadoop-client" % HADOOP_VERSION) else None).toSeq,
    unmanagedSourceDirectories in Compile <+= baseDirectory{ _ / ("src/hadoop" + HADOOP_MAJOR_VERSION + "/scala") }
  ) ++ assemblySettings ++ extraAssemblySettings ++ Twirl.settings

  def rootSettings = sharedSettings ++ Seq(
    publish := {}
  )

/*  def replSettings = sharedSettings ++ Seq(
    name := "spark-repl",
    libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _)
  )*/

  def examplesSettings = sharedSettings ++ Seq(
    name := "spark-examples"
  )

  def bagelSettings = sharedSettings ++ Seq(name := "spark-bagel")

  def extraAssemblySettings() = Seq(test in assembly := {}) ++ Seq(
    mergeStrategy in assembly := {
      case m if m.toLowerCase.endsWith("manifest.mf") => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
}
