#!/usr/bin/env node
// facade.js -> ../server/src/main/assets/javascript/cm-engine.js (esbuild, one minified file).
//   node build.cjs          write
//   node build.cjs --check  do not write; compare byte for byte with the committed file, exit 1 on a difference (CI)
// Deterministic: package-lock.json pins the versions; the header has no date, only versions.
const esbuild = require("esbuild");
const fs = require("fs"), path = require("path");
const TARGET = path.join(__dirname, "..", "server", "src", "main", "assets", "javascript", "cm-engine.js");
const lock = require("./package-lock.json").packages;
const version = (name) => lock["node_modules/" + name].version;
const header = "/* GENERATED FILE, DO NOT EDIT: kojojs-editor/cm-engine/ (node build.cjs). " +
  "CodeMirror view " + version("@codemirror/view") + ", state " + version("@codemirror/state") +
  ", autocomplete " + version("@codemirror/autocomplete") + ", lint " + version("@codemirror/lint") +
  ", legacy-modes " + version("@codemirror/legacy-modes") + "; esbuild " + version("esbuild") + " */\n";
(async () => {
  const r = await esbuild.build({
    entryPoints: [path.join(__dirname, "facade.js")], bundle: true, minify: true, format: "iife",
    globalName: "EditorEngine", charset: "utf8", target: ["es2018"], legalComments: "none", write: false, logLevel: "error"
  });
  const output = header + r.outputFiles[0].text;
  const kb = (s) => Math.round(s.length / 1024) + " KB";
  if (process.argv.includes("--check")) {
    const old = fs.existsSync(TARGET) ? fs.readFileSync(TARGET, "utf8") : "";
    if (old === output) { console.log("same: cm-engine.js is byte for byte what the build produces (" + kb(output) + ")"); return; }
    console.error("DIFFERENT: server/src/main/assets/javascript/cm-engine.js differs from the build output (committed " +
      kb(old) + ", build " + kb(output) + ").\nTo fix: cd cm-engine && npm ci && npm run build, and commit the result.");
    process.exit(1);
  }
  fs.writeFileSync(TARGET, output);
  console.log("written: " + path.relative(path.join(__dirname, ".."), TARGET) + " (" + kb(output) + ")");
})().catch((e) => { console.error(e); process.exit(1); });
