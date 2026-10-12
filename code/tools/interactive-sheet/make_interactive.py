r"""Build csheet_fantasy_interactive.htm.ftl from csheet_fantasy_std.htm.ftl.

The interactive sheet is the standard HTML sheet with the values a session can change wrapped in
<@adj role> markers, TEMP boxes turned into inputs, and the session tracker (session.js) added.
Rerun after the standard sheet changes; every pattern asserts how often it matches, so a changed
standard sheet fails loudly instead of silently losing markers.

Usage (from the repository root):
  python3 -I code/tools/interactive-sheet/make_interactive.py \
      outputsheets/d20/fantasy/htmlxml/csheet_fantasy_std.htm.ftl \
      code/tools/interactive-sheet/session.js \
      outputsheets/d20/fantasy/htmlxml/csheet_fantasy_interactive.htm.ftl
"""
import re
import sys
from collections import Counter

std, js_path, out_path = sys.argv[1:4]
s = open(std, encoding='utf-8').read()
js = open(js_path, encoding='utf-8').read()
counts = Counter()


def wrap(pattern, role, expected):
    """Wrap every ${pcstring('<token>')} whose token matches pattern in <@adj role>."""
    global s
    rx = re.compile(r"\$\{pcstring\('(" + pattern + r")'\)\}")
    s, n = rx.subn(lambda m: '<@adj ' + role + '>' + m.group(0) + '</@adj>', s)
    counts[pattern] = n
    assert n == expected, (pattern, n, expected)


def replace(old, new, expected=1):
    global s
    n = s.count(old)
    assert n == expected, (old, n, expected)
    s = s.replace(old, new)


wrap(r'HP', '"hp"', 3)
wrap(r'AC\.Total', '"ac"', 1)
wrap(r'AC\.Touch', '"touch"', 1)
wrap(r'AC\.Flatfooted', '"flat"', 1)
wrap(r'INITIATIVEMOD', '"init"', 1)
wrap(r'CHECK\.\$\{checks\}\.TOTAL', '"save-" + pcstring("CHECK.${checks}.NAME")?lower_case', 1)
wrap(r'ATTACK\.MELEE\.TOTAL', '"melee"', 1)
wrap(r'ATTACK\.RANGED\.TOTAL', '"ranged"', 1)
wrap(r'ATTACK\.GRAPPLE\.TOTAL', '"grapple"', 1)
wrap(r'VAR\.CMB(?:_(?!STAT)[A-Za-z]+)?\.INTVAL\.SIGN', '"cmb"', 21)
wrap(r'VAR\.CMD_[A-Za-z]+\.INTVAL|VAR\.CMB_[A-Za-z]+_DEF\.INTVAL', '"cmd"', 20)
wrap(r'WEAPONH\.TOTALHIT', '"atk-melee"', 1)
wrap(r'WEAPONH\.DAMAGE', '"dmg-melee"', 1)
wrap(r'WEAPON\.\$\{weap\}\.(?:BASEHIT|OHHIT|THHIT|TWPHITH|TWPHITL|TWOHIT)', '"atk-melee"', 6)
wrap(r'WEAPON\.\$\{weap\}\.(?:BASICDAMAGE|OHDAMAGE|THDAMAGE)', '"dmg-melee"', 6)
# double, natural and non-standard melee weapons
wrap(r'WEAPON\.\$\{weap\}\.TOTALHIT', '"atk-melee"', 1)
wrap(r'WEAPON\.\$\{weap\}\.DAMAGE', '"dmg-melee"', 1)
wrap(r'WEAPON\.\$\{weap\}\.RANGELIST\.\$\{range1\}(?:\.CONTENTS\.\$\{ammo\})?\.TOTALHIT', '"atk-ranged"', 3)
wrap(r'WEAPON\.\$\{weap\}\.RANGELIST\.\$\{range2\}(?:\.CONTENTS\.\$\{ammo\})?\.DAMAGE', '"dmg-ranged"', 3)
wrap(r'ABILITYALL\.Natural Attack\.VISIBLE\.\$\{ability\}\.TYPE=NaturalAttack\.ASPECT\.NaturalAttackToHit\.SIGN',
     '"atk-melee"', 1)
wrap(r'ABILITYALL\.Natural Attack\.VISIBLE\.\$\{ability\}\.TYPE=NaturalAttack\.ASPECT\.NaturalAttackDamage',
     '"dmg-melee"', 1)
wrap(r'SKILLSIT\.\$\{skill\}\.TOTAL', '"skill-" + pcstring("SKILLSIT.${skill}.ABILITY")', 1)

# the TEMPORARY score / modifier columns of the ability table
replace("""<td class="abt">${pcstring('STAT.${stat}')}</td>""",
        """<td class="abt"><@adj "score-" + pcstring("STAT.${stat}.NAME")>${pcstring('STAT.${stat}')}</@adj></td>""")
replace("""<td class="abt">${pcstring('STAT.${stat}.MOD')}</td>""",
        """<td class="abt"><@adj "mod-" + pcstring("STAT.${stat}.NAME")>${pcstring('STAT.${stat}.MOD')}</@adj></td>""")

# TEMP boxes of the saves (in their loop), melee, ranged, CMB and grapple rows become inputs
temps = ['<#if (pcstring("CHECK.${checks}.NAME")?lower_case?starts_with("fort"))>fort'
         '<#elseif (pcstring("CHECK.${checks}.NAME")?lower_case?starts_with("ref"))>ref'
         '<#else>will</#if>',
         'attack-melee', 'attack-ranged', 'cmb', 'cmb']
parts = s.split('<td align="center" class="tempborder"><br /></td>')
assert len(parts) == len(temps) + 1, len(parts)
s = parts[0]
for q, rest in zip(temps, parts[1:]):
    s += ('<td align="center" class="tempborder"><input class="tmp" type="text" inputmode="numeric" data-q="'
          + q + '" /></td>' + rest)

# Pathfinder / 3.5 hit point table: the blank "wounds / current HP" and "subdual" boxes show the session
branch = '<#elseif (pcvar("UseAlternateDamage") = 0 )>'
head, tail = s.split(branch)
tail = tail.replace('<input type="text"/>', '<span class="hpbox" data-hp="current"></span>', 1)
tail = tail.replace('<input type="text"/>', '<span class="hpbox" data-hp="nl"></span>', 1)
s = head + branch + tail

# the marker macro, defined right after the ftl header
macro = ('<#-- Values the session tracker may change: the exported text is the base value. -->\n'
         '<#macro adj r><span class="adj" data-r="${r}"><#nested></span></#macro>\n')
first_newline = s.index('\n') + 1
s = s[:first_newline] + macro + s[first_newline:]

replace('<title>${pcstring(\'NAME\')} [${pcstring(\'POOL.COST\')} Points]</title>',
        '<title>${pcstring(\'NAME\')} [${pcstring(\'POOL.COST\')} Points] - interactive</title>')

css = """	/* session tracker */
	.session { font-family: Arial, sans-serif; font-size: small; background: #fffbe6; border: 2px solid #c9a227;
		padding: 4px 8px; margin-bottom: 6px; }
	.session button, .session select, .session input, .session textarea { font-size: small; }
	.session .num { width: 4em; }
	.session .amount { width: 5.5em; }
	.ses-title { margin-bottom: 4px; }
	.ses-tools { float: right; }
	.ses-row { border-top: 1px solid #e3d6a0; padding: 3px 0; }
	.ses-label { display: inline-block; width: 6.5em; font-weight: bold; vertical-align: top; }
	.ses-hp { font-size: large; font-weight: bold; margin-right: 6px; }
	.ses-hp.hurt { color: #b00020; }
	.ses-status { margin-right: 10px; font-style: italic; }
	.ses-cond, .ses-quick, .ses-group, .session label { margin-right: 10px; white-space: nowrap; }
	.ses-group { display: inline-block; }
	.ses-effect { margin-left: 6.5em; }
	.ses-effect.off { color: #888; }
	.ses-editor { margin: 3px 0 6px 1.5em; padding: 3px; border-left: 3px solid #c9a227; }
	.ses-hint { color: #666; font-size: x-small; }
	.session textarea { width: 70%; vertical-align: top; }
	.changed { color: #0b57d0; text-decoration: underline dotted; cursor: help; }
	.hpbox { display: inline-block; min-width: 3em; font-weight: bold; }
	input.tmp { width: 2.5em; text-align: center; border: 0; }
	@media print { .session { display: none; } }
"""
replace('\t.tempborder {', css + '\t.tempborder {')

data = """<!-- START Session Tracker (needs JavaScript; changes stay in this browser) -->
<div id="session" class="session"><noscript>The session tracker needs JavaScript.</noscript></div>
<script type="text/javascript">
var PcSheetData = {
	name: "${pcstring('NAME')?js_string}",
	player: "${pcstring('PLAYERNAME')?js_string}",
	hp: "${pcstring('HP')?js_string}",
	hitDice: "${pcstring('HITDICE')?js_string}",
	stats: {<@loop from=0 to=pcvar('COUNT[STATS]-1') ; stat , stat_has_next>"${pcstring('STAT.${stat}.NAME')?js_string}": "${pcstring('STAT.${stat}')?js_string}"<#if stat_has_next>, </#if></@loop>},
	ac: {armor: "${pcstring('AC.Armor')}", shield: "${pcstring('AC.Shield')}", "natural armor": "${pcstring('AC.NaturalArmor')}",
		deflection: "${pcstring('AC.Deflection')}", dodge: "${pcstring('AC.Dodge')}", insight: "${pcstring('AC.Insight')}",
		luck: "${pcstring('AC.Luck')}", morale: "${pcstring('AC.Morale')}", profane: "${pcstring('AC.Profane')}",
		sacred: "${pcstring('AC.Sacred')}"}
};
</script>
<!-- STOP Session Tracker -->
"""
# right after <body ...>
m = re.search(r'<body[^>]*>\n', s)
assert m, 'no body'
s = s[:m.end()] + data + s[m.end():]

script = """<script type="text/javascript">
<#noparse>
""" + js + """
(function () {
	'use strict';
	var d = PcSheetData;
	var stats = {};
	Object.keys(d.stats).forEach(function (k) {
		var n = parseInt(d.stats[k], 10);
		if (!isNaN(n)) {
			stats[k.toUpperCase()] = n;
		}
	});
	var ac = {};
	Object.keys(d.ac).forEach(function (k) { ac[k] = parseInt(d.ac[k], 10) || 0; });
	/* HITDICE looks like "(3d6)" or "(2d8+4d10)" */
	var hd = 0;
	(d.hitDice.match(/\\d+(?=d\\d)/g) || []).forEach(function (n) { hd += parseInt(n, 10); });
	PcSession.start({name: d.name, player: d.player, hp: parseInt(d.hp, 10) || 0, hitDice: hd || 1,
		stats: stats, ac: ac});
}());
</#noparse>
</script>
"""
replace('</body>', script + '</body>')

open(out_path, 'w', encoding='utf-8').write(s)
print('wrapped:', dict(counts))
