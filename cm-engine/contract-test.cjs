#!/usr/bin/env node
// Contract test of window.EditorEngine in headless Chrome: the facade that FiddleEditor.scala will call.
// Run: CHROME_BIN=/path/to/chrome node contract-test.cjs   (CI: google-chrome)
// Source stays ASCII: the non-ASCII letters the completion test needs are written as \u escapes.
const {chromium} = require("playwright-core");
const fs = require("fs"), path = require("path");
const BUNDLE = path.join(__dirname, "..", "server", "src", "main", "assets", "javascript", "cm-engine.js");
const SAMPLE = fs.readFileSync(path.join(__dirname, "sample.kojo"), "utf8");
const EXTRA = ["repeat", "repeatWhile"];
// Letters of Turkish: dotless i (\u0131), dotted capital I (\u0130), g-breve (\u011f), s-cedilla (\u015f)
const CANDIDATES = [
  {name: "(n: Double): Unit", value: "ileri"}, {name: "(n: Double): Unit", value: "\u0130leri"},
  {name: "(): Unit", value: "i\u011fne"}, {name: "(): Unit", value: "I\u015f\u0131k"},
  {name: "(): Unit", value: "\u0131\u015f\u0131k"}, {name: ": Double", value: "\u0131s\u0131"},
  {name: "(x: Double, y: Double): Unit", value: "ilerleGit"}
];
let count = 0, failed = 0;
function check(name, ok, detail) { count++; if (ok) console.log("  ok  " + name); else { failed++; console.log("  FAILED  " + name + (detail !== undefined ? "  -> " + JSON.stringify(detail) : "")); } }
const same = (a, b) => JSON.stringify(a.slice().sort()) === JSON.stringify(b.slice().sort());

(async () => {
  if (!process.env.CHROME_BIN) { console.error("Set CHROME_BIN to a Chrome/Chromium executable."); process.exit(2); }
  const b = await chromium.launch({executablePath: process.env.CHROME_BIN, args: ["--no-sandbox"]});
  const c = await b.newContext({viewport: {width: 412, height: 915}, deviceScaleFactor: 2, isMobile: true, hasTouch: true});
  const p = await c.newPage();
  const errors = []; p.on("pageerror", (e) => errors.push(String(e))); p.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  await p.setContent('<!doctype html><meta charset="utf-8"><div id="editor" style="position:absolute;inset:0"></div>');
  await p.addScriptTag({path: BUNDLE});
  await p.evaluate(({sample, candidates, extra}) => {
    window.m = EditorEngine.create(document.getElementById("editor"), {extraKeywords: extra, locale: "tr"});
    window.inputs = 0; window.m.onInput(() => { window.inputs++; });
    window.calls = []; window.m.setCompleter((r, c, prefix, cb) => { window.calls.push([r, c, prefix]); cb(candidates); });
    window.m.setValue(sample);
  }, {sample: SAMPLE, candidates: CANDIDATES, extra: EXTRA});
  await p.waitForSelector(".cm-content");

  console.log("contract:");
  check("create() + setValue/getValue return the same text", (await p.evaluate(() => window.m.getValue())) === SAMPLE);
  check("setValue resets the history: undo changes nothing", await p.evaluate(() => { const v = window.m.getValue(); window.m.undo(); return window.m.getValue() === v; }));

  // cursor
  const k = await p.evaluate(() => { window.m.moveCursorTo(2, 4); return window.m.getCursorPosition(); });
  check("moveCursorTo(2,4) -> getCursorPosition {row:2,column:4} (0-based)", k.row === 2 && k.column === 4, k);
  const k2 = await p.evaluate(() => { window.m.moveCursorTo(1, 999); return window.m.getCursorPosition(); });
  check("the column is clamped to the end of the line", k2.row === 1 && k2.column === "clear()".length, k2);

  // extra keywords: expected count from Node with the same regular expression (whole words only)
  const expected = (SAMPLE.match(new RegExp("(?<![\\p{L}\\p{N}_$])(" + EXTRA.join("|") + ")(?![\\p{L}\\p{N}_$])", "gu")) || []).length;
  // only visible lines are decorated: a tall viewport makes the whole document visible
  await p.setViewportSize({width: 412, height: 6000}); await p.waitForTimeout(150);
  const decorated = await p.evaluate(() => document.querySelectorAll(".extra-keyword").length);
  check("extra keywords are decorated, whole words only (" + expected + " expected)", decorated === expected && expected > 0, decorated);
  await p.setViewportSize({width: 412, height: 915});
  const none = await p.evaluate(() => {
    const d = document.createElement("div"); d.style.cssText = "position:absolute;inset:0"; document.body.appendChild(d);
    const e = EditorEngine.create(d); e.setValue("repeat(3) { forward(10) }");
    const n = d.querySelectorAll(".extra-keyword").length; d.remove(); return n;
  });
  check("without extraKeywords nothing is decorated", none === 0, none);

  // diagnostics
  const t = await p.evaluate(() => {
    window.m.setAnnotations([{row: 1, col: 0, text: "test error", type: "error"}, {row: 3, col: 2, text: "a warning", type: "warning"}]);
    return {error: document.querySelectorAll(".cm-lintRange-error").length, warning: document.querySelectorAll(".cm-lintRange-warning").length, gutter: document.querySelectorAll(".cm-lint-marker").length};
  });
  check("setAnnotations: 1 error + 1 warning range, 2 gutter markers", t.error === 1 && t.warning === 1 && t.gutter === 2, t);
  const t2 = await p.evaluate(() => { window.m.clearAnnotations(); return document.querySelectorAll(".cm-lintRange-error, .cm-lintRange-warning, .cm-lint-marker").length; });
  check("clearAnnotations removes them all", t2 === 0, t2);

  // typing and onInput
  await p.evaluate(() => { window.m.moveCursorTo(99999, 0); window.m.focus(); });
  const i0 = await p.evaluate(() => window.inputs);
  await p.keyboard.type("\n\u00e7\u015f\u011f\u0131\u0130\u00f6\u00fc", {delay: 10});
  check("non-ASCII letters typed on the keyboard arrive in the document", (await p.evaluate(() => window.m.getValue())).endsWith("\n\u00e7\u015f\u011f\u0131\u0130\u00f6\u00fc"));
  check("onInput fires while typing", (await p.evaluate(() => window.inputs)) > i0);

  // Ctrl+Enter: defaultKeymap binds "Mod-Enter" to insertBlankLine, which clashed with FiddleEditor.scala's
  // global Compile shortcut (a Windows/Linux tester measured: it both ran the script and inserted a blank
  // line). The engine must stay out of this key: the text must not change.
  const before = await p.evaluate(() => window.m.getValue());
  await p.keyboard.press("Control+Enter"); await p.waitForTimeout(50);
  check("Ctrl+Enter does not change the text (Mod-Enter is filtered out)", (await p.evaluate(() => window.m.getValue())) === before);

  // completion: case-insensitive filter with locale "tr"
  async function suggestions(prefix) {
    await p.keyboard.type("\n" + prefix, {delay: 20}); await p.waitForTimeout(350);
    const l = await p.evaluate(() => [...document.querySelectorAll(".cm-tooltip-autocomplete li .cm-completionLabel")].map((e) => e.textContent));
    await p.keyboard.press("Escape");
    return l;
  }
  const lowerI = await suggestions("i");
  check("locale tr, prefix i -> ileri, \u0130leri, i\u011fne, ilerleGit (not I\u015f\u0131k, \u0131s\u0131)", same(lowerI, ["ileri", "ilerleGit", "i\u011fne", "\u0130leri"]), lowerI);
  const upperI = await suggestions("I");
  check("locale tr, prefix I -> I\u015f\u0131k, \u0131\u015f\u0131k, \u0131s\u0131 (not ileri)", same(upperI, ["I\u015f\u0131k", "\u0131\u015f\u0131k", "\u0131s\u0131"]), upperI);
  const dotless = await suggestions("\u0131");
  check("locale tr, prefix \u0131 -> I\u015f\u0131k, \u0131\u015f\u0131k, \u0131s\u0131", same(dotless, ["I\u015f\u0131k", "\u0131\u015f\u0131k", "\u0131s\u0131"]), dotless);
  const last = await p.evaluate(() => window.calls[window.calls.length - 1]);
  check("the completer is called with (row, col, prefix); the last call has prefix \u0131, column 1", Array.isArray(last) && last[2] === "\u0131" && last[1] === 1, last);

  // without a locale the filter is plain toLowerCase: "I" matches "ileri" (the point of the option)
  const plain = await p.evaluate((candidates) => {
    const d = document.createElement("div"); d.id = "plain"; d.style.cssText = "position:absolute;inset:0;background:#fff"; document.body.appendChild(d);
    window.m2 = EditorEngine.create(d); window.m2.setCompleter((r, c, prefix, cb) => cb(candidates)); window.m2.focus();
    return true;
  }, CANDIDATES);
  await p.keyboard.type("I", {delay: 20}); await p.waitForTimeout(350);
  const plainList = await p.evaluate(() => [...document.querySelectorAll(".cm-tooltip-autocomplete li .cm-completionLabel")].map((e) => e.textContent));
  await p.keyboard.press("Escape");
  check("without a locale, prefix I also matches ileri (plain toLowerCase)", plainList.includes("ileri"), plainList);
  await p.evaluate(() => document.getElementById("plain").remove());
  await p.evaluate(() => window.m.focus());

  // insertion: with parameters -> cursor inside, first parameter selected
  await p.keyboard.type("\nilerle", {delay: 20}); await p.waitForTimeout(350);
  await p.keyboard.press("Enter"); await p.waitForTimeout(100);
  const e1 = await p.evaluate(() => { const s = window.m._view.state; const r = s.selection.main; return {line: s.doc.lineAt(r.anchor).text, selected: s.sliceDoc(r.from, r.to)}; });
  check("ilerle + Enter -> ilerleGit(x, y) with x selected", e1.line === "ilerleGit(x, y)" && e1.selected === "x", e1);
  // a signature without parentheses -> the name is inserted, cursor at the end
  await p.evaluate(() => { window.m.moveCursorTo(99999, 99999); });
  await p.keyboard.type("\n\u0131s", {delay: 20}); await p.waitForTimeout(350);
  await p.keyboard.press("Enter"); await p.waitForTimeout(100);
  const e2 = await p.evaluate(() => { const s = window.m._view.state; const r = s.selection.main; return {line: s.doc.lineAt(r.anchor).text, empty: r.empty, atEnd: r.head === s.doc.lineAt(r.head).to}; });
  check("\u0131s + Enter -> \u0131s\u0131, cursor at the end, no selection", e2.line === "\u0131s\u0131" && e2.empty && e2.atEnd, e2);
  // complete(): an explicit request on an empty prefix opens the list
  await p.keyboard.type("\n"); await p.evaluate(() => window.m.complete()); await p.waitForTimeout(350);
  const open = await p.evaluate(() => document.querySelectorAll(".cm-tooltip-autocomplete li").length);
  check("complete() on an empty prefix opens all candidates (" + CANDIDATES.length + ")", open === CANDIDATES.length, open);
  await p.keyboard.press("Escape");

  // wrapping option: wrapped by default, {wrap: false} turns it off
  check("default: lines are wrapped (cm-lineWrapping class present)", await p.evaluate(() => document.querySelector(".cm-content").classList.contains("cm-lineWrapping")));
  const wrapOff = await p.evaluate(() => {
    const d = document.createElement("div"); d.style.cssText = "position:absolute;inset:0"; document.body.appendChild(d);
    EditorEngine.create(d, {wrap: false});
    return d.querySelector(".cm-content").classList.contains("cm-lineWrapping");
  });
  check("wrap:false -> lines are not wrapped", !wrapOff);

  // Enter indentation like Ace (copy the previous line, +1 unit after an opener, a closer lines up with its opener).
  // CodeMirror's default computed depth x 2: in a script indented by 4, Enter from a `val` line went to 6.
  const four = "def a() = {\n    while (true) {\n        val x = 5\n    }    \n}";
  async function afterEnter(text, row, column, then) {
    await p.evaluate(([t, r, c]) => { window.m.setValue(t); const s = t.split("\n")[r]; window.m.moveCursorTo(r, c === null ? s.length : c); window.m.focus(); }, [text, row, column === undefined ? null : column]);
    await p.keyboard.press("Enter");
    if (then) await p.keyboard.type(then);
    return p.evaluate(() => window.m.getValue().split("\n"));
  }
  const indentOf = (l) => l.match(/^ */)[0].length;
  let l1 = await afterEnter(four, 2, undefined);
  check("script indented by 4: Enter after a val line -> 8 spaces (lined up with the previous line)", indentOf(l1[3]) === 8, l1[3]);
  l1 = await afterEnter(four, 1, undefined);
  check("Enter after a line ending in { -> previous indent + 1 unit (4 + 2)", indentOf(l1[2]) === 6, l1[2]);
  l1 = await afterEnter(four, 3, undefined);
  check("Enter after a } line -> lined up with the previous line (4)", indentOf(l1[4]) === 4, l1[4]);
  l1 = await afterEnter("def a() = {\n    while (true) {", 1, undefined, "}");
  check("Enter after {, then typing } -> goes back to the indent of the matching { line (4)", l1[2] === "    }", l1[2]);
  l1 = await afterEnter("    f {}", 0, 7);
  check("Enter between {|}: the middle line gets +1 unit, the closer the opener's indent", l1.length === 3 && indentOf(l1[1]) === 6 && l1[2] === "    }", l1);
  l1 = await afterEnter("def a() = {\n    // note {\n}", 1, undefined);
  check("a { at the end of a comment does not increase the indent", indentOf(l1[2]) === 4, l1[2]);

  // indent option: default 2; {indent: 4} makes Tab and Enter after an opener 4.
  async function withOptions(opts, text, row, keys) {
    return p.evaluate(([o, t, r]) => {
      const d = document.createElement("div"); d.style.cssText = "position:absolute;inset:0;background:#fff"; document.body.appendChild(d);
      window.m3 = EditorEngine.create(d, o); window.m3.setValue(t); const s = t.split("\n")[r]; window.m3.moveCursorTo(r, s.length); window.m3.focus();
    }, [opts, text, row]).then(async () => { for (const key of keys) await p.keyboard.press(key); return p.evaluate(() => window.m3.getValue().split("\n")); });
  }
  let gl = await withOptions({indent: 4}, "def a() = {", 0, ["Enter"]);
  check("indent:4 -> Enter after { gives 4 spaces", indentOf(gl[1]) === 4, gl[1]);
  gl = await withOptions({indent: 4}, "def a() = {\n    while (true) {", 1, ["Enter"]);
  check("indent:4, script indented by 4 -> Enter after { gives 4 + 4 = 8", indentOf(gl[2]) === 8, gl[2]);
  gl = await withOptions({indent: 4}, "x", 0, ["Home", "Tab"]);
  check("indent:4 -> Tab puts 4 spaces at the start of the line", indentOf(gl[0]) === 4, gl[0]);
  gl = await withOptions(undefined, "x", 0, ["Home", "Tab"]);
  check("default -> Tab puts 2 spaces", indentOf(gl[0]) === 2, gl[0]);
  for (const n of [1, 3, 5, 8]) {
    gl = await withOptions({indent: n}, "x", 0, ["Home", "Tab"]);
    check("indent:" + n + " -> Tab puts " + n + " spaces", indentOf(gl[0]) === n, gl[0]);
  }
  gl = await withOptions({indent: 3}, "def a() = {\n  if (x) {", 1, ["Enter"]);
  check("indent:3 -> Enter after { gives 2 + 3 = 5", indentOf(gl[2]) === 5, gl[2]);
  gl = await withOptions({indent: 99}, "x", 0, ["Home", "Tab"]);
  check("an invalid indent (99) falls back to 2", indentOf(gl[0]) === 2, gl[0]);

  check("no page errors", errors.length === 0, errors);
  await b.close();
  console.log(count + " checks, " + failed + " failed");
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
