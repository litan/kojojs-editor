package scalafiddle.client

import scala.scalajs.js

/** UI strings, delivered by the server in `ScalaFiddleConfig.messages` (from `conf/messages`).
  * `{0}`, `{1}` ... are replaced by the arguments; an unknown key is shown as is. */
object Messages {
  def apply(key: String, args: Any*): String = {
    val template = ScalaFiddleConfig.messages.getOrElse(key, key)
    args.zipWithIndex.foldLeft(template) { case (s, (arg, i)) => s.replace(s"{$i}", arg.toString) }
  }
}
