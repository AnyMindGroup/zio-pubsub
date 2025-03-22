import zio.sbt.githubactions.{ActionRef, Condition, Job, Step}
import _root_.io.circe.Json

import scala.annotation.tailrec
enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

lazy val _scala3 = "3.3.5"

lazy val defaultJavaVersion = "21"

lazy val zioGcpVersion = "0.1.2"

def withTestSetupUpdate(j: Job) = if (j.id == "test") {
  val startPubsub = Step.SingleStep(
    name = "Start up pubsub emulator",
    run = Some(
      "docker compose up -d && until curl -s http://localhost:8085; do printf 'waiting for pubsub...'; sleep 1; done && echo \"pubsub ready\""
    ),
  )
  j.copy(steps = j.steps.flatMap {
    case s: Step.SingleStep if s.name.contains("Git Checkout") => Seq(s, startPubsub)
    case s: Step.SingleStep if s.name.contains("Install libuv") =>
      Seq(s.copy(run = Some("sudo apt-get update && sudo apt-get install -y libuv1-dev libidn2-dev libcurl3-dev")))
    case s => Seq(s)
  })
} else j

inThisBuild(
  List(
    name         := "ZIO Google Cloud Pub/Sub",
    organization := "com.anymindgroup",
    licenses     := Seq(License.Apache2),
    homepage     := Some(url("https://anymindgroup.github.io/zio-pubsub")),
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
    zioVersion         := "2.1.16",
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    versionScheme      := Some("early-semver"),
    ciEnabledBranches  := Seq("master", "series/0.2.x"),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions := Seq(defaultJavaVersion),
    ciDefaultJavaVersion := defaultJavaVersion,
    ciBuildJobs := ciBuildJobs.value.map { j =>
      j.copy(steps =
        j.steps.map {
          case s @ Step.SingleStep("Check all code compiles", _, _, _, _, _, _) =>
            Step.SingleStep(
              name = s.name,
              run = Some("sbt 'Test/compile; examples/compile'"),
            )
          case s @ Step.SingleStep("Check website build process", _, _, _, _, _, _) =>
            Step.StepSequence(
              Seq(
                s,
                Step.SingleStep(
                  "Adjust baseUrl in website build",
                  run = Some(
                    """sed -i "s/baseUrl:.*/baseUrl: \"\/zio-pubsub\/\",/g" zio-pubsub-docs/target/website/docusaurus.config.js && sbt docs/buildWebsite"""
                  ),
                ),
              )
            )
          case s => s
        } :+ Step.SingleStep(
          name = "Upload website build",
          uses = Some(ActionRef("actions/upload-pages-artifact@v3")),
          parameters = Map("path" -> Json.fromString("zio-pubsub-docs/target/website/build")),
        )
      )
    },
    ciTestJobs             := ciTestJobs.value.map(withTestSetupUpdate),
    sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost,
    ciReleaseJobs := ciReleaseJobs.value.map(j =>
      j.copy(
        steps = j.steps.map {
          case Step.SingleStep(name @ "Release", _, _, _, _, _, env) =>
            Step.SingleStep(
              name = name,
              run = Some(
                """|echo "$PGP_SECRET" | base64 -d -i - > /tmp/signing-key.gpg
                   |echo "$PGP_PASSPHRASE" | gpg --pinentry-mode loopback --passphrase-fd 0 --import /tmp/signing-key.gpg
                   |(echo "$PGP_PASSPHRASE"; echo; echo) | gpg --command-fd 0 --pinentry-mode loopback --change-passphrase $(gpg --list-secret-keys --with-colons 2> /dev/null | grep '^sec:' | cut --delimiter ':' --fields 5 | tail -n 1)
                   |sbt 'publishSigned; sonatypeCentralRelease'""".stripMargin
              ),
              env = env,
            )
          case s => s
        }
      )
    ),
    // this overrides the default post release jobs generated by zio-sbt-ci which publish the docs to NPM Registry
    // can try to make it work with NPM later
    ciPostReleaseJobs := Nil,
    // zio.sbt.githubactions.Job doesn't provide options for adding permissions and environment
    // overriding ciGenerateGithubWorkflow as well as ciCheckGithubWorkflow to get both working
    ciGenerateGithubWorkflow := ciGenerateGithubWorkflowV2.value,
    ciCheckGithubWorkflow := Def.task {
      import sys.process.*
      val _ = ciGenerateGithubWorkflowV2.value

      if ("git diff --exit-code".! == 1) {
        sys.error(
          "The ci.yml workflow is not up-to-date!\n" +
            "Please run `sbt ciGenerateGithubWorkflow` and commit new changes."
        )
      }
    },
    scalafmt         := true,
    scalafmtSbtCheck := true,
  )
)

lazy val ciGenerateGithubWorkflowV2 = Def.task {
  val _ = ciGenerateGithubWorkflow.value

  IO.append(
    new File(s".github/workflows/ci.yml"),
    """|  deploy-website:
       |    name: Deploy website
       |    runs-on: ubuntu-latest
       |    continue-on-error: false
       |    permissions:
       |      pages: write
       |      id-token: write
       |    environment:
       |      name: github-pages
       |      url: ${{ steps.deployment.outputs.page_url }}
       |    needs:
       |    - release
       |    steps:
       |    - name: Deploy to GitHub Pages
       |      uses: actions/deploy-pages@v4
       |""".stripMargin,
  )
}

lazy val commonSettings = List(
  javacOptions ++= Seq("-source", defaultJavaVersion),
  Compile / scalacOptions ++= Seq("-source:future", s"-release:$defaultJavaVersion"),
  Compile / scalacOptions --= sys.env.get("CI").fold(Seq("-Xfatal-warnings"))(_ => Nil),
  Test / scalafixConfig := Some(new File(".scalafix_test.conf")),
  Test / scalacOptions --= Seq("-Xfatal-warnings"),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
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
      zioPubsubHttp.jvm,
      zioPubsubHttp.native,
      zioPubsubGoogle,
      zioPubsubTestkit.jvm,
      zioPubsubTestkit.native,
      zioPubsubSerdeZioSchema.jvm,
      zioPubsubSerdeZioSchema.native,
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
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"         % zioVersion.value,
      "dev.zio" %%% "zio-streams" % zioVersion.value,
    )
  )

lazy val zioPubsubHttp = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub-http"))
  .settings(moduleName := "zio-pubsub-http")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.anymindgroup" %%% "zio-gcp-auth"      % zioGcpVersion,
      "com.anymindgroup" %%% "zio-gcp-pubsub-v1" % zioGcpVersion,
    )
  )

val zioSchemaVersion = "1.6.6"
lazy val zioPubsubSerdeZioSchema = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub-serde-zio-schema"))
  .settings(moduleName := "zio-pubsub-serde-zio-schema")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-schema" % zioSchemaVersion
    )
  )

val googleCloudPubsubVersion = "1.138.0"
lazy val zioPubsubGoogle = (project in file("zio-pubsub-google"))
  .settings(moduleName := "zio-pubsub-google")
  .dependsOn(zioPubsub.jvm, zioPubsubTest.jvm % "test->test")
  .aggregate(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.google.cloud" % "google-cloud-pubsub" % googleCloudPubsubVersion
    ),
    coverageEnabled            := true,
    (Test / parallelExecution) := true,
    (Test / fork)              := true,
  )

lazy val zioPubsubTestkit =
  crossProject(JVMPlatform, NativePlatform)
    .in(file("zio-pubsub-testkit"))
    .dependsOn(zioPubsub, zioPubsubHttp)
    .settings(moduleName := "zio-pubsub-testkit")
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test" % zioVersion.value
      )
    )

lazy val zioPubsubTest =
  crossProject(JVMPlatform, NativePlatform)
    .in(file("zio-pubsub-test"))
    .dependsOn(zioPubsub % "test", zioPubsubTestkit % "test")
    .settings(moduleName := "zio-pubsub-test")
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(testDeps)
    .jvmSettings(coverageEnabled := true)
    .nativeSettings(coverageEnabled := false)

lazy val examples = (project in file("examples"))
  .dependsOn(zioPubsubHttp.jvm, zioPubsubGoogle)
  .settings(noPublishSettings)
  .settings(
    coverageEnabled := false,
    fork            := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % "0.7.39"
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
    // docusaurusPublishGhpages := docusaurusPublishGhpages.value,
  )
  .enablePlugins(WebsitePlugin)
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
