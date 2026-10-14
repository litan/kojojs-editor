package scalafiddle.server

import javax.inject.Inject

import akka.stream.Materializer
import play.api.mvc.{Cookie, Filter, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Adds `SameSite=Lax` to the cookies set by the response.
  *
  * Silhouette 5.0.1 has no SameSite setting in its cookie settings (`CookieAuthenticatorSettings`),
  * so the session (`authenticator`), OAuth state and OAuth1 cookies were sent without the
  * attribute and protection was left to the browser default. Silhouette sets its cookies with
  * `result.withCookies`, so they all end up in `Result.newCookies`.
  *
  * `Lax`, not `Strict`: the return from the GitHub login is a top-level navigation and the OAuth
  * state cookie has to go with it. A cookie that has its own SameSite is left alone. (Play's own
  * session and flash cookies never pass through this filter; they are baked in at the server layer
  * and get the default of `play.http.session.sameSite`, "lax".)
  */
class SameSiteFilter @Inject()(implicit val mat: Materializer, ec: ExecutionContext) extends Filter {
  def apply(next: RequestHeader => Future[Result])(rh: RequestHeader): Future[Result] =
    next(rh).map(r => SameSiteFilter.addLax(r))
}

object SameSiteFilter {
  def addLax(r: Result): Result =
    if (r.newCookies.isEmpty) r
    else r.copy(newCookies = r.newCookies.map(laxCookie))

  def laxCookie(c: Cookie): Cookie =
    if (c.sameSite.isEmpty) c.copy(sameSite = Some(Cookie.SameSite.Lax)) else c
}
