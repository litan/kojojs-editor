# cm-engine: a CodeMirror 6 engine for the fiddle editor

`facade.js` reduces the surface that `FiddleEditor.scala` uses from its code editor to one small
facade, `window.EditorEngine`. esbuild bundles it into a single file,
`server/src/main/assets/javascript/cm-engine.js`, which is **committed**.

Why the generated file is committed: the editor's own build (sbt 0.13) knows nothing about npm and no
CI job runs it, and the file is served from `assets/javascript` like `mousetrap.js`. CI rebuilds the
bundle and compares it byte for byte (`npm run check`), and runs the contract test in headless Chrome.

```
cd cm-engine
npm ci
npm run build                          # write cm-engine.js
npm run check                          # rebuild and compare; exit 1 on a difference
CHROME_BIN=/path/to/chrome npm test    # contract test (Playwright, headless Chrome)
```

Versions are pinned in `package.json` and `package-lock.json`. `node_modules/` is in the root `.gitignore`.

## The facade

`EditorEngine.create(el, options)` mounts an editor in `el` and returns an object. Rows and columns are
0-based, as in Ace.

| option | meaning |
|---|---|
| `wrap` | wrap long lines (default `true`; Ace does not wrap) |
| `indent` | indent unit in spaces, an integer 1-8 (default 2) |
| `extraKeywords` | words to highlight as keywords on top of the Scala ones (default none) |
| `locale` | locale for case-insensitive completion matching, e.g. `"tr"` (default: plain `toLowerCase`) |

| method | meaning |
|---|---|
| `getValue()`, `setValue(text)` | `setValue` resets the undo history, like Ace's `session.setValue` |
| `onInput(cb)` | `cb()` on every document change |
| `focus()` | |
| `getCursorPosition()` | `{row, column}` |
| `moveCursorTo(row, column)` | clamped to the document |
| `setAnnotations([{row, col, text, type}])`, `clearAnnotations()` | compiler messages; `type` is `"error"`, `"warning"` or `"info"` |
| `setCompleter(f)` | `f(row, col, prefix, callback)`; call `callback([{name, value}])` with `name` the signature and `value` the name to insert |
| `complete()` | open the completion list |
| `undo()`, `_view` | for tests and debugging; the Scala side does not use them |

Two decisions worth knowing:

- `Mod-Enter` is removed from CodeMirror's default keymap. `FiddleEditor.scala` binds Ctrl/Cmd-Enter
  globally to Compile; with the default keymap both fired and a blank line was inserted as well.
- Enter indents like Ace (copy the previous line, one more unit after an opener, a closer lines up with
  its opener). CodeMirror's Scala mode instead computes nesting depth times unit, which ignores the
  indentation a script actually uses.

`sample.kojo` is a fixed input for the contract test, a made-up script. It is frozen on purpose: the
test counts keyword matches in it.
