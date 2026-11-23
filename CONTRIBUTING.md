# Contributing to OpenTRAP

Thanks for considering a contribution. OpenTRAP is a community project and changes
of any size are welcome, from a one-line fix to a whole tool.

## How to contribute a change

Changes come in as pull requests from your own fork of the repository.

1. **Fork the repository** with the Fork button at the top of the GitHub page.
   That gives you a copy under your own account that you can push to.
2. **Clone your fork** and add the main repository as `upstream`, so you can
   keep your copy current:

       git clone https://github.com/<your-user>/opentrap.git
       cd opentrap
       git remote add upstream https://github.com/opentrap/opentrap.git

3. **Create a branch** for the change, starting from an up-to-date `main`:

       git fetch upstream
       git checkout -b my-change upstream/main

4. **Make the change** and keep it focused on one thing. Build and run it
   (`just build` then `just run`) and confirm the app still launches and your
   change behaves as intended.
5. **Commit and push** the branch to your fork:

       git push -u origin my-change

6. **Open a pull request** from that branch to `main` on `opentrap/opentrap`.
   After the push, GitHub shows a "Compare & pull request" button on your fork.
   Say what the change does and why.

A maintainer reviews the pull request, may ask for adjustments, and merges it.
The merged commit keeps you as its author, and you are credited in
`CONTRIBUTORS.md`.

Reporting a problem counts too: when an issue you opened leads to a fix, you are
credited in `CONTRIBUTORS.md` under issue reporters.

Keep each change self-contained. Match the style of the surrounding code: the same
naming, comment density and idioms. Do not reformat unrelated code.

## Building

    just build      # compiles src/ and repacks dist/opentrap.jar
    just run        # launches the app
    just test       # compiles and runs the test suite
    just test-sandbox     # only the cases that run headless, as in a sandboxed agent session
    just test-nosandbox   # only the cases that need a display
    just --list     # every recipe: fetching, releasing, packaging

Tests live under `tests/` rather than `src/`, so they are never packed into the
shipped jar. Any change with logic to it should come with test cases. A case
that needs a display (it opens a window, or asks the toolkit for the menu
shortcut key) is declared with `Harness.displayTest` instead of `Harness.test`,
so `just test-sandbox` leaves it out and `just test-nosandbox` runs it.

Requirements:

- Java 27. The recipes compile with `--release 27` (`JAVA_RELEASE` in
  `tools/versions`); the AppImage ships Eclipse Temurin 27, and CI builds and
  tests on it. Few distributions package Java 27 yet: on Linux x64 `just jdk`
  installs the pinned Temurin into `jdk/`, which the recipes prefer. On macOS
  they use the Temurin 27 that `java_home` finds (`brew install --cask
  temurin`). `OPENTRAP_JDK` set to a JDK home overrides both.
- [just](https://github.com/casey/just) 1.59 or newer (`JUST_VERSION` in
  `tools/versions`), from your package manager or `brew install just`.
- bash. Recipes run under the first `bash` on PATH; on macOS put Homebrew's
  bash ahead of the system bash 3.2.
- curl, unzip and zip for the fetch and release recipes.

BouncyCastle and a Brotli decoder are vendored under `lib/` (`just libs`
fetches the pinned jars again); the ZAP scan engine and the Chromium browser
are fetched on demand by `just engine` and `just browser`. See
`DEPENDENCIES.md` for how bundled tools are pinned and updated across
releases, and run `just check-updates` to see what has a newer version.

## How the code is organised

The source lives under `src/opentrap/`:

- `core` - shared services: the suite context, message store, scope, scan tasks,
  issues, HTTP primitives.
- `engine` - drives the bundled OWASP ZAP scan engine, run headless: starting it,
  running crawl-and-audit, mapping ZAP's findings into OpenTRAP issues and its
  traffic into the logger, and configuring ZAP's rules. All detection is ZAP's;
  OpenTRAP keeps none of its own.
- `ui` - the main window, menus, shared Swing helpers, HiDPI scaling, bootstrap.
- `proxy` - the intercepting proxy, the per-host certificate authority, and the
  embedded browser launcher.
- one package per tool: `target`, `proxy`, `scanner`, `intruder`, `repeater`,
  `collaborator`, `sequencer`, `decoder`, `comparer`, `logger`, `dashboard`.

`CONTRACT.md` is the full API reference for the tool contract, `SuiteContext`,
`HttpMessage` and the shared UI helpers.

## Architecture at a glance

OpenTRAP is a shell that hosts independent tools around one shared context.

- **`SuiteContext` is the hub.** It holds the state every tool reads and writes:
  the message store (all captured traffic), the target scope, settings, the issue
  list, scan tasks, and the collaborator. Tools talk through it, not to each other.
- **Each tool is a `ToolModule` that contributes a tab.** The shell calls
  `init(ctx)` once, then `tabs()` to build its UI; tools are listed in
  `Bootstrap.registerTools` in tab order.
- **Traffic flows one way.** The proxy captures an exchange into `ctx.store()`;
  the store notifies listeners; the site map, logger and dashboard update from it.
- **Scanning is delegated to the engine** (`opentrap.engine`), which crawls and
  audits and returns findings mapped into `ctx`'s issue list.
- **`Ui` holds the shared Swing helpers and look and feel**, so every tool matches.

To understand any tool, read its `*Tool.java`: that one file is the whole of it.

## Add a tool in a few minutes

A tool is one class implementing `opentrap.core.ToolModule`, plus one line in
`Bootstrap`. This is a complete, working tool:

```java
package opentrap.hello;

import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;
import javax.swing.*;

public class HelloTool implements ToolModule {
    @Override public String name() { return "hello"; }    // the tab caption
    @Override public void init(SuiteContext ctx) { }       // grab shared state here
    @Override public Tab[] tabs() {
        JPanel p = new JPanel();
        p.add(Ui.label("Hello from a new tool."));
        return new Tab[]{ new Tab("hello", p) };
    }
}
```

Register it in `src/opentrap/ui/Bootstrap.java`:

```java
frame.register(new opentrap.hello.HelloTool());
```

Then `just build run`, and a "hello" tab appears. That is the whole
extension model. To receive requests from other tools' "send to" menus, call
`ctx.registerSink("hello", ...)` in `init`; everything else shared (traffic,
scope, issues) is on `SuiteContext`.

## Good first areas

- **Deeper ZAP engine integration.** Surface more of the engine through the UI:
  the AJAX spider for single-page apps, active-rule and scan-policy configuration,
  and persisting rule settings across runs.
- Protocol support (WebSocket, HTTP/2).
- Payload libraries and processors for intruder.
- UI polish and accessibility.

## House style

- Clear, self-documenting code over cleverness.
- Describe behaviour on its own terms in comments and docs.
- Do not use em dashes or en dashes anywhere in the project; use a plain hyphen.
- Keep the interface aligned and uniform. Labelled inputs line up through `Ui.form(...)`, filter strips through `Ui.filterBar(...)`, and matching controls share one height. A screen where fields or rows do not line up is a bug to fix, not to ship.
