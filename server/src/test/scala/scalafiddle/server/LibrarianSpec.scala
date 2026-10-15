package scalafiddle.server

import org.scalatest._

import java.io.ByteArrayInputStream

import scala.io.BufferedSource

class LibrarianSpec extends WordSpec with Matchers {

  // Two groups, three library versions: group index, fallbacks from library to version,
  // the "org/lib" documentation shorthand, and a compile-time-only library.
  val sample: String =
    """[
      |  {"group": "Web", "libraries": [
      |    {"name": "Foo", "organization": "org.foo", "artifact": "foo", "doc": "foo-org/foo",
      |     "compileTimeOnly": false,
      |     "versions": [
      |       {"version": "1.0", "scalaVersions": ["2.12"]},
      |       {"version": "2.0", "scalaVersions": ["2.12"], "artifact": "foo2", "doc": "https://example.com/doc",
      |        "extraDeps": ["org.foo %%% foo-extra % 2.0"]}
      |     ]}
      |  ]},
      |  {"group": "Types", "libraries": [
      |    {"name": "Bar", "organization": "org.bar", "artifact": "bar", "doc": "https://example.com/bar",
      |     "compileTimeOnly": true,
      |     "versions": [{"version": "3.0", "scalaVersions": ["2.12"]}]}
      |  ]}
      |]""".stripMargin

  // Librarian wants a BufferedSource, which Source.fromString does not return
  def librarianOf(json: => String) =
    new Librarian(() => new BufferedSource(new ByteArrayInputStream(json.getBytes("UTF-8"))))

  "Librarian" should {
    "parse the shipped libraries.json" in {
      // The shipped file may list no libraries (its groups can be empty), so an empty list is fine.
      // What this guards is that the file stays valid and in the expected shape.
      // `loadLibraries`, not `libraries`: refresh() swallows every exception, so a broken file
      // would look exactly like an empty one.
      // a fresh stream per call, as in production: the constructor already reads one via refresh()
      def shipped = new BufferedSource(getClass.getResourceAsStream("/libraries.json"))
      getClass.getResource("/libraries.json") should not be null
      noException should be thrownBy new Librarian(() => shipped).loadLibraries
    }

    "read groups, versions and fallbacks" in {
      val libs = librarianOf(sample).loadLibraries
      libs.map(l => (l.name, l.version)) shouldBe Seq(("Foo", "1.0"), ("Foo", "2.0"), ("Bar", "3.0"))

      val foo1 = libs(0)
      foo1.organization shouldBe "org.foo"
      foo1.artifact shouldBe "foo" // taken from the library: the version has none
      foo1.group shouldBe "00:Web"
      foo1.docUrl shouldBe "https://github.com/foo-org/foo" // "org/lib" shorthand
      foo1.extraDeps shouldBe empty
      foo1.compileTimeOnly shouldBe false

      val foo2 = libs(1)
      foo2.artifact shouldBe "foo2" // the version overrides the library
      foo2.docUrl shouldBe "https://example.com/doc"
      foo2.extraDeps shouldBe Seq("org.foo %%% foo-extra % 2.0")

      val bar = libs(2)
      bar.group shouldBe "01:Types"
      bar.compileTimeOnly shouldBe true
    }

    "keep %%% (Scala.js) and %% (compile-time only) lookups apart" in {
      val librarian = librarianOf(sample)
      librarian.libraries should have size 3
      librarian.findLibrary("org.foo %%% foo % 1.0").map(_.version) shouldBe Some("1.0")
      librarian.findLibrary("org.foo %% foo % 1.0") shouldBe None // Foo is not compile-time only
      librarian.findLibrary("org.bar %% bar % 3.0").map(_.name) shouldBe Some("Bar")
      librarian.findLibrary("org.bar %%% bar % 3.0") shouldBe None // Bar is compile-time only
      librarian.findLibrary("org.foo %%% foo % 9.9") shouldBe None
      librarian.findLibrary("not a dependency") shouldBe None
    }

    "fail loudly in loadLibraries but keep the previous list in refresh" in {
      var json = sample
      val librarian = librarianOf(json)
      librarian.libraries should have size 3

      json = "this is not json"
      an[Exception] should be thrownBy librarian.loadLibraries
      librarian.refresh() // logs the error, must not throw
      librarian.libraries should have size 3
    }
  }
}
