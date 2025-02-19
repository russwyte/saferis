import xerial.sbt.Sonatype.sonatypeCentralHost

val scala3Version = "3.6.3"
val zioVersion    = "2.1.14"
usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

lazy val root = project
  .in(file("."))
  .settings(
    name                 := "saferis",
    description          := "Saferis mitigates the discord of unsafe SQL. It is a resource safe SQL client library.",
    licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage             := Some(url("https://github.com/russwyte/saferis")),
    scalaVersion         := scala3Version,
    organization         := "io.github.russwyte",
    organizationName     := "russwyte",
    organizationHomepage := Some(url("https://github.com/russwyte")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/russwyte/saferis"),
        "scm:git@github.com:russwyte/saferis.git",
      )
    ),
    developers := List(
      Developer(
        id = "russwyte",
        name = "Russ White",
        email = "356303+russwyte@users.noreply.github.com",
        url = url("https://github.com/russwyte"),
      )
    ),
    publishMavenStyle      := true,
    pomIncludeRepository   := { _ => false },
    sonatypeCredentialHost := sonatypeCentralHost,
    publishTo              := sonatypePublishToBundle.value,
    versionScheme          := Some("early-semver"),
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"                       % zioVersion % "provided",
      "dev.zio"           %% "zio-logging-slf4j2-bridge" % "2.4.0"    % Test,
      "dev.zio"           %% "zio-test"                  % zioVersion % Test,
      "dev.zio"           %% "zio-test-sbt"              % zioVersion % Test,
      "dev.zio"           %% "zio-test-magnolia"         % zioVersion % Test,
      "org.testcontainers" % "postgresql"                % "1.20.4"   % Test,
      "org.postgresql"     % "postgresql"                % "42.7.5"   % Test,
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-Wunused:all",
      "-feature",
    ),
    scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23",
  )
