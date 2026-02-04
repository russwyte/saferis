import xerial.sbt.Sonatype.sonatypeCentralHost

val scala3Version = "3.7.4"
val zioVersion    = "2.1.24"

// Global settings using ThisBuild scope
ThisBuild / scalaVersion         := scala3Version
ThisBuild / organization         := "io.github.russwyte"
ThisBuild / organizationName     := "russwyte"
ThisBuild / organizationHomepage := Some(url("https://github.com/russwyte"))
ThisBuild / licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage             := Some(url("https://github.com/russwyte/saferis"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/russwyte/saferis"),
    "scm:git@github.com:russwyte/saferis.git",
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte"),
  )
)
ThisBuild / versionScheme          := Some("early-semver")
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-Wunused:all",
    "-feature",
  ),
  scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23",
)

lazy val publishSettings = Seq(
  publishMavenStyle      := true,
  pomIncludeRepository   := { _ => false },
  sonatypeCredentialHost := sonatypeCentralHost,
  publishTo              := sonatypePublishToBundle.value,
)

// Root project aggregates all modules but is not published
lazy val root = project
  .in(file("."))
  .aggregate(core, docs)
  .settings(
    name           := "saferis-root",
    publish / skip := true,
  )

// Core library - the main publishable artifact
lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name        := "saferis",
    description := "Saferis mitigates the discord of unsafe SQL. It is a resource safe SQL client library.",
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"                       % zioVersion % "provided",
      "dev.zio"           %% "zio-json"                  % "0.8.0"    % "provided",
      "dev.zio"           %% "zio-logging-slf4j2-bridge" % "2.5.3"    % Test,
      "dev.zio"           %% "zio-test"                  % zioVersion % Test,
      "dev.zio"           %% "zio-test-sbt"              % zioVersion % Test,
      "dev.zio"           %% "zio-test-magnolia"         % zioVersion % Test,
      "org.testcontainers" % "postgresql"                % "1.21.4"   % Test,
      "org.postgresql"     % "postgresql"                % "42.7.9"   % Test,
    ),
  )

// Documentation module - uses mdoc to compile code examples
lazy val docs = project
  .in(file("saferis-docs"))
  .dependsOn(core)
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(
    name           := "saferis-docs",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio"            %% "zio"        % zioVersion,
      "dev.zio"            %% "zio-json"   % "0.8.0",
      "org.testcontainers"  % "postgresql" % "1.21.4",
      "org.postgresql"      % "postgresql" % "42.7.9",
    ),
    mdocVariables := Map(
      "VERSION" -> version.value,
    ),
    mdocIn  := file("saferis-docs") / "docs",
    mdocOut := file("docs"),
    // Suppress unused warnings in mdoc - examples often show imports without using them
    scalacOptions ~= (_.filterNot(_ == "-Wunused:all")),
  )
