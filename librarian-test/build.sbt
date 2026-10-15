// A separate build that compiles and runs LibrarianSpec (the class that reads the library list
// from libraries.json) on current sbt and a current JDK.
//
// WHY SEPARATE: the editor's `server/test` needs sbt 0.13 + Scala.js 0.6 + Play 2.6 and no CI job
// runs it, so LibrarianSpec could stay red unnoticed. Librarian itself only depends on upickle
// 0.4.4, slf4j and the shared `Library` class; this project compiles those and runs the spec.
// The sources are NOT copies: they are the editor's own files, read straight from there.
//
// OUT OF SCOPE: the rest of the server (Play controllers, ApiGuardSpec, UpdateFiddleSpec, ...) is
// not part of this build; those still run only with `JAVA_HOME=...1.8 sbt server/test`.
//
// Run:  cd librarian-test && sbt test
name := "librarian-test"
// The editor uses 2.12.10, which does not run on Java 21. Librarian has no version-specific code.
scalaVersion := "2.12.20"

Compile / unmanagedSources :=
  Seq(baseDirectory.value / ".." / "server/src/main/scala/scalafiddle/server/Librarian.scala") ++
    ((baseDirectory.value / ".." / "shared/src/main/scala") ** "*.scala").get
Test / unmanagedSources := Seq(baseDirectory.value / ".." / "server/src/test/scala/scalafiddle/server/LibrarianSpec.scala")
// Only libraries.json: the rest of server/src/main/resources is not this test's business.
Test / unmanagedResourceDirectories += baseDirectory.value / ".." / "server/src/main/resources"
Test / unmanagedResources / includeFilter := "libraries.json"

libraryDependencies ++= Seq(
  // same as the editor: versions.upickle in Settings.scala
  "com.lihaoyi" %% "upickle" % "0.4.4",
  "org.slf4j" % "slf4j-api" % "1.7.25",
  // 3.1.x: org.scalatest.WordSpec / Matchers still exist (removed in 3.2); the editor uses 3.0.3
  "org.scalatest" %% "scalatest" % "3.1.4" % Test
)
