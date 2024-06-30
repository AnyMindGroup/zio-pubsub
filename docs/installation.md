# Installation

To get started with sbt, add the following to your build.sbt file:

```scala
libraryDependencies ++= Seq(
    // core components
    "com.anymindgroup" %% "zio-pubsub"         % "@VERSION@",
    
    // to use the implementation with Google's Java library
    "com.anymindgroup" %% "zio-pubsub-google"  % "@VERSION@",

    // include for testing support
    "com.anymindgroup" %% "zio-pubsub-testkit" % "@VERSION@" % Test
)
```