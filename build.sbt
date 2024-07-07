import zio.sbt.githubactions.{Job, Step, Condition}
enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

lazy val _scala2 = "2.13.14"

lazy val _scala3 = "3.3.3"

inThisBuild(
  List(
    name         := "ZIO Google Cloud Pub/Sub",
    organization := "com.anymindgroup",
    licenses     := Seq(License.Apache2),
    homepage     := Some(url("https://github.com/AnyMindGroup/zio-pubsub")),
    developers := List(
      Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://github.com/rolang")),
      Developer(
        id = "dutch3883",
        name = "Panuwach Boonyasup",
        email = "dutch3883@hotmail.com",
        url = url("https://github.com/dutch3883"),
      ),
      Developer(
        id = "qhquanghuy",
        name = "Huy Nguyen",
        email = "huy_ngq@flinters.vn",
        url = url("https://github.com/qhquanghuy"),
      ),
    ),
    zioVersion         := "2.1.5",
    scala213           := _scala2,
    scala3             := _scala3,
    scalaVersion       := _scala2,
    crossScalaVersions := Seq(_scala2, _scala3),
    versionScheme      := Some("early-semver"),
    ciEnabledBranches  := Seq("master"),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions := Seq("17", "21"),
    ciBuildJobs := ciBuildJobs.value.map { j =>
      j.copy(steps = j.steps.map {
        case s @ Step.SingleStep("Check all code compiles", _, _, _, _, _, _) =>
          Step.SingleStep(
            name = s.name,
            run = Some("sbt '+Test/compile; +examples/compile'"),
          )
        case s => s
      })
    },
    ciTestJobs := ciTestJobs.value.map {
      case j if j.id == "test" =>
        val startPubsub = Step.SingleStep(
          name = "Start up pubsub",
          run = Some(
            "docker compose up -d && until curl -s http://localhost:8085; do printf 'waiting for pubsub...'; sleep 1; done && echo \"pubsub ready\""
          ),
        )
        j.copy(steps = j.steps.flatMap {
          case s: Step.SingleStep if s.name.contains("Git Checkout") => Seq(s, startPubsub)
          case s                                                     => Seq(s)
        })
      case j => j
    },
    sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost,
    // remove the release step modification once public
    ciReleaseJobs := ciReleaseJobs.value.map(j =>
      j.copy(
        steps = j.steps.map {
          case Step.SingleStep(name @ "Release", _, _, _, _, _, env) =>
            Step.SingleStep(
              name = name,
              run = Some(
                """|echo "$PGP_SECRET" | base64 -d -i - > /tmp/signing-key.gpg
                   | && echo "$PGP_PASSPHRASE" | gpg --pinentry-mode loopback --passphrase-fd 0 --import /tmp/signing-key.gpg
                   | && (echo "$PGP_PASSPHRASE"; echo; echo) | gpg --command-fd 0 --pinentry-mode loopback --change-passphrase $(gpg --list-secret-keys --with-colons 2> /dev/null | grep '^sec:' | cut --delimiter ':' --fields 5 | tail -n 1)
                   | && sbt '+publishSigned; sonatypeCentralRelease'""".stripMargin
              ),
              env = env,
            )
          case s => s
        },
        condition = Some(Condition.Expression("startsWith(github.ref, 'refs/tags/v')")),
      )
    ),
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
) ++ scalafixSettings

val releaseSettings = List(
  publishTo := sonatypePublishToBundle.value
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
  .in(file("zio-pubsub"))
  .settings(moduleName := "zio-pubsub")
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"         % zioVersion.value,
      "dev.zio" %%% "zio-streams" % zioVersion.value,
    )
  )

val vulcanVersion = "1.10.1"
lazy val zioPubsubSerdeVulcan = (project in file("zio-pubsub-serde-vulcan"))
  .settings(moduleName := "zio-pubsub-serde-vulcan")
  .dependsOn(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "vulcan"         % vulcanVersion,
      "com.github.fd4s" %% "vulcan-generic" % vulcanVersion,
    )
  )

val circeVersion = "0.14.7"
lazy val zioPubsubSerdeCirce = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub-serde-circe"))
  .settings(moduleName := "zio-pubsub-serde-circe")
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

val googleCloudPubsubVersion = "1.130.1"
lazy val zioPubsubGoogle = (project in file("zio-pubsub-google"))
  .settings(moduleName := "zio-pubsub-google")
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
  .in(file("zio-pubsub-google-test"))
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle, zioPubsubTestkit, zioPubsubSerdeCirce.jvm, zioPubsubSerdeVulcan)
  .settings(moduleName := "zio-pubsub-google-test")
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
  (project in file("zio-pubsub-testkit"))
    .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
    .settings(moduleName := "zio-pubsub-testkit")
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
    .in(file("zio-pubsub-test"))
    .dependsOn(zioPubsub, zioPubsubSerdeCirce)
    .settings(moduleName := "zio-pubsub-test")
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(testDeps)
    .jvmSettings(coverageEnabled := true)
    .nativeSettings(coverageEnabled := false)

lazy val examples = (project in file("examples"))
  .dependsOn(zioPubsubGoogle)
  .settings(noPublishSettings)
  .settings(
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    coverageEnabled    := false,
    fork               := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % "0.7.1"
    ),
  )

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %%% "zio-test"     % zioVersion.value % Test,
    "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test,
  )
)

lazy val docs = project
  .in(file("zio-pubsub-docs"))
  .settings(
    moduleName := "zio-pubsub-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := "ZIO Google Cloud Pub/Sub",
    mainModuleName                             := (zioPubsub.jvm / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioPubsub.jvm),
    readmeContribution :=
      """|If you have any question or problem feel free to open an issue or discussion.
         |
         |People are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md) when discussing on the GitHub issues or PRs.""".stripMargin,
    readmeSupport       := "Open an issue or discussion on [GitHub](https://github.com/AnyMindGroup/zio-pubsub/issues)",
    readmeCodeOfConduct := "See the [Code of Conduct](CODE_OF_CONDUCT.md)",
    readmeCredits := """|Inspired by libraries like [zio-kafka](https://github.com/zio/zio-kafka) 
                        |and [fs2-pubsub](https://github.com/permutive-engineering/fs2-pubsub) to provide a similar experience.""".stripMargin,
  )
  .enablePlugins(WebsitePlugin)
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
