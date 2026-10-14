package scalafiddle.server

import play.api.http.HeaderNames
import play.api.mvc.Headers

import scalafiddle.shared.ApiHeader

/**
  * Protection against requests sent from another site with a logged-in user's cookies (CSRF).
  */
object ApiGuard {

  /**
    * Should this `/api` request be accepted?
    *
    * - `ApiHeader` is required. A page on another site can only add a custom header through a
    *   preflight request, and the preflight is refused because `/api` is not open to CORS.
    *   A form or a `text/plain` POST cannot carry the header.
    * - `Origin: null` (a frame with an opaque origin, including the editor's own result frame)
    *   is rejected explicitly, so that user code never reaches the API.
    */
  def allowed(headers: Headers): Boolean =
    headers.get(ApiHeader.Name).contains(ApiHeader.Value) &&
      !headers.get(HeaderNames.ORIGIN).contains("null")

  /** Is this the format produced by `Persistence.createId` (7 characters, 0-9A-Za-z)? */
  def isFiddleId(id: String): Boolean = id.matches("[0-9A-Za-z]{7}")
}
