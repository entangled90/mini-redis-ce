val scala3Version = "3.3.1"
val fs2Version = "3.9.3"

lazy val root = project
  .in(file("."))
  .settings(
    name := "mini-redis-ce",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.scalameta" %% "munit" % "1.0.0-M10" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0-M4" % "test"
    ),
    run / fork := true,
    javaOptions ++= Seq(
      "-XX:+UnlockExperimentalVMOptions",
      "-Xmx4096M",
      "-XX:+UseZGC"
    )
  )
