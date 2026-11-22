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
- `just engine` records the engine pins and the hash of every installed file
  in `engine/.pinned`. `just release` refuses to embed an `engine/` that no
  longer matches that record or whose pins have moved since, so an add-on
  added or updated with `just addon` or `just addons update` never ships by
  accident. Move its pin in `tools/versions` and re-run `just engine` to ship
  it.

## Release-cycle workflow

1. Run the update check:

       just check-updates

   It prints, for the ZAP engine and its pinned add-ons, Chromium,
   BouncyCastle, the Brotli decoder, the Temurin JDK, appimagetool and just,
   the pinned version, the latest upstream version, and whether an update is
   available. It changes nothing.

2. For each tool you want to move, edit its version and its SHA256 together
   in `tools/versions`. For a release asset, the sha256 is published alongside
   the download (GitHub shows asset digests on the release page); for the
   JDK, the Adoptium API lists it with each binary.

3. Re-install the bundled binaries:

       just engine     # after changing ZAP_VERSION
       just browser    # after changing CHROMIUM_REVISION

   For a jar under `lib/`, replace it with the new version from Maven
   Central and update its version and sha256 pin. The three BouncyCastle
   jars depend on each other and move together.

4. Build and run the tests, then smoke-test a crawl and a scan:

       just test

5. Record the bump in the release notes, and keep the licence attribution in
   `CREDITS.md` current if a tool is added or removed.

## Why pin rather than always-latest

A pinned version means two developers, and CI, build the same product, and a
crawler or browser update that changes behaviour lands deliberately in a release
rather than silently between runs. The update check keeps the pins from going
stale without taking the choice of when to move away from you.
