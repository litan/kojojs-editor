package scalafiddle.client

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scalafiddle.client.component.EditorEngine

@JSExportTopLevel("AppMain")
object AppMain extends js.JSApp {
  @JSExport
  def main(): Unit = {
    // must come first: the router redirects the address, which drops the ?engine= query
    EditorEngine.readFlags()
    AppRouter.router().renderIntoDOM(dom.document.getElementById("root"))
  }
}
