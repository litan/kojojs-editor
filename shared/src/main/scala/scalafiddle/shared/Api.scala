package scalafiddle.shared

import scala.concurrent.Future

/**
  * Header that every `/api` request must carry. Another site cannot add a custom header
  * without a preflight request (`/api` is not open to CORS), so the server rejects requests
  * without it.
  */
object ApiHeader {
  val Name  = "X-Requested-With"
  val Value = "kojojs"
}

case class LoginProvider(id: String, name: String, logoUrl: String)

case class UserInfo(id: String, name: String, avatarUrl: Option[String], loggedIn: Boolean)

case class FiddleVersions(id: String, name: String, libraries: Seq[String], latestVersion: Int, updated: Long)

trait Api {
  def save(fiddle: FiddleData): Future[Either[String, FiddleId]]

  def update(fiddle: FiddleData, id: String): Future[Either[String, FiddleId]]

  def fork(fiddle: FiddleData, id: String, version: Int): Future[Either[String, FiddleId]]

  def loginProviders(): Seq[LoginProvider]

  def userInfo(): UserInfo

  def listFiddles(): Future[Seq[FiddleVersions]]
}
