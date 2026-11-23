# Maintaining OpenTRAP

Notes for building, releasing, and picking the project back up on a fresh clone.
For contributing changes, see [CONTRIBUTING.md](CONTRIBUTING.md); for the bundled
components and their licences, see [DEPENDENCIES.md](DEPENDENCIES.md) and
[CREDITS.md](CREDITS.md).

## Fresh clone: get a working tree

Every task below is a recipe in the `justfile` (`just --list`); CONTRIBUTING.md
lists what the recipes need. A clone has the source but not the large, fetched
tools (they are gitignored). Fetch them once:

```
just jdk         # Linux x64: portable Temurin JDK into jdk/ (required for a portable AppImage)
just engine      # OWASP ZAP scan engine into engine/
just browser     # optional, Linux x64: the testing browser into browser/
```

Pinned versions live in `tools/versions`, the single source of truth; the fetch
recipes install those versions. Bump a line there as part of a release, then
re-run the matching fetch recipe.

Java is pinned twice there. `JAVA_RELEASE` (27) is the language and API level:
every build compiles with `--release` set to it, so it is the oldest JDK that
can build or run OpenTRAP. `JDK_VERSION` (Temurin 27.0.1.1+4) is the exact JDK
the AppImage ships. Both are on 27, the current Java release; when Temurin 28
is out, move both and the CI `java-version` together. `JAVA_RELEASE` must never
be above the runtime's major.

## Build and run

```
just build      # lean dev jar  -> dist/opentrap.jar
just run        # launch the dev jar (auto HiDPI scale; just run 2 to force)
just test       # run the test suite
```

## Roadmap

`ROADMAP.md` is generated, never edited by hand. Its single source is the registry
of not-yet-implemented features in `src/opentrap/ui/Unbuilt.java`, which also
drives the Roadmap tab under Discover and the dialog each placeholder opens.
After changing the registry, rebuild and regenerate the file:

```
just build
java -cp build opentrap.ui.Unbuilt ROADMAP.md
```

Commit the regenerated file with the change, so the repository and the interface
never disagree about what is open.

## Release (the AppImage is the only published artefact)

```
just appimage    # builds the release jar, then the AppImage -> dist/opentrap-x86_64.appimage
```

Test the AppImage by hand, then publish:

```
just publish <timestamp> <notes.md>
```

- `<timestamp>` is the build **embedded in the AppImage**. `just appimage`
  runs `just release`, which stamps a fresh `opentrap_<timestamp>.jar`, so the
  timestamp to publish is the jar inside the AppImage, not an earlier one left in
  `dist/`. Find it with:
  `./dist/opentrap-x86_64.appimage --appimage-extract 'usr/lib/app/*'` then
  `ls squashfs-root/usr/lib/app/`.
- `just publish` creates the GitHub release, uploads the AppImage under the fixed
  name `opentrap-x86_64.appimage`, marks it latest, and verifies that the
  permanent link
  `https://github.com/opentrap/opentrap/releases/latest/download/opentrap-x86_64.appimage`
  resolves to the new tag.

## Critical: AppImage portability (glibc)

The AppImage must be built from the **portable Temurin JDK** in `jdk/`
(`just jdk`), not the system JDK. Temurin is built on glibc 2.17, so the
packaged app runs across mainstream Linux. Building the runtime from a
bleeding-edge system JDK (for example on Kali, glibc 2.38) produces an AppImage
that quits before showing its window on older distributions such as Ubuntu LTS and
Debian stable, and fails the AppImageHub launch-and-screenshot test.

On Linux every recipe prefers `jdk/` when it is present, and `just appimage`
fetches it if missing and builds the release jar with it whatever JDK the other
recipes would pick. Temurin ships without `jmods`, so `just appimage` uses the
JDK as the runtime image directly rather than running `jlink`.

Verify portability after building: every binary in the AppImage should require no
newer than `GLIBC_2.17`:

```
dist/opentrap-x86_64.appimage --appimage-extract >/dev/null
find squashfs-root -type f \( -name '*.so*' -o -perm -u+x \) \
  -exec sh -c 'readelf -V "$1" 2>/dev/null | grep -oE "GLIBC_[0-9]+\.[0-9]+"' _ {} \; \
  | sort -V | tail -1
```

## How it fits together

A few parts are non-obvious and worth knowing before changing them.

**The scan engine runs as a separate process.** The release jar embeds the trimmed
OWASP ZAP engine as a resource (`/opentrap/engine/engine.zip`, put there by
`just release`). On first use `opentrap.engine.ZapEngine` extracts it once to
`~/.opentrap/engine`, then launches it **headless as its own Java process** using
the bundled runtime (`java.home/bin/java`). OpenTRAP never links ZAP in-process; it
drives it over ZAP's local HTTP API (`opentrap.engine.ZapApi`, e.g.
`http://127.0.0.1:8090`). This is why the bundled runtime must keep `bin/java` and
a full module set: the engine is a second JVM, not a library call.

**The engine is bound to the suite scope through a context.** Before each scan
job, `opentrap.engine.ZapScanner.applyScope` rebuilds one engine context
("OpenTRAP scope") from the current `Scope` rules, each rule turned into a
whole-URL regex by `Scope.Rule.urlRegex`, flags it as the engine's scope, and
starts the crawler, the AJAX crawler and the active scan inside it. The engine
refuses a seed outside the context and never requests or attacks anything the
context does not cover, so the excludes (logout pages and the like) and the
subdomain rule hold at the engine as well as in the suite. A scan that cannot
be bound to the scope fails rather than running unbounded. The engine's message
history is streamed into the logger by record type: its crawlers' pages become
`crawl` entries and feed the site map, its scanner's probes become `audit`
entries, and traffic it only relayed for the proxy is skipped because the proxy
recorded it already. The read position lives on the shared `ZapEngine`, so a
second scan carries on from where the first stopped.

**The testing browser is not bundled in the release.** It is large (~680 MB), so
the release stays small. `opentrap.proxy.EmbeddedBrowser` uses a Chromium found in
the product `browser/` folder (populated by `just browser`, or by the optional
in-app download) or one already on the system `PATH`. It launches with an isolated
profile under `~/.opentrap/browser`, pointed at the proxy
(`--proxy-server=http://127.0.0.1:<port>`) and trusting the proxy CA, so TLS
interception works with nothing to configure. It is launched with its own window
class (`--class=opentrap-browser`), and `opentrap.proxy.BrowserDesktopEntry`
installs a hidden desktop entry plus an icon set under `~/.local/share` that the
desktop maps to that class. Panels that take the icon a window advertises
itself (XFCE, Cinnamon, most X11 taskbars) ignore that mapping, so
`opentrap.proxy.BrowserWindowIcon` also watches the browser process and rewrites
the `_NET_WM_ICON` property of every window of that class, through `xprop`, as
each one opens. Either way the taskbar shows OpenTRAP's own orange globe rather
than the browser vendor's logo; only windows of that class are ever touched.
The browser is launched with `--ozone-platform=x11` on purpose: from Chrome 140
it picks Wayland automatically on a Wayland desktop, and as a native Wayland
client it pushes its vendor icon to the compositor itself (Chrome 135+) and does
not carry the class, so nothing above would attach. Through XWayland it does.
The helper's activity is recorded in `~/.opentrap/browser/window-icon.log`.

**HiDPI scaling works by relaunching the JVM.** Java fixes `sun.java2d.uiScale` at
startup, so the UI scales uniformly only if the factor is set before any window is
built. `opentrap.ui.UiScale` picks the factor (the `-Dopentrap.scale` flag, else the
saved preference, else a value detected from the screen) and, when it differs from
the running JVM, relaunches a fresh JVM with the factor applied via
`JAVA_TOOL_OPTIONS`. Inside the AppImage that relaunch is avoided: `AppRun` calls
`OpenTRAP --print-scale` (a headless probe that prints the factor and exits) and
sets the scale up front, because relaunching would tear down the AppImage mount and
kill the app.

**A project file is one serialised object graph in a zip, and it is slimmed on the
way out.** `opentrap.ui.StateIO` writes format version 6: the history, scope,
settings, session state, organizer, issues, tool states and scan cards. Every
version from 1 still opens. On write, `StateIO.historyToSave` filters the history
by the `SaveOptions` (scope, tools) and calls `LogEntry.slim`: an entry tagged with
the audit tool (`ProjectOptions.AUDIT_TOOL`) is written with headers only unless
`project.keepAuditBodies` is set, and any body over `project.bodyCapKb` is cut;
both leave a marker and the full length on the entry, which the message editor
shows as a note. The live session is never changed by a save. In memory,
`MessageStore` caps audit traffic at `project.auditMemoryMb` (default 100 MB) and
drops the oldest probes past it; nothing from any other tool is ever dropped.
Only the project keys in `ProjectOptions.KEYS` travel in the file, and they never
reach the preferences store; every other setting is the user's and stays out of
the file, and a key a file carries that is not in `KEYS` is skipped on open.
Every object stream read from a project goes through `core.ProjectSerial`, an
allowlist filter, so a new class in a saved state must be added to its list.

**Tiling and wlroots-family compositors need a toolkit hint.** Java's X11
toolkit assumes a reparenting window manager; under sway, i3, Hyprland, river,
dwl, bspwm and labwc, Swing windows paint blank white without
`_JAVA_AWT_WM_NONREPARENTING=1`. The JVM reads it from the environment at
startup, so both launchers (`AppRun` written by `just appimage`, and
`just run`) set it when `XDG_CURRENT_DESKTOP` names one of those and the caller
has not set it already. The in-app scale relaunch inherits the environment.

**The build identifier is the jar manifest version.** `just release` stamps
`Implementation-Version` with the UTC build timestamp; `opentrap.ui.Main.VERSION`
reads it back (a dev build reports `dev`). It appears in the title bar and About
box, and it is the release tag.

## Known limitations and scope

- **Linux x86_64 only.** The release is a single x86_64 Linux AppImage; there are no
  Windows, macOS or ARM builds. The code is portable Java, but the packaging, the
  bundled runtime and the AppImage target are x86_64 Linux.
- **The AppImage requires glibc 2.17 or newer** (see above), which covers
  mainstream distributions from roughly 2014 onward.
- **The testing browser is a separate, optional download**, not part of the
  release; without it OpenTRAP falls back to a system Chromium if one is installed.
- **Local state lives in `~/.opentrap/`** (the extracted engine, the browser
  profile, saved settings). Deleting it returns the app to first-run behaviour.

## Where things are

- `src/opentrap/` - application source (Java)
- `icon/opentrap-s1-light.svg`, `icon/opentrap-s1-dark.svg` - the S1 (SektionEins) fork logo, the app icon (window, dock, launcher, AppImage), for light and dark backgrounds, rasterised as `src/opentrap/ui/icons/opentrap-s1-<light|dark>_<size>.png`; `icon/wordmark-s1-light.svg`, `icon/wordmark-s1-dark.svg` - its wordmarks
- `icon/opentrap.svg`, `icon/wordmark.svg`, `src/opentrap/ui/icons/opentrap_<size>.png` - the original orange logo, kept from upstream and no longer used
- `docs/` - the GitHub Pages site (`index.md`) and README screenshots (`images/`)
- `tools/versions` - pinned versions of the JDK, ZAP engine, testing browser and vendored libraries
- `lib/` - vendored compile-time libraries (BouncyCastle, Brotli decoder)
