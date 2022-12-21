val scala3Version = "3.2.1"
val fs2Version = "3.4.0"
val catsV = "3.4.2"
import scala.scalanative.build._

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "mini-redis-ce",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % fs2Version,
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "cats-effect" % catsV,
      "com.armanbilge" %%% "epollcat" % "0.1.2", // Runtime
      "org.scalameta" %%% "munit" % "1.0.0-M6" % Test
      // "org.typelevel" %%% "munit-cats-effect-3" % "1.0.7" % Test
    ),
    Compile / mainClass := Some("Main")
    // javaOptions ++= Seq(
    //   "-XX:+UnlockExperimentalVMOptions",
    //   "-Xmx4096M",
    //   "-XX:+UseZGC"
    // )
    // nativeConfig ~= {
    //   _.withLTO(LTO.thin)
    //     .withMode(Mode.releaseFast)
    //     .withGC(GC.commix)
    // }
  )
