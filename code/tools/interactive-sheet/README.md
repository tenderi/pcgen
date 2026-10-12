# Interactive HTML sheet

`outputsheets/d20/fantasy/htmlxml/csheet_fantasy_interactive.htm.ftl` is the standard fantasy HTML
sheet plus a session tracker: hit points, conditions, short-term effects (Bless, Haste, Rage, …),
manual adjustments and notes. Changes are kept in the browser's local storage per character
(name + player), applied on top of the exported values, and survive re-exports. Pathfinder
stacking rules apply: same-type bonuses don't stack (except dodge, circumstance, untyped),
penalties always stack, ability-score changes flow through their modifiers. AC knows which bonus
types the export already contains (e.g. a ring's deflection); other values don't, so a buff set in
PCGen's Temporary Bonuses tab must not also be added on the sheet.

The template is generated; edit the sources here, then rebuild:

    python3 -I code/tools/interactive-sheet/make_interactive.py \
        outputsheets/d20/fantasy/htmlxml/csheet_fantasy_std.htm.ftl \
        code/tools/interactive-sheet/session.js \
        outputsheets/d20/fantasy/htmlxml/csheet_fantasy_interactive.htm.ftl

- `session.js`: the tracker (rules engine and panel). It's wrapped in `<#noparse>`, so FreeMarker
  leaves it alone; it just must not contain the text `</#noparse>`.
- `make_interactive.py`: wraps every changeable value of the standard sheet in `<@adj "role">`
  (the exported text becomes the base value) and turns the TEMP boxes into inputs. Each pattern
  asserts its match count, so an upstream change to the standard sheet fails the build step
  instead of silently dropping markers.

Tests (export a character with the sheet first, see CLAUDE.md "Checking output sheets"):

    node code/tools/interactive-sheet/engine-test.js <exported.htm>           # rules engine
    node code/tools/interactive-sheet/ui-test.mjs <exported.htm> <empty dir>   # headless Firefox

`ui-test.mjs` drives Firefox over WebDriver BiDi (no extra packages; needs Node ≥ 22 and Firefox)
and checks the controls, the sheet's values and that the session survives a reload. The Gradle
`pfinttest` suite has `PfrpgInteractiveSheetTest`, which checks the export and its markers.

A link ending in `#session=<url-encoded JSON>` loads that session (used by tests; also a way to
move a session to another browser besides "Save to file" / "Load from file").
