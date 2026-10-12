// Usage: node engine-test.js <file containing PcSession>
const fs = require('fs');
const assert = require('assert');
const src = fs.readFileSync(process.argv[2], 'utf8');
const m = /var PcSession = [\s\S]*?\n\}\(\)\);/.exec(src);
assert(m, 'PcSession not found');
const S = new Function(m[0] + '; return PcSession;')();

const pc = {name: 'Test', player: 'Tester', hp: 13, hitDice: 3,
	stats: {STR: 7, DEX: 16, CON: 11, INT: 14, WIS: 10, CHA: 18},
	ac: {armor: 0, shield: 0, 'natural armor': 0, deflection: 1, dodge: 0, insight: 0, luck: 0, morale: 0, profane: 0, sacred: 0}};

function preset(name) { const p = JSON.parse(JSON.stringify(S.PRESETS.find(x => x.name === name))); p.on = true; return p; }
function deltas(state) {
	const c = S.contributions(state), st = S.statChanges(pc, c), out = {};
	for (const r of ['ac','touch','flat','cmd','init','melee','ranged','cmb','atk-melee','atk-ranged','dmg-melee','dmg-ranged','hp',
		'save-fortitude','save-reflex','save-will','skill-DEX','skill-STR','skill-CHA','score-STR','mod-STR','score-DEX','mod-DEX'])
		out[r] = S.roleDelta(r, pc, c, st).delta;
	return out;
}
let n = 0;
function check(name, state, expected) {
	const d = deltas(state);
	for (const k of Object.keys(expected)) assert.strictEqual(d[k], expected[k], name + ': ' + k + ' was ' + d[k]);
	n++;
}

let s = S.newState(); s.conds.fatigued = true;
check('fatigued', s, {'score-STR': -2, 'mod-STR': -1, 'score-DEX': -2, 'mod-DEX': -1, ac: -1, touch: -1, flat: 0,
	cmd: -2, init: -1, melee: -1, ranged: -1, cmb: -1, 'dmg-melee': -1, 'dmg-ranged': 0, 'save-reflex': -1,
	'save-fortitude': 0, 'skill-DEX': -1, 'skill-STR': -1, 'skill-CHA': 0});

s = S.newState(); s.conds.exhausted = true;   // DEX 16 -> 10: mod +3 -> 0; flat-footed keeps 0
check('exhausted', s, {'mod-DEX': -3, ac: -3, flat: 0, 'mod-STR': -3});

s = S.newState(); s.effects.push(preset('Shield of Faith'));   // +2 deflection over the ring's +1
check('shield of faith', s, {ac: 1, touch: 1, flat: 1, cmd: 1, melee: 0});

s = S.newState(); s.effects.push(preset('Bless'), preset('Heroism'), preset('Prayer'));
check('morale does not stack, luck does', s, {melee: 3, ranged: 3, cmb: 3, 'atk-melee': 3, 'save-will': 3,
	'skill-CHA': 3, 'dmg-melee': 1, ac: 0});

s = S.newState(); s.effects.push(preset('Rage'));   // STR 7->11 (+2 mod), CON 11->15 (+2 mod, x3 HD)
check('rage', s, {melee: 2, cmb: 2, 'dmg-melee': 2, hp: 6, 'save-fortitude': 2, 'save-will': 2, ac: -2, touch: -2,
	flat: -2, cmd: 0 /* +2 STR, -2 AC penalty */});

s = S.newState(); s.effects.push(preset('Haste'));
check('haste', s, {melee: 1, ranged: 1, ac: 1, touch: 1, flat: 0, cmd: 1, 'save-reflex': 1, 'save-will': 0});

s = S.newState(); s.effects.push(preset('Mage Armor'));
check('mage armor', s, {ac: 4, touch: 0, flat: 4, cmd: 0});

s = S.newState(); s.quick = {ac: 2, 'attack-melee': -1, saves: 1, fort: 1};
check('manual', s, {ac: 2, touch: 2, flat: 2, cmd: 0, melee: -1, ranged: 0, 'save-fortitude': 2, 'save-will': 1});

s = S.newState(); s.conds.shaken = true; s.conds.sickened = true;   // penalties from different conditions stack
check('penalties stack', s, {melee: -4, 'save-will': -4, 'dmg-melee': -2});

assert.strictEqual(S.adjustText('+5/+0', 2, 'melee'), '+7/+2');
assert.strictEqual(S.adjustText('13', -2, 'cmd'), '11');
assert.strictEqual(S.adjustText('-1', 1, 'melee'), '+0');
assert.strictEqual(S.adjustText('1d3-2', 2, 'dmg-melee'), '1d3');
assert.strictEqual(S.adjustText('1d8', -1, 'dmg-melee'), '1d8-1');
assert.strictEqual(S.adjustText('2d6+3 plus 1d6 fire', 1, 'dmg-melee'), '2d6+4 plus 1d6 fire');
assert.strictEqual(S.adjustText('1d8+1', 0, 'dmg-ranged'), '1d8+1');
n++;

s = S.newState(); s.hp.temp = 5;
S.takeDamage(s, 8); assert.deepStrictEqual(s.hp, {dmg: 3, nl: 0, temp: 0});
s.hp.nl = 4; S.heal(s, 2); assert.deepStrictEqual(s.hp, {dmg: 1, nl: 2, temp: 0});
let c = S.contributions(s), st = S.statChanges(pc, c);
assert.strictEqual(S.hitPoints(pc, s, c, st).status, 'Wounded');
s.hp.dmg = 13; assert.strictEqual(S.hitPoints(pc, s, c, st).status, 'Disabled');
s.hp.dmg = 20; assert.strictEqual(S.hitPoints(pc, s, c, st).status, 'Dying');
s.hp.dmg = 24; assert.strictEqual(S.hitPoints(pc, s, c, st).status, 'Dead');
s.hp.dmg = 3; s.hp.nl = 10; assert.strictEqual(S.hitPoints(pc, s, c, st).status, 'Staggered (nonlethal)');
n++;

const bad = S.normalize({hp: {dmg: '4', nl: -3}, effects: [{name: 'X', on: 1, bonuses: [{t: 'ac', type: 'bogus', v: '2'}]}, null, {}],
	conds: {shaken: 1, nonsense: 1}, quick: {ac: '0', init: '3'}, notes: 5});
assert.deepStrictEqual(bad, {v: 1, hp: {dmg: 4, nl: 0, temp: 0}, effects: [{name: 'X', on: true, bonuses: [{t: 'ac', type: 'untyped', v: 2}]}],
	conds: {shaken: true}, quick: {init: 3}, notes: ''});
n++;
console.log('ok, ' + n + ' groups passed');
