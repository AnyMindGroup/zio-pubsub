//| mvnDeps:
//| - com.lihaoyi::mill-contrib-docker:$MILL_VERSION

package build

import mill.*, scalalib.*, scalanativelib.*
import contrib.docker.DockerModule
import mill.scalanativelib.api.ReleaseMode
import mill.api.Task.Simple

val zioPubsubVersion = "0.3.6"

trait CommonModule extends ScalaModule {
  def scalaVersion = "3.7.3"

  def scalacOptions = Seq(
    "-source:future-migration",
    "-feature",
    "-Wunused:all",
    "-Wvalue-discard",
    // "-rewrite",
  )

  def mvnDeps = Seq(
    mvn"com.anymindgroup::zio-pubsub::$zioPubsubVersion",
    mvn"dev.zio::zio-logging::2.5.1",
  )
}

trait CommonJvmModule extends CommonModule {
  val targetJava = "21"

  def javacOptions = Seq("-source", targetJava, "-target", targetJava)

  def scalacOptions = super.scalacOptions() ++ Seq(s"-release:$targetJava")
}

trait CommonNativeModule extends CommonModule, ScalaNativeModule {
  def scalaNativeVersion = "0.5.8"

  def nativeMultithreading = Some(true)

  def nativeCompileOptions = super.nativeCompileOptions() ++ Seq(
    "-DGC_INITIAL_HEAP_SIZE=256m",
    "-DGC_MAXIMUM_HEAP_SIZE=768g",
    // "-DDEBUG_PRINT",
  )
}

object `test-runtime-jvm` extends CommonJvmModule

object `test-runtime-native` extends CommonNativeModule

object `test-core` extends CommonJvmModule

object testCoreNative extends CommonNativeModule {
  def moduleDir = super.moduleDir / ".." / "test-core"
}

trait JvmDockerModule extends CommonJvmModule, DockerModule {
  trait CommonDockerConfig extends DockerConfig {
    def platform  = "linux/amd64"
    def baseImage = "eclipse-temurin:21-jre"
  }
}

object `test-google` extends JvmDockerModule {
  def moduleDeps = Seq(`test-core`, `test-runtime-jvm`)

  def mvnDeps = Seq(
    mvn"com.anymindgroup::zio-pubsub-google:$zioPubsubVersion"
  )

  object docker extends CommonDockerConfig {
    def tags = List(
      s"asia-docker.pkg.dev/anychat-staging/docker/zio_pubsub_test_google:latest"
    )
  }
}

trait TestHttpModule extends CommonModule {
  def moduleDir = super.moduleDir / ".." / "test-http"

  def mvnDeps = Seq(
    mvn"com.anymindgroup::zio-pubsub-http::$zioPubsubVersion"
  )
}

object testHttpJvm extends TestHttpModule, JvmDockerModule {
  def moduleDeps = Seq(`test-core`, `test-runtime-jvm`)

  object docker extends CommonDockerConfig {
    def tags = List(
      s"asia-docker.pkg.dev/anychat-staging/docker/zio_pubsub_test_http_jvm:latest"
    )
  }
}

object testHttpNative extends TestHttpModule, CommonNativeModule {
  def moduleDeps = Seq(testCoreNative, `test-runtime-native`)
}
