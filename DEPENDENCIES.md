# Bundled tools and dependencies

OpenTRAP drives a few external tools and vendors a couple of libraries. This is
how they are tracked and kept current across release cycles. It is for
developers; a user never has to think about it.

## What is bundled

| Tool | Version pin | Where | How it is installed | Licence |
|---|---|---|---|---|
| OWASP ZAP (scan engine) | `tools/versions` | `engine/` | `just engine` | Apache 2.0 |
| Chromium (browser) | `tools/versions` | `browser/` | `just browser` | BSD |
| BouncyCastle (CA) | `tools/versions` | `lib/` | jar in `lib/` | MIT |
| Brotli decoder | `tools/versions` | `lib/` | jar in `lib/` | MIT |

ZAP is the crawl and active-scan engine: OpenTRAP runs it **headless** (no UI)
as a child process on the loopback and drives it over its JSON API, mapping its
findings into OpenTRAP's own issue model. The user never sees ZAP. It is fetched
on demand (the Core package, no bundled JRE, since OpenTRAP runs on a JVM) rather
than packed into the jar. Chromium is the embedded testing browser. BouncyCastle
and the Brotli decoder are small jars vendored under `lib/` and unpacked into
`dist/opentrap.jar` by the build.

## Single source of truth

`tools/versions` holds the pinned version of every bundled tool and the
sha256 of every artifact the fetch recipes download. The fetch recipes in
the `justfile` install exactly those bytes and refuse a download whose hash
does not match, so a build is reproducible rather than pulling whatever is
latest that day.

Two checks keep a build to those pins:

- Every build refuses a `lib/` that holds other jars than the pinned ones, or
  other bytes (the sha256 pins are those of the jars on Maven Central).
- `just engine` records the ZAP pins and the hash of every installed file in
  `engine/.pinned`. `just release` refuses to embed an `engine/` that no
  longer matches that record or whose pins have moved since, so an add-on
  added or updated afterwards with `just addon` or `just addons update` never
  ships by accident. Re-run `just engine` to ship the latest add-ons.

ZAP itself is pinned, its add-ons are not. `just engine` installs the newest
version of every add-on OpenTRAP keeps that ZAP publishes for the pinned ZAP
release (`ZapVersions-<major.minor>.xml`, which ZAP's own update check reads),
each checked against the sha256 in that list. It installs the same add-ons as
`just addons update`, so a release carries the add-ons current on the day
`just engine` last ran; the file names in `engine/.pinned` record which.

## Release-cycle workflow

1. Run the update check:

       just check-updates

   It prints, for the ZAP engine, Chromium, BouncyCastle, the Brotli decoder,
   the Temurin JDK, appimagetool and just, the pinned version, the latest
   upstream version, and whether an update is available. It changes nothing.
   `just addons` does the same for the add-ons in `engine/`.

2. For each tool you want to move, edit its version and its SHA256 together
   in `tools/versions`. For a release asset, the sha256 is published alongside
   the download (GitHub shows asset digests on the release page); for the
   JDK, the Adoptium API lists it with each binary.

3. Re-install the bundled binaries:

       just engine     # after changing ZAP_VERSION, and for the latest add-ons
       just browser    # after changing CHROMIUM_REVISION
       just libs       # after changing a version for a jar in lib/

   `just libs` downloads each jar pinned in `tools/versions` from Maven
   Central, refuses one that does not match its sha256, and removes any
   other jar from `lib/`; commit the result. Central serves the sha256 next
   to most jars (`<jar>.sha256`, as for BouncyCastle). Where it serves only a
   `.sha1` (the Brotli decoder 0.1.2), check the download against that; on a
   mismatch `just libs` prints the hash it got. The three BouncyCastle jars
   depend on each other and move together.

4. Build and run the tests, then smoke-test a crawl and a scan:

       just test

5. Record the bump in the release notes, and keep the licence attribution in
   `CREDITS.md` current if a tool is added or removed.

## Why pin rather than always-latest

A pinned version means two developers, and CI, build the same product, and a
crawler or browser update that changes behaviour lands deliberately in a release
rather than silently between runs. The update check keeps the pins from going
stale without taking the choice of when to move away from you.
