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
<img src="https://img.shields.io/badge/platform-macOS-9aa7b4?style=flat-square" alt="Platform: macOS">
<img src="https://img.shields.io/badge/platform-Linux-9aa7b4?style=flat-square" alt="Platform: Linux">
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
  classes. An in-scope copy now leaves out every request whose path as sent is
  out of scope. A project file from someone else asks before its session
  handling rules, macros, cookies and project settings take effect; declined,
  they stay in the file unused.
- **Security fixes in the toolset.** Text from captured traffic is no longer
  rendered as HTML in tables and labels, the Render tab loads nothing a page
  names (images, frames and objects show as placeholders, style sheets are not
  fetched, forms do not submit), "Copy as curl" quotes every part of the
  request, and redirects no longer carry cookies and credentials to another
  origin. Repeater's "On-site only" and "In-scope only" modes stop at a redirect
  that leaves the site or the scope, and the status says why. Scope checks
  read a path the way most servers do: `..` segments and percent-escapes no
  longer step out of a path prefix in scope or around an excluded path. A
  request whose path is in scope as sent is still recorded and kept, so
  traversal probes stay in the history. The proxy refuses to forward a request
  to itself. A cookie set without a `Domain` goes back only to the host that
  set it, not to its subdomains, and the cookie jar shows which cookies are
  host-only. A site can no longer set a cookie for another site or for a
  public suffix such as `co.uk`, and when two cookies share a name the most
  specific one is sent. The CA key and the saved application logins are
  written readable by their owner only from the start.
  Repeater and Intruder send over HTTP/2 and auto through the upstream proxy
  rules as they do over HTTP/1, a SOCKS rule refuses those modes instead of
  going direct, and the proxy no longer goes direct when the live-scan engine
  cannot be reached. HTTP/2 sends reach hosts whose certificate names another
  host, as HTTP/1 sends do.
  Stopping and restarting a Sequencer capture no longer leaves the old capture
  running and mixing its tokens into the new one.
  A response full of broken input tags no longer freezes the window when the
  request is selected for live capture, and the form field list shows each
  field's value.
  A WebSocket frame larger than a set size (16 MB by default, in the project
  settings) is streamed through instead of held whole, so a server can no
  longer make the proxy allocate gigabytes with a frame header; the history
  keeps the frame's first part and marks it. Such a frame also skips match and
  replace and interception.
  The Decoder stops a gzip, deflate or brotli decode at 16 MB of output and
  says so, so a small compressed body can no longer exhaust the memory.
  A gzip, deflate or brotli response that decodes to more than a set limit
  (16 MB unless changed) stays compressed, and the history says why. All
  responses decoding at once share 256 MB, so a small compressed body can no
  longer exhaust the memory. A body with several content codings is decoded in
  the right order.
- **Scan engine scope.** The engine's crawl and scan are bound to no more than
  the URLs the app's scope covers. An advanced host, port or file pattern can no
  longer reach into the path or the userinfo, a prefix with a non-default port no
  longer admits the default one, a prefix's path keeps its case, and excludes also
  hold for IPv6-literal hosts. A rule the engine cannot match exactly is left out of
  the includes, widened in the excludes and named in the task log; a scope with no
  include it can bind refuses the scan. When a target is an IPv6 literal, the task
  log also names the host-pattern rules that cannot meet it.
- **A real extension store.** Extensions > Store installs and updates real ZAP
  add-ons from ZAP's own feed, from feeds you add and from local folders of
  `.zap` files, and Installed enables, disables and removes them, the add-ons
  that ship with the engine included. Store shows for each add-on whether it
  is installed, disabled or has a change queued. A download must match its
  feed's sha256 and lie in its download prefix's folder, compared as a URL, so
  dot segments or a longer folder name cannot leave it, and adding a source
  asks first. Descriptions and change notes are shown as plain
  text, as the source escaped them, and a feed full of broken tags no longer slows a refresh or the search. Changes wait for Restart
  engine, a real engine restart that keeps the session, the live audit and your
  rule edits, and stops only the crawls and audits running on the engine.
  Nothing is fetched unless you click Refresh, Install, Update, Update all,
  Switch source or, for a new ZAP version, Look them up, or switch on the
  startup check. A crawl or audit whose add-ons are disabled says which ones
  instead of failing on an engine error, and the rule tabs report a rule change
  the engine refuses instead of showing it as applied.
- **Fonts.** The interface uses the system's own UI font, and both the UI font
  and the monospace font can be changed in Settings, with a reset to the
  default.
- **Themes.** Light and dark are theme files, and Settings > User interface >
  Display lists them with your own: add a theme from a file after a summary of
  what it sets, what it leaves to its base theme and which colour pairs read
  poorly, and remove it again. Seven more ship: High Contrast, Linen, Deep Sea,
  Ember, S1 Light, S1 Dark and Phosphor. Every shipped theme passes the
  contrast checks, which also cover links and accent text, the advisory's
  severity, syntax colours under a search match and the Discover headline,
  and High Contrast reaches 7 to 1 for text. Linen is the
  default until you choose another. Badge glyphs and numbers take white or
  black, whichever reads on their fill. The file format is described in
  [docs/themes.md](docs/themes.md). A change that is saved but not yet applied
  is undone by choosing the active font or theme again.
- **Working hotkeys.** The palette's Go to shortcuts and the Send to shortcuts
  in the history and message editor work without opening a menu, and the
  Hotkeys settings page lists the suite's real shortcuts and rebinds them.
- **No roadmap.** The in-app roadmap, the "not yet implemented" dialog and
  `ROADMAP.md` are gone; the Discover tab keeps the getting-started page.
- **No Performance settings page.** Its feedback, local logging and
  maximum memory options were saved but never used: nothing was sent or
  logged, and the heap limit stays at half of system memory.
- **Smaller things.** Quitting with Cmd-Q asks to save like closing the window
  does, the scan engine starts on systems without `setsid`, typing in the
  Decoder no longer moves the focus away after each character, the live audit
  still raises its findings after it is switched off and on again, search
  highlights in the message editor sit on the text they found instead of
  drifting right line by line, a tool with unread results shows a larger dot
  in the accent colour on its tab, as do the Event log and All issues links in
  the status bar, the HTML report and the proxy's certificate page use the
  fork's accent and badge colours that read at 4.5 to 1, and the test suite
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
