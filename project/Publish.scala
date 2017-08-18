import sbt._
import sbt.Keys._

import java.net.URL

import scala.xml.NodeSeq
import com.typesafe.sbt.SbtPgp._

object Publish {

  lazy val settings: Seq[Setting[_]] = Seq(
    publishMavenStyle := true,

    publishTo := {
      val artifactory = "http://artifactory.service.iad1.consul:8081/artifactory/"
      val (name, url) = if (version.value.contains("-SNAPSHOT"))
        ("Artifactory Realm", artifactory + "libs-snapshot;build.timestamp=" + new java.util.Date().getTime)
      else
        ("Artifactory Realm", artifactory + "libs-release;build.timestamp=" + new java.util.Date().getTime)
      Some(Resolver.url(name, new URL(url)))
    },

    publishArtifact in Test := false,

    pomIncludeRepository := { _ => false },

    //credentials could also be just embedded in ~/.sbt/0.13/sonatype.sbt
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),

    pomExtra := pomExtraGen,

    Option(System.getenv("PGP_PASSPHRASE")).fold(
      pgpPassphrase := None
    )( passPhrase =>
      pgpPassphrase :=  Some(passPhrase.toCharArray)
    ),

    pgpSecretRing := file("secring.gpg"),

    pgpPublicRing := file("pubring.gpg"),

    licenses := Seq("Apache License, Version 2.0"-> new URL("http://www.apache.org/licenses/LICENSE-2.0.html")),

    homepage :=  Some(url("https://github.com/tumblr/colossus"))
  )

  private def pomExtraGen = {
    <inceptionYear>2014</inceptionYear>
     <scm>
       <url>git@github.com/tumblr/colossus.git</url>
       <connection>scm:git:git@github.com/tumblr/colossus.git</connection>
     </scm> ++ pomDevelopersGen(Seq(("dsimon", "Dan Simon"), ("sauron", "Nick Sauro")))
  }

  private def pomDevelopersGen(developers : Seq[(String, String)]) : NodeSeq = {
    <developers>
      {
        developers.map{case (uid, name) => <developer><id>{uid}</id><name>{name}</name></developer>}
      }
    </developers>

  }

}
