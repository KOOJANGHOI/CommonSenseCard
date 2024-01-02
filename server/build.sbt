name := "server"

version := "1.0"

lazy val `server` = (project in file(".")).enablePlugins(PlayScala)

resolvers += "Akka Snapshot Repository" at "https://repo.akka.io/snapshots/"

scalaVersion := "2.13.5"
libraryDependencies ++= {
  val playJsonVersion = "2.9.0"
  val slickVersion = "3.3.3"
  val slickPgVersion = "0.19.7"
  val postgresqlVersion = "42.2.19"
  val scalazVersion = "7.2.30"

  Seq(
    jdbc,
    ehcache,
    ws,
    specs2 % Test,
    guice,

    // 데이터베이스와 상호작용하기 위해 Play Slick 또는 Play Anorm과 같은 라이브러리를 추가합니다. build.sbt 파일에 다음과 같이 의존성을 추가할 수 있습니다.
    /* Scala Libraries */
    "com.typesafe.play" %% "play-json" % playJsonVersion,
    "com.typesafe.play" %% "play-ws" % playJsonVersion,
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.github.tminglei" %% "slick-pg" % slickPgVersion,
    "com.github.tminglei" %% "slick-pg_play-json" % slickPgVersion,

    "org.scalaz" %% "scalaz-core" % scalazVersion,
    "org.scalaz" %% "scalaz-concurrent" % scalazVersion,

    "io.netty" % "netty-all" % "4.1.73.Final",

    /* Database Libraries */
    "org.postgresql" % "postgresql" % postgresqlVersion,

    /* Logging */
    "ch.qos.logback" % "logback-classic" % "1.2.3"
  )
}
