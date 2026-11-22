# Build and run OpenTRAP on macOS with Eclipse Temurin 27 (Homebrew cask
# `temurin`). Stands in for build.sh, test.sh and run.sh, which target Linux
# and the pinned portable JDK in jdk/.
#
#   just build        compile and pack dist/opentrap.jar
#   just test         build, then compile and run the test suite
#   just run          launch the app (builds first if the jar is missing)
#   just run 1.5      launch at a fixed interface scale
#   just clean        remove build/, build-tests/ and dist/
#   just engine       fetch the pinned OWASP ZAP scan engine into engine/
#   just addon spiderAjax 23.33.0    add a ZAP add-on to engine/plugin/
#   just addon pscanrulesBeta 50 beta <sha256>   fixed quality, verified hash
#   just addons                      compare installed add-ons with the latest
#   just addons update               update every installed add-on to the latest
#   just addons update retire        also install add-ons by id (with dependencies)
#
# "Latest" means the newest version ZAP publishes for the engine's own ZAP
# release (ZapVersions-<major.minor>.xml), so updates stay compatible with it.
#
# The app runs a dev build's engine from engine/ unless ~/.opentrap/engine
# (left by a release jar) holds one; that one wins.
#
# Set OPENTRAP_JDK to use another JDK home.

set shell := ["bash", "-euo", "pipefail", "-c"]

jdk := env("OPENTRAP_JDK", `/usr/libexec/java_home -v 27 2>/dev/null || echo /Library/Java/JavaVirtualMachines/temurin-27.jdk/Contents/Home`)
java := jdk / "bin/java"
javac := jdk / "bin/javac"
jar := jdk / "bin/jar"
# Language level from tools/versions; the JDK above (27) only needs to be at least that.
java_release := `sed -n 's/^JAVA_RELEASE=//p' tools/versions`

export JAVA_HOME := jdk

default: build

# Compile the sources and pack them, the icons and BouncyCastle into dist/opentrap.jar
build: _check-jdk
    #!/usr/bin/env bash
    set -euo pipefail
    # Start from an empty build/ so nothing left by an earlier build is packed.
    rm -rf build && mkdir -p build dist
    echo "Compiling..."
    srcs=()
    while IFS= read -r f; do srcs+=("$f"); done < <(find src -name '*.java')
    "{{ javac }}" --release {{ java_release }} -d build -cp "lib/*:build" "${srcs[@]}"
    echo "Copying resources..."
    mkdir -p build/opentrap/ui/icons
    cp src/opentrap/ui/icons/*.png build/opentrap/ui/icons/
    echo "Unpacking BouncyCastle into the jar..."
    (cd build && for j in ../lib/*.jar; do "{{ jar }}" xf "$j"; done)
    rm -f build/META-INF/*.SF build/META-INF/*.DSA build/META-INF/*.RSA
    "{{ jar }}" --create --file dist/opentrap.jar --main-class opentrap.ui.Main -C build .
    echo "Built dist/opentrap.jar ($(du -h dist/opentrap.jar | cut -f1))"

# Build, then compile and run the test suite (tests/ is never packed into the jar)
test: build
    #!/usr/bin/env bash
    set -euo pipefail
    rm -rf build-tests && mkdir -p build-tests
    echo "Compiling tests..."
    srcs=()
    while IFS= read -r f; do srcs+=("$f"); done < <(find tests -name '*.java')
    "{{ javac }}" --release {{ java_release }} -nowarn -d build-tests -cp "lib/*:build" "${srcs[@]}"
    echo "Running tests..."
    echo
    exec "{{ java }}" -Djava.io.tmpdir="${TMPDIR:-/tmp}" -cp "lib/*:build:build-tests" RunTests

# Launch OpenTRAP; pass a scale such as 1.5 or 2 to force the interface scale
run scale="": _check-jdk
    #!/usr/bin/env bash
    set -euo pipefail
    [ -f dist/opentrap.jar ] || just build
    scale="{{ scale }}"
    scale="${OPENTRAP_SCALE:-$scale}"
    args=()
    if [ -n "$scale" ]; then
        args+=("-Dsun.java2d.uiScale=$scale" "-Dopentrap.scaled=$scale")
    fi
    # Max heap as a share of RAM; the scan engine and testing browser run as
    # their own processes outside this JVM.
    exec "{{ java }}" -XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:G1PeriodicGCInterval=300000 \
        -Xdock:name=OpenTRAP -Xdock:icon=src/opentrap/ui/icons/opentrap_256.png \
        ${args[@]+"${args[@]}"} -jar dist/opentrap.jar

# Fetch the pinned ZAP engine into engine/; this replaces engine/, dropping add-ons added with 'just addon'
engine:
    ./fetch-zap.sh

# Download a ZAP add-on into engine/plugin/; quality is release, beta, alpha or auto (tries each)
addon id version quality="auto" sha256="":
    #!/usr/bin/env bash
    set -euo pipefail
    id="{{ id }}" ver="{{ version }}" quality="{{ quality }}" want="{{ sha256 }}"
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
    got="$(shasum -a 256 "$tmp" | cut -d' ' -f1)"
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
    echo "Restart OpenTRAP to load it."

# Check ZAP add-ons against the latest for the engine's ZAP release; 'update' installs them, extra ids add more
addons action="check" *ids:
    #!/usr/bin/env bash
    set -euo pipefail
    action="{{ action }}"
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
    for id in {{ ids }}; do
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
        echo "${#todo[@]} to install. Run: just addons update {{ ids }}"
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
        got="$(shasum -a 256 "$tmp" | cut -d' ' -f1)"
        [ "$got" = "${hash#SHA-256:}" ] || { printf 'sha256 mismatch for %s:\n  expected %s\n  got      %s\n' "$file" "${hash#SHA-256:}" "$got" >&2; exit 1; }
        unzip -l "$tmp" ZapAddOn.xml >/dev/null 2>&1 || { echo "$file is not a ZAP add-on (no ZapAddOn.xml)" >&2; exit 1; }
        rm -f "$dir/$id-release-"*.zap "$dir/$id-beta-"*.zap "$dir/$id-alpha-"*.zap
        mv "$tmp" "$dir/$file"
    done
    echo "Installed ${#todo[@]} add-on(s), each checked against its published sha256. Restart OpenTRAP to load them."
    echo "Note: 'just engine' resets engine/ to the pinned versions; run 'just addons update' again after it."

# Remove build outputs
clean:
    rm -rf build build-tests dist

_check-jdk:
    @[ -x "{{ javac }}" ] || { echo "No JDK at {{ jdk }}. Install it with: brew install --cask temurin" >&2; exit 1; }
