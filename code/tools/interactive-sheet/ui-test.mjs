// Drive an exported interactive sheet in headless Firefox over WebDriver BiDi: click the session
// controls, reload, and check the sheet's values. Works with any character: it checks changes
// relative to the exported values.
// Usage: node ui-test.mjs <exported sheet.htm> <empty Firefox profile dir>
import {spawn} from 'node:child_process';
import assert from 'node:assert';
import path from 'node:path';

const [sheetArg, profile] = process.argv.slice(2);
const sheet = path.resolve(sheetArg);
const port = 9000 + Math.floor(Math.random() * 900);
const ff = spawn('firefox', ['--headless', '--no-remote', '--profile', profile, '--remote-debugging-port', String(port)],
	{stdio: 'ignore'});

let ws;
for (let i = 0; i < 60 && !ws; i++) {
	await new Promise(r => setTimeout(r, 500));
	try {
		const s = new WebSocket('ws://127.0.0.1:' + port + '/session');
		await new Promise((ok, bad) => { s.onopen = ok; s.onerror = bad; });
		ws = s;
	} catch (e) { /* not up yet */ }
}
assert(ws, 'could not connect to Firefox');
let id = 0;
const pending = new Map();
ws.onmessage = ev => {
	const msg = JSON.parse(ev.data);
	if (msg.id && pending.has(msg.id)) {
		pending.get(msg.id)(msg);
		pending.delete(msg.id);
	}
};
function send(method, params) {
	const n = ++id;
	ws.send(JSON.stringify({id: n, method, params}));
	return new Promise((ok, bad) => pending.set(n, m => m.type === 'error' ? bad(new Error(method + ': ' + m.message)) : ok(m.result)));
}

let context;
async function js(expr) {
	const r = await send('script.evaluate', {expression: '(async () => JSON.stringify(await (async () => {' + expr + '})()))()',
		target: {context}, awaitPromise: true});
	if (r.type === 'exception') {
		throw new Error('page: ' + r.exceptionDetails.text);
	}
	return JSON.parse(r.result.value);
}
async function open() {
	await send('browsingContext.navigate', {context, url: 'file://' + sheet, wait: 'complete'});
}
// numbers on the sheet by role, and the panel's state
const read = `
	const n = r => { const e = document.querySelector('.adj[data-r="' + r + '"]'); return e ? parseInt(e.textContent, 10) : null; };
	const hp = document.querySelector('.ses-hp').textContent.split(' / ').map(x => parseInt(x, 10));
	return {melee: n('melee'), ranged: n('ranged'), ac: n('ac'), touch: n('touch'), flat: n('flat'), will: n('save-will'),
		str: n('score-STR'), con: n('score-CON'), hp: hp[0], maxHp: hp[1],
		status: document.querySelector('.ses-status').textContent,
		current: document.querySelector('[data-hp="current"]').textContent,
		changed: document.querySelectorAll('.adj.changed').length,
		notes: document.querySelector('.session textarea').value,
		willTemp: document.querySelector('input[data-q="will"]').value,
		hitDice: (PcSheetData.hitDice.match(/\\d+(?=d\\d)/g) || []).reduce((a, x) => a + parseInt(x, 10), 0)};`;
const button = text => `[...document.querySelectorAll('.session button')].find(b => b.textContent === ${JSON.stringify(text)})`;
const addEffect = `[...document.querySelectorAll('.session select')].find(x => x.options[0].text.startsWith('Add effect'))`;
function setAndChange(selector, value) {
	return `const e = ${selector}; e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event('change')); return true;`;
}
function presetIndex(name) {
	return `return [...${addEffect}.options].findIndex(o => o.text === ${JSON.stringify(name)}) - 1;`;
}

try {
	await send('session.new', {capabilities: {}});
	context = (await send('browsingContext.getTree', {})).contexts[0].context;
	await open();

	const base = await js(read);
	assert(base.melee !== null && base.ac !== null && base.will !== null && base.str !== null, JSON.stringify(base));
	assert.deepStrictEqual([base.hp, base.status, base.changed], [base.maxHp, 'Unhurt', 0]);

	// damage through the amount box
	await js(`document.querySelector('.session input.amount').value = '3'; ${button('Damage')}.click(); return 1;`);
	let s = await js(read);
	assert.deepStrictEqual([s.hp, s.current, s.status], [base.maxHp - 3, String(base.maxHp - 3), 'Wounded']);

	// Bless from the effect list, then Shaken
	await js(setAndChange(addEffect, String(await js(presetIndex('Bless')))));
	s = await js(read);
	assert.strictEqual(s.melee, base.melee + 1, 'Bless +1 morale');
	await js(`const l = [...document.querySelectorAll('.ses-cond')].find(x => x.textContent.trim() === 'Shaken');
		l.querySelector('input').click(); return 1;`);
	s = await js(read);
	assert.deepStrictEqual([s.melee, s.will], [base.melee - 1, base.will - 2], 'Shaken -2 attack and saves');

	// edit Bless to +2
	await js(`${button('Edit')}.click(); return 1;`);
	await js(setAndChange(`document.querySelector('.ses-editor input[type="number"]')`, '2'));
	s = await js(read);
	assert.strictEqual(s.melee, base.melee, 'edited Bless +2, Shaken -2');

	// the Will TEMP box on the sheet and a manual AC adjustment in the panel
	await js(setAndChange(`document.querySelector('input[data-q="will"]')`, '+2'));
	await js(setAndChange(`[...document.querySelectorAll('.ses-quick')].find(x => x.textContent.startsWith('AC')).querySelector('input')`, '3'));
	s = await js(read);
	assert.deepStrictEqual([s.will, s.ac, s.touch, s.flat, s.willTemp], [base.will, base.ac + 3, base.touch + 3, base.flat + 3, '+2']);

	// Rage: +4 STR and CON (+2 modifier each), -2 AC
	await js(setAndChange(addEffect, String(await js(presetIndex('Rage')))));
	s = await js(read);
	assert.deepStrictEqual([s.str, s.con, s.maxHp, s.ac, s.melee],
		[base.str + 4, base.con + 4, base.maxHp + 2 * s.hitDice, base.ac + 1, base.melee + 2]);

	// notes are saved while typing
	await js(`const t = document.querySelector('.session textarea'); t.value = 'Owes 5 gp'; t.dispatchEvent(new Event('input')); return 1;`);

	// a new page load: everything must come back
	const before = await js(read);
	await open();
	s = await js(read);
	assert.deepStrictEqual(s, before, 'state survives a reload');
	assert(s.changed > 5, 'changed values are marked');
	const title = await js(`return document.querySelector('.adj[data-r="melee"]').title;`);
	assert(title.includes('morale (Bless)') && title.includes('Shaken'), title);

	// clear the session
	await js(`window.confirm = () => true; ${button('Clear session')}.click(); return 1;`);
	await open();
	s = await js(read);
	assert.deepStrictEqual(s, Object.assign({}, base), 'cleared');
	console.log('ui ok');
} finally {
	try { await send('session.end', {}); } catch (e) { /* closing */ }
	ff.kill();
}
