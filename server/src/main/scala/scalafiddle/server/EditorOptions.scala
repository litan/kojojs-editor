package scalafiddle.server

import play.api.libs.json.Json

/**
  * The options the page gives to the editor engine (`ScalaFiddleConfig.editorOptions`):
  *  - `extraKeywords`: the words to highlight as keywords on top of the Scala ones, for a language. They
  *    are given only where a compiler that accepts them is on (the language has an entry in
  *    `langScalaVersions`), otherwise the editor would colour words that the compiler does not know;
  *  - `locale`: the language code for case-insensitive completion matching (where "I" lower-cases to
  *    a dotless i). Left out for English.
  */
object EditorOptions {
  def json(
      lang: String,
      langScalaVersions: Map[String, String],
      langKeywords: Map[String, Seq[String]]
  ): String = {
    val keywords = if (langScalaVersions.contains(lang)) langKeywords.getOrElse(lang, Nil) else Nil
    val fields   = Seq("extraKeywords" -> Json.toJson(keywords)) ++
      (if (lang == "en") Nil else Seq("locale" -> Json.toJson(lang)))
    // "</" cannot end the script element that the page puts this in
    Json.toJson(fields.toMap).toString.replace("</", "<\\/")
  }
}
