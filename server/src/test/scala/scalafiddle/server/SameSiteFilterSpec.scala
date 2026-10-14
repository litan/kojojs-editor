package scalafiddle.server

import org.scalatest._
import play.api.mvc.{Cookie, DefaultCookieHeaderEncoding, DiscardingCookie, RequestHeader, Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * `SameSite=Lax` on the cookies set by the response. Silhouette 5.0.1 sets cookies with
  * `withCookies`/`discardingCookies`, so they all end up in `newCookies`.
  */
class SameSiteFilterSpec extends WordSpec with Matchers {
  "SameSiteFilter" should {
    "add Lax to a cookie without SameSite and leave the rest alone" in {
      val c = Cookie("authenticator", "v", maxAge = Some(60), secure = true, httpOnly = true)
      SameSiteFilter.laxCookie(c) shouldBe c.copy(sameSite = Some(Cookie.SameSite.Lax))
    }
    "leave a cookie that has its own SameSite alone" in {
      val c = Cookie("other", "v", sameSite = Some(Cookie.SameSite.Strict))
      SameSiteFilter.laxCookie(c) shouldBe c
    }
    "cover every new cookie of the response, including discarded ones" in {
      val r = Results.Ok
        .withCookies(Cookie("authenticator", "v"), Cookie("OAuth2State", "s"))
        .discardingCookies(DiscardingCookie("OAuth1TokenSecret"))
      val result = SameSiteFilter.addLax(r)
      result.newCookies.map(_.name) should contain theSameElementsAs Seq("authenticator", "OAuth2State", "OAuth1TokenSecret")
      result.newCookies.foreach { c =>
        withClue(s"${c.name} -- ") { c.sameSite shouldBe Some(Cookie.SameSite.Lax) }
      }
      result.header shouldBe r.header
    }
    "return a response without cookies unchanged" in {
      val r = Results.Ok("x")
      SameSiteFilter.addLax(r) should be theSameInstanceAs r
    }
    "be written by Play's Set-Cookie encoder" in {
      val header = new DefaultCookieHeaderEncoding()
        .encodeSetCookieHeader(Seq(SameSiteFilter.laxCookie(Cookie("authenticator", "v"))))
      header should include("SameSite=Lax")
    }
  }

  "SameSiteFilter (the filter)" should {
    // The request header and the Materializer are not used by the filter; null is enough.
    val filter = new SameSiteFilter()(null, ExecutionContext.global)

    "add Lax to the cookies in the action's response" in {
      // Explicitly typed function: Filter.apply is overloaded (there is an EssentialAction version too).
      val action: RequestHeader => Future[Result] = _ => Future.successful(Results.Ok.withCookies(Cookie("authenticator", "v")))
      val result = Await.result(filter.apply(action)(null), 5.seconds)
      result.newCookies.map(_.sameSite) shouldBe Seq(Some(Cookie.SameSite.Lax))
    }
    "be registered in Filters (if removed, cookies go out without the attribute again)" in {
      new Filters(null, null, filter).filters should contain(filter)
    }
  }
}
