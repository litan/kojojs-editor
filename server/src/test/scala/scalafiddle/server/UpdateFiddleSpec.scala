package scalafiddle.server

import java.sql.DriverManager

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.mohiva.play.silhouette.api.LoginInfo
import org.scalatest._
import play.api.Configuration

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try
import scalafiddle.server.dao.Fiddle
import scalafiddle.server.models.User
import scalafiddle.shared.{FiddleData, FiddleId, UserInfo}

/**
  * Is the right to update a fiddle decided by the stored owner? `ApiService.update` used to
  * look at `FiddleData.author`, which the client sends; an empty author was accepted, so anyone
  * could add a version to someone else's fiddle on their behalf.
  *
  * Uses the real Persistence actor with the in-memory "h2" database from application.conf.
  */
class UpdateFiddleSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  implicit val timeout: Timeout = 15.seconds

  val system = ActorSystem("update-fiddle-spec")

  // application.conf "h2": jdbc:h2:mem:tsql1, DB_CLOSE_DELAY=-1. The database lives as long as
  // this connection stays open; the table is created here.
  val connection = DriverManager.getConnection("jdbc:h2:mem:tsql1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
  connection.createStatement().execute(
    """CREATE TABLE IF NOT EXISTS "fiddle" (
      |  "id" VARCHAR NOT NULL, "version" INTEGER NOT NULL, "name" VARCHAR NOT NULL,
      |  "description" VARCHAR NOT NULL, "sourcecode" VARCHAR NOT NULL, "libraries" VARCHAR NOT NULL,
      |  "scala_version" VARCHAR NOT NULL, "user" VARCHAR NOT NULL, "parent" VARCHAR,
      |  "created" BIGINT NOT NULL, "removed" BOOLEAN NOT NULL)""".stripMargin)

  val persistence = system.actorOf(Props(new Persistence(Configuration.from(Map("scalafiddle.dbConfig" -> "h2")))))

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    connection.close()
  }

  private def user(id: String) =
    User(id, LoginInfo("github", id), None, None, Some(id), None, None, activated = true)

  private def fiddle(code: String, author: Option[UserInfo]) =
    FiddleData("name", "", code, Nil, Nil, "2.13", author)

  private def api(u: Option[User]) = new ApiService(persistence, u, Nil)

  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, 15.seconds)

  private def latestVersion(id: String): Fiddle =
    await((persistence ? FindLastFiddle(id)).mapTo[Try[Fiddle]]).get

  private def save(u: Option[User]): FiddleId =
    await(api(u).save(fiddle("owner's code", None))) match {
      case Right(fid) => fid
      case Left(e)    => fail(e)
    }

  "ApiService.update" should {
    "not add a version to someone else's fiddle, even if the author is sent empty" in {
      val fid    = save(Some(user("github:owner")))
      val result = await(api(Some(user("github:other"))).update(fiddle("attacker's code", None), fid.id))
      result shouldBe Left("Not allowed to update fiddle")
      latestVersion(fid.id).sourceCode shouldBe "owner's code"
    }

    "reject a session-less request even if the author names the owner" in {
      val fid  = save(Some(user("github:owner")))
      val fake = Some(UserInfo("github:owner", "owner", None, loggedIn = true))
      await(api(None).update(fiddle("attacker's code", fake), fid.id)) shouldBe Left("Not allowed to update fiddle")
      latestVersion(fid.id).sourceCode shouldBe "owner's code"
    }

    "allow the owner to update their own fiddle" in {
      val owner = Some(user("github:owner"))
      val fid   = save(owner)
      await(api(owner).update(fiddle("new code", None), fid.id)) shouldBe Right(FiddleId(fid.id, fid.version + 1))
      val latest = latestVersion(fid.id)
      latest.sourceCode shouldBe "new code"
      latest.user shouldBe "github:owner"
    }

    "let anyone update an anonymous fiddle (previous behaviour)" in {
      val fid = save(None)
      await(api(Some(user("github:someone"))).update(fiddle("new code", None), fid.id)) shouldBe
        Right(FiddleId(fid.id, fid.version + 1))
      await(api(None).update(fiddle("newer code", None), fid.id)) shouldBe Right(FiddleId(fid.id, fid.version + 2))
    }
  }

  "FiddleOwnership.mayUpdate" should {
    "allow the stored owner and anonymous fiddles, and reject everyone else" in {
      FiddleOwnership.mayUpdate("github:owner", "github:owner") shouldBe true
      FiddleOwnership.mayUpdate("anonymous", "github:someone") shouldBe true
      FiddleOwnership.mayUpdate("anonymous", "anonymous") shouldBe true
      FiddleOwnership.mayUpdate("github:owner", "github:other") shouldBe false
      FiddleOwnership.mayUpdate("github:owner", "anonymous") shouldBe false
    }
  }
}
