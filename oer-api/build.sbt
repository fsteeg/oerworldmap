name := "oer-api"

version := "0.1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  cache,
  "org.elasticsearch" % "elasticsearch" % "1.1.0" withSources(),
  "org.mindrot" % "jbcrypt" % "0.3m" withSources(),
  "org.apache.jena" % "jena-arq" % "2.11.2"
)

resolvers += "jena-dev" at "https://repository.apache.org/content/repositories/snapshots"

play.Project.playJavaSettings
