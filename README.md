<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="icon/wordmark-s1-dark.svg">
  <img src="icon/wordmark-s1-light.svg" alt="OpenTRAP-S1" height="56">
</picture>

<p><strong>Open Traffic Replay and Analysis Proxy</strong><br>
A free and open-source alternative to Burp Suite, built on the trusted OWASP ZAP engine.</p>

<p>
<img src="https://img.shields.io/badge/licence-AGPL--3.0-e5484d?style=flat-square" alt="Licence">
<img src="https://img.shields.io/badge/Java-27%2B-5b8def?style=flat-square" alt="Java">
<img src="https://img.shields.io/badge/platform-macOS%20%7C%20Linux-9aa7b4?style=flat-square" alt="Platform">
<img src="https://img.shields.io/badge/status-active%20development-3fb950?style=flat-square" alt="Status">
</p>

</div>

---

## A fork of OpenTRAP

OpenTRAP-S1 is a fork of [OpenTRAP](https://github.com/opentrap/opentrap).
[SektionEins](https://sektioneins.de) made the fork on 22 November 2026 and
maintains it. It keeps OpenTRAP's Burp-style workflow on top of the OWASP ZAP
engine and adds a number of fixes and changes of its own.

For everything else, the original project is the place to go:

- [Installation and first steps](https://github.com/opentrap/opentrap#get-started)
- [Help and FAQ](https://github.com/opentrap/opentrap#faq)
- [Roadmap](https://github.com/opentrap/opentrap/blob/main/ROADMAP.md)
- [Reporting a security issue](https://github.com/opentrap/opentrap/blob/main/SECURITY.md)
- [Contributing](https://github.com/opentrap/opentrap/blob/main/CONTRIBUTING.md)
- [Project site and screenshots](https://opentrap.github.io/opentrap/)

## What is different in this fork

- **macOS as well as Linux.** The build, the tests and the app run on both, with
  `just` as the only entry point; the shell scripts are gone. On macOS the
  menus sit in the system menu bar at the top of the screen, and the
  application menu's About and Settings open the suite's own. Shortcuts use
  Command: Cmd+C, Cmd+V and the rest in text fields, lists and tables, Option
  to move by word, and Cmd with the arrows to the line and text ends. The interface
  scale items are left out on macOS, where Java cannot apply them. The browser
  button starts an installed Chromium and, when there is none, says to install
  it with `brew install ungoogled-chromium`. The testing browser opens
  without a start page, so a profile chooser shows when its profile folder holds
  more than one profile.
- **Java 27.** The suite builds and ships on Java 27, with the version pinned
  once in `tools/versions`. Proxy connections run on virtual threads.
- **Newer protocols.** The proxy uses the post-quantum hybrid key exchange on
  both sides, and Repeater and Intruder can send over HTTP/3, saying when they
  fell back.
- **Safer project files.** Opening a project from someone else no longer writes
  its settings into your own preferences, no longer copies your secrets into
  projects you save, and reads its data only through an allowlist of known
  classes. An in-scope copy now really leaves out everything out of scope.
  A project file from someone else asks before its session handling rules,
  macros, cookies and project settings take effect; declined, they stay in the
  file unused.
- **Security fixes in the toolset.** Text from captured traffic is no longer
  rendered as HTML in tables and labels, "Copy as curl" quotes every part of the
  request, redirects no longer carry cookies and credentials to another origin,
  and the proxy refuses to forward a request to itself. A cookie set without a
  `Domain` goes back only to the host that set it, not to its subdomains, and the
  cookie jar shows which cookies are host-only. The CA key and the saved
  application logins are written readable by their owner only from the start.
- **Scan engine scope.** The engine's crawl and scan are bound to no more than
  the URLs the app's scope covers. An advanced host, port or file pattern can no
  longer reach into the path or the userinfo, a prefix with a non-default port no
  longer admits the default one, a prefix's path keeps its case, and excludes also
  hold for IPv6-literal hosts. A rule the engine cannot match exactly is left out of
  the includes, widened in the excludes and named in the task log; a scope with no
  include it can bind refuses the scan. When a target is an IPv6 literal, the task
  log also names the host-pattern rules that cannot meet it.
- **Fonts.** The interface uses the system's own UI font, and both the UI font
  and the monospace font can be changed in Settings, with a reset to the
  default.
- **Working hotkeys.** The palette's Go to shortcuts and the Send to shortcuts
  in the history and message editor work without opening a menu, and the
  Hotkeys settings page lists the suite's real shortcuts and rebinds them.
- **No roadmap.** The in-app roadmap, the "not yet implemented" dialog and
  `ROADMAP.md` are gone; the Discover tab keeps the getting-started page.
- **Smaller things.** Quitting with Cmd-Q asks to save like closing the window
  does, the scan engine starts on systems without `setsid`, typing in the
  Decoder no longer moves the focus away after each character, and the test suite
  runs in about 3 seconds instead of 24.

## Licence

OpenTRAP-S1, like OpenTRAP, is released under the **GNU Affero General Public
License v3.0**. You are free to use, study, modify and share it; if you
distribute a modified version or run one as a network service, you release your
changes under the same licence, so improvements stay open for everyone. The full
text is in [LICENSE](LICENSE).

---

<sub>OpenTRAP-S1 is independent open-source software. It bundles third-party
open-source components under their respective licences; see
[CREDITS.md](CREDITS.md).</sub>
