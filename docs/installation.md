# Installation

To get started with sbt, add the following line to your build.sbt file to use the implementation with the Google Java library:

```scala
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-google" % "@VERSION@"
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-testkit" % "@VERSION@" % Test
```