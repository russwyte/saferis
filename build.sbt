val scala3Version = "3.3.8"
val zioVersion    = "2.1.26"

// Global settings using ThisBuild scope
ThisBuild / scalaVersion         := scala3Version
ThisBuild / organization         := "io.github.russwyte"
ThisBuild / organizationName     := "russwyte"
ThisBuild / organizationHomepage := Some(url("https://github.com/russwyte"))
ThisBuild / licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage             := Some(url("https://github.com/russwyte/saferis"))
ThisBuild / scmInfo              := Some(
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
ThisBuild / versionScheme := Some("early-semver")

// Publishing to Sonatype's Central Portal. sbt 1.11+ has built-in support via
// `localStaging` / `publishSigned` / `sonaRelease` — no sbt-sonatype plugin needed.
ThisBuild / publishTo := {
  val centralSnapshots =
    "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// CI overrides the key via PGP_KEY_HEX (dedicated GitHub Actions signing key);
// local manual publishing falls back to the personal key on this machine.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "2F64727A87F1BCF42FD307DD8582C4F16659A7D6"))

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-Wunused:all",
    "-feature",
  ),
  scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23",
)

lazy val publishSettings = Seq(
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
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
      "dev.zio"           %% "zio-streams"               % zioVersion % "provided",
      "dev.zio"           %% "zio-json"                  % "0.9.0"    % "provided",
      "dev.zio"           %% "zio-logging-slf4j2-bridge" % "2.5.3"    % Test,
      "dev.zio"           %% "zio-test"                  % zioVersion % Test,
      "dev.zio"           %% "zio-test-sbt"              % zioVersion % Test,
      "dev.zio"           %% "zio-test-magnolia"         % zioVersion % Test,
      "org.testcontainers" % "postgresql"                % "1.21.4"   % Test,
      "org.postgresql"     % "postgresql"                % "42.7.11"  % Test,
    ),
    dependencyOverrides ++= Seq(
      "org.apache.commons"         % "commons-compress" % "1.27.1" % Test,
      "org.apache.commons"         % "commons-lang3"    % "3.18.0" % Test,
      "com.fasterxml.jackson.core" % "jackson-core"     % "2.18.6" % Test,
    ),
  )

lazy val docs = project
  .in(file("saferis-docs"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name           := "saferis-docs",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"        % zioVersion,
      "dev.zio"           %% "zio-json"   % "0.9.0",
      "org.testcontainers" % "postgresql" % "1.21.4",
      "org.postgresql"     % "postgresql" % "42.7.11",
      "org.slf4j"          % "slf4j-nop"  % "1.7.36",
    ),
    marklitTargetDirectory  := (ThisBuild / baseDirectory).value / "docs",
    marklitRunResourceClass := Some("saferis.docs.DocsTransactor"),
    marklitShowWarnings     := false,
    scalacOptions ~= (_.filterNot(_ == "-Wunused:all")),
  )
