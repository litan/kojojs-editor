package scalafiddle.server

import org.scalatest._
import play.api.mvc.Headers

import scalafiddle.shared.ApiHeader

/**
  * CSRF protection of `/api`: the editor's requests carry `ApiHeader`; another site's form,
  * a `text/plain` POST or a frame with an opaque origin cannot.
  */
class ApiGuardSpec extends WordSpec with Matchers {
  private val header = ApiHeader.Name -> ApiHeader.Value

  "ApiGuard.allowed" should {
    "accept the editor's request (header present, same-site Origin)" in {
      ApiGuard.allowed(Headers(header, "Origin" -> "https://editor.example")) shouldBe true
    }
    "accept a request with the header but without Origin" in {
      ApiGuard.allowed(Headers(header)) shouldBe true
    }
    "ignore the case of the header name" in {
      ApiGuard.allowed(Headers(ApiHeader.Name.toLowerCase -> ApiHeader.Value)) shouldBe true
    }
    "reject a request without the header (another site's form)" in {
      ApiGuard.allowed(Headers("Content-Type" -> "text/plain", "Origin" -> "https://evil.example")) shouldBe false
    }
    "reject a request whose header value is wrong" in {
      ApiGuard.allowed(Headers(ApiHeader.Name -> "XMLHttpRequest")) shouldBe false
    }
    "reject Origin: null (opaque frame) even if the header is present" in {
      ApiGuard.allowed(Headers(header, "Origin" -> "null")) shouldBe false
    }
  }

  "ApiGuard.isFiddleId" should {
    "accept the format produced by Persistence.createId (7 characters, 0-9A-Za-z)" in {
      ApiGuard.isFiddleId("a1B2c3D") shouldBe true
    }
    "reject everything else" in {
      // Apart from the length cases these are 7 characters: they must fail on the quote or
      // symbol, not on the length.
      Seq("", "abc", "a1B2c3", "a1B2c3D4", "abc\"def", "ab<cdef", "ab/cdef", "ab cdef", "ab_cdef", "abcde\u00e9f")
        .foreach { id =>
          withClue(s"'$id' -- ") { ApiGuard.isFiddleId(id) shouldBe false }
        }
    }
  }
}
