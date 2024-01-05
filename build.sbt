val scala213 = "2.13.12"
val scala3   = "3.3.1"
val allScala = Seq(scala213, scala3)

ThisBuild / organization       := "com.anymindgroup"
ThisBuild / licenses           := Seq(License.Apache2)
ThisBuild / homepage           := Some(url("https://anymindgroup.com"))
ThisBuild / scalaVersion       := scala213
ThisBuild / crossScalaVersions := allScala
ThisBuild / scalafmt           := true
ThisBuild / scalafmtSbtCheck   := true
ThisBuild / semanticdbEnabled  := true
ThisBuild / semanticdbOptions ++= { if (scalaVersion.value == scala3) Seq() else Seq("-P:semanticdb:synthetics:on") }
ThisBuild / semanticdbVersion          := scalafixSemanticdb.revision // use Scalafix compatible version
ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies ++= List(
  "com.github.liancheng" %% "organize-imports" % "0.5.0",
  "com.github.vovapolu"  %% "scaluzzi"         % "0.1.23",
)

lazy val commonSettings = List(
  libraryDependencies ++= {
    if (scalaVersion.value == scala3)
      Seq()
    else
      Seq(
        compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full)),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      )
  },
  javacOptions ++= Seq("-source", "17"),
  Compile / scalacOptions ++= {
    if (scalaVersion.value == scala3)
      Seq(
        "-source:future"
      )
    else
      Seq("-Ymacro-annotations", "-Xsource:3")
  },
  Compile / scalacOptions --= sys.env.get("CI").fold(Seq("-Xfatal-warnings"))(_ => Nil),
  Test / scalafixConfig := Some(new File(".scalafix_test.conf")),
  Test / scalacOptions --= Seq("-Xfatal-warnings"),
  version ~= { v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v },
  credentials += {
    for {
      username <- sys.env.get("ARTIFACT_REGISTRY_USERNAME")
      apiKey   <- sys.env.get("ARTIFACT_REGISTRY_PASSWORD")
    } yield Credentials("https://asia-maven.pkg.dev", "asia-maven.pkg.dev", username, apiKey)
  }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("fix", "; all scalafixAll; all scalafmtSbt scalafmtAll")
addCommandAlias("check", "; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check")

val releaseSettings = List(
  publishTo := Some("AnyChat Maven" at "https://asia-maven.pkg.dev/anychat-staging/maven")
)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

lazy val root =
  (project in file("."))
    .dependsOn(
      zioPubsub,
      zioPubsubGoogle,
      zioPubsubTestkit,
      zioPubsubSerdeCirce,
      zioPubsubSerdeVulcan,
      zioPubsubTest,
    )
    .aggregate(
      zioPubsub,
      zioPubsubGoogle,
      zioPubsubTestkit,
      zioPubsubSerdeCirce,
      zioPubsubSerdeVulcan,
      zioPubsubTest,
      examplesGoogle,
    )
    .settings(commonSettings)
    .settings(noPublishSettings)

val zioVersion = "2.0.21"
lazy val zioPubsub = (project in file("zio-gc-pubsub"))
  .settings(moduleName := "zio-gc-pubsub")
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
    )
  )

val vulcanVersion = "1.9.0"
lazy val zioPubsubSerdeVulcan = (project in file("zio-gc-pubsub-serde-vulcan"))
  .settings(moduleName := "zio-gc-pubsub-serde-vulcan")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "vulcan"         % vulcanVersion,
      "com.github.fd4s" %% "vulcan-generic" % vulcanVersion,
    )
  )

val circeVersion = "0.14.6"
lazy val zioPubsubSerdeCirce = (project in file("zio-gc-pubsub-serde-circe"))
  .settings(moduleName := "zio-gc-pubsub-serde-circe")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
    )
  )

val googleCloudPubsubVersion = "1.125.13"
lazy val zioPubsubGoogle = (project in file("zio-gc-pubsub-google"))
  .settings(moduleName := "zio-gc-pubsub-google")
  .dependsOn(zioPubsub)
  .aggregate(zioPubsub)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    scalacOptions --= List("-Wunused:nowarn"),
    libraryDependencies ++= Seq(
      "com.google.cloud" % "google-cloud-pubsub" % googleCloudPubsubVersion
    ),
  )

lazy val zioPubsubTestkit =
  (project in file("zio-gc-pubsub-testkit"))
    .dependsOn(zioPubsub, zioPubsubGoogle, zioPubsubSerdeCirce, zioPubsubSerdeVulcan)
    .settings(moduleName := "zio-gc-pubsub-testkit")
    .settings(commonSettings)
    .settings(releaseSettings)
    .settings(
      scalafixConfig := Some(new File(".scalafix_test.conf")),
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test"          % zioVersion,
        "dev.zio" %% "zio-test-sbt"      % zioVersion,
        "dev.zio" %% "zio-test-magnolia" % zioVersion,
      ),
    )

lazy val zioPubsubTest =
  (project in file("zio-gc-pubsub-test"))
    .dependsOn(zioPubsub, zioPubsubTestkit, zioPubsubSerdeCirce, zioPubsubSerdeVulcan)
    .settings(moduleName := "zio-gc-pubsub-test")
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(
      coverageEnabled            := true,
      (Test / parallelExecution) := true,
      (Test / fork)              := true,
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

lazy val examplesGoogle = (project in file("examples/google"))
  .dependsOn(zioPubsubGoogle)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(coverageEnabled := false)
