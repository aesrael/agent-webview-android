// Smoke tests for the in-page library, run against jsdom (no layout engine, so requireLayout=false).
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { JSDOM } = require('jsdom');
// Objects built inside jsdom belong to another realm; normalise before deep comparison.
const plain = (v) => JSON.parse(JSON.stringify(v));

const src = fs.readFileSync(path.join(__dirname, '../src/main/assets/agentwebview/agent.js'), 'utf8');
const OPTS = { requireLayout: false };

function page(html) {
  const dom = new JSDOM(html, { url: 'https://example.test/', runScripts: 'outside-only', pretendToBeVisual: true });
  const api = dom.window.eval('(' + src + ')()');
  return { dom, api, doc: dom.window.document };
}

function flatten(nodes, out = []) {
  for (const n of nodes) { out.push(n); flatten(n.children || [], out); }
  return out;
}
const byRef = (snap, ref) => flatten(snap.nodes).find((n) => n.ref === ref);

let passed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log('ok   ' + name); }
  catch (e) { console.error('FAIL ' + name + '\n' + e.stack); process.exitCode = 1; }
}

test('snapshot finds interactive elements, text and headings', () => {
  const { api } = page('<body><h1>Title</h1><p>Hello world</p><button>Save</button><a href="/x">Go</a><input placeholder="Search"></body>');
  const s = api.snapshot(1, OPTS);
  const all = flatten(s.nodes);
  assert(all.some((n) => n.role === 'heading' && n.name === 'Title' && n.level === 1));
  assert(all.some((n) => n.role === 'text' && n.name === 'Hello world'));
  assert(all.some((n) => n.role === 'button' && n.name === 'Save' && n.ref === '@e1'));
  assert(all.some((n) => n.role === 'link' && n.href === 'https://example.test/x'));
  assert(all.some((n) => n.role === 'textbox' && n.name === 'Search'));
  assert.strictEqual(s.nextIndex, 4);
});

test('refs never restart and old stamps are cleared', () => {
  const { api, doc } = page('<body><button id="a">A</button></body>');
  const s1 = api.snapshot(1, OPTS);
  assert.strictEqual(s1.nextIndex, 2);
  doc.getElementById('a').insertAdjacentHTML('afterend', '<button id="b">B</button>');
  const s2 = api.snapshot(s1.nextIndex, OPTS);
  assert.strictEqual(doc.querySelectorAll('[data-agent-ref]').length, 2);
  assert.strictEqual(doc.getElementById('a').getAttribute('data-agent-ref'), '@e2');
  assert.strictEqual(doc.querySelector('[data-agent-ref="@e1"]'), null, 'old ref must be gone');
  assert.strictEqual(api.click('@e1').code, 'STALE_REF');
  assert.strictEqual(s2.nextIndex, 4);
});

test('hidden elements are skipped', () => {
  const { api } = page('<body><button style="display:none">X</button><button hidden>Y</button><button aria-hidden="true">Z</button><button>OK</button></body>');
  const names = flatten(api.snapshot(1, OPTS).nodes).filter((n) => n.role === 'button').map((n) => n.name);
  assert.deepStrictEqual(plain(names), ['OK']);
});

test('angle brackets and quotes survive intact in names', () => {
  const { api } = page('<body><button aria-label="&lt;Back &quot;now&quot;">x</button></body>');
  assert.strictEqual(flatten(api.snapshot(1, OPTS).nodes)[0].name, '<Back "now"');
});

test('password, card and otp fields never leak values', () => {
  const { api } = page('<body><input type="password" value="hunter2"><input autocomplete="cc-number" value="4111111111111111"><input autocomplete="one-time-code" value="123456"></body>');
  const nodes = flatten(api.snapshot(1, OPTS).nodes);
  assert.strictEqual(nodes.length, 3);
  for (const n of nodes) { assert.strictEqual(n.value, undefined); assert(n.states.includes('sensitive')); }
  assert(nodes[0].states.includes('password'));
  assert(nodes[1].states.includes('cc'));
  assert(nodes[2].states.includes('otp'));
  assert(!JSON.stringify(nodes).includes('hunter2'));
});

test('labels name inputs; select reports options and value', () => {
  const { api } = page('<body><label for="e">Email</label><input id="e" value="a@b.c"><select aria-label="Colour"><option>Red</option><option selected>Blue</option></select></body>');
  const nodes = flatten(api.snapshot(1, OPTS).nodes);
  const email = nodes.find((n) => n.role === 'textbox');
  assert.strictEqual(email.name, 'Email'); assert.strictEqual(email.value, 'a@b.c');
  const sel = nodes.find((n) => n.role === 'combobox');
  assert.deepStrictEqual(plain(sel.options), ['Red', 'Blue']); assert.strictEqual(sel.value, 'Blue');
});

test('node cap sets truncated', () => {
  const { api } = page('<body>' + '<button>b</button>'.repeat(50) + '</body>');
  const s = api.snapshot(1, { requireLayout: false, maxNodes: 10 });
  assert(s.truncated); assert(flatten(s.nodes).length <= 10);
});

test('shadow DOM and same-origin iframes are reachable', () => {
  const { dom, api } = page('<body><div id="host"></div><iframe id="f"></iframe></body>');
  const doc = dom.window.document;
  const root = doc.getElementById('host').attachShadow({ mode: 'open' });
  root.innerHTML = '<button>Shadow</button>';
  doc.getElementById('f').contentDocument.body.innerHTML = '<button>Framed</button>';
  const s = api.snapshot(1, OPTS);
  const names = flatten(s.nodes).filter((n) => n.role === 'button').map((n) => n.name).sort();
  assert.deepStrictEqual(plain(names), ['Framed', 'Shadow']);
  assert.strictEqual(api.focus('@e1').ok, true); // resolves through shadow root / iframe
  assert.strictEqual(api.focus('@e2').ok, true);
});

test('fill uses the native setter and fires input and change', () => {
  const { dom, api, doc } = page('<body><input id="i" aria-label="Name"></body>');
  const events = [];
  const i = doc.getElementById('i');
  i.addEventListener('input', () => events.push('input'));
  i.addEventListener('change', () => events.push('change'));
  const s = api.snapshot(1, OPTS);
  assert.deepStrictEqual(plain(api.fill(s.nodes[0].ref, 'Ada <b>')), { ok: true });
  assert.strictEqual(i.value, 'Ada <b>');
  assert.deepStrictEqual(events, ['input', 'change']);
});

test('fill refuses non-text and disabled elements', () => {
  const { api } = page('<body><button>B</button><input disabled aria-label="D"><input type="checkbox" aria-label="C"></body>');
  const s = api.snapshot(1, OPTS);
  assert.strictEqual(api.fill('@e1', 'x').code, 'NOT_ACTIONABLE');
  assert.strictEqual(api.fill('@e2', 'x').code, 'NOT_ACTIONABLE');
  assert.strictEqual(api.fill('@e3', 'x').code, 'NOT_ACTIONABLE');
});

test('select by value or label; unknown option lists choices', () => {
  const { api, doc } = page('<body><select aria-label="S"><option value="r">Red</option><option value="b">Blue</option></select></body>');
  api.snapshot(1, OPTS);
  assert.strictEqual(api.select('@e1', 'Blue').ok, true);
  assert.strictEqual(doc.querySelector('select').value, 'b');
  assert.strictEqual(api.select('@e1', 'r').ok, true);
  const bad = api.select('@e1', 'Green');
  assert.strictEqual(bad.code, 'NOT_ACTIONABLE'); assert(bad.message.includes('Red'));
});

test('check toggles only when needed', () => {
  const { api, doc } = page('<body><input type="checkbox" aria-label="T"></body>');
  api.snapshot(1, OPTS);
  assert.deepStrictEqual(plain(api.check('@e1', true)), { ok: true, changed: true });
  assert.strictEqual(doc.querySelector('input').checked, true);
  assert.deepStrictEqual(plain(api.check('@e1', true)), { ok: true, changed: false });
  assert.deepStrictEqual(plain(api.check('@e1', false)), { ok: true, changed: true });
});

test('a page that duplicates a ref attribute is detected as ambiguous', () => {
  const { api, doc } = page('<body><button>A</button><button>B</button></body>');
  api.snapshot(1, OPTS);
  doc.querySelectorAll('button')[1].setAttribute('data-agent-ref', '@e1');
  assert.strictEqual(api.click('@e1').code, 'AMBIGUOUS_REF');
});

test('probe reports selector presence; sample installs the monitor', () => {
  const { api } = page('<body><div class="captcha"></div></body>');
  const p = api.probe(['.captcha', '.gone', '!!bad']);
  assert.deepStrictEqual(plain(p.present), ['.captcha']);
  const s1 = api.sample();
  assert.strictEqual(s1.sinceMutation, 0);
  assert.strictEqual(s1.pending, 0);
  assert.strictEqual(typeof api.sample().sinceMutation, 'number');
});

test('malformed refs are stale, not exceptions', () => {
  const { api } = page('<body><button>A</button></body>');
  api.snapshot(1, OPTS);
  for (const bad of ['', 'e1', '@e1"]; alert(1);//', null, undefined, 5]) {
    assert.strictEqual(api.click(bad).code, 'STALE_REF');
  }
});

console.log(`\n${passed} passed`);
