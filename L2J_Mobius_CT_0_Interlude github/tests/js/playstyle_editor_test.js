/*
 * Regression harness for the Phantom Playstyle editor inside tools/l2admin/index.html.
 *
 * The panel is deliberately ONE dependency-free HTML file with no build step, so there is nothing to
 * import. This harness therefore reads index.html and evaluates its <script> block inside a minimal
 * browser stub - which means it always tests the SHIPPING code and can never drift from a copy.
 *
 * What it protects:
 *   1. The line-oriented XML writer round-trips the real PhantomPlaystyles.xml byte for byte, and an
 *      edit rewrites ONLY its own line. This is the whole reason the panel may write that file at
 *      all: the header and per-lineage rationale comments are most of the file's value.
 *   2. The inline validation agrees with research/validate_playstyles.py (which reports the shipped
 *      file clean), and every rule actually fires when something is broken.
 *   3. Learn levels derived from parentClassId match the datapack, which is what the level scrubber
 *      and the coverage gate are built on.
 *
 * Usage: node tests/js/playstyle_editor_test.js     (run from the project root, or anywhere)
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..", "..");
const DATA = path.join(ROOT, "dist", "game", "data");
const PANEL = path.join(ROOT, "tools", "l2admin", "index.html");
const PLAYSTYLES = path.join(DATA, "PhantomPlaystyles.xml");

let failures = 0;
const pass = (msg) => console.log("  ok    " + msg);
const fail = (msg) => { failures++; console.log("  FAIL  " + msg); };
const check = (cond, msg) => cond ? pass(msg) : fail(msg);

/* ------------------------------------------------------------------ *
 * Load the panel's real script into a stubbed browser.
 * ------------------------------------------------------------------ */
const html = fs.readFileSync(PANEL, "utf8");
const scriptMatch = /<script>\s*"use strict";([\s\S]*?)<\/script>/.exec(html);
if (!scriptMatch){ console.log("FAIL  could not find the panel script block"); process.exit(1); }

const stubEl = () => ({ classList:{ add(){}, remove(){}, toggle(){} }, style:{}, innerHTML:"",
  textContent:"", appendChild(){}, querySelector:() => null, querySelectorAll:() => [], dataset:{},
  addEventListener(){}, onclick:null });
const toastEl = stubEl();
const sandbox = {
  window:{ addEventListener(){} },                       // no showDirectoryPicker -> FS_OK is false
  document:{ getElementById:(id) => id === "toast" ? toastEl : stubEl(), querySelectorAll:() => [], createElement:() => stubEl(),
    body:stubEl(), documentElement:{ setAttribute(){}, getAttribute:() => "dark" } },
  localStorage:{ getItem:() => null, setItem(){} },
  indexedDB:{ open:() => ({}) },
  console, setTimeout, clearTimeout,
  URL:{ createObjectURL:() => "", revokeObjectURL(){} },
  DOMParser: function(){ this.parseFromString = () => ({ querySelectorAll:() => [] }); }
};
const api = {};
new Function(...Object.keys(sandbox), "__api", scriptMatch[1] +
  "\n;Object.assign(__api,{psParse,psSerialize,psAttrs,psSkillLine,psValidate,psState,psList," +
  "psClassIds,psChain,psLearnLevels,psEarliest,psWhenText,psRoundTripOk,psDirty,psSelfTest,psSave});"
)(...Object.values(sandbox), api);

/* ------------------------------------------------------------------ *
 * Index the datapack the same way the browser does (regex here instead
 * of DOMParser - only the parse mechanism differs, not the shape).
 * ------------------------------------------------------------------ */
function buildIndex(){
  const skills = {};
  const skillDir = path.join(DATA, "stats", "skills");
  for (const f of fs.readdirSync(skillDir).filter(n => n.endsWith(".xml"))){
    const t = fs.readFileSync(path.join(skillDir, f), "utf8");
    const re = /<skill id="(\d+)" levels="\d+"[^>]*? name="([^"]*)"/g;
    let m; while ((m = re.exec(t))) skills[m[1]] = { name:m[2], icon:"", mp:"", reuse:0, range:0, area:0, weapon:"", desc:"" };
  }
  const trees = {};
  const treeRoot = path.join(DATA, "stats", "players", "skillTrees");
  for (const dir of fs.readdirSync(treeRoot)){
    const p = path.join(treeRoot, dir);
    if (!fs.statSync(p).isDirectory()) continue;
    for (const f of fs.readdirSync(p).filter(n => n.endsWith(".xml"))){
      const t = fs.readFileSync(path.join(p, f), "utf8");
      const head = /<skillTree([^>]*)>/.exec(t);
      if (!head || !/type="classSkillTree"/.test(head[1])) continue;
      const cid = /classId="(\d+)"/.exec(head[1]);
      if (!cid) continue;
      const par = /parentClassId="(-?\d+)"/.exec(head[1]);
      const rec = trees[+cid[1]] = trees[+cid[1]] || { name:f.replace(/\.xml$/,""), tier:dir,
        parent:(par && +par[1] >= 0) ? +par[1] : null, skills:{} };
      const re = /skillId="(\d+)"[^/]*?getLevel="(\d+)"/g;
      let m; while ((m = re.exec(t))) if (rec.skills[m[1]] == null || +m[2] < rec.skills[m[1]]) rec.skills[m[1]] = +m[2];
    }
  }
  return { skills, trees };
}
api.psState.idx = buildIndex();
api.psState.learnCache = {};

function load(xml){
  api.psState.learnCache = {};
  api.psState.doc = api.psParse(xml);
  return api.psList();
}
function findingsFor(xml){
  const out = [];
  load(xml).forEach(ps => api.psValidate(ps).forEach(f => out.push(f)));
  return out;
}
const wrap = (inner) => '<?xml version="1.0" encoding="UTF-8"?>\n<list>\n' + inner + '\n</list>\n';

/* ================================================================== *
 * 1. The writer
 * ================================================================== */
console.log("\n>>> line-oriented XML writer");
const text = fs.readFileSync(PLAYSTYLES, "utf8");
const list = load(text);

{
  const out = api.psSerialize(api.psState.doc);
  if (out === text) pass("round-trip is byte-identical (" + text.split("\n").length + " lines)");
  else {
    const a = text.split(/\r?\n/), b = out.split(/\r?\n/);
    let differingLine = false;
    for (let i = 0; i < Math.max(a.length, b.length); i++){
      if (a[i] !== b[i]){ fail("round-trip differs at line " + (i+1) + "\n        file: " + a[i] + "\n        ours: " + b[i]); differingLine = true; break; }
    }
    if (!differingLine) fail("round-trip differs in line endings despite identical line contents");
  }
}
check(list.length === (text.match(/<playstyle /g) || []).length, "every <playstyle> parsed");
check(list.reduce((n,p) => n + p.entries.length, 0) === (text.match(/<skill /g) || []).length, "every <skill> row parsed");
check(list.every(p => p.entries.every(e => e.attrs.id && e.attrs.use)), "every row exposes id + use");

{
  const fixture = wrap('\t<playstyle name="X" classIds="1">\n' +
    '\t\t<skill id="255" name="Power Smash" use="ROTATION" when="ALWAYS" />\n\t</playstyle>');
  for (const eol of ["\n", "\r\n"]){
    const xml = fixture.replace(/\r?\n/g, eol);
    api.psState.original = xml;
    api.psState.doc = api.psParse(xml);
    api.psState.safe = api.psRoundTripOk();
    check(api.psState.safe && api.psSerialize(api.psState.doc) === xml,
      "the write guard accepts an exact " + (eol === "\n" ? "LF" : "CRLF") + " round-trip");
    check(!api.psDirty(), "an unchanged document has no unsaved edits");
    api.psSelfTest();
    check(toastEl.textContent.startsWith("Round-trip clean:"), "Self-test confirms an exact round-trip");
    api.psState.doc.items.find(i => i.t === "ps").entries[0].edited = true;
    api.psState.doc.items.find(i => i.t === "ps").entries[0].attrs.mpAbove = "42";
    check(api.psDirty(), "a real edit is still marked dirty");
  }
  const mixed = fixture.replace("\n", "\r\n");
  api.psState.original = mixed;
  api.psState.doc = api.psParse(mixed);
  api.psState.safe = api.psRoundTripOk();
  check(!api.psState.safe, "mixed separators are rejected by the byte-exact write guard");
  check(!api.psDirty(), "a read-only normalization mismatch is not shown as an unsaved edit");
  api.psSelfTest();
  check(toastEl.textContent.includes("differs in line endings"), "Self-test reports a separator-only mismatch");
  let writes = 0;
  api.psState.fileHandle = { createWritable(){ writes++; throw new Error("unexpected write"); } };
  // psSave is async; await its refusal in the final assertions below.
  const refused = api.psSave().then(() => false, err => /round-trip guard/.test(err.message));
  api.__writeRefusal = { refused, writes:() => writes };
  api.psState.fileHandle = null;
  api.psState.original = text;
  load(text);
  api.psState.safe = api.psRoundTripOk();
}

{
  const fsk = list.find(p => p.attrs.name === "Fortune Seeker");
  check(fsk && fsk.entries[0].lead.length > 0,
    "a row's own comment travels with it (Fortune Seeker / Spoil)");
}
{
  let bad = 0;
  list.forEach(p => p.entries.forEach(e => {
    const back = api.psAttrs(api.psSkillLine(e.indent, e.attrs));
    Object.keys(e.attrs).forEach(k => { if (String(back[k]) !== String(e.attrs[k])) bad++; });
  }));
  check(bad === 0, "rebuilt lines re-parse to identical attributes");
}
{
  const doc = api.psParse(text);
  const target = doc.items.filter(i => i.t === "ps")[0].entries[0];
  target.attrs.mpAbove = "42"; target.edited = true;
  const before = text.split(/\r?\n/), after = api.psSerialize(doc).split(/\r?\n/);
  const changed = before.map((l,i) => l !== after[i] ? i+1 : 0).filter(Boolean);
  check(changed.length === 1, "editing one attribute rewrites only that line (line " + changed.join(",") + ")");
}
{
  const doc = api.psParse(text);
  const ps = doc.items.filter(i => i.t === "ps")[0];
  const [moved] = ps.entries.splice(0,1); ps.entries.splice(2,0,moved);
  check(api.psSerialize(doc).split(/\r?\n/).length === text.split(/\r?\n/).length,
    "reordering rows keeps the line count stable");
}

/* ================================================================== *
 * 2. Lineage + learn levels (what the level scrubber shows)
 * ================================================================== */
console.log("\n>>> lineage and learn levels");
load(text);
[
  ["Archer (Human)", 56, 5, "Power Shot"],
  ["Archer (Human)", 101, 36, "Stunning Shot"],
  ["Archer (Human)", 19, 40, "Double Shot"],
  ["Archer (Human)", 24, 46, "Burst Shot"],
  ["Warrior (1st class)", 255, 20, "Power Smash"],
  ["Warrior (1st class)", 100, 20, "Stun Attack"],
  ["Warrior (1st class)", 121, 28, "Battle Roar"],
].forEach(([name, sid, want, label]) => {
  const ps = api.psList().find(p => p.attrs.name === name);
  const got = ps ? api.psEarliest(ps, sid) : null;
  check(got === want, name + " learns " + label + " at " + want + " (got " + got + ")");
});
{
  const archer = api.psList().find(p => p.attrs.name === "Archer (Human)");
  const live = archer.entries.filter(e => {
    const at = api.psEarliest(archer, +e.attrs.id);
    const lo = +(e.attrs.minLevel || 1), hi = +(e.attrs.maxLevel || 100);
    return at != null && at <= 25 && 25 >= lo && 25 <= hi;
  });
  check(live.length === 1 && live[0].attrs.name === "Power Shot",
    "a level-25 human archer has exactly one usable entry (Power Shot)");
}
{
  let bad = 0;
  api.psList().forEach(p => p.entries.forEach(e => {
    const s = api.psWhenText(e.attrs);
    if (s.indexOf("{v}") >= 0 || s.indexOf("?") >= 0) bad++;
  }));
  check(bad === 0, "every row's conditions render to English with no missing parameter");
}

/* ================================================================== *
 * 3. Validation - clean file is clean, and every rule fires
 * ================================================================== */
console.log("\n>>> validation");
{
  const blocking = findingsFor(text).filter(f => f.bad);
  check(blocking.length === 0,
    "the shipped file reports no blocking findings (matches validate_playstyles.py)" +
    (blocking.length ? "\n        first: " + blocking[0].msg : ""));
}

const ROW = '\t\t<skill id="255" name="Power Smash" use="ROTATION" when="ALWAYS" />';
const fires = (inner, needle, label) => {
  const hits = findingsFor(wrap(inner)).filter(f => f.msg.indexOf(needle) >= 0);
  check(hits.length > 0, "catches " + label);
};

fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="1069" name="Sleep" use="CONTROL" when="ALWAYS" />\n' + ROW + '\n\t</playstyle>',
  "manager-owned", "a manager-owned skill (Sleep 1069)");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="999999" name="Nope" use="ROTATION" when="ALWAYS" />\n' + ROW + '\n\t</playstyle>',
  "not defined in the datapack", "an undefined skill id");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="1177" name="Wind Strike" use="ROTATION" when="ALWAYS" />\n' + ROW + '\n\t</playstyle>',
  "ever learns it", "a skill the lineage never learns");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="255" name="Power Smash" use="BOGUS" when="ALWAYS" />\n\t</playstyle>',
  "unknown use", "an unknown use value");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="255" name="Power Smash" use="ROTATION" when="NOPE" />\n\t</playstyle>',
  "unknown condition", "an unknown condition token");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="255" name="Power Smash" use="ROTATION" when="ALWAYS" minLevel="60" maxLevel="30" />\n\t</playstyle>',
  "is above maxLevel", "minLevel above maxLevel");
fires('\t<playstyle name="X" classIds="1">\n' + ROW + '\n\t</playstyle>\n\t<playstyle name="Y" classIds="1">\n\t\t<skill id="100" name="Stun Attack" use="CONTROL" when="ALWAYS" />\n\t</playstyle>',
  "already claimed", "a second role-agnostic playstyle for the same class (dead code)");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="121" name="Battle Roar" use="PANIC" when="SELF_HP_BELOW" selfHpBelow="40" />\n\t</playstyle>',
  "nothing castable at level 20", "a coverage hole that would park a member into silence");
fires('\t<playstyle name="X" classIds="16">\n\t\t<skill id="1217" name="Group Heal" use="ROTATION" when="ALWAYS" />\n\t</playstyle>',
  "supportTick plays those classes", "a support class, whose playstyle the engine ignores");
fires('\t<playstyle name="X" classIds="14">\n\t\t<skill id="1177" name="Wind Strike" use="ROTATION" when="ALWAYS" />\n\t</playstyle>',
  "servitor control is not implemented", "a summoner lineage (works, but no servitor control)");
fires('\t<playstyle name="X" classIds="1" role="BOGUS">\n' + ROW + '\n\t</playstyle>',
  "Unknown role", "an unknown role");
fires('\t<playstyle name="X" classIds="1">\n\t\t<skill id="255" name="Wrong Name" use="ROTATION" when="ALWAYS" />\n\t</playstyle>',
  "doesn't match the datapack", "a skill name that disagrees with the datapack");

{
  // Rogue (7) legitimately carries BOTH an archer and a dagger playstyle - that must not be a conflict.
  const hits = findingsFor(wrap(
    '\t<playstyle name="A" classIds="7" role="ARCHER">\n\t\t<skill id="56" name="Power Shot" use="ROTATION" when="ALWAYS" />\n\t</playstyle>\n' +
    '\t<playstyle name="B" classIds="7" role="DAGGER">\n\t\t<skill id="16" name="Mortal Blow" use="ROTATION" when="ALWAYS" />\n\t</playstyle>'
  )).filter(f => f.msg.indexOf("already claimed") >= 0);
  check(hits.length === 0, "does NOT flag a legitimate role split on a shared class id");
}

/* ------------------------------------------------------------------ */
api.__writeRefusal.refused.then(refused => {
  check(refused && api.__writeRefusal.writes() === 0, "saving a read-only mismatch refuses before any file write");
  console.log("\n" + (failures ? "FAILED: " + failures + " check(s)" : "ALL CHECKS PASSED"));
  process.exit(failures ? 1 : 0);
}).catch(err => {
  console.error(err);
  process.exit(1);
});
