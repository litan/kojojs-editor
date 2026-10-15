// A CodeMirror 6 editor engine for the fiddle editor: it reduces the surface that the editor
// (FiddleEditor.scala) uses from Ace to one small facade, `window.EditorEngine`. Nothing of
// CodeMirror's own API leaves this file; the Scala side only calls the names returned by `create`.
//
// create(el, options) -> engine
//   options.wrap          wrap long lines (default true; CodeMirror's usual behaviour, Ace does not wrap)
//   options.indent        indent unit in spaces, an integer 1-8 (default 2)
//   options.extraKeywords words to highlight as keywords on top of the Scala ones (default none)
//   options.locale        locale for case-insensitive completion matching, e.g. "tr" (default: none,
//                         plain toLowerCase). Needed where "I" lower-cases to "\u0131", not "i".
import {EditorView, keymap, lineNumbers, drawSelection, highlightActiveLine, Decoration, ViewPlugin, MatchDecorator} from "@codemirror/view";
import {EditorState, Compartment, Prec, countColumn} from "@codemirror/state";
import {defaultKeymap, history, historyKeymap, indentWithTab, undo} from "@codemirror/commands";
import {StreamLanguage, syntaxHighlighting, defaultHighlightStyle, bracketMatching, matchBrackets, indentOnInput, indentUnit, getIndentUnit, indentService, syntaxTree} from "@codemirror/language";
import {scala} from "@codemirror/legacy-modes/mode/clike";
import {autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap, startCompletion} from "@codemirror/autocomplete";
import {setDiagnostics, lintGutter} from "@codemirror/lint";

// Characters that can be part of an identifier (Unicode aware).
const wordChar = "[\\p{L}\\p{N}_$]";

// Extra keywords: the Scala stream mode takes them for plain names, so they get a decoration on top.
function extraKeywordPlugin(words) {
  const escaped = words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  const matcher = new MatchDecorator({
    regexp: new RegExp("(?<!" + wordChar + ")(" + escaped.join("|") + ")(?!" + wordChar + ")", "gu"),
    decoration: Decoration.mark({class: "extra-keyword"})
  });
  return ViewPlugin.fromClass(class {
    constructor(v) { this.d = matcher.createDeco(v); }
    update(u) { this.d = matcher.updateDeco(u, this.d); }
  }, {decorations: v => v.d});
}

// The same as the Ace completer's insertMatch (FiddleEditor.scala): take the parameter names out of
// the signature. "(n: Int, color: Color): Unit" -> "(n, color)"; a signature without parentheses -> "".
function paramList(signature) {
  const parts = signature.split(/[()]/);
  if (parts.length > 2 && parts[0].length === 0) {
    return "(" + parts[1].split(",").map(p => p.split(":")[0].trim()).join(", ") + ")";
  }
  return "";
}

const theme = EditorView.theme({
  "&": {height: "100%", fontSize: "16px", backgroundColor: "#ffffff", color: "#000000"},
  ".cm-scroller": {fontFamily: "Monaco, Menlo, 'Ubuntu Mono', Consolas, source-code-pro, monospace", overflow: "auto"},
  ".cm-content": {caretColor: "#000000"},
  ".cm-gutters": {backgroundColor: "#f3f3f3", color: "#888888", border: "none"},
  ".cm-activeLineGutter": {backgroundColor: "#e8f2fe"},
  ".cm-activeLine": {backgroundColor: "#e8f2fe55"},
  ".extra-keyword": {color: "#7f0055", fontWeight: "bold"}
});

// Indentation of a new line, like Ace: copy the previous line's; add one unit when that line ends in
// `{`, `(`, `[` or `case ...:`; a line that starts with `}` `)` `]` lines up with the line of its opener.
// CodeMirror's default (the Scala stream mode) COMPUTES indentation as nesting depth x unit and ignores
// the file's real indentation: in a script indented by 4, Enter went to 6 instead of 8 (measured).
// Prec.high: asked before the language's own indent service.
const openerLine = /^.*(?:\bcase\b.*:|[{(\[])\s*$/;
const indentColumn = (state, text) => countColumn(text, state.tabSize, text.search(/\S|$/));
const aceIndent = Prec.high(indentService.of((cx, pos) => {
  const state = cx.state, doc = state.doc;
  const fresh = cx.lineAt(pos, 1);
  const closer = /^\s*[}\])]/.exec(fresh.text);
  if (closer) {
    const match = matchBrackets(state, fresh.from + closer[0].length, -1);
    // matchBrackets: start = the closer we asked about, end = its opener
    if (match && match.matched && match.end) return indentColumn(state, doc.lineAt(match.end.from).text);
  }
  let before;
  const brk = cx.options.simulateBreak;
  if (brk != null) {
    // Enter: the text before the break. cx.lineAt returns an empty line for a double break ({|}).
    const line = doc.lineAt(brk);
    before = {text: line.text.slice(0, brk - line.from), from: line.from};
  } else {
    const no = doc.lineAt(pos).number;
    if (no === 1) return 0;
    before = doc.line(no - 1);
  }
  let indent = indentColumn(state, before.text);
  if (openerLine.test(before.text)) {
    const end = before.from + before.text.replace(/\s+$/, "").length;
    if (!/comment/i.test(syntaxTree(state).resolveInner(end, -1).name)) indent += getIndentUnit(state);
  }
  return indent;
}));

export function create(el, options) {
  let inputCb = null;
  let completer = null;
  const wrapCompartment = new Compartment();
  const wrap = !options || options.wrap !== false;
  const i = options && options.indent;
  const indent = Number.isInteger(i) && i >= 1 && i <= 8 ? i : 2;
  const extraKeywords = (options && options.extraKeywords) || [];
  const locale = options && options.locale;
  const fold = (s) => (locale ? s.toLocaleLowerCase(locale) : s.toLowerCase());

  // The completion source: candidates come from Scala through setCompleter; filtering and insertion are here.
  const source = (ctx) => {
    if (!completer) return null;
    const word = ctx.matchBefore(new RegExp(wordChar + "*", "u"));
    if (!word) return null;
    if (word.from === word.to && !ctx.explicit) return null;
    const line = ctx.state.doc.lineAt(ctx.pos);
    const prefix = word.text;
    return new Promise((resolve) => {
      completer(line.number - 1, ctx.pos - line.from, prefix, (candidates) => {
        const p = fold(prefix);
        const items = [];
        for (const c of candidates || []) {
          const name = String(c.value), signature = String(c.name || "");
          if (!fold(name).startsWith(p)) continue;
          const params = paramList(signature);
          items.push({
            label: name, detail: signature, type: "function",
            apply: (view, _completion, from, to) => {
              const text = name + params;
              const open = text.indexOf("(");
              let anchor, head;
              if (open === -1) { anchor = head = from + text.length; }
              else if (text[open + 1] === ")") { anchor = head = from + open + 2; }
              else {
                // the first parameter name is selected (Ace: selectWordRight)
                const first = text.slice(open + 1).match(new RegExp("^" + wordChar + "*", "u"))[0];
                anchor = from + open + 1; head = anchor + first.length;
              }
              view.dispatch({changes: {from, to, insert: text}, selection: {anchor, head}, userEvent: "input.complete"});
            }
          });
        }
        resolve(items.length ? {from: word.from, options: items, filter: false} : null);
      });
    });
  };

  // defaultKeymap binds "Mod-Enter" (Ctrl-Enter on Windows/Linux, Cmd-Enter on macOS) to insertBlankLine.
  // FiddleEditor.scala already binds the same key GLOBALLY (Mousetrap.bindGlobal) to Compile; unfiltered,
  // both fired: Ctrl+Enter ran the script and also inserted a blank line. What Mod-Enter does is for the
  // host to decide; the engine stays out of it.
  const baseKeys = defaultKeymap.filter((b) => b.key !== "Mod-Enter");
  const extensions = [
    lineNumbers(), lintGutter(), history(), drawSelection(), highlightActiveLine(),
    indentUnit.of(" ".repeat(indent)), aceIndent, EditorState.languageData.of(() => [{indentOnInput: /^\s*\}$/}]), indentOnInput(), bracketMatching(), closeBrackets(),
    wrapCompartment.of(wrap ? EditorView.lineWrapping : []),
    StreamLanguage.define(scala), syntaxHighlighting(defaultHighlightStyle),
    ...(extraKeywords.length ? [extraKeywordPlugin(extraKeywords)] : []),
    autocompletion({override: [source], activateOnTyping: true}),
    EditorView.updateListener.of((u) => { if (u.docChanged && inputCb) inputCb(); }),
    theme,
    keymap.of([...closeBracketsKeymap, ...baseKeys, ...historyKeymap, ...completionKeymap, indentWithTab])
  ];
  const view = new EditorView({parent: el, state: EditorState.create({doc: "", extensions})});

  function position(row, column) {
    const doc = view.state.doc;
    const line = doc.line(Math.max(1, Math.min(doc.lines, row + 1)));
    return Math.min(line.from + Math.max(0, column), line.to);
  }

  return {
    getValue: () => view.state.doc.toString(),
    // Like Ace's session.setValue: the undo history is reset (template toggle, loading a script).
    setValue: (text) => { view.setState(EditorState.create({doc: String(text), extensions})); },
    onInput: (cb) => { inputCb = cb; },
    focus: () => view.focus(),
    getCursorPosition: () => {
      const head = view.state.selection.main.head, line = view.state.doc.lineAt(head);
      return {row: line.number - 1, column: head - line.from};
    },
    moveCursorTo: (row, column) => { const p = position(row, column); view.dispatch({selection: {anchor: p}, scrollIntoView: true}); },
    // Ace: [{row, col, text, type}] (0-based). The diagnostic range runs from the column to the end of the line, at least one character.
    setAnnotations: (list) => {
      const doc = view.state.doc;
      const diagnostics = (list || []).map((a) => {
        const line = doc.line(Math.max(1, Math.min(doc.lines, (a.row | 0) + 1)));
        const from = Math.min(line.from + Math.max(0, a.col | 0), line.to);
        const to = from < line.to ? line.to : Math.min(from + 1, doc.length);
        const severity = a.type === "error" ? "error" : a.type === "warning" ? "warning" : "info";
        return {from, to: Math.max(from, to), severity, message: String(a.text || "")};
      });
      view.dispatch(setDiagnostics(view.state, diagnostics));
    },
    clearAnnotations: () => { view.dispatch(setDiagnostics(view.state, [])); },
    // Scala: engine.setCompleter((row, col, prefix, callback) => fetch candidates from the server; callback([{name, value}]))
    // `name` is the signature, `value` the name to insert.
    setCompleter: (f) => { completer = f; },
    complete: () => startCompletion(view),
    undo: () => undo(view),
    // for tests and debugging; the Scala side does not use it
    _view: view
  };
}
