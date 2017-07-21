
lazy val commonSettings = Seq(
    name := "Eldarica",
    organization := "uuverifiers",
    version := "2017-07-21-SNAPSHOT",
    scalaVersion := "2.11.8",
    crossScalaVersions := Seq("2.11.8", "2.12.1"),
    publishTo := Some(Resolver.file("file",  new File( "./" )) )
)

// Actual project

lazy val root = (project in file(".")).
    settings(commonSettings: _*).
//
    settings(
      scalaSource in Compile := baseDirectory.value / "src",
//
      mainClass in Compile := Some("lazabs.Main"),
//

unmanagedJars in Compile <++= baseDirectory map { base =>
    val baseDirectories = (base / "lib") +++ (base / "flata")
    val customJars = (baseDirectories ** "*.jar")  // +++ (base / "d" / "my.jar")
    customJars.classpath
},

	// exclude any folders 
/*	excludeFilter in Compile := {
		val refine = (baseDirectory.value / "src" / "lazabs" / "refine" ).getCanonicalPath
  		new SimpleFileFilter(f => 
  			(f.getCanonicalPath startsWith refine)  			
  		)
	},
*/
//
    scalacOptions in Compile ++=
      List("-feature",
           "-language:implicitConversions,postfixOps,reflectiveCalls"),
    scalacOptions <+= scalaVersion map { sv => sv match {
      case "2.11.8" => "-optimise"
      case "2.12.1" => "-opt:l:classpath"
    }},
//
    libraryDependencies +=
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
//
    libraryDependencies +=
      "net.sf.squirrel-sql.thirdparty-non-maven" % "java-cup" % "0.11a",
//
    libraryDependencies +=
      "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.5",
//
    resolvers += "uuverifiers" at "http://logicrunch.it.uu.se:4096/~wv/maven/",
    libraryDependencies += "uuverifiers" %% "princess" % "2017-07-17"
//    libraryDependencies += "uuverifiers" %% "princess" % "nightly-SNAPSHOT"
)

//