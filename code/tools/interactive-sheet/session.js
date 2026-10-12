/*
 * Session tracker for the interactive PCGen sheet. The sheet shows the values PCGen exported;
 * everything changed here (damage, conditions, short-term effects, manual adjustments) is
 * kept in the browser's local storage per character and applied on top of those values.
 * Longer-lasting buffs belong in PCGen's Temporary Bonuses tab, which is already included
 * in the exported values; do not apply the same buff in both places.
 */
var PcSession = (function () {
	'use strict';

	var VERSION = 1;
	var STATS = ['STR', 'DEX', 'CON', 'INT', 'WIS', 'CHA'];
	/* Bonus types that stack with themselves. Penalties always stack. */
	var STACKING = {untyped: true, dodge: true, circumstance: true};
	var TYPES = ['untyped', 'alchemical', 'armor', 'circumstance', 'competence', 'deflection', 'dodge',
		'enhancement', 'insight', 'luck', 'morale', 'natural armor', 'profane', 'racial', 'resistance',
		'sacred', 'shield', 'size', 'trait'];
	var TARGETS = [
		['attack', 'Attack rolls'], ['attack-melee', 'Melee attacks'], ['attack-ranged', 'Ranged attacks'],
		['damage', 'Weapon damage'], ['damage-melee', 'Melee damage'], ['ac', 'AC'], ['saves', 'All saves'],
		['fort', 'Fortitude'], ['ref', 'Reflex'], ['will', 'Will'], ['skills', 'Skill checks'],
		['init', 'Initiative'], ['cmb', 'CMB'], ['cmd', 'CMD'], ['hp', 'Max HP'],
		['STR', 'Strength'], ['DEX', 'Dexterity'], ['CON', 'Constitution'], ['INT', 'Intelligence'],
		['WIS', 'Wisdom'], ['CHA', 'Charisma']];
	/* AC bonus types that do not count against touch attacks */
	var NOT_TOUCH = {armor: true, shield: true, 'natural armor': true, enhancement: true};
	/* AC bonus types that also apply to CMD (penalties to AC always do) */
	var CMD_FROM_AC = {circumstance: true, deflection: true, dodge: true, insight: true, luck: true,
		morale: true, profane: true, sacred: true};

	function b(target, type, v) { return {t: target, type: type, v: v}; }

	/* Pathfinder 1e defaults; added effects are copies and can be edited (e.g. caster level). */
	var PRESETS = [
		{name: 'Bless', bonuses: [b('attack', 'morale', 1)]},
		{name: 'Prayer', bonuses: [b('attack', 'luck', 1), b('damage', 'luck', 1), b('saves', 'luck', 1),
			b('skills', 'luck', 1)]},
		{name: 'Heroism', bonuses: [b('attack', 'morale', 2), b('saves', 'morale', 2), b('skills', 'morale', 2)]},
		{name: 'Good Hope', bonuses: [b('attack', 'morale', 2), b('damage', 'morale', 2), b('saves', 'morale', 2),
			b('skills', 'morale', 2)]},
		{name: 'Divine Favor', bonuses: [b('attack', 'luck', 1), b('damage', 'luck', 1)]},
		{name: 'Inspire Courage', bonuses: [b('attack', 'competence', 1), b('damage', 'competence', 1)]},
		{name: 'Resistance', bonuses: [b('saves', 'resistance', 1)]},
		{name: 'Shield of Faith', bonuses: [b('ac', 'deflection', 2)]},
		{name: 'Mage Armor', bonuses: [b('ac', 'armor', 4)]},
		{name: 'Shield (spell)', bonuses: [b('ac', 'shield', 4)]},
		{name: 'Haste', bonuses: [b('attack', 'untyped', 1), b('ac', 'dodge', 1), b('ref', 'dodge', 1)]},
		{name: 'Rage', bonuses: [b('STR', 'morale', 4), b('CON', 'morale', 4), b('will', 'morale', 2),
			b('ac', 'untyped', -2)]},
		{name: "Bull's Strength", bonuses: [b('STR', 'enhancement', 4)]},
		{name: "Cat's Grace", bonuses: [b('DEX', 'enhancement', 4)]},
		{name: "Bear's Endurance", bonuses: [b('CON', 'enhancement', 4)]},
		{name: "Fox's Cunning", bonuses: [b('INT', 'enhancement', 4)]},
		{name: "Owl's Wisdom", bonuses: [b('WIS', 'enhancement', 4)]},
		{name: "Eagle's Splendor", bonuses: [b('CHA', 'enhancement', 4)]},
		{name: 'Flanking', bonuses: [b('attack-melee', 'untyped', 2)]},
		{name: 'Charging', bonuses: [b('attack-melee', 'untyped', 2), b('ac', 'untyped', -2)]},
		{name: 'Fighting defensively', bonuses: [b('attack', 'untyped', -4), b('ac', 'dodge', 2)]},
		{name: 'Total defense', bonuses: [b('ac', 'dodge', 4)]}
	];

	/* Conditions: untyped penalties, one checkbox each. */
	var CONDITIONS = [
		{id: 'shaken', name: 'Shaken', bonuses: [b('attack', 'untyped', -2), b('saves', 'untyped', -2),
			b('skills', 'untyped', -2)]},
		{id: 'frightened', name: 'Frightened', bonuses: [b('attack', 'untyped', -2), b('saves', 'untyped', -2),
			b('skills', 'untyped', -2)]},
		{id: 'sickened', name: 'Sickened', bonuses: [b('attack', 'untyped', -2), b('damage', 'untyped', -2),
			b('saves', 'untyped', -2), b('skills', 'untyped', -2)]},
		{id: 'dazzled', name: 'Dazzled', bonuses: [b('attack', 'untyped', -1)]},
		{id: 'fatigued', name: 'Fatigued', bonuses: [b('STR', 'untyped', -2), b('DEX', 'untyped', -2)]},
		{id: 'exhausted', name: 'Exhausted', bonuses: [b('STR', 'untyped', -6), b('DEX', 'untyped', -6)]},
		{id: 'entangled', name: 'Entangled', bonuses: [b('attack', 'untyped', -2), b('DEX', 'untyped', -4)]},
		{id: 'grappled', name: 'Grappled', bonuses: [b('attack', 'untyped', -2), b('DEX', 'untyped', -4)]},
		{id: 'prone', name: 'Prone', bonuses: [b('attack-melee', 'untyped', -4)]}
	];

	/* Manual adjustments: one untyped value per target. */
	var QUICK = [['ac', 'AC'], ['init', 'Init'], ['attack', 'Attack'], ['damage', 'Damage'],
		['saves', 'Saves'], ['skills', 'Skills'], ['cmd', 'CMD']];

	function newState() {
		return {v: VERSION, hp: {dmg: 0, nl: 0, temp: 0}, effects: [], conds: {}, quick: {}, notes: ''};
	}

	function int(x) {
		var n = parseInt(x, 10);
		return isNaN(n) ? 0 : n;
	}

	/* Make a loaded or imported state safe to use. */
	function normalize(s) {
		var out = newState();
		if (!s || typeof s !== 'object') {
			return out;
		}
		if (s.hp) {
			out.hp = {dmg: int(s.hp.dmg), nl: Math.max(0, int(s.hp.nl)), temp: Math.max(0, int(s.hp.temp))};
		}
		(Array.isArray(s.effects) ? s.effects : []).forEach(function (e) {
			if (e && typeof e.name === 'string') {
				out.effects.push({name: e.name, on: !!e.on, bonuses: (Array.isArray(e.bonuses) ? e.bonuses : [])
					.filter(function (x) { return x && typeof x.t === 'string'; })
					.map(function (x) { return b(x.t, TYPES.indexOf(x.type) >= 0 ? x.type : 'untyped', int(x.v)); })});
			}
		});
		if (s.conds && typeof s.conds === 'object') {
			CONDITIONS.forEach(function (c) { if (s.conds[c.id]) { out.conds[c.id] = true; } });
		}
		if (s.quick && typeof s.quick === 'object') {
			Object.keys(s.quick).forEach(function (k) { if (int(s.quick[k])) { out.quick[k] = int(s.quick[k]); } });
		}
		out.notes = typeof s.notes === 'string' ? s.notes : '';
		return out;
	}

	/* Every active bonus as {t, type, v, src}. */
	function contributions(state) {
		var list = [];
		state.effects.forEach(function (e) {
			if (e.on) {
				e.bonuses.forEach(function (x) { list.push({t: x.t, type: x.type, v: x.v, src: e.name}); });
			}
		});
		CONDITIONS.forEach(function (c) {
			if (state.conds[c.id]) {
				c.bonuses.forEach(function (x) { list.push({t: x.t, type: x.type, v: x.v, src: c.name}); });
			}
		});
		Object.keys(state.quick).forEach(function (k) {
			if (state.quick[k]) {
				list.push({t: k, type: 'untyped', v: state.quick[k], src: 'manual'});
			}
		});
		return list;
	}

	/*
	 * Apply the stacking rules. existing maps a bonus type to what the exported value already
	 * contains of that type (known for AC only); a new bonus of that type only adds the excess.
	 * Returns {total, parts: [text]}.
	 */
	function combine(contribs, existing) {
		existing = existing || {};
		var total = 0;
		var parts = [];
		var best = {};
		contribs.forEach(function (c) {
			if (!c.v) {
				return;
			}
			if (c.v < 0 || STACKING[c.type]) {
				total += c.v;
				parts.push(signed(c.v) + ' ' + c.type + ' (' + c.src + ')');
			} else if (!best[c.type] || c.v > best[c.type].v) {
				if (best[c.type]) {
					parts.push('not stacking: ' + signed(best[c.type].v) + ' ' + c.type + ' (' + best[c.type].src + ')');
				}
				best[c.type] = c;
			} else {
				parts.push('not stacking: ' + signed(c.v) + ' ' + c.type + ' (' + c.src + ')');
			}
		});
		Object.keys(best).forEach(function (type) {
			var c = best[type];
			var have = Math.max(0, int(existing[type]));
			var add = Math.max(0, c.v - have);
			total += add;
			if (add === c.v) {
				parts.push(signed(c.v) + ' ' + type + ' (' + c.src + ')');
			} else {
				parts.push(signed(add) + ' ' + type + ' (' + c.src + ' ' + signed(c.v) + ', sheet already has ' +
					signed(have) + ')');
			}
		});
		return {total: total, parts: parts};
	}

	function signed(n) {
		return (n >= 0 ? '+' : '') + n;
	}

	function abilityMod(score) {
		return Math.floor((score - 10) / 2);
	}

	/* Ability score changes and the resulting modifier changes. */
	function statChanges(pc, contribs) {
		var out = {};
		STATS.forEach(function (s) {
			var r = combine(contribs.filter(function (c) { return c.t === s; }));
			var base = pc.stats[s];
			if (base === undefined) {
				out[s] = {score: 0, mod: 0, parts: []};
				return;
			}
			var oldMod = abilityMod(base);
			var newMod = abilityMod(base + r.total);
			out[s] = {score: r.total, mod: newMod - oldMod, oldMod: oldMod, newMod: newMod, parts: r.parts};
		});
		return out;
	}

	/*
	 * What feeds each value shown on the sheet: the bonus targets, an optional type filter,
	 * the abilities whose modifier change applies, and the bonuses it already contains.
	 */
	function roleSpec(role, pc) {
		var m;
		switch (role) {
			case 'ac':
				return {targets: ['ac'], stats: ['DEX'], existing: pc.ac};
			case 'touch':
				return {targets: ['ac'], stats: ['DEX'], existing: pc.ac,
					filter: function (c) { return c.v < 0 || !NOT_TOUCH[c.type]; }};
			case 'flat':
				/* flat-footed loses Dex bonuses but keeps Dex penalties */
				return {targets: ['ac'], stats: ['DEX'], negativeStatOnly: true, existing: pc.ac,
					filter: function (c) { return c.type !== 'dodge' || c.v < 0; }};
			case 'cmd':
				return {targets: ['cmd', 'ac'], stats: ['STR', 'DEX'], existing: pc.ac,
					filter: function (c) { return c.t === 'cmd' || c.v < 0 || CMD_FROM_AC[c.type]; }};
			case 'init':
				return {targets: ['init'], stats: ['DEX']};
			case 'melee':
			case 'atk-melee':
				return {targets: ['attack', 'attack-melee'], stats: ['STR']};
			case 'ranged':
			case 'atk-ranged':
				return {targets: ['attack', 'attack-ranged'], stats: ['DEX']};
			case 'cmb':
			case 'grapple':
				return {targets: ['attack', 'cmb'], stats: ['STR']};
			case 'dmg-melee':
				return {targets: ['damage', 'damage-melee'], stats: ['STR']};
			case 'dmg-ranged':
				return {targets: ['damage'], stats: []};
			case 'hp':
				return {targets: ['hp'], stats: ['CON'], perHitDie: true};
			default:
				break;
		}
		if ((m = /^save-(.*)$/.exec(role))) {
			var save = m[1].substring(0, 4);
			if (save === 'fort') {
				return {targets: ['saves', 'fort'], stats: ['CON']};
			}
			if (save === 'refl') {
				return {targets: ['saves', 'ref'], stats: ['DEX']};
			}
			if (save === 'will') {
				return {targets: ['saves', 'will'], stats: ['WIS']};
			}
			return {targets: ['saves'], stats: []};
		}
		if ((m = /^skill-(.*)$/.exec(role))) {
			return {targets: ['skills'], stats: STATS.indexOf(m[1]) >= 0 ? [m[1]] : []};
		}
		return null;
	}

	/* The change to one sheet value: {delta, parts}. */
	function roleDelta(role, pc, contribs, stats) {
		var m;
		if ((m = /^(score|mod)-(.*)$/.exec(role))) {
			var sc = stats[m[2]];
			if (!sc) {
				return {delta: 0, parts: []};
			}
			return {delta: m[1] === 'score' ? sc.score : sc.mod, parts: sc.parts};
		}
		var spec = roleSpec(role, pc);
		if (!spec) {
			return {delta: 0, parts: []};
		}
		var mine = contribs.filter(function (c) {
			return spec.targets.indexOf(c.t) >= 0 && (!spec.filter || spec.filter(c));
		});
		var r = combine(mine, spec.existing);
		var delta = r.total;
		var parts = r.parts.slice();
		spec.stats.forEach(function (s) {
			var sc = stats[s];
			if (!sc || !sc.mod) {
				return;
			}
			var d = spec.negativeStatOnly ? Math.min(sc.newMod, 0) - Math.min(sc.oldMod, 0) : sc.mod;
			if (spec.perHitDie) {
				d *= pc.hitDice;
			}
			if (d) {
				delta += d;
				parts.push(signed(d) + ' ' + s + ' modifier ' + signed(sc.oldMod) + ' → ' + signed(sc.newMod) +
					(spec.perHitDie ? ' × ' + pc.hitDice + ' HD' : ''));
			}
		});
		return {delta: delta, parts: parts};
	}

	/* Rewrite an exported value: "13", "+5/+0", "1d8+1" or "2d6-1 plus 1d6 fire". */
	function adjustText(text, delta, role) {
		if (!delta) {
			return text;
		}
		if (/^dmg-/.test(role)) {
			var done = false;
			var out = text.replace(/(\d+d\d+)([+-]\d+)?/, function (all, dice, mod) {
				done = true;
				var n = int(mod) + delta;
				return dice + (n > 0 ? '+' + n : n < 0 ? String(n) : '');
			});
			if (done) {
				return out;
			}
		}
		return text.replace(/[+-]?\d+/g, function (num) {
			var n = int(num) + delta;
			return /^[+-]/.test(num) ? signed(n) : String(n);
		});
	}

	/* Hit points: {max, current, temp, nl, status}. */
	function hitPoints(pc, state, contribs, stats) {
		var r = roleDelta('hp', pc, contribs, stats);
		var max = pc.hp + r.delta;
		var current = max - state.hp.dmg;
		var con = pc.stats.CON === undefined ? 10 : pc.stats.CON + stats.CON.score;
		var status;
		if (current <= -con) {
			status = 'Dead';
		} else if (current < 0) {
			status = 'Dying';
		} else if (current === 0) {
			status = 'Disabled';
		} else if (state.hp.nl > current) {
			status = 'Unconscious (nonlethal)';
		} else if (state.hp.nl === current) {
			status = 'Staggered (nonlethal)';
		} else if (state.hp.dmg > 0) {
			status = 'Wounded';
		} else {
			status = 'Unhurt';
		}
		return {max: max, base: pc.hp, current: current, temp: state.hp.temp, nl: state.hp.nl, status: status,
			parts: r.parts};
	}

	/* Damage goes to temporary hit points first. */
	function takeDamage(state, amount) {
		amount = Math.max(0, int(amount));
		var fromTemp = Math.min(state.hp.temp, amount);
		state.hp.temp -= fromTemp;
		state.hp.dmg += amount - fromTemp;
	}

	/* Healing also heals the same amount of nonlethal damage. */
	function heal(state, amount) {
		amount = Math.max(0, int(amount));
		state.hp.dmg = Math.max(0, state.hp.dmg - amount);
		state.hp.nl = Math.max(0, state.hp.nl - amount);
	}

	function summary(bonuses) {
		var names = {};
		TARGETS.forEach(function (t) { names[t[0]] = t[1]; });
		return bonuses.map(function (x) {
			return signed(x.v) + (x.type === 'untyped' ? '' : ' ' + x.type) + ' ' + (names[x.t] || x.t);
		}).join(', ');
	}

	function copy(o) {
		return JSON.parse(JSON.stringify(o));
	}

	/* ---------------------------------------------------------------- page */

	function start(pc) {
		var key = 'pcgen-session:' + pc.name + '|' + pc.player;
		var state = load();
		var editing = -1;
		var root = document.getElementById('session');
		var adjusted = [].slice.call(document.querySelectorAll('.adj'));
		adjusted.forEach(function (el) { el.setAttribute('data-base', el.textContent.trim()); });

		var hash = /^#session=(.*)$/.exec(window.location.hash);
		if (hash) {
			try {
				state = normalize(JSON.parse(decodeURIComponent(hash[1])));
				save();
			} catch (e) {
				window.alert('Could not read the session in the link.');
			}
			if (window.history && window.history.replaceState) {
				window.history.replaceState(null, '', window.location.pathname + window.location.search);
			}
		}

		function load() {
			try {
				var raw = window.localStorage.getItem(key);
				return raw ? normalize(JSON.parse(raw)) : newState();
			} catch (e) {
				return newState();
			}
		}

		function save() {
			try {
				window.localStorage.setItem(key, JSON.stringify(state));
				return true;
			} catch (e) {
				return false;
			}
		}

		function el(tag, attrs, children) {
			var e = document.createElement(tag);
			Object.keys(attrs || {}).forEach(function (k) {
				if (k === 'text') {
					e.textContent = attrs[k];
				} else if (k.substring(0, 2) === 'on') {
					e.addEventListener(k.substring(2), attrs[k]);
				} else {
					e.setAttribute(k, attrs[k]);
				}
			});
			(children || []).forEach(function (c) {
				if (c) {
					e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
				}
			});
			return e;
		}

		function numberInput(value, onchange, cls) {
			return el('input', {type: 'number', step: '1', value: String(value), 'class': cls || 'num',
				onchange: function (ev) { onchange(int(ev.target.value)); changed(); }});
		}

		function select(options, value, onchange) {
			var s = el('select', {onchange: function (ev) { onchange(ev.target.value); changed(); }},
				options.map(function (o) {
					var opt = el('option', {value: o[0], text: o[1]});
					if (o[0] === value) {
						opt.selected = true;
					}
					return opt;
				}));
			return s;
		}

		function changed() {
			save();
			render();
		}

		function apply() {
			var contribs = contributions(state);
			var stats = statChanges(pc, contribs);
			adjusted.forEach(function (node) {
				var role = node.getAttribute('data-r');
				var base = node.getAttribute('data-base');
				var r = roleDelta(role, pc, contribs, stats);
				node.textContent = adjustText(base, r.delta, role);
				if (r.delta) {
					node.className = 'adj changed';
					node.title = 'Sheet: ' + base + '\n' + r.parts.join('\n');
				} else {
					node.className = 'adj';
					node.removeAttribute('title');
				}
			});
			var hp = hitPoints(pc, state, contribs, stats);
			[].forEach.call(document.querySelectorAll('[data-hp]'), function (node) {
				var what = node.getAttribute('data-hp');
				node.textContent = what === 'current' ? String(hp.current) + (hp.temp ? ' (+' + hp.temp + ' temp)' : '')
					: what === 'nl' ? String(hp.nl) : '';
			});
			[].forEach.call(document.querySelectorAll('input[data-q]'), function (input) {
				var v = state.quick[input.getAttribute('data-q')] || 0;
				if (document.activeElement !== input) {
					input.value = v ? signed(v) : '';
				}
			});
			return hp;
		}

		function render() {
			var hp = apply();
			root.innerHTML = '';
			root.appendChild(el('div', {'class': 'ses-title'}, [
				el('b', {text: 'Session'}),
				' — changes are kept in this browser for ' + pc.name + '. Hover a ',
				el('span', {'class': 'changed', text: 'blue'}), ' value to see why it changed.',
				el('span', {'class': 'ses-tools'}, [
					el('button', {type: 'button', text: 'Save to file', onclick: exportFile}),
					el('button', {type: 'button', text: 'Load from file', onclick: importFile}),
					el('button', {type: 'button', text: 'Clear session', onclick: function () {
						if (window.confirm('Clear damage, conditions, effects, adjustments and notes for ' + pc.name + '?')) {
							state = newState();
							changed();
						}
					}})
				])
			]));

			var amount = el('input', {type: 'number', min: '0', step: '1', 'class': 'num amount', placeholder: 'amount'});
			function withAmount(fn) {
				return function () {
					fn(int(amount.value));
					amount.value = '';
					changed();
				};
			}
			root.appendChild(el('div', {'class': 'ses-row'}, [
				el('span', {'class': 'ses-label', text: 'Hit points'}),
				el('span', {'class': 'ses-hp' + (hp.current < hp.max ? ' hurt' : ''),
					title: hp.parts.length ? 'Sheet: ' + hp.base + '\n' + hp.parts.join('\n') : '',
					text: hp.current + ' / ' + hp.max}),
				el('span', {'class': 'ses-status', text: hp.status}),
				el('span', {'class': 'ses-group'}, [
					amount,
					el('button', {type: 'button', text: 'Damage', onclick: withAmount(function (n) { takeDamage(state, n); })}),
					el('button', {type: 'button', text: 'Heal', onclick: withAmount(function (n) { heal(state, n); })}),
					el('button', {type: 'button', text: 'Nonlethal', onclick: withAmount(function (n) { state.hp.nl += n; })}),
					el('button', {type: 'button', text: 'Set temp HP', onclick: withAmount(function (n) { state.hp.temp = n; })})
				]),
				el('span', {'class': 'ses-group'}, [
					el('label', {}, ['Damage taken ', numberInput(state.hp.dmg, function (n) { state.hp.dmg = n; })]),
					el('label', {}, [' Nonlethal ', numberInput(state.hp.nl, function (n) { state.hp.nl = Math.max(0, n); })]),
					el('label', {}, [' Temp HP ', numberInput(state.hp.temp, function (n) { state.hp.temp = Math.max(0, n); })])
				])
			]));

			root.appendChild(el('div', {'class': 'ses-row'}, [el('span', {'class': 'ses-label', text: 'Conditions'})]
				.concat(CONDITIONS.map(function (c) {
					var box = el('input', {type: 'checkbox', onchange: function (ev) {
						if (ev.target.checked) {
							state.conds[c.id] = true;
						} else {
							delete state.conds[c.id];
						}
						changed();
					}});
					box.checked = !!state.conds[c.id];
					return el('label', {'class': 'ses-cond', title: summary(c.bonuses)}, [box, ' ' + c.name]);
				}))));

			var effects = el('div', {'class': 'ses-row'}, [el('span', {'class': 'ses-label', text: 'Effects'})]);
			state.effects.forEach(function (e, i) {
				var box = el('input', {type: 'checkbox', onchange: function (ev) { e.on = ev.target.checked; changed(); }});
				box.checked = e.on;
				effects.appendChild(el('div', {'class': 'ses-effect' + (e.on ? '' : ' off')}, [
					el('label', {}, [box, ' ', el('b', {text: e.name})]),
					' ' + summary(e.bonuses) + ' ',
					el('button', {type: 'button', text: editing === i ? 'Done' : 'Edit',
						onclick: function () { editing = editing === i ? -1 : i; render(); }}),
					el('button', {type: 'button', text: 'Remove', onclick: function () {
						state.effects.splice(i, 1);
						editing = -1;
						changed();
					}}),
					editing === i ? editor(e) : null
				]));
			});
			var presets = el('select', {}, [el('option', {value: '', text: 'Add effect…'})]
				.concat(PRESETS.map(function (p, i) { return el('option', {value: String(i), text: p.name}); }))
				.concat([el('option', {value: 'custom', text: 'Custom…'})]));
			presets.addEventListener('change', function () {
				var v = presets.value;
				if (!v) {
					return;
				}
				var effect = v === 'custom' ? {name: 'Custom effect', bonuses: [b('attack', 'untyped', 1)]}
					: copy(PRESETS[int(v)]);
				effect.on = true;
				state.effects.push(effect);
				editing = v === 'custom' ? state.effects.length - 1 : -1;
				changed();
			});
			effects.appendChild(el('div', {}, [presets, el('span', {'class': 'ses-hint',
				text: ' Buffs that last a while are better set in PCGen (Temporary Bonuses tab); the sheet already includes those.'})]));
			root.appendChild(effects);

			root.appendChild(el('div', {'class': 'ses-row'}, [el('span', {'class': 'ses-label', text: 'Adjust ±'})]
				.concat(QUICK.map(function (q) {
					return el('label', {'class': 'ses-quick'}, [q[1] + ' ', numberInput(state.quick[q[0]] || 0, function (n) {
						if (n) {
							state.quick[q[0]] = n;
						} else {
							delete state.quick[q[0]];
						}
					})]);
				}))
				.concat([el('span', {'class': 'ses-hint', text: 'Saves and attacks also have a TEMP box on the sheet.'})])));

			var notes = el('textarea', {rows: '2', placeholder: 'Notes for this session'});
			notes.value = state.notes;
			notes.addEventListener('input', function () { state.notes = notes.value; save(); });
			root.appendChild(el('div', {'class': 'ses-row'}, [el('span', {'class': 'ses-label', text: 'Notes'}), notes]));
		}

		function editor(e) {
			var box = el('div', {'class': 'ses-editor'}, [
				el('label', {}, ['Name ', el('input', {type: 'text', value: e.name,
					onchange: function (ev) { e.name = ev.target.value || 'Effect'; changed(); }})])
			]);
			e.bonuses.forEach(function (x, i) {
				box.appendChild(el('div', {}, [
					numberInput(x.v, function (n) { x.v = n; }),
					select(TYPES.map(function (t) { return [t, t]; }), x.type, function (v) { x.type = v; }),
					' to ',
					select(TARGETS, x.t, function (v) { x.t = v; }),
					el('button', {type: 'button', text: '✕', title: 'Remove this bonus',
						onclick: function () { e.bonuses.splice(i, 1); changed(); }})
				]));
			});
			box.appendChild(el('button', {type: 'button', text: 'Add bonus',
				onclick: function () { e.bonuses.push(b('attack', 'untyped', 1)); changed(); }}));
			return box;
		}

		function exportFile() {
			var blob = new Blob([JSON.stringify(state, null, 1)], {type: 'application/json'});
			var a = el('a', {href: URL.createObjectURL(blob),
				download: pc.name.replace(/[\\/:*?"<>|]/g, '_') + ' session.json'});
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
		}

		function importFile() {
			var input = el('input', {type: 'file', accept: '.json,application/json'});
			input.addEventListener('change', function () {
				var file = input.files[0];
				if (!file) {
					return;
				}
				var reader = new FileReader();
				reader.onload = function () {
					try {
						state = normalize(JSON.parse(reader.result));
						editing = -1;
						changed();
					} catch (e) {
						window.alert('That file is not a saved session.');
					}
				};
				reader.readAsText(file);
			});
			input.click();
		}

		[].forEach.call(document.querySelectorAll('input[data-q]'), function (input) {
			input.addEventListener('change', function () {
				var n = int(input.value);
				var k = input.getAttribute('data-q');
				if (n) {
					state.quick[k] = n;
				} else {
					delete state.quick[k];
				}
				changed();
			});
		});
		window.addEventListener('storage', function (ev) {
			/* the same sheet open in another tab */
			if (ev.key === key) {
				state = load();
				render();
			}
		});
		render();
	}

	return {start: start, normalize: normalize, newState: newState, contributions: contributions, combine: combine,
		statChanges: statChanges, roleDelta: roleDelta, adjustText: adjustText, hitPoints: hitPoints,
		takeDamage: takeDamage, heal: heal, PRESETS: PRESETS, CONDITIONS: CONDITIONS};
}());
