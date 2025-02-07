val scala3Version = "3.6.3"

val zioVersion = "2.1.14"

ThisBuild / scalaVersion := scala3Version
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-Wunused:all",
)
ThisBuild / scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
ThisBuild / libraryDependencies ++= Seq(
  "dev.zio"           %% "zio"                       % zioVersion,
  "dev.zio"           %% "zio-logging-slf4j2-bridge" % "2.4.0"    % Test,
  "dev.zio"           %% "zio-test"                  % zioVersion % Test,
  "dev.zio"           %% "zio-test-sbt"              % zioVersion % Test,
  "dev.zio"           %% "zio-test-magnolia"         % zioVersion % Test,
  "org.testcontainers" % "postgresql"                % "1.20.4"   % Test,
  "org.postgresql"     % "postgresql"                % "42.7.5"   % Test,
)

lazy val root = project
  .in(file("."))
  .settings(
    name    := "saferis",
    version := "0.1.0-SNAPSHOT",
  )
