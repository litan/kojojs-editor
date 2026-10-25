package scalafiddle.server

import org.scalatest._
import play.api.libs.json.{JsArray, Json}

class EditorOptionsSpec extends WordSpec with Matchers {
  private val keywords = Map("tr" -> Seq("dez", "tanım"))

  private def parse(s: String) = Json.parse(s)

  "EditorOptions.json" should {
    "give the keywords and the locale of a language whose keyword compiler is on" in {
      val o = parse(EditorOptions.json("tr", Map("tr" -> "2.13-tr"), keywords))
      (o \ "extraKeywords").as[Seq[String]] shouldBe Seq("dez", "tanım")
      (o \ "locale").as[String] shouldBe "tr"
    }
    "give no keywords while the keyword compiler is off, but still the locale" in {
      val o = parse(EditorOptions.json("tr", Map.empty, keywords))
      (o \ "extraKeywords").as[JsArray].value shouldBe empty
      (o \ "locale").as[String] shouldBe "tr"
    }
    "leave the locale out for English, with no keywords" in {
      val o = parse(EditorOptions.json("en", Map("tr" -> "2.13-tr"), keywords))
      (o \ "extraKeywords").as[JsArray].value shouldBe empty
      (o \ "locale").toOption shouldBe None
    }
    "give no keywords for a language without a list" in {
      val o = parse(EditorOptions.json("sv", Map("sv" -> "2.13-sv"), keywords))
      (o \ "extraKeywords").as[JsArray].value shouldBe empty
    }
    "not let a keyword end the script element of the page" in {
      EditorOptions.json("tr", Map("tr" -> "x"), Map("tr" -> Seq("</script>"))) should not include "</"
    }
  }
}
