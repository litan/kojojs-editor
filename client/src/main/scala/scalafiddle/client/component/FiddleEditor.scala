package scalafiddle.client.component

import diode.data.Ready
import diode.react.ModelProxy
import diode.{Action, ModelR, ModelRO}
import japgolly.scalajs.react._
import japgolly.scalajs.react.raw.SyntheticEvent
import org.scalajs.dom
import org.scalajs.dom.raw.{Event, HTMLIFrameElement, MessageEvent}

import scala.concurrent.ExecutionContext.Implicits.{global => ecGlobal}
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Dynamic._
import scala.scalajs.js.{Dynamic => Dyn}
import scala.util.{Random, Success}
import scalafiddle.client._
import scalafiddle.shared._

object FiddleEditor {
  import japgolly.scalajs.react.vdom.Implicits._

  private var editorRef: dom.raw.HTMLDivElement    = _
  private var resultRef: dom.raw.HTMLIFrameElement = _

  case class EditorBinding(name: String, keys: String, action: () => Any)

  case class Props(data: ModelProxy[FiddleData],
                   fiddleId: Option[FiddleId],
                   outputData: ModelR[AppModel, OutputData],
                   loginData: ModelR[AppModel, LoginData]) {
    def dispatch(a: Action) = data.dispatchCB(a)
  }

  case class State(
      outputData: OutputData,
      status: CompilerStatus,
      showTemplate: Boolean = false,
      preCode: List[String] = Nil,
      mainCode: List[String] = Nil,
      postCode: List[String] = Nil,
      indent: Int = 0
  )

  case class Backend($ : BackendScope[Props, State]) {
    var unsubscribe: () => Unit          = () => ()
    var unsubscribeLoginData: () => Unit = () => ()
    var editor: Dyn                      = _
    def resultFrame                      = dom.document.getElementById("resultframe").asInstanceOf[HTMLIFrameElement]
    @volatile var frameReady: Boolean    = false
    var pendingMessages: List[js.Object] = Nil
    var prevFrame                        = ""

    def render(props: Props, state: State) = {
      import japgolly.scalajs.react.vdom.all._

      def showSave    = props.fiddleId.isEmpty
      def fiddleHasId = props.fiddleId.nonEmpty

      def showUpdate: Boolean = {
        if (!fiddleHasId)
          false
        else if (props.data().author.isEmpty)
          true
        else {
          props.loginData().userInfo match {
            case Ready(userInfo) =>
              userInfo.id == props.data().author.get.id
            case _ =>
              false
          }
        }
      }

      div(cls := "full-screen")(
        header(
          div(cls := "left")(
            div(cls := "logo")(
              a(href := "/")(
                img(src := "/assets/images/scalafiddle-logo.png", alt := "ScalaFiddle")
              )
            ),
            div(
              cls := "ui basic button",
              onClick --> {
                Callback.future(beginCompilation().map(_ => {
                  buildFullSource.flatMap { source =>
                    props.dispatch(compile(source, FastOpt))
                  }
                }))
              },
              Icon.play,
              "Run"
            ),
            div(
              cls := "ui basic button",
              onClick --> {
                Callback.future(beginCompilation().map(_ => {
                  buildStopReloadSource.flatMap { source =>
                    props.dispatch(compile(source, FastOpt))
                  }
                }))
              },
              Icon.stop,
              "Stop/Reload"
            ),
            div(cls := "ui basic button", onClick --> props.dispatch(SaveFiddle(reconstructSource(state))))(
              Icon.save,
              "Save").when(showSave),
            div(cls := "ui basic button", onClick --> props.dispatch(UpdateFiddle(reconstructSource(state))))(
              Icon.pencilSquare,
              "Update").when(showUpdate),
            div(cls := "ui basic button", onClick --> props.dispatch(ForkFiddle(reconstructSource(state))))(
              Icon.codeFork,
              "Fork").when(fiddleHasId)
          ),
          div(cls := "right")(
            div(cls := "ui basic button")(
              Icon.book,
              a(href := "http://docs.kogics.net", target := "_blank", "Help")
            ),
            UserLogin(props.loginData)
          )
        ),
        div(cls := "main")(
          Sidebar(props.data),
          div(cls := "editor-area")(
            div(cls := "editor")(
              div.ref(editorRef = _)(id := "editor")
            ),
            state.outputData match {
              case data: CompilerData =>
                div(cls := "output")(
                  div(cls := "label", state.status.show),
                  iframe.ref(resultRef = _)(
                    id := "resultframe",
                    onLoad ==> frameLoaded,
                    VdomAttr[String]("data-frameid") := "frame-code",
                    width := "100%",
                    height := "100%",
                    frameBorder := "0",
                    scrolling := "no",
                    overflow := "hidden",
                    sandbox := "allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox allow-modals",
                    src := s"/resultframe?theme=light"
                  )
                )
              case UserFiddleData(fiddles) =>
                val libMap = props
                  .data()
                  .available
                  .map { lib =>
                    Library.stringify(lib) -> lib.name
                  }
                  .toMap
                div(cls := "output")(
                  div(id := "output")(
                    h1("My fiddles"),
                    if (fiddles.isEmpty) {
                      p("No fiddles stored")
                    } else {
                      table(cls := "ui celled table fiddle-list")(
                        thead(
                          tr(
                            th("Id", width := "10%"),
                            th("Name"),
                            th("Versions"),
                            th("Latest update"),
                            th("Libs", width := "20%")
                          )
                        ),
                        tbody(
                          fiddles.toTagMod {
                            fiddle =>
                              def fLink(version: Int)(content: TagMod*) =
                                a(href := s"/sf/${fiddle.id}/$version")(content: _*)
                              val versions = (0 to fiddle.latestVersion)
                                .flatMap(v => Seq[TagMod](fLink(v)(v.toString), span(" | ")))
                                .init
                                .toTagMod

                              tr(
                                td(fLink(fiddle.latestVersion)(fiddle.id)),
                                td(fLink(fiddle.latestVersion)(if (fiddle.name.isEmpty) "<no name>" else fiddle.name)),
                                td(versions),
                                td(new js.Date(fiddle.updated).toLocaleString()),
                                td(fiddle.libraries.flatMap(libMap.get).mkString(", "))
                              )
                          }
                        )
                      )
                    }
                  ),
                  iframe.ref(resultRef = _)(
                    id := "resultframe",
                    onLoad ==> frameLoaded,
                    VdomAttr[String]("data-frameid") := "frame-uer",
                    width := "0%",
                    height := "0%",
                    frameBorder := "0",
                    sandbox := "allow-scripts allow-popups allow-popups-to-escape-sandbox",
                    src := s"/resultframe?theme=light"
                  )
                )
              case ScalaFiddleHelp(url) =>
                div(cls := "output")(
                  iframe.ref(resultRef = _)(
                    id := "resultframe",
                    onLoad ==> frameLoaded,
                    VdomAttr[String]("data-frameid") := "frame-help",
                    width := "100%",
                    height := "100%",
                    frameBorder := "0",
                    sandbox := "allow-scripts allow-popups allow-popups-to-escape-sandbox allow-same-origin allow-top-navigation",
                    src := url
                  )
                )
            }
          )
        ),
        footer(
          div(cls := "right")(
            div(cls := "ui blue basic label")(
              "Powered by ",
              a(href := "http://www.kogics.net/kojo", target := "_blank", color := "black", "Kojo"),
              " and ",
              a(href := "https://github.com/scalafiddle", target := "_blank", color := "black", "ScalaFiddle")
            ),
            div(cls := "ui orange basic label")(
              "Hosted at ",
              a(href := "http://www.lund.se", target := "_blank", color := "black", "Lund University")
            )
          )
        )
      )
    }

    def frameLoaded(e: SyntheticEvent[HTMLIFrameElement]): Callback = Callback {
      val frame = e.target
      // println("Frame loaded")
      frameReady = true
      // send pending messages
      pendingMessages.reverse.foreach(msg => frame.contentWindow.postMessage(msg, "*"))
      pendingMessages = Nil
    }

    def updated: Callback = {
      Callback {
        val frame   = resultFrame
        val frameId = frame.getAttribute("data-frameid")
        if (frameId != prevFrame) {
          frameReady = false
          prevFrame = frameId
        }
      }
    }

    def sendFrameCmd(cmd: String, data: String = "") = {
      val msg = js.Dynamic.literal(cmd = cmd, data = data)
      if (frameReady) {
        resultFrame.contentWindow.postMessage(msg, "*")
      } else {
        // println(s"Buffering a frame command: $cmd")
        pendingMessages = msg :: pendingMessages
      }
    }

    def complete(): Unit = {
      editor.completer.showPopup(editor)
      // needed for firefox on mac
      editor.completer.cancelContextMenu()
    }

    def beginCompilation(): Future[Unit] = {
      // fully clear the code iframe by reloading it
      val p = Promise[Unit]
      lazy val handler: js.Function1[Event, Unit] = (e: Event) => {
        p.complete(Success(()))
        resultFrame.removeEventListener("load", handler)
      }
      resultFrame.addEventListener("load", handler)
      resultFrame.src = resultFrame.src
      p.future
    }

    def compile(source: String, opt: SJSOptimization): Action = {
      CompileFiddle(source, opt)
    }

    def reconstructSource(state: State): String = {
      val editorContent = editor.getSession().getValue().asInstanceOf[String]
      val source = if (state.showTemplate) {
        editorContent
      } else {
        val reIndent  = " " * state.indent
        val newSource = editorContent.split("\n").map(reIndent + _)
        (state.preCode ++ newSource ++ state.postCode).mkString("\n")
      }
      //println(s"Reconstructed:\n$source")
      source
    }

    def reconstructStopReloadSource(state: State): String = {
      val editorContent = ""
      val source = if (state.showTemplate) {
        editorContent
      } else {
        val reIndent  = " " * state.indent
        val newSource = editorContent.split("\n").map(reIndent + _)
        (state.preCode ++ newSource ++ state.postCode).mkString("\n")
      }
      //println(s"Reconstructed:\n$source")
      source
    }

    def addDeps(source: String, deps: Seq[Library], scalaVersion: String): String = {
      val extraDeps = deps.flatMap(_.extraDeps)
      val allDeps   = extraDeps ++ deps.map(Library.stringify)
      source + "\n" +
        allDeps.map(dep => s"// $$FiddleDependency $dep\n").mkString +
        s"// $$ScalaVersion $scalaVersion\n"
    }

    def buildFullSource: CallbackTo[String] = {
      for {
        props <- $.props
        state <- $.state
      } yield {
        val source = reconstructSource(state)
        addDeps(source, props.data().libraries, props.data().scalaVersion)
      }
    }

    def buildStopReloadSource: CallbackTo[String] = {
      for {
        props <- $.props
        state <- $.state
      } yield {
        val source = reconstructStopReloadSource(state)
        addDeps(source, props.data().libraries, props.data().scalaVersion)
      }
    }

    def coordinatesFrom(row: Int, col: Int): (Int, Int) = {
      val state = $.state.runNow()
      if (!state.showTemplate) {
        (row + state.preCode.size, col + state.indent)
      } else
        (row, col)
    }

    def coordinatesTo(row: Int, col: Int): (Int, Int) = {
      val state = $.state.runNow()
      if (!state.showTemplate) {
        (row - state.preCode.size, col - state.indent)
      } else
        (row, col)
    }

    def showJSCode(state: State): Unit = {
      state.outputData match {
        case compilerData: CompilerData =>
          compilerData.jsCode.foreach { jsCode =>
            sendFrameCmd("showCode", jsCode)
          }
        case _ =>
      }
    }

    def mounted(props: Props): Callback = {
      import JsVal.jsVal2jsAny

      Callback {
        // create the Ace editor and configure it
        val Autocomplete = global.require("ace/autocomplete").Autocomplete
        val completer    = Dyn.newInstance(Autocomplete)()
        editor = global.ace.edit(editorRef)

        editor.setTheme("ace/theme/eclipse")
        editor.getSession().setMode("ace/mode/scala")
        editor.getSession().setTabSize(2)
        editor.setShowPrintMargin(false)
        editor.getSession().setOption("useWorker", false)
        editor.updateDynamic("completer")(completer) // because of SI-7420
        editor.updateDynamic("$blockScrolling")(Double.PositiveInfinity)
        editor.setFontSize("16px") // default is 14

        val globalBindings = Seq(
          EditorBinding("Compile",
                        "enter",
                        () =>
                          beginCompilation().foreach(_ => {
                            buildFullSource
                              .flatMap { source =>
                                props.dispatch(compile(source, FastOpt))
                              }
                              .runNow()
                          })),
          EditorBinding("FullOptimize",
                        "shift+enter",
                        () =>
                          beginCompilation().foreach(_ => {
                            buildFullSource
                              .flatMap { source =>
                                props.dispatch(compile(source, FullOpt))
                              }
                              .runNow()
                          })),
          EditorBinding("Show JavaScript", "j", () => $.state.map(state => showJSCode(state)).runNow()),
          EditorBinding(
            "Save",
            "s",
            () =>
              $.state
                .flatMap { state =>
                  // select between save/update/fork
                  val action =
                    if (props.fiddleId.isEmpty)
                      SaveFiddle(reconstructSource(state))
                    else if (props
                               .data()
                               .author
                               .isEmpty || props.loginData().userInfo.exists(_.id == props.data().author.get.id))
                      UpdateFiddle(reconstructSource(state))
                    else
                      ForkFiddle(reconstructSource(state))
                  props.dispatch(action)
                }
                .runNow()
          )
        )
        for (EditorBinding(name, key, func) <- globalBindings) {
          val binding = js.Array(s"ctrl+$key", s"command+$key")
          Mousetrap.bindGlobal(binding, (e: dom.KeyboardEvent) => { func(); false })
        }

        val editorBindings = Seq(
          EditorBinding("Complete", "Space", () => complete())
        )
        for (EditorBinding(name, key, func) <- editorBindings) {
          val binding = s"Ctrl-$key|Cmd-$key"
          editor.commands.addCommand(
            JsVal.obj(
              "name" -> name,
              "bindKey" -> JsVal.obj(
                "win"    -> binding,
                "mac"    -> binding,
                "sender" -> "editor|cli"
              ),
              "exec" -> func
            ))
        }

        // register auto complete
        editor.completers = js.Array(
          JsVal
          .obj(
            "getCompletions" -> { (editor: Dyn, session: Dyn, pos: Dyn, prefix: Dyn, callback: Dyn) =>
              {
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
                val (row, col) = coordinatesFrom(pos.row.asInstanceOf[Int], pos.column.asInstanceOf[Int])
                // build full source
                buildFullSource
                  .flatMap { source =>
                    // dispatch an action to fetch completion results
                    props.dispatch(AutoCompleteFiddle(source, row, col, applyResults))
                  }
                  .runNow()
              }
            }
          )
          .value
        )

        // listen for changes in source code
        editor.on("input",
                  () =>
                    $.state
                      .flatMap { state =>
                        props.dispatch(UpdateSource(reconstructSource(state)))
                      }
                      .runNow())
        // focus to the editor
        editor.focus()

        // listen to messages from the iframe
        dom.window.addEventListener("message", (e: MessageEvent) => {
          e.data match {
            case "evalCompleted" =>
              $.modState(s => s.copy(status = CompilerStatus.Result)).runNow()
            case _ =>
          }
        })

        // subscribe to changes in compiler data
        unsubscribe = AppCircuit.subscribe(props.outputData)(outputDataUpdated)
        unsubscribeLoginData = AppCircuit.subscribe(props.loginData)(_ => $.forceUpdate.runNow())
      } >>
        props.dispatch(UpdateLoginInfo) >>
        updateFiddle(props.data()) >>
        Callback.when(props.fiddleId.isDefined)(
          props.dispatch(
            compile(addDeps(props.data().sourceCode, props.data().libraries, props.data().scalaVersion), FastOpt)))
    }

    val fiddleStart = """\s*// \$FiddleStart\s*$""".r
    val fiddleEnd   = """\s*// \$FiddleEnd\s*$""".r

    // separate source code into pre,main,post blocks
    def extractCode(src: String): (List[String], List[String], List[String]) = {
      val lines = src.split("\n")
      val (pre, main, post) = lines.foldLeft((List.empty[String], List.empty[String], List.empty[String])) {
        case ((preList, mainList, postList), line) =>
          line match {
            case fiddleStart() =>
              (line :: mainList ::: preList, Nil, Nil)
            case fiddleEnd() if preList.nonEmpty =>
              (preList, mainList, line :: postList)
            case l if postList.nonEmpty =>
              (preList, mainList, line :: postList)
            case _ =>
              (preList, line :: mainList, postList)
          }
      }
      (pre.reverse, main.reverse, post.reverse)
    }

    def updateFiddle(fiddle: FiddleData): Callback = {
      val (pre, main, post) = extractCode(fiddle.sourceCode)
      $.state.flatMap { state =>
        if (state.showTemplate) {
          editor.getSession().setValue((pre ++ main ++ post).mkString("\n"))
          $.setState(state.copy(preCode = pre, mainCode = main, postCode = post, indent = 0))
        } else {
          // figure out indentation
          val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
          editor.getSession().setValue(main.map(_.drop(indent)).mkString("\n"))
          $.setState(state.copy(preCode = pre, mainCode = main, postCode = post, indent = indent))
        }
      }
    }

    def unmounted: Callback = {
      Callback {
        Mousetrap.reset()
        unsubscribe()
        unsubscribeLoginData()
      }
    }

    def switchTemplate: Callback = {
      $.modState { s =>
        val row               = editor.getCursorPosition().row.asInstanceOf[Int]
        val col               = editor.getCursorPosition().column.asInstanceOf[Int]
        val source            = reconstructSource(s)
        val (pre, main, post) = extractCode(source)
        if (!s.showTemplate) {
          editor.getSession().setValue((pre ++ main ++ post).mkString("\n"))
          editor.moveCursorTo(row + pre.size, col + s.indent)
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = 0, showTemplate = !s.showTemplate)
        } else {
          // figure out indentation
          val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
          editor.getSession().setValue(main.map(_.drop(indent)).mkString("\n"))
          editor.moveCursorTo(math.max(0, row - pre.size), math.max(0, col - indent))
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = indent, showTemplate = !s.showTemplate)
        }
      }
    }

    def clearResult() = sendFrameCmd("clear")

    def outputDataUpdated(data: ModelRO[OutputData]): Unit = {
      data() match {
        case compilerData: CompilerData =>
          import scala.scalajs.js.JSConverters._
          clearResult()

          // show error messages, if any
          editor.getSession().clearAnnotations()
          if (compilerData.annotations.nonEmpty) {
            val aceAnnotations = compilerData.annotations.map { ann =>
              // adjust coordinates
              val (row, col) = coordinatesTo(ann.row, ann.col)
              JsVal
                .obj(
                  "row"  -> row,
                  "col"  -> col,
                  "text" -> ann.text.mkString("\n"),
                  "type" -> ann.tpe
                )
                .value
            }.toJSArray
            editor.getSession().setAnnotations(aceAnnotations)

            // show compiler errors in output
            val allErrors = compilerData.annotations
              .map { ann =>
                // adjust coordinates
                val (row, _) = coordinatesTo(ann.row, ann.col)
                s"ScalaFiddle.scala:${row + 1}: ${ann.tpe}: ${ann.text.mkString("\n")}"
              }
              .mkString("\n")

            // update after a short delay, to allow DOM to update in case the code is slow to complete
            js.timers.setTimeout(50) {
              sendFrameCmd("print", s"""<pre class="error">$allErrors</pre>""")
            }

          }

          compilerData.errorMessage.foreach { error =>
            js.timers.setTimeout(50) {
              sendFrameCmd("print", s"""<pre class="error">$error</pre>""")
            }
          }

          compilerData.jsCode.foreach { jsCode =>
            $.modState(s => s.copy(status = CompilerStatus.Running)).runNow()
            // start running the code after a short delay, to allow DOM to update in case the code is slow to complete
            js.timers.setTimeout(50) {
              sendFrameCmd("code", jsCode)
            }
          }
          $.modState(s => s.copy(outputData = compilerData, status = compilerData.status)).runNow()

        case fiddles: UserFiddleData =>
          $.modState(s => s.copy(outputData = fiddles)).runNow()

        case help: ScalaFiddleHelp =>
          $.modState(s => s.copy(outputData = help)).runNow()
      }
    }
  }

  val component = ScalaComponent
    .builder[Props]("FiddleEditor")
    .initialStateFromProps(props => State(props.outputData(), CompilerStatus.Result))
    .renderBackend[Backend]
    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .componentWillUnmount(scope => scope.backend.unmounted)
    .componentDidUpdate(scope => scope.backend.updated)
    .build

  def apply(data: ModelProxy[FiddleData],
            fiddleId: Option[FiddleId],
            compilerData: ModelR[AppModel, OutputData],
            loginData: ModelR[AppModel, LoginData]) =
    component(Props(data, fiddleId, compilerData, loginData))
}
