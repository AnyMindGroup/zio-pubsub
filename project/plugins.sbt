val zioSbtVersion = "0.4.0-alpha.28"
addSbtPlugin("dev.zio" % "zio-sbt-website" % zioSbtVersion)

// need sbt-sonatype version which supports new Sonatype Central API added in v3.11.0 https://github.com/xerial/sbt-sonatype/releases/tag/v3.11.0
// might be removed later once zio-sbt-ci / sbt-ci-release update sbt-sonatype
addSbtPlugin("dev.zio"        % "zio-sbt-ci"   % zioSbtVersion exclude ("org.xerial.sbt", "sbt-sonatype"))
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.11.0")

// zio-sbt-ecosystem depends on scala-native 0.5.x which breaks the build, for now only 0.4.x is supported
addSbtPlugin("dev.zio"          % "zio-sbt-ecosystem" % zioSbtVersion exclude ("org.scala-native", "sbt-scala-native"))
addSbtPlugin("org.scala-native" % "sbt-scala-native"  % "0.4.17")

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
