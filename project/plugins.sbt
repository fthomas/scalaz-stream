resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
    url("http://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.1.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.6.0")

addSbtPlugin("com.github.tkawachi" % "sbt-doctest" % "0.3.2")
