val scala3Version = "3.1.0"
val fs2Version = "3.2.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "mini-redis-ce",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.scalameta" %% "munit" % "0.7.26" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.3" % Test
    ),
    run / fork := true,
    javaOptions ++= Seq(
      "-XX:+UnlockExperimentalVMOptions",
      "-Xmx4096M",
      "-XX:+UseZGC"
    )
  )
