import zio.sbt.githubactions.{Job, Step}
enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name               := "ZIO Google Cloud Pub/Sub",
    zioVersion         := "2.0.21",
    organization       := "com.anymindgroup",
    licenses           := Seq(License.Apache2),
    homepage           := Some(url("https://anymindgroup.com")),
    crossScalaVersions := Seq("2.13.12", "3.3.1"),
    ciEnabledBranches  := Seq("master"),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTestJobs := ciTestJobs.value.map {
      case j if j.id == "test" =>
        val startPubsub = Step.SingleStep(name = "Start up pubsub", run = Some("docker compose up -d"))
        j.copy(steps = j.steps.flatMap {
          case s: Step.SingleStep if s.name.contains("Git Checkout") => Seq(s, startPubsub)
          case s                                                     => Seq(s)
        })
      case j => j
    },
    scalafmt         := true,
    scalafmtSbtCheck := true,
    scalafixDependencies ++= List(
      "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
    ),
  )
)

lazy val commonSettings = List(
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
      case _            => Seq()
    }
  },
  javacOptions ++= Seq("-source", "17"),
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Ymacro-annotations", "-Xsource:3")
      case _            => Seq("-source:future")
    }
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
) ++ scalafixSettings

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
    .aggregate(
      zioPubsub.jvm,
      zioPubsub.native,
      zioPubsubGoogle,
      zioPubsubGoogleTest,
      zioPubsubTestkit,
      zioPubsubSerdeCirce.jvm,
      zioPubsubSerdeCirce.native,
      zioPubsubSerdeVulcan,
      zioPubsubTest.jvm,
      zioPubsubTest.native,
      examplesGoogle,
    )
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(
      coverageDataDir := {
        val scalaVersionMajor = scalaVersion.value.head
        target.value / s"scala-$scalaVersionMajor"
      }
    )

lazy val zioPubsub = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-gc-pubsub"))
  .settings(moduleName := "zio-gc-pubsub")
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"         % zioVersion.value,
      "dev.zio" %%% "zio-streams" % zioVersion.value,
    )
  )

val vulcanVersion = "1.10.1"
lazy val zioPubsubSerdeVulcan = (project in file("zio-gc-pubsub-serde-vulcan"))
  .settings(moduleName := "zio-gc-pubsub-serde-vulcan")
  .dependsOn(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "vulcan"         % vulcanVersion,
      "com.github.fd4s" %% "vulcan-generic" % vulcanVersion,
    )
  )

val circeVersion = "0.14.6"
lazy val zioPubsubSerdeCirce = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-gc-pubsub-serde-circe"))
  .settings(moduleName := "zio-gc-pubsub-serde-circe")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"    % circeVersion,
      "io.circe" %%% "circe-parser"  % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
    )
  )

val googleCloudPubsubVersion = "1.126.2"
lazy val zioPubsubGoogle = (project in file("zio-gc-pubsub-google"))
  .settings(moduleName := "zio-gc-pubsub-google")
  .dependsOn(zioPubsub.jvm)
  .aggregate(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    scalacOptions --= List("-Wunused:nowarn"),
    libraryDependencies ++= Seq(
      "com.google.cloud" % "google-cloud-pubsub" % googleCloudPubsubVersion
    ),
  )

lazy val zioPubsubGoogleTest = project
  .in(file("zio-gc-pubsub-google-test"))
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle, zioPubsubTestkit, zioPubsubSerdeCirce.jvm, zioPubsubSerdeVulcan)
  .settings(moduleName := "zio-gc-pubsub-google-test")
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(testDeps)
  .settings(
    coverageEnabled            := true,
    (Test / parallelExecution) := true,
    (Test / fork)              := true,
  )

// TODO remove dependency on zioPubsubGoogle
lazy val zioPubsubTestkit =
  (project in file("zio-gc-pubsub-testkit"))
    .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
    .settings(moduleName := "zio-gc-pubsub-testkit")
    .settings(commonSettings)
    .settings(releaseSettings)
    .settings(
      scalafixConfig := Some(new File(".scalafix_test.conf")),
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test" % zioVersion.value
      ),
    )

lazy val zioPubsubTest =
  crossProject(JVMPlatform, NativePlatform)
    .in(file("zio-gc-pubsub-test"))
    .dependsOn(zioPubsub, zioPubsubSerdeCirce)
    .settings(moduleName := "zio-gc-pubsub-test")
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(testDeps)
    .jvmSettings(coverageEnabled := true)
    .nativeSettings(coverageEnabled := false)

lazy val examplesGoogle = (project in file("examples/google"))
  .dependsOn(zioPubsubGoogle)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(coverageEnabled := false)

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %%% "zio-test"     % zioVersion.value % Test,
    "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test,
  )
)

lazy val docs = project
  .in(file("zio-gc-pubsub-docs"))
  .settings(
    moduleName := "zio-gc-pubsub-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := "ZIO Google Cloud Pub/Sub",
    mainModuleName                             := (zioPubsub.jvm / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioPubsub.jvm),
    readmeDocumentation                        := "",
    readmeContribution                         := "",
    readmeSupport                              := "",
    readmeLicense                              := "",
    readmeAcknowledgement                      := "",
    readmeCodeOfConduct                        := "",
    readmeCredits                              := "",
    readmeBanner                               := "",
    readmeMaintainers                          := "",
  )
  .enablePlugins(WebsitePlugin)
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
