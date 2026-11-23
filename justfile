# Build, test, package and fetch the dependencies of OpenTRAP, on Linux and
# macOS. This is the only entry point; there are no build scripts besides it.
#
#   just build        compile and pack dist/opentrap.jar
#   just test         build, then compile and run the test suite
#   just test-sandbox     only the cases a sandboxed agent session can run (headless)
#   just test-nosandbox   only the cases that need a display, the rest of just test
#   just run          launch the app (builds first if the jar is missing)
#   just run 1.5      launch at a fixed interface scale; later arguments go to the app
#   just release      build the self-contained release jar dist/opentrap_<timestamp>.jar
#   just appimage     Linux x64: pack the release jar into dist/opentrap-x86_64.appimage
#   just publish 20261019-085441 notes.md   Linux: publish a tested AppImage to GitHub
#   just clean        remove build/, build-tests/, dist/ and AppDir/
#
#   just libs         fetch the jars pinned in tools/versions into lib/ (it is committed)
#   just jdk          Linux x64: fetch the pinned portable JDK into jdk/
#   just engine       fetch the pinned OWASP ZAP scan engine, with the latest add-ons, into engine/
#   just browser      Linux x64: fetch the pinned Chromium and chromedriver into browser/
#   just addon spiderAjax 23.33.0    add a ZAP add-on to engine/plugin/
#   just addon pscanrulesBeta 50 beta <sha256>   fixed quality, verified hash
#   just addons                      compare installed add-ons with the latest
#   just addons update               update every installed add-on to the latest
#   just addons update retire        also install add-ons by id (with dependencies)
#   just check-updates               compare the pins in tools/versions with upstream
#   just install-desktop             Linux: menu entry and icon for this checkout
#   just install-desktop system      the same for all users (needs root)
#
# "Latest" add-ons are the newest versions ZAP publishes for the engine's own
# ZAP release (ZapVersions-<major.minor>.xml), so updates stay compatible.
#
# The app runs a dev build's engine from engine/ unless ~/.opentrap/engine
# (left by a release jar) holds one; that one wins.
#
# The JDK is OPENTRAP_JDK when set. Otherwise it is the pinned jdk/ on Linux
# when present, then JAVA_HOME, then the javac on PATH; on macOS it is the
# Temurin that java_home reports for the pinned major version.
#
# Every pin in tools/versions is in each recipe's environment. Recipes run
# under the first bash on PATH and take their arguments as positional
# parameters, so an argument is never pasted into the script text.

set shell := ["bash", "-euo", "pipefail", "-c"]
set dotenv-path := "tools/versions"
set positional-arguments

jdk_major := replace_regex(env("JDK_VERSION"), '[.+].*', '')
jdk := env("OPENTRAP_JDK", if os() == "macos" {
    shell('/usr/libexec/java_home -v "$1" 2>/dev/null || echo "/Library/Java/JavaVirtualMachines/temurin-$1.jdk/Contents/Home"', jdk_major)
} else if path_exists(justfile_directory() / "jdk/bin/javac") == "true" {
    justfile_directory() / "jdk"
} else {
    env("JAVA_HOME", `j="$(command -v javac 2>/dev/null)" && dirname "$(dirname "$(readlink -f "$j")")" || true`)
})

export JAVA_HOME := jdk

# How a recipe calls another recipe of this justfile, and how it hashes a file.
just := quote(just_executable()) + " --justfile " + quote(justfile())
hasher := if os() == "macos" { "shasum -a 256" } else { "sha256sum" }

default: build

# Compile the sources and pack them, the icons and the vendored jars into dist/opentrap.jar
build: _classes
    "$JAVA_HOME/bin/jar" --create --file dist/opentrap.jar --main-class opentrap.ui.Main -C build .
    @echo "Built dist/opentrap.jar ($(du -h dist/opentrap.jar | cut -f1))"

# Build, then compile and run the test suite (tests/ is never packed into the jar)
test: build (_tests "all")

# Run every case that passes in a sandboxed agent session: headless, none that need a display
test-sandbox: build (_tests "sandbox")

# Run only the cases that need a display, the ones test-sandbox leaves out
test-nosandbox: build (_tests "nosandbox")

# Compile the tests and run the cases $1 selects: all, sandbox or nosandbox
_tests cases:
    #!/usr/bin/env bash
    set -euo pipefail
    rm -rf build-tests && mkdir -p build-tests
    echo "Compiling tests..."
    srcs=()
    while IFS= read -r f; do srcs+=("$f"); done < <(find tests -name '*.java')
    "$JAVA_HOME/bin/javac" --release "$JAVA_RELEASE" -nowarn -d build-tests -cp "lib/*:build" "${srcs[@]}"
    echo "Running tests..."
    echo
    # Tests create their scratch files with Files.createTemp*. Java on macOS
    # takes its temp directory from the system, not from $TMPDIR, so a
    # sandboxed run would write somewhere it may not. Follow $TMPDIR when set.
    # A sandbox has no display, and a JVM that is not headless hangs there
    # while it initialises AppKit, so the sandbox selection runs headless.
    jvm=()
    if [ "$1" = sandbox ]; then jvm+=(-Djava.awt.headless=true); fi
    exec "$JAVA_HOME/bin/java" -Djava.io.tmpdir="${TMPDIR:-/tmp}" -Dopentrap.test.cases="$1" "${jvm[@]}" \
        -cp "lib/*:build:build-tests" RunTests

# Launch OpenTRAP; a first argument such as 1.5 or 2 forces the interface scale, the rest go to the app
run *args: _check-jdk
    #!/usr/bin/env bash
    set -euo pipefail
    [ -f dist/opentrap.jar ] || {{ just }} build
    # The interface scale is normally detected from the screen (a 4K display
    # gets 200%, 2.5K 150%, anything smaller 100%), and a choice from the
    # view menu overrides it from then on. A numeric first argument or
    # OPENTRAP_SCALE forces one for this launch.
    scale=""
    if [[ "${1:-}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then scale="$1"; shift; fi
    scale="${OPENTRAP_SCALE:-$scale}"
    # Max heap as a share of RAM; the scan engine and the testing browser run
    # as their own processes outside this JVM.
    opts=(-XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:G1PeriodicGCInterval=300000)
    if [ -n "$scale" ]; then
        # Setting the real HiDPI property here means the first JVM already has
        # the right scale, so the app does not have to relaunch itself.
        opts+=("-Dsun.java2d.uiScale=$scale" "-Dopentrap.scaled=$scale")
    fi
    if [ "$(uname -s)" = Darwin ]; then
        opts+=(-Xdock:name=OpenTRAP -Xdock:icon=src/opentrap/ui/icons/opentrap_256.png)
    elif [ -z "${_JAVA_AWT_WM_NONREPARENTING+x}" ]; then
        # Java's X11 toolkit assumes a reparenting window manager. Under the
        # tiling and wlroots-family compositors below, Swing windows paint
        # blank white unless this hint is set. Set it only when the caller has
        # not; it is harmless where it is not needed.
        for d in $(printf '%s' "${XDG_CURRENT_DESKTOP:-}" | tr ':' ' ' | tr '[:upper:]' '[:lower:]'); do
            case "$d" in
                sway|i3|hyprland|river|dwl|bspwm|labwc) export _JAVA_AWT_WM_NONREPARENTING=1; break ;;
            esac
        done
    fi
    exec "$JAVA_HOME/bin/java" "${opts[@]}" -jar dist/opentrap.jar "$@"

# Build the self-contained release jar, with the scan engine in engine/ embedded
release: _classes
    #!/usr/bin/env bash
    set -euo pipefail
    # Unlike the lean dev jar, this one carries the trimmed ZAP engine as a
    # resource. On first run the app extracts it once to ~/.opentrap/engine
    # and runs it headless, so the one jar is all a user needs. The UTC
    # timestamp names the file and is the jar's Implementation-Version, which
    # the app shows in its title. The testing browser is not embedded.
    ts="$(date -u +%Y%m%d-%H%M%S)"
    echo "Release build $ts (UTC)"
    if ls engine/zap-*.jar >/dev/null 2>&1; then
        # Ship only the engine 'just engine' last installed, for the current
        # ZAP pins: nothing added, removed or updated since (such as by
        # 'just addon'), and no stray files.
        [ -f engine/.pinned ] || { echo "engine/ was not installed by 'just engine', so it cannot be checked against tools/versions. Run 'just engine'." >&2; exit 1; }
        state="$(mktemp "${TMPDIR:-/tmp}/opentrap-engine.XXXXXX")"
        trap 'rm -f "$state"' EXIT
        {{ just }} _engine-state > "$state"
        if ! cmp -s engine/.pinned "$state"; then
            echo "engine/ is not what 'just engine' installed for tools/versions (- recorded, + now):" >&2
            diff engine/.pinned "$state" | grep '^[<>]' | sed 's/^</-/; s/^>/+/' | head -20 >&2 || true
            echo "Run 'just engine' to reinstall ZAP ${ZAP_VERSION} with the latest add-ons." >&2
            exit 1
        fi
        echo "Embedding scan engine (as 'just engine' installed it)..."
        mkdir -p build/opentrap/engine
        (cd engine && zip -qr -1 ../build/opentrap/engine/engine.zip .)
        zv="$(ls engine/zap-*.jar | head -1 | sed -E 's#.*/zap-([0-9.]+)\.jar#\1#')"
        printf '%s' "$zv" > build/opentrap/engine/VERSION
        echo "  engine $zv embedded ($(du -h build/opentrap/engine/engine.zip | cut -f1))"
    else
        echo "WARNING: no engine/ present. Run 'just engine' first, or the release jar"
        echo "         will fall back to a system ZAP at runtime if one is installed."
    fi
    manifest="$(mktemp "${TMPDIR:-/tmp}/opentrap-manifest.XXXXXX")"
    trap 'rm -f "$manifest" ${state:+"$state"}' EXIT
    printf 'Implementation-Version: %s\n' "$ts" > "$manifest"
    out="dist/opentrap_${ts}.jar"
    "$JAVA_HOME/bin/jar" --create --file "$out" --manifest "$manifest" --main-class opentrap.ui.Main -C build .
    echo
    echo "Built $out ($(du -h "$out" | cut -f1))"
    echo "Run it with:  java -jar $out"

# Pack the release jar, a Java runtime and the engine into one AppImage
[linux]
appimage:
    #!/usr/bin/env bash
    set -euo pipefail
    [ "$(uname -m)" = x86_64 ] || { echo "The AppImage is built for x86_64 only; this machine is $(uname -m)." >&2; exit 1; }
    # The runtime inside must come from the pinned portable JDK (old glibc),
    # or the app only starts on machines as new as the build host. Use it for
    # the release build too, and for jpackage.
    {{ just }} jdk
    export JAVA_HOME="$PWD/jdk"
    echo ">> building the self-contained release jar"
    OPENTRAP_JDK="$JAVA_HOME" {{ just }} release
    jar="$(ls -t dist/opentrap_*.jar | head -1)"

    # appimagetool runs as part of this build, so it is fetched pinned to the
    # version and sha256 in tools/versions and cached per version under
    # ~/.cache/opentrap. An unverified download is never run.
    ait="$HOME/.cache/opentrap/appimagetool-x86_64-${APPIMAGETOOL_VERSION}.AppImage"
    if [ ! -x "$ait" ]; then
        echo ">> fetching appimagetool ${APPIMAGETOOL_VERSION}"
        mkdir -p "$(dirname "$ait")"
        trap 'rm -f "$ait.part"' EXIT
        curl -fsSL -o "$ait.part" "https://github.com/AppImage/appimagetool/releases/download/${APPIMAGETOOL_VERSION}/appimagetool-x86_64.AppImage"
        {{ just }} _verify "$ait.part" "$APPIMAGETOOL_SHA256"
        chmod +x "$ait.part"
        mv "$ait.part" "$ait"
    fi

    echo ">> assembling a portable Java runtime from the bundled JDK"
    rm -rf build/otin build/appimage build/runtime AppDir
    mkdir -p build/otin
    cp "$jar" build/otin/
    # The runtime must keep bin/java and a full module set: the bundled scan
    # engine runs as its own Java process (java.home/bin/java), and jpackage's
    # default runtime strips bin/java and trims modules. The portable Temurin
    # ships without jmods, so jlink cannot repackage it; copy it as the runtime
    # image instead and drop only what is not needed at run time.
    cp -a jdk build/runtime
    rm -rf build/runtime/lib/src.zip build/runtime/include build/runtime/man \
           build/runtime/demo build/runtime/sample build/runtime/jmods

    echo ">> jpackage: self-contained app image using that runtime"
    "$JAVA_HOME/bin/jpackage" --type app-image --name OpenTRAP \
        --input build/otin --main-jar "$(basename "$jar")" --main-class opentrap.ui.Main \
        --icon src/opentrap/ui/icons/opentrap_256.png \
        --runtime-image build/runtime --java-options "-XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:G1PeriodicGCInterval=300000" \
        --dest build/appimage

    echo ">> assembling AppDir (with a scale-probing launcher)"
    mkdir -p AppDir/usr
    cp -a build/appimage/OpenTRAP/. AppDir/usr/
    cp src/opentrap/ui/icons/opentrap_256.png AppDir/opentrap.png
    # The launcher picks a HiDPI scale with a quick headless probe and sets it
    # before the real JVM starts. Inside an AppImage the in-app relaunch the
    # plain jar uses would tear down the mount and kill the app, so the scale
    # is chosen up front instead. AppRun is generated, and stays /bin/sh.
    cat > AppDir/AppRun <<'RUN'
    #!/bin/sh
    HERE="$(dirname "$(readlink -f "$0")")"
    # Java's X11 toolkit assumes a reparenting window manager. Under the tiling
    # and wlroots-family compositors below, Swing windows paint blank white unless
    # this hint is set. Set it only when the caller has not set it themselves; it
    # is harmless where it is not needed.
    if [ -z "${_JAVA_AWT_WM_NONREPARENTING+x}" ]; then
      for d in $(printf '%s' "${XDG_CURRENT_DESKTOP:-}" | tr ':' ' ' | tr '[:upper:]' '[:lower:]'); do
        case "$d" in
          sway|i3|hyprland|river|dwl|bspwm|labwc) export _JAVA_AWT_WM_NONREPARENTING=1; break ;;
        esac
      done
    fi
    SCALE="$("$HERE/usr/bin/OpenTRAP" --print-scale 2>/dev/null | tr -d '[:space:]')"
    case "$SCALE" in
      1.25|1.5|2|2.0|3|3.0) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dsun.java2d.uiScale=$SCALE -Dopentrap.scaled=$SCALE" ;;
    esac
    exec "$HERE/usr/bin/OpenTRAP" "$@"
    RUN
    chmod +x AppDir/AppRun
    cat > AppDir/OpenTRAP.desktop <<'DESK'
    [Desktop Entry]
    Type=Application
    Name=OpenTRAP
    Comment=Open web security testing proxy
    Exec=OpenTRAP
    Icon=opentrap
    Categories=Development;Network;Security;
    Terminal=false
    DESK

    echo ">> packing the AppImage"
    ARCH=x86_64 "$ait" --appimage-extract-and-run AppDir dist/opentrap-x86_64.appimage
    echo
    echo "Built dist/opentrap-x86_64.appimage"
    echo "Run it with:  chmod +x dist/opentrap-x86_64.appimage && ./dist/opentrap-x86_64.appimage"

# Publish the tested AppImage in dist/ as a GitHub release; the timestamp names the jar it was built from
[linux]
publish timestamp notes:
    #!/usr/bin/env bash
    set -euo pipefail
    # A release is exactly one file, the AppImage, which carries the Java
    # runtime and the scan engine; the jar is not published. The asset keeps
    # a fixed name, so the permanent link
    #   https://github.com/opentrap/opentrap/releases/latest/download/opentrap-x86_64.appimage
    # always serves the newest release. The version is the release tag.
    ts="$1" notes="$2"
    repo="opentrap/opentrap"
    name="opentrap-x86_64.appimage"
    app="$PWD/dist/$name"
    [ -f "dist/opentrap_${ts}.jar" ] || { echo "missing dist/opentrap_${ts}.jar" >&2; exit 1; }
    [ -f "$app" ] || { echo "missing dist/$name" >&2; exit 1; }
    [ -f "$notes" ] || { echo "missing notes file $notes" >&2; exit 1; }

    stage="$(mktemp -d "${TMPDIR:-/tmp}/opentrap.XXXXXX")"
    trap 'rm -rf "$stage"' EXIT
    # The AppImage must have been built from this jar.
    (cd "$stage" && "$app" --appimage-extract >/dev/null)
    if [ ! -f "$stage/squashfs-root/usr/lib/app/opentrap_${ts}.jar" ]; then
        echo "the AppImage does not contain opentrap_${ts}.jar; rebuild it with 'just appimage'" >&2
        exit 1
    fi
    rm -rf "$stage/squashfs-root"
    cp "$app" "$stage/$name"

    echo ">> publishing $ts"
    gh release create "$ts" "$stage/$name" \
        --repo "$repo" --title "OpenTRAP $ts" --notes-file "$notes" --latest

    echo ">> verifying"
    local_sum="$({{ hasher }} "$stage/$name" | cut -d' ' -f1)"
    remote_sum="$(gh release view "$ts" --repo "$repo" --json assets \
        --jq ".assets[] | select(.name==\"$name\") | .digest" | sed 's/^sha256://')"
    target="$(curl -sI "https://github.com/$repo/releases/latest/download/$name" \
        | grep -i '^location' | grep -o 'releases/download/[^/]*' || true)"
    if [ "$local_sum" = "$remote_sum" ] && [ "$target" = "releases/download/$ts" ]; then
        echo "   ok  $name  (checksum matches, latest link -> $ts)"
    else
        echo "   FAIL $name  local=$local_sum remote=$remote_sum latest->$target" >&2
        exit 1
    fi
    echo "Published https://github.com/$repo/releases/tag/$ts"

# Remove build outputs
clean:
    rm -rf build build-tests dist AppDir

# Fetch the pinned portable Temurin JDK into jdk/ (the AppImage runtime is built from it)
[linux]
jdk:
    #!/usr/bin/env bash
    set -euo pipefail
    # Temurin is built on an old glibc (2.17), so a runtime made from it
    # starts on mainstream Linux, not only on the machine that built it. The
    # exact release and its sha256 are pinned in tools/versions.
    [ "$(uname -m)" = x86_64 ] || { echo "The pinned JDK is for x86_64 Linux; this machine is $(uname -m). Set OPENTRAP_JDK to a JDK home instead." >&2; exit 1; }
    if [ -x jdk/bin/javac ]; then
        # The JDK's own release file names its exact version, e.g. 27.0.1.1+4.
        have="$(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"$/\1/p' jdk/release 2>/dev/null || true)"
        if [ "$have" != "$JDK_VERSION" ]; then
            echo "jdk/ holds ${have:-an unknown version}, but tools/versions pins ${JDK_VERSION}." >&2
            echo "Delete jdk/ and re-run to fetch the pinned JDK." >&2
            exit 1
        fi
        echo "jdk/ already present: $(jdk/bin/java -version 2>&1 | head -1)"
        echo "Delete jdk/ and re-run to refresh."
        exit 0
    fi
    # Temurin assets follow a fixed scheme: tag jdk-27.0.1.1+4 ("+" is %2B in
    # the URL), file OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.1_4.tar.gz.
    major="${JDK_VERSION%%.*}"
    url="https://github.com/adoptium/temurin${major}-binaries/releases/download/jdk-${JDK_VERSION//+/%2B}/OpenJDK${major}U-jdk_x64_linux_hotspot_${JDK_VERSION//+/_}.tar.gz"
    echo ">> downloading Temurin JDK ${JDK_VERSION} (x64 linux)"
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/opentrap.XXXXXX")"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "$tmp/jdk.tar.gz" "$url"
    {{ just }} _verify "$tmp/jdk.tar.gz" "$JDK_SHA256"
    tar -xzf "$tmp/jdk.tar.gz" -C "$tmp"
    src="$(find "$tmp" -maxdepth 1 -type d -name 'jdk-*' | head -1)"
    [ -n "$src" ] || { echo "could not find the extracted JDK directory" >&2; exit 1; }
    rm -rf jdk
    mv "$src" jdk
    echo "Installed jdk/: $(jdk/bin/java -version 2>&1 | head -1)"

# Show the JDK the recipes use (macOS has no pinned portable JDK; Temurin comes from Homebrew)
[macos]
jdk: _check-jdk
    @echo "Using $JAVA_HOME: $("$JAVA_HOME/bin/java" -version 2>&1 | grep -v "^Picked up" | head -1)"

# Fetch the jars pinned in tools/versions from Maven Central into lib/, removing any other jar there
libs:
    #!/usr/bin/env bash
    set -euo pipefail
    # lib/ is committed, so no build or CI run needs this. It restores lib/,
    # or fills it after a version and its sha256 were moved in tools/versions.
    # Every jar must match its pinned sha256; one that lib/ already holds with
    # that hash is kept without a download.
    want=(
        "org/bouncycastle bcprov-jdk18on $BOUNCYCASTLE_VERSION $BOUNCYCASTLE_PROV_SHA256"
        "org/bouncycastle bcpkix-jdk18on $BOUNCYCASTLE_VERSION $BOUNCYCASTLE_PKIX_SHA256"
        "org/bouncycastle bcutil-jdk18on $BOUNCYCASTLE_VERSION $BOUNCYCASTLE_UTIL_SHA256"
        "org/brotli dec $BROTLI_DEC_VERSION $BROTLI_DEC_SHA256"
    )
    # Downloads land next to lib/ so the final move is a rename, and lib/
    # changes only once every jar is verified.
    mkdir -p lib
    tmp="$(mktemp -d lib/.fetch.XXXXXX)"
    trap 'rm -rf "$tmp"' EXIT
    pinned=" "
    for w in "${want[@]}"; do
        read -r group artifact ver sha <<<"$w"
        jar="$artifact-$ver.jar"
        pinned+="$jar "
        if [ -f "lib/$jar" ] && [ "$({{ hasher }} "lib/$jar" | cut -d' ' -f1)" = "$sha" ]; then
            echo "  kept     lib/$jar"
            continue
        fi
        url="https://repo1.maven.org/maven2/$group/$artifact/$ver/$jar"
        echo "Downloading $url ..."
        curl -fsSL --proto '=https' -o "$tmp/$jar" "$url"
        {{ just }} _verify "$tmp/$jar" "$sha"
    done
    for f in lib/*.jar; do
        [ -e "$f" ] || continue
        case "$pinned" in *" ${f#lib/} "*) ;; *) rm -f "$f"; echo "  removed  $f" ;; esac
    done
    for f in "$tmp"/*.jar; do
        [ -e "$f" ] || continue
        mv -f "$f" lib/
        echo "  fetched  lib/${f##*/}"
    done
    {{ just }} _check-libs
    echo "lib/ holds the jars pinned in tools/versions."

# Fetch the pinned ZAP engine into engine/; this replaces engine/, dropping add-ons added with 'just addon'
engine:
    #!/usr/bin/env bash
    set -euo pipefail
    # OpenTRAP runs ZAP (Apache 2.0, see CREDITS.md) with no user interface,
    # as a headless scan engine on the loopback. The Core package is used (no
    # bundled JRE) because OpenTRAP already runs on a JVM. ZAP itself is
    # pinned: to update, bump ZAP_VERSION and its sha256 in tools/versions
    # and re-run this. The add-ons are the latest published for that release.
    dest="$PWD/engine"
    echo "Installing pinned ZAP engine v${ZAP_VERSION} (edit tools/versions to change) with the latest add-ons..."
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/opentrap.XXXXXX")"
    trap 'rm -rf "$tmp"' EXIT
    url="https://github.com/zaproxy/zaproxy/releases/download/v${ZAP_VERSION}/ZAP_${ZAP_VERSION}_Core.zip"
    echo "Downloading $url ..."
    curl -L --fail -o "$tmp/zap.zip" "$url"
    {{ just }} _verify "$tmp/zap.zip" "$ZAP_SHA256"
    unzip -q "$tmp/zap.zip" -d "$tmp"

    # The zip holds a single top-level ZAP_<ver>/ directory; flatten it into engine/.
    inner="$(ls -d "$tmp"/ZAP_* 2>/dev/null | head -1 || true)"
    [ -n "$inner" ] || inner="$(ls -d "$tmp"/*/ 2>/dev/null | head -1 || true)"
    [ -n "$inner" ] || { echo "Unexpected archive layout" >&2; exit 1; }
    rm -rf "$dest"; mkdir -p "$dest"
    cp -a "$inner"/. "$dest"/

    # Keep only the add-ons OpenTRAP drives (active and passive scan rules, the
    # spiders and the infrastructure they need), so the bundled engine stays
    # small.
    keep=" ascanrules ascanrulesBeta pscan pscanrules pscanrulesBeta spider spiderAjax selenium retire oast directorylistv1 commonlib network database callhome "
    for f in "$dest"/plugin/*.zap; do
        [ -e "$f" ] || continue
        name="$(basename "$f" | sed -E 's/-(release|beta|alpha)-.*//')"
        case "$keep" in *" $name "*) ;; *) rm -f "$f" ;; esac
    done

    # The AJAX spider and its Selenium backend are not in Core, and the beta
    # scan-rule packs add many more checks. Every kept add-on is then brought
    # to the newest version ZAP publishes for this ZAP release, exactly as
    # 'just addons update' does, dependencies included (spiderAjax needs a
    # newer commonlib than Core ships). Each download is checked against the
    # sha256 in ZAP's list.
    {{ just }} addons update selenium spiderAjax ascanrulesBeta pscanrulesBeta

    jar="$(ls "$dest"/zap-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$jar" ] || { echo "No zap jar after unpack" >&2; exit 1; }
    # Record the ZAP pins and every installed file's hash, so 'just release'
    # can tell whether engine/ still holds exactly this.
    {{ just }} _engine-state > "$dest/.pinned"
    echo "ZAP engine ready: $jar"
    echo "Add-ons: $(ls "$dest"/plugin/*.zap 2>/dev/null | wc -l | tr -d ' ')"

# Fetch the pinned Chromium snapshot and its chromedriver into browser/
[linux]
browser:
    #!/usr/bin/env bash
    set -euo pipefail
    # Plain open-source Chromium (BSD), not Chrome for Testing, so there is no
    # "automated testing" banner and it can be shipped. The snapshot revision
    # and the sha256 of both archives are pinned in tools/versions; Google's
    # snapshot storage publishes no hash, so the pins are the hashes recorded
    # when the archives were first fetched.
    [ "$(uname -m)" = x86_64 ] || { echo "The pinned Chromium snapshot is for x86_64 Linux; this machine is $(uname -m)." >&2; exit 1; }
    dest="$PWD/browser"
    base="https://commondatastorage.googleapis.com/chromium-browser-snapshots/Linux_x64/$CHROMIUM_REVISION"
    mkdir -p "$dest"
    # Downloads land in a scratch directory and move into browser/ only once
    # complete and verified, so an interrupted run leaves no half tree behind.
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/opentrap.XXXXXX")"
    trap 'rm -rf "$tmp"' EXIT
    echo "Installing pinned Chromium snapshot $CHROMIUM_REVISION (edit tools/versions to change)..."
    echo "Downloading Chromium..."
    curl -fSL -o "$tmp/chrome.zip" "$base/chrome-linux.zip"
    {{ just }} _verify "$tmp/chrome.zip" "$CHROMIUM_CHROME_SHA256"
    echo "Extracting..."
    unzip -q "$tmp/chrome.zip" -d "$tmp"
    rm -rf "$dest/chrome-linux"
    mv -f "$tmp/chrome-linux" "$dest/chrome-linux"
    chmod +x "$dest/chrome-linux/chrome"
    echo "$CHROMIUM_REVISION" > "$dest/chrome-linux/REVISION"
    echo "Bundled Chromium ready at $dest/chrome-linux/chrome"
    "$dest/chrome-linux/chrome" --version 2>/dev/null || true
    # The chromedriver from the same snapshot, so ZAP's AJAX spider can drive
    # this exact Chromium headless.
    echo "Downloading matching chromedriver..."
    curl -fSL -o "$tmp/chromedriver.zip" "$base/chromedriver_linux64.zip"
    {{ just }} _verify "$tmp/chromedriver.zip" "$CHROMIUM_DRIVER_SHA256"
    unzip -q "$tmp/chromedriver.zip" -d "$tmp"
    # Flatten to browser/chromedriver, where EmbeddedBrowser.findDriver looks first.
    rm -rf "$dest/chromedriver" "$dest/chromedriver_linux64"
    mv -f "$tmp/chromedriver_linux64/chromedriver" "$dest/chromedriver"
    chmod +x "$dest/chromedriver"
    echo "Bundled chromedriver ready at $dest/chromedriver"
    "$dest/chromedriver" --version 2>/dev/null || true

# There is no pinned Chromium for macOS yet
[macos]
browser:
    @echo "The pinned Chromium snapshot is for x86_64 Linux only; there is none for macOS yet." >&2
    @exit 1

# Download a ZAP add-on into engine/plugin/; quality is release, beta, alpha or auto (tries each)
addon id version quality="auto" sha256="":
    #!/usr/bin/env bash
    set -euo pipefail
    id="$1" ver="$2" quality="$3" want="$4"
    dir=engine/plugin
    [ -d "$dir" ] || { echo "No engine in engine/. Fetch it first: just engine" >&2; exit 1; }
    [[ "$id" =~ ^[A-Za-z0-9_]+$ ]] || { echo "Not an add-on id: $id" >&2; exit 1; }
    [[ "$ver" =~ ^[0-9][0-9.]*$ ]] || { echo "Not a version: $ver" >&2; exit 1; }
    case "$quality" in
        auto) quals=(release beta alpha) ;;
        release|beta|alpha) quals=("$quality") ;;
        *) echo "Quality must be release, beta, alpha or auto, not: $quality" >&2; exit 1 ;;
    esac
    # Download next to the target so the final move is a rename, and clean up
    # the partial file on any failure.
    tmp="$dir/.$id.part"
    trap 'rm -f "$tmp"' EXIT
    base="https://github.com/zaproxy/zap-extensions/releases/download/${id}-v${ver}"
    name=""
    for q in "${quals[@]}"; do
        echo "Trying ${id}-${q}-${ver}.zap ..."
        if curl -fsL -o "$tmp" "$base/${id}-${q}-${ver}.zap"; then name="${id}-${q}-${ver}.zap"; break; fi
    done
    [ -n "$name" ] || { echo "${id} ${ver} is not on the ZAP marketplace (tried: ${quals[*]}). Check the id and version." >&2; exit 1; }
    unzip -l "$tmp" ZapAddOn.xml >/dev/null 2>&1 || { echo "$name is not a ZAP add-on (no ZapAddOn.xml)" >&2; exit 1; }
    got="$({{ hasher }} "$tmp" | cut -d' ' -f1)"
    if [ -n "$want" ] && [ "$got" != "$want" ]; then
        printf 'sha256 mismatch for %s:\n  expected %s\n  got      %s\n' "$name" "$want" "$got" >&2
        exit 1
    fi
    # Replace any other version or quality of the same add-on.
    rm -f "$dir/${id}-release-"*.zap "$dir/${id}-beta-"*.zap "$dir/${id}-alpha-"*.zap
    mv "$tmp" "$dir/$name"
    echo "Installed $dir/$name"
    [ -n "$want" ] || echo "sha256 $got (pass it as the fourth argument to pin this download)"
    needs="$(unzip -p "$dir/$name" ZapAddOn.xml | sed -n 's:.*<not-before-version>\(.*\)</not-before-version>.*:\1:p' | head -1)"
    have="$(ls engine/zap-*.jar 2>/dev/null | head -1 | sed -E 's:.*/zap-(.*)\.jar:\1:')"
    [ -z "$needs" ] || echo "Needs ZAP ${needs} or newer; the engine is ${have:-unknown}."
    echo "Restart OpenTRAP to load it. 'just release' embeds only what 'just engine' installed, so it refuses this one."

# Check ZAP add-ons against the latest for the engine's ZAP release; 'update' installs them, extra ids add more
addons action="check" *ids:
    #!/usr/bin/env bash
    set -euo pipefail
    action="$1"; shift
    case "$action" in
        check|update) ;;
        *) echo "Usage: just addons [check|update] [add-on id ...]" >&2; exit 1 ;;
    esac
    dir=engine/plugin
    [ -d "$dir" ] || { echo "No engine in engine/. Fetch it first: just engine" >&2; exit 1; }
    jarf="$(ls engine/zap-*.jar 2>/dev/null | head -1)"
    [ -n "$jarf" ] || { echo "No zap-*.jar in engine/. Fetch it first: just engine" >&2; exit 1; }
    zap="${jarf##*/zap-}"; zap="${zap%.jar}"
    minor="$(cut -d. -f1-2 <<<"$zap")"

    # ZAP's own list of the add-on versions published for this ZAP release,
    # with each file's URL and sha256.
    feed="engine/.ZapVersions-$minor.xml"
    trap 'rm -f "$feed" "$dir"/.*.part' EXIT
    curl -fsSL --max-time 60 -o "$feed" "https://raw.githubusercontent.com/zaproxy/zap-admin/master/ZapVersions-$minor.xml" \
        || { echo "Could not download the add-on list for ZAP $minor." >&2; exit 1; }
    field() { xmllint --xpath "string(/ZAP/addon_$1/$2)" "$feed" 2>/dev/null || true; }
    deps() {
        xmllint --xpath "/ZAP/addon_$1/dependencies/addons/addon/id" "$feed" 2>/dev/null \
            | grep -oE '<id>[A-Za-z0-9_]+</id>' | sed -E 's:</?id>::g' || true
    }
    # The installed file of an add-on, any quality, or nothing.
    installed() { ls "$dir/$1-release-"*.zap "$dir/$1-beta-"*.zap "$dir/$1-alpha-"*.zap 2>/dev/null | head -1 || true; }

    # Targets: every installed add-on, the ids given, and their dependencies.
    targets=()
    for f in "$dir"/*.zap; do
        [ -e "$f" ] || continue
        b="${f##*/}"
        [[ "$b" =~ ^([A-Za-z0-9_]+)-(release|beta|alpha)-.+\.zap$ ]] && targets+=("${BASH_REMATCH[1]}")
    done
    for id in "$@"; do
        [[ "$id" =~ ^[A-Za-z0-9_]+$ ]] || { echo "Not an add-on id: $id" >&2; exit 1; }
        [ -n "$(field "$id" version)" ] || { echo "$id is not published for ZAP $minor." >&2; exit 1; }
        targets+=("$id")
    done
    i=0
    while [ "$i" -lt "${#targets[@]}" ]; do
        for d in $(deps "${targets[$i]}"); do
            case " ${targets[*]} " in *" $d "*) ;; *) targets+=("$d") ;; esac
        done
        i=$((i + 1))
    done
    [ "${#targets[@]}" -gt 0 ] || { echo "No add-ons installed in $dir."; exit 0; }

    todo=()
    printf '%-18s %-12s %-12s %s\n' "ADD-ON" "INSTALLED" "LATEST" "STATE"
    for id in $(printf '%s\n' "${targets[@]}" | sort -u); do
        f="$(installed "$id")"
        have=""
        if [ -n "$f" ]; then have="${f##*/}"; have="${have%.zap}"; have="${have#"$id"-*-}"; fi
        latest="$(field "$id" version)"
        if [ -z "$latest" ]; then state="not published for ZAP $minor"
        elif [ -z "$have" ]; then state="not installed"; todo+=("$id")
        elif [ "$have" = "$latest" ]; then state="up to date"
        elif [ "$(printf '%s\n%s\n' "$have" "$latest" | sort -V | tail -1)" = "$have" ]; then state="newer than published, kept"
        else state="update available"; todo+=("$id")
        fi
        printf '%-18s %-12s %-12s %s\n' "$id" "${have:--}" "${latest:--}" "$state"
    done
    echo

    if [ "${#todo[@]}" -eq 0 ]; then echo "Nothing to install for ZAP $zap."; exit 0; fi
    if [ "$action" = check ]; then
        echo "${#todo[@]} to install. Run: just addons update${*:+ $*}"
        exit 0
    fi

    for id in "${todo[@]}"; do
        file="$(field "$id" file)" url="$(field "$id" url)" hash="$(field "$id" hash)"
        [[ "$file" =~ ^"$id"-(release|beta|alpha)-[A-Za-z0-9._]+\.zap$ ]] || { echo "Unexpected file name for $id: $file" >&2; exit 1; }
        [[ "$url" == https://github.com/zaproxy/* ]] || { echo "Unexpected download URL for $id: $url" >&2; exit 1; }
        [[ "$hash" == SHA-256:* ]] || { echo "No sha256 published for $id" >&2; exit 1; }
        tmp="$dir/.$id.part"
        echo "Downloading $file ..."
        curl -fsSL -o "$tmp" "$url"
        {{ just }} _verify "$tmp" "${hash#SHA-256:}"
        unzip -l "$tmp" ZapAddOn.xml >/dev/null 2>&1 || { echo "$file is not a ZAP add-on (no ZapAddOn.xml)" >&2; exit 1; }
        rm -f "$dir/$id-release-"*.zap "$dir/$id-beta-"*.zap "$dir/$id-alpha-"*.zap
        mv "$tmp" "$dir/$file"
    done
    echo "Installed ${#todo[@]} add-on(s), each checked against its published sha256. Restart OpenTRAP to load them."
    echo "'just release' embeds only what 'just engine' installed; re-run it to ship the latest add-ons."

# Report which pins in tools/versions have a newer upstream release (changes nothing)
check-updates:
    #!/usr/bin/env bash
    # Deliberately no -e: one failed upstream lookup must not stop the report.
    set -uo pipefail
    row() { printf '  %-14s pinned %-14s latest %-14s %s\n' "$1" "$2" "$3" "$4"; }
    flag() {
        if [ -z "$2" ]; then echo "lookup failed"
        elif [ "$1" != "$2" ]; then echo "UPDATE AVAILABLE"
        else echo "up to date"; fi
    }
    maven() {  # <group as a path> <artifact>; Central's own metadata, not its lagging search index
        curl -fsS --max-time 20 "https://repo1.maven.org/maven2/$1/$2/maven-metadata.xml" 2>/dev/null \
            | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1
    }
    echo "OpenTRAP bundled tool versions:"
    echo
    zap="$(curl -fsS --max-time 20 https://api.github.com/repos/zaproxy/zaproxy/releases/latest 2>/dev/null \
        | grep -oE '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's/^v//')"
    row zap-engine "$ZAP_VERSION" "${zap:-?}" "$(flag "$ZAP_VERSION" "$zap")"
    chrome="$(curl -fsS --max-time 20 https://commondatastorage.googleapis.com/chromium-browser-snapshots/Linux_x64/LAST_CHANGE 2>/dev/null)"
    row chromium "$CHROMIUM_REVISION" "${chrome:-?}" "$(flag "$CHROMIUM_REVISION" "$chrome")"
    bc="$(maven org/bouncycastle bcprov-jdk18on)"
    row bouncycastle "$BOUNCYCASTLE_VERSION" "${bc:-?}" "$(flag "$BOUNCYCASTLE_VERSION" "$bc")"
    br="$(maven org/brotli dec)"
    row brotli-dec "$BROTLI_DEC_VERSION" "${br:-?}" "$(flag "$BROTLI_DEC_VERSION" "$br")"
    # The newest Temurin of the pinned major, as Adoptium names it (jdk-27.0.1.1+4).
    major="${JDK_VERSION%%.*}"
    jdk="$(curl -fsS --max-time 20 "https://api.adoptium.net/v3/assets/latest/$major/hotspot?os=linux&architecture=x64&image_type=jdk" 2>/dev/null \
        | grep -oE '"release_name": *"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's/^jdk-//')"
    row "temurin-$major" "$JDK_VERSION" "${jdk:-?}" "$(flag "$JDK_VERSION" "$jdk")"
    gh_latest() {  # <owner/repo>
        curl -fsS --max-time 20 "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
            | grep -oE '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4
    }
    # appimagetool's "latest" release is the rolling "continuous" tag; take
    # the highest numbered one instead.
    ait="$(curl -fsS --max-time 20 "https://api.github.com/repos/AppImage/appimagetool/releases?per_page=30" 2>/dev/null \
        | grep -oE '"tag_name": *"[0-9][0-9.]*"' | cut -d'"' -f4 | sort -V | tail -1)"
    row appimagetool "$APPIMAGETOOL_VERSION" "${ait:-?}" "$(flag "$APPIMAGETOOL_VERSION" "$ait")"
    jst="$(gh_latest casey/just)"
    row just "$JUST_VERSION" "${jst:-?}" "$(flag "$JUST_VERSION" "$jst")"
    echo
    echo "To update: edit tools/versions (a version and its sha256 together), then"
    echo "run the matching recipe (just engine, just browser, just jdk, just libs)."
    echo "The add-ons follow the latest for ZAP_VERSION: just addons."

# Install the launcher icon and a menu entry for this checkout; 'system' installs for all users (needs root)
[linux]
install-desktop scope="user":
    #!/usr/bin/env bash
    set -euo pipefail
    # The icon goes into the hicolor theme, which every desktop falls back to
    # last, and the entry refers to it by the name "opentrap", so it looks the
    # same whatever the user's theme.
    case "$1" in
        user)   iconroot="$HOME/.local/share/icons/hicolor"; appdir="$HOME/.local/share/applications" ;;
        system) iconroot=/usr/share/icons/hicolor; appdir=/usr/share/applications ;;
        *) echo "Scope must be user or system, not: $1" >&2; exit 1 ;;
    esac
    # The entry launches 'just run' in this checkout. Desktop entries give $,
    # backquote, backslash and double quote a meaning even inside quotes, so
    # refuse paths that hold one rather than escape them twice over.
    launcher=({{ quote(just_executable()) }} --justfile {{ quote(justfile()) }} run)
    for a in "${launcher[@]}"; do
        case "$a" in *[\$\`\\\"]*) echo "Cannot write a desktop entry for a path with \$, \`, \\ or \": $a" >&2; exit 1 ;; esac
    done
    exec_line="Exec="
    for a in "${launcher[@]}"; do exec_line+="\"${a//%/%%}\" "; done

    tmp="$(mktemp -d "${TMPDIR:-/tmp}/opentrap.XXXXXX")"
    trap 'rm -rf "$tmp"' EXIT
    echo "Installing OpenTRAP desktop entry ($1 scope)..."
    # Scalable source, for themes and desktops that prefer SVG.
    mkdir -p "$iconroot/scalable/apps"
    cp icon/opentrap.svg "$iconroot/scalable/apps/opentrap.svg"
    # Raster sizes, rendered by the app itself so no SVG tool is needed.
    if [ -f dist/opentrap.jar ] && [ -x "$JAVA_HOME/bin/java" ]; then
        "$JAVA_HOME/bin/java" -cp dist/opentrap.jar opentrap.ui.AppIcon "$tmp" >/dev/null
        for size in 16 24 32 48 64 128 256; do
            mkdir -p "$iconroot/${size}x${size}/apps"
            cp "$tmp/opentrap_${size}.png" "$iconroot/${size}x${size}/apps/opentrap.png"
        done
    else
        echo "  note: no dist/opentrap.jar or no JDK, installed the SVG only (run 'just build' for PNG sizes)"
    fi
    # Any Exec line from the template or an earlier install is dropped first,
    # so re-running (or moving the checkout) replaces it.
    mkdir -p "$appdir"
    { grep -v '^Exec=' icon/opentrap.desktop; printf '%s\n' "${exec_line% }"; } > "$appdir/opentrap.desktop"
    chmod +x "$appdir/opentrap.desktop"
    # Refresh the caches so the entry appears without a logout.
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -f -t "$iconroot" >/dev/null 2>&1 || true; fi
    if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$appdir" >/dev/null 2>&1 || true; fi
    echo "Installed:"
    echo "  icon  -> $iconroot/{scalable,*x*}/apps/opentrap.*"
    echo "  entry -> $appdir/opentrap.desktop"
    echo "OpenTRAP should now appear in your application menu with its own icon."

# Compile src/ into an empty build/, with the icons and the unpacked vendored jars
_classes: _check-jdk _check-libs
    #!/usr/bin/env bash
    set -euo pipefail
    # Start from an empty build/ so nothing left by an earlier build or by the
    # AppImage packaging (its runtime and staging live under build/) is packed.
    rm -rf build && mkdir -p build dist
    echo "Compiling..."
    srcs=()
    while IFS= read -r f; do srcs+=("$f"); done < <(find src -name '*.java')
    "$JAVA_HOME/bin/javac" --release "$JAVA_RELEASE" -d build -cp "lib/*:build" "${srcs[@]}"
    echo "Copying resources..."
    mkdir -p build/opentrap/ui/icons
    cp src/opentrap/ui/icons/*.png build/opentrap/ui/icons/
    echo "Unpacking the vendored jars..."
    (cd build && for j in ../lib/*.jar; do "$JAVA_HOME/bin/jar" xf "$j"; done)
    rm -f build/META-INF/*.SF build/META-INF/*.DSA build/META-INF/*.RSA

# Fail unless lib/ holds exactly the jars pinned in tools/versions, byte for byte
_check-libs:
    #!/usr/bin/env bash
    set -euo pipefail
    want=(
        "bcprov-jdk18on-$BOUNCYCASTLE_VERSION.jar $BOUNCYCASTLE_PROV_SHA256"
        "bcpkix-jdk18on-$BOUNCYCASTLE_VERSION.jar $BOUNCYCASTLE_PKIX_SHA256"
        "bcutil-jdk18on-$BOUNCYCASTLE_VERSION.jar $BOUNCYCASTLE_UTIL_SHA256"
        "dec-$BROTLI_DEC_VERSION.jar $BROTLI_DEC_SHA256"
    )
    names=""
    for w in "${want[@]}"; do names+="${w%% *}"$'\n'; done
    have="$(cd lib && ls -1 *.jar 2>/dev/null | LC_ALL=C sort || true)"
    if [ "$have" != "$(printf '%s' "$names" | LC_ALL=C sort)" ]; then
        echo "lib/ does not hold the jars pinned in tools/versions." >&2
        echo "  pinned: $(printf '%s' "$names" | LC_ALL=C sort | tr '\n' ' ')" >&2
        echo "  found:  $(printf '%s' "$have" | tr '\n' ' ')" >&2
        echo "Run 'just libs' to fetch the pinned jars." >&2
        exit 1
    fi
    for w in "${want[@]}"; do {{ just }} _verify "lib/${w%% *}" "${w#* }"; done

# The engine pins from tools/versions and the hash of every file in engine/, one per line
_engine-state:
    #!/usr/bin/env bash
    set -euo pipefail
    grep -E '^ZAP_[A-Z0-9_]+=' tools/versions | LC_ALL=C sort
    cd engine && find . -type f ! -path ./.pinned -print0 | LC_ALL=C sort -z | xargs -0 {{ hasher }}

_check-jdk:
    #!/usr/bin/env bash
    if [ ! -x "$JAVA_HOME/bin/javac" ]; then
        if [ "$(uname -s)" = Darwin ]; then hint="brew install --cask temurin"; else hint="just jdk (x86_64), or set OPENTRAP_JDK to a JDK home"; fi
        echo "No JDK at ${JAVA_HOME:-(none found)}. Install one with: $hint" >&2
        exit 1
    fi

# Fail unless the file's sha256 is the expected one
_verify file expected:
    #!/usr/bin/env bash
    set -euo pipefail
    got="$({{ hasher }} "$1" | cut -d' ' -f1)"
    if [ "$got" != "$2" ]; then
        printf 'sha256 mismatch for %s:\n  expected %s (update the pin in tools/versions?)\n  got      %s\n' "$1" "$2" "$got" >&2
        exit 1
    fi
