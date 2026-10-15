package scalafiddle.client.component

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.{Dynamic => Dyn}
import scalafiddle.client.JsVal

/**
 * The editing surface FiddleEditor needs from a code editor. Rows and columns are 0-based, as in Ace.
 * `setValue` resets the undo history (like Ace's `session.setValue`).
 */
trait EditorEngine {
  def getValue: String
  def setValue(text: String): Unit
  def onInput(cb: () => Unit): Unit
  def focus(): Unit
  def getCursorPosition: (Int, Int)
  def moveCursorTo(row: Int, col: Int): Unit
  def setAnnotations(items: Seq[EditorEngine.Diagnostic]): Unit
  def clearAnnotations(): Unit
  def setCompleter(f: EditorEngine.Completer): Unit
  def complete(): Unit
}

object EditorEngine {

  /** A compiler message: the same fields as an Ace annotation; `kind` is "error", "warning" or "info". */
  case class Diagnostic(row: Int, col: Int, text: String, kind: String)

  /** (row, col, prefix, callback(candidates)); candidates are the (signature, name) pairs from AutoCompleteFiddle. */
  type Completer = (Int, Int, String, Seq[(String, String)] => Unit) => Unit
}

/** Ace 1.2.4. Setup, Ctrl-Space and the completer are moved here from FiddleEditor.mounted unchanged. */
class AceEngine(el: dom.raw.HTMLElement) extends EditorEngine {
  import JsVal.jsVal2jsAny

  private var completer: Option[EditorEngine.Completer] = None

  private val Autocomplete = global.require("ace/autocomplete").Autocomplete
  private val aceCompleter = Dyn.newInstance(Autocomplete)()
  private val editor: Dyn  = global.ace.edit(el)

  editor.setTheme("ace/theme/eclipse")
  editor.getSession().setMode("ace/mode/scala")
  editor.getSession().setTabSize(2)
  editor.setShowPrintMargin(false)
  editor.getSession().setOption("useWorker", false)
  editor.updateDynamic("completer")(aceCompleter) // because of SI-7420
  editor.updateDynamic("$blockScrolling")(Double.PositiveInfinity)
  editor.setFontSize("16px") // default is 14

  {
    val binding                  = "Ctrl-Space|Cmd-Space"
    val exec: js.Function0[Unit] = () => complete()
    editor.commands.addCommand(
      JsVal.obj(
        "name" -> "Complete",
        "bindKey" -> JsVal.obj(
          "win"    -> binding,
          "mac"    -> binding,
          "sender" -> "editor|cli"
        ),
        "exec" -> exec
      ))
  }

  // register auto complete
  editor.completers = js.Array(
    JsVal
      .obj(
        "getCompletions" -> { (editor: Dyn, session: Dyn, pos: Dyn, prefix: Dyn, callback: Dyn) =>
          def applyResults(results: Seq[(String, String)]): Unit = {
          def params(signature: String): String = {
            val parts = signature.split(Array('(', ')'))
            if (parts.length > 2 && parts(0).length == 0) {
              val paramStr = parts(1)
              val params = paramStr.split(',')
              val pnames = params.map { p =>
                p.split(':')(0)
              }
              pnames.mkString("(", ", ", ")")
            }
            else {
              ""
            }
          }

          val aceVersion = results.map {
            case (name, value) =>
              val completionParams = params(name)
              JsVal
                .obj(
                  "value" -> (value + completionParams),
                  "caption" -> (value + name),
                  "completer" -> JsVal.obj(
                    "insertMatch" -> { (editor: Dyn, data: Dyn) =>
                      val text = data.value.asInstanceOf[String]
                      editor.removeWordLeft()
                      val completionStartPos = editor.getCursorPosition()
                      editor.session.insert(completionStartPos, text)
                      val completionStartCol = completionStartPos.column.asInstanceOf[Int]
                      val bracketOpenIndex = text.indexOf('(')
                      val bracketCloseIndex = text.indexOf(')')
                      val delta = if (bracketOpenIndex != -1) {
                        if (bracketCloseIndex == bracketOpenIndex + 1) bracketOpenIndex + 2 else bracketOpenIndex + 1
                      }
                      else {
                        text.length
                      }
                      editor.moveCursorTo(completionStartPos.row, completionStartCol + delta)
                      val paramsStrLen = completionParams.length
                      if (paramsStrLen != 0 && paramsStrLen != 2) {
                        // avoid no params and empty brackets
                        editor.getSelection().selectWordRight()
                      }
                    }
                  ).value
                )
                .value
          }
          callback(null, js.Array(aceVersion: _*))
          }
          completer match {
            case Some(f) =>
              f(pos.row.asInstanceOf[Int], pos.column.asInstanceOf[Int], prefix.asInstanceOf[String], applyResults)
            case None => callback(null, js.Array())
          }
        }
      )
      .value
  )

  def getValue: String             = editor.getSession().getValue().asInstanceOf[String]
  def setValue(text: String): Unit = editor.getSession().setValue(text)

  def onInput(cb: () => Unit): Unit = {
    val f: js.Function0[Unit] = () => cb()
    editor.on("input", f)
  }
  def focus(): Unit = editor.focus()

  def getCursorPosition: (Int, Int) = {
    val p = editor.getCursorPosition()
    (p.row.asInstanceOf[Int], p.column.asInstanceOf[Int])
  }
  def moveCursorTo(row: Int, col: Int): Unit = editor.moveCursorTo(row, col)

  def setAnnotations(items: Seq[EditorEngine.Diagnostic]): Unit = {
    val aceAnnotations = items.map { d =>
      JsVal.obj("row" -> d.row, "col" -> d.col, "text" -> d.text, "type" -> d.kind).value
    }.toJSArray
    editor.getSession().setAnnotations(aceAnnotations)
  }
  def clearAnnotations(): Unit = editor.getSession().clearAnnotations()

  def setCompleter(f: EditorEngine.Completer): Unit = completer = Some(f)
  def complete(): Unit = {
    editor.completer.showPopup(editor)
    // needed for firefox on mac
    editor.completer.cancelContextMenu()
  }
}
