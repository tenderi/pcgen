# CLAUDE.md

Notes for a personal fork of PCGen that is built and run locally on Linux. The owner **does not
contribute upstream**.

## Git: use the fork, only the fork

**In all cases, work against the personal fork. Never touch upstream.**

- Remotes: `fork` = the personal fork (the only remote to push to); `origin` = upstream
  `PCGen/pcgen`, **read-only** (fetch/pull `master` from it, nothing else).
- All work is committed on the local branch `local`, which tracks **`fork/main`** (the fork's
  main branch holds our work). Push with `git push fork local:main` (or plain `git push`
  with `push.default=upstream`).
- Never push to `origin`, never open PRs or issues against `PCGen/pcgen`, and never create
  branches, releases or anything else upstream. Use `gh` only against the fork (pass
  `--repo <fork>` explicitly, since `gh` defaults to the upstream parent).
- The fork is public: keep personal data (names, emails, home paths, machine details) out of
  commits, file contents and commit messages.

## What this is

PCGen is a Java desktop app (Swing + JavaFX) for building and managing tabletop RPG characters
(D&D 3.5 / SRD, Pathfinder 1e, Starfinder, 5e-ish SRD, many smaller systems). Rules content is data
driven: `.lst`/`.pcc` files under `data/` define game systems and sourcebooks; `outputsheets/`
holds character-sheet templates (HTML/FreeMarker/PDF via FOP); `system/` holds game-mode config.
Entry point: `pcgen.system.Main` (`code/src/java/pcgen/system/Main.java`). For deep build/CI/convention
detail, see `AGENTS.md` (upstream's agent doc).

## Prerequisites (Arch-based Linux)

- **JDK 25 exactly** for the toolchain (`gradle.properties` `javaVersion=25`); newer JDKs (Arch's
  `jdk-openjdk`, Homebrew's `openjdk`) don't satisfy it. Gradle itself runs on any JDK ≥ 17.
  - Locally added: the foojay toolchain resolver (`settings.gradle`) makes Gradle **download**
    Temurin 25 into `~/.gradle/jdks` when no JDK 25 is installed. An installed one is preferred:
    `sudo pacman -S jdk25-openjdk` (Arch) / `brew install openjdk@25` (macOS).
  - Verify: `./gradlew -q javaToolchains`
  - If an installed JDK isn't found: `./gradlew -Porg.gradle.java.installations.paths=<jdk-25-home> run`
- **JavaFX is not a system package** here. The build downloads the Gluon OpenJFX SDK
  (`javafxVersion` in `gradle.properties`) into `mods/` (gitignored) via `extractJavaFXLocal`.
  `run`, `compileJava` and the tests depend on it, so it happens automatically on the first build.
  Don't install `java-openjfx` from pacman; it isn't used.
- Network access is needed on the first build (Gradle distribution, Maven deps, JavaFX SDK).

## Running

```bash
./gradlew run                             # build + launch the GUI
./gradlew run --args="--name-generator"   # standalone name generator
```

- `run` puts exploded `build/classes/java/main` on the classpath. Don't `clean`/rebuild while the
  app is running, or you'll get bogus `NoClassDefFoundError`s.
- On Wayland the Swing/JavaFX UI runs through XWayland, which works. HiDPI is handled by the local
  UI zoom feature (below).
- Heap for `run` is 2 GB (`maxHeapSize` in `build.gradle`); the Gradle daemon gets 4 GB.

### Installing (no Gradle at launch time)

- **Arch Linux:** `cd packaging/arch && makepkg -si` builds this checkout into `pcgen-fork` and
  installs it: the self-contained app image (own Java 25 runtime + JavaFX, no system Java needed)
  in `/opt/pcgen`, `/usr/bin/pcgen`, a desktop entry and an icon. `makepkg` without `-i` just builds
  the `.pkg.tar.zst` (~150 MB). The version is `<app version>.r<commit count>.g<commit>`.
- **macOS / other:** `./gradlew jpackageImage` → `build/jpackage/PcGen` (`PcGen.app` on macOS);
  `./gradlew fullJpackage` → native installer (`.dmg` / `.deb` / `.exe`). Both download their own
  Temurin + JavaFX jmods for the host platform.
- `./gradlew qbuild` is broken upstream (its `output/pcgen.jar` expects a `libs/` folder that
  isn't copied); don't use it.

## User data locations

- `config.ini` says where settings live via `settingsPath`. For `./gradlew run` (working dir = repo
  root, which is also the install root) it's in the repo root (gitignored). Choosing the "PCGen
  folder" option on first run puts settings in `<repo>/settings/` (gitignored); the Linux default is
  otherwise `~/.config/pcgen/`, and the "user dir" option uses `~/.pcgen/`. Don't delete settings
  directories without asking.
- **Packaged installs** (Arch package, `.app`, `.deb`) start with an unrelated working dir ($HOME,
  or `/` on macOS). Locally changed so that `config.ini` goes to the per-user settings dir
  (`~/.config/pcgen`, `~/Library/Preferences/pcgen`) and `pcgen.log` to the per-user log dir
  (`~/.local/state/pcgen`, `~/Library/Logs/PCGen`) instead of the working dir. See
  `Main.defaultConfigDir` and `Logging.readConfigurationWithLogsIn`.
- Character saves default to the XDG documents dir (`<Documents>/PCGen/characters`).
- `characters/` in the repo root holds sample `.pcg` files shipped with the source.
- **Startup sources:** `settings/options.ini` has `pcgen.options.autoloadSourcesAtStart=true`
  (Preferences → Sources), so each launch reloads `lastLoadedGame` / `lastLoadedSources`. As of
  2026-09-28 that's the group's Pathfinder 1e list (`Pathfinder_RPG`, 17 books from their
  character file). Loading a different set in the app replaces it on exit.
- `pcgen.log*` in the repo root are runtime/test logs (gitignored).

## Local modifications (not upstream)

**UI zoom:** Java under KDE/XWayland ignores a fractional desktop display scale and draws
everything at 1×. `code/src/java/pcgen/gui2/UIZoom.java` fixes this:
- The zoom level is stored as `uiZoom` in `config.ini`. If it's unset, the default comes from
  `GDK_SCALE` or `xrdb` `Xft.dpi` / 96, rounded to 25% steps.
- At startup the whole-number part goes to `sun.java2d.uiScale`, because Linux Java2D ignores
  fractions. The rest scales the Metal look-and-feel fonts. JavaFX gets the full value through
  `glass.gtk.uiScale`.
- **View → Zoom In/Out/Reset** (Ctrl +/−/0, plus Ctrl+= and numpad keys) rescale Swing fonts live
  and raise minimum table/tree row heights. JavaFX windows and the Java2D scale only change after
  a restart.
- The feature is hooked in at: `Main.main` (`UIZoom.initialize()` before AWT/JavaFX start),
  `PCGenUIManager.initializeGUI` (`installSwingZoom()`), `PCGenActionMap` (zoom actions),
  `PCGenMenuBar.createViewMenu`, and the `in_mnuView*` keys in `LanguageBundle.properties`.
  The test is `code/src/test/pcgen/gui2/UIZoomTest.java`.
- If an upstream pull conflicts in these files, keep both sides. The hooks are one-liners.
- Extra zoom keys use `Toolkit.getMenuShortcutKeyMaskEx()` (Cmd on macOS). Fonts are only
  overridden once the zoom differs from 100%, so native look-and-feel fonts (Aqua) stay intact.

**macOS:** `Main.configureMacDesktop` sets `apple.laf.useScreenMenuBar` and
`apple.awt.application.name` before AWT starts, so the menus live in the macOS menu bar.
`PCGenUIManager.initializeGUI` now installs the system look and feel *before* building the main
window. Upstream set it afterwards, so the main window stayed Metal (no Aqua/GTK). These are
untested on real macOS hardware here; verify on a Mac when possible.

**Linux bug fixes:**
- `DesktopBrowserLauncher`: under KDE/XWayland, Java reports `Desktop.Action.BROWSE` as unsupported,
  so Help → Documentation, info-pane links and About-dialog links only showed an "unable to browse"
  warning. The launcher now falls back to `xdg-open`, then to `Desktop.open` for `file:` URIs.
- `GuiUtility.existingInitialDirectory`: JavaFX file and directory choosers throw
  `IllegalArgumentException` for a missing or blank initial directory, so the button looks dead.
  Every `setInitialDirectory` call is now wrapped with this helper. New chooser code should use it too.
- `LocationPanel`: the settings-folder radio choice was always overwritten by the text-field path.
  An untouched "user dir" choice now keeps its current location.
- Preferences → Character Stats → **Purchase Mode Configuration** never opened: a JavaFX button built
  a Swing dialog on the FX thread and a thread assertion threw. The dialog now also has the Preferences
  window as its owner, so it isn't stacked behind it. OK now writes the config to disk too; the save
  was hooked to a Swing button that was never added to the dialog.
- Preferences → **Copy Settings**: the handler asserted it was off the FX thread, so after copying it
  never refreshed the other panels or confirmed.
- `PCGenFrame.show{Error,Info,Warning}Message` / `showInputDialog` threw when reached from the FX thread
  (via `ShowMessageDelegate`), so those messages were lost. They now use
  `GuiUtility.runOnJavaFXThreadAndWait`, which works from any thread.
- Equipment customizer **Buy** button had lost its handler in upstream `ffb42f2c62`; it's rewired now.
- Chooser, radio chooser, spell choice, equipment customizer, single-pref, kit and `AbstractDialog`
  (Preferences) dialogs committed state and disposed Swing windows on the FX thread; now done on the EDT.
- Removed a blank, actionless button from the Biography tab (leftover from removed custom bio fields).

**Known upstream issue, deliberately not fixed:** loading a Pathfinder character logs 72 ×
`Evaluation called on invalid variable: 'Score' / 'Mod' / 'CHANNEL*STATSCORE'`. Cause: since
#7563, `ScopeFacet.get(CharID, VarScoped)` asks for the global scope, and
`CDOMObject.getProviderFor(global)` returns the object itself. So stat `MODIFY` formulas run in a
per-object global scope instance that can't see the `PC.STAT` locals. The final values are still
correct (pfinttest passes). Restoring the pre-#7563 walk to the object's local scope removes the
warnings, but breaks 35e Jump speed bonuses (rsrdinttest Quasvin/JimDop/QPsiCrystal: −18, because
`MOVE[Walk]` reads 0 at bonus time). That's true even when only objects with a local scope are
changed. Revisit only together with that ordering problem.
Caveat found later: those three rsrdinttest cases also fail *without* any scope change when all
five `*inttest` suites run in one Gradle invocation, but pass when `rsrdinttest` runs alone. So
the narrow variant may have been fine. Judge it with `./gradlew rsrdinttest` run on its own.

**Threading rule these fixes follow:** a JavaFX handler (`setOnAction`, `OKCloseButtonBar`, `@FXML`)
runs on the FX thread. Anything touching Swing components, chooser/character facades or
`PCGenFrame` goes through `SwingUtilities.invokeLater`. JavaFX controls read or written from Swing
code go through `Platform.runLater` or `GuiUtility.runOnJavaFXThreadAndWait`. PCGen's `GuiAssertions`
throw when this is violated. The exception lands in the log (`pcgen.log` / console) as
`WrongThreadException`, and to the user the button just looks dead.

## Updating from upstream

```bash
git checkout master && git pull --ff-only origin master   # read-only use of upstream
git checkout local && git rebase master                   # resolve conflicts, keep both sides
git push --force-with-lease fork local:main               # rebase rewrote local; fork is personal
./gradlew run
```

If a pull bumps `javaVersion` in `gradle.properties`, install the matching `jdkNN-openjdk`.
If it bumps `javafxVersion`, delete `mods/` so the new SDK is fetched.

`gradlew.bat` may show as modified in `git status` as a line-ending-only diff (CRLF vs LF), which is
harmless on Linux. Don't commit it; `git checkout gradlew.bat` restores it if it ever blocks a pull.

## Testing

- `./gradlew test`: fast, headless unit tests (~17k).
- Character integration tests, one per game mode: `pfinttest sfinttest rsrdinttest srdinttest
  msrdinttest` (a few minutes in total). Run them for any change under `pcgen/cdom`, `pcgen/core` or
  the formula code.

## Static analysis (SpotBugs)

`./gradlew spotbugsMain` (SpotBugs 4.10.4, upgraded locally) only reports on `pcgen.base`,
`pcgen.cdom` and `pcgen.output`. The first rule in `code/standards/spotbugs_ignore.xml` excludes all
"older code", i.e. the GUI, core rules, I/O and plugins, so its report is nearly empty. For a real
bug hunt, run it once with `--info`, copy the `FindBugs2` command it logs, and rerun that command
with `-xml:withMessages=<out.xml>` and a copy of the filter without that first `<Match>`. That
gives ~2,600 findings. Triage `CORRECTNESS` / `MT_CORRECTNESS` first; most `SECURITY` /
`MALICIOUS_CODE` findings are noise for a desktop app. Exit code 1 from SpotBugs with `-exitcode`
means "bugs found", not a crash.

Known, deliberately unfixed findings: `EquipmentChoice.addParentsExistingEquipmentModifiersToChooser`
compares an `EquipmentModifier` to `this` (always unequal), but only for `TYPE=LASTCHOICE`, which no
shipped data uses. `ContainsToken.unparse` null capacity list is unreachable from `parse`.

## Troubleshooting

- "PCGen requires JDK 25 to build, but Gradle could not find one" → JDK 25 not installed / not
  detected; see prerequisites.
- Configuration-cache weirdness after changing JDKs or properties: `./gradlew --no-configuration-cache run`
  or `rm -rf .gradle/configuration-cache`.
- Stale/corrupt JavaFX download: `rm -rf mods && ./gradlew run`.
- Full reset of build outputs: `./gradlew clean` (also wipes `mods/`, `output/`, downloaded JDKs).
