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
    var editor: EditorEngine             = _
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
              Messages("ui.run")
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
              Messages("ui.stopReload")
            ),
            div(cls := "ui basic button", onClick --> props.dispatch(SaveFiddle(reconstructSource(state))))(
              Icon.save,
              Messages("ui.save")).when(showSave),
            div(cls := "ui basic button", onClick --> props.dispatch(UpdateFiddle(reconstructSource(state))))(
              Icon.pencilSquare,
              Messages("ui.update")).when(showUpdate),
            div(cls := "ui basic button", onClick --> props.dispatch(ForkFiddle(reconstructSource(state))))(
              Icon.codeFork,
              Messages("ui.fork")).when(fiddleHasId)
          ),
          div(cls := "right")(
            div(cls := "ui basic button")(
              Icon.book,
              a(href := "https://docs.kogics.net/ikojo.html", target := "_blank", Messages("ui.help"))
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
                def allowFullScreen = VdomAttr("allowFullScreen")
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
                    allowFullScreen := "true",
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
                    h1(Messages("ui.myFiddles")),
                    if (fiddles.isEmpty) {
                      p(Messages("ui.noFiddles"))
                    } else {
                      table(cls := "ui celled table fiddle-list")(
                        thead(
                          tr(
                            th(Messages("ui.col.id"), width := "10%"),
                            th(Messages("ui.col.name")),
                            th(Messages("ui.col.versions")),
                            th(Messages("ui.col.latestUpdate")),
                            th(Messages("ui.col.libs"), width := "20%")
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
              a(href := "http://lth.se/programmera", target := "_blank", color := "black", "Lund University")
            ),
            div(cls := "")(
              "\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0"
            ),
            div(cls := "ui teal basic button")(
              Icon.download,
              a(href := "https://www.kogics.net/kojo-download", target := "_blank", "Download Desktop Kojo")
            ),
            div(cls := "ui teal basic button")(
              Icon.compass,
              a(href := "https://docs.kogics.net/contribute-code.html", target := "_blank", "Contribute Code")
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
      val editorContent = editor.getValue
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
      Callback {
        EditorEngine.create(editorRef) { engine =>
          editor = engine

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

          // register auto complete
          editor.setCompleter { (row0, col0, prefix, callback) =>
            val (row, col) = coordinatesFrom(row0, col0)
            // build full source
            buildFullSource
              .flatMap { source =>
                // dispatch an action to fetch completion results
                props.dispatch(AutoCompleteFiddle(source, row, col, callback))
              }
              .runNow()
          }

          // listen for changes in source code
          editor.onInput(
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

          // updateFiddle writes to the engine, so it cannot run before the engine is ready: it is here
          // and not in mounted's `>>` chain.
          (props.dispatch(UpdateLoginInfo) >>
            updateFiddle(props.data()) >>
            Callback.when(props.fiddleId.isDefined)(
              props.dispatch(
                compile(addDeps(props.data().sourceCode, props.data().libraries, props.data().scalaVersion), FastOpt)))).runNow()
        }
      }
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
          editor.setValue((pre ++ main ++ post).mkString("\n"))
          $.setState(state.copy(preCode = pre, mainCode = main, postCode = post, indent = 0))
        } else {
          // figure out indentation
          val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
          editor.setValue(main.map(_.drop(indent)).mkString("\n"))
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
        val (row, col)        = editor.getCursorPosition
        val source            = reconstructSource(s)
        val (pre, main, post) = extractCode(source)
        if (!s.showTemplate) {
          editor.setValue((pre ++ main ++ post).mkString("\n"))
          editor.moveCursorTo(row + pre.size, col + s.indent)
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = 0, showTemplate = !s.showTemplate)
        } else {
          // figure out indentation
          val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length).min
          editor.setValue(main.map(_.drop(indent)).mkString("\n"))
          editor.moveCursorTo(math.max(0, row - pre.size), math.max(0, col - indent))
          s.copy(preCode = pre, mainCode = main, postCode = post, indent = indent, showTemplate = !s.showTemplate)
        }
      }
    }

    def clearResult() = sendFrameCmd("clear")

    def outputDataUpdated(data: ModelRO[OutputData]): Unit = {
      data() match {
        case compilerData: CompilerData =>
          clearResult()

          // show error messages, if any
          editor.clearAnnotations()
          if (compilerData.annotations.nonEmpty) {
            val annotations = compilerData.annotations.map { ann =>
              // adjust coordinates
              val (row, col) = coordinatesTo(ann.row, ann.col)
              EditorEngine.Diagnostic(row, col, ann.text.mkString("\n"), ann.tpe)
            }
            editor.setAnnotations(annotations)

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
