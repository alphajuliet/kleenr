# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kleenr is a client-side text processing utility — a single-file HTML application (`index.html`) with no build system, no server, and no dependencies beyond CDN-loaded libraries. All text processing happens entirely in the browser; no data is transmitted.

## Development

**No build step required.** Open `index.html` directly in a browser or serve it with any static file server:

```
python3 -m http.server 8000
# or
npx serve .
```

There are no tests, no linter, and no package.json.

## Architecture

The entire application lives in `index.html` (~1080 lines) using **Scittle** — a browser-based ClojureScript runtime that interprets code at load time, requiring no compilation step.

**Stack**: Scittle 0.8.31 → Reagent (ClojureScript React wrapper) → React 18, all loaded from CDNs.

### Code Structure (inside `<script type="application/x-scittle">`)

1. **State**: Single Reagent atom (`app-state`) holds all mutable state — text content, undo/redo stacks (max 100), selected category, regex fields, status messages, tab width.

2. **Text Operations**: ~40 pure functions (`op-*` naming convention) organized into categories: whitespace, tabs, case, quotes, lines, encoding. Each takes text and returns transformed text.

3. **Operation Wrapping**: `apply-op!` wraps every operation to diff old/new text and automatically manage the undo stack — never call `op-*` functions directly from UI handlers.

4. **Regex Module**: Separate system with `try-regex` for safe RegExp construction, live match counting, and 13 preset patterns.

5. **Components**: Reagent components using Hiccup syntax (Clojure vectors as markup). Root component is `app`, rendered to `#app` div.

6. **Theming**: CSS variables for full dark/light mode support via `prefers-color-scheme`.

### Key Patterns

- Operations are **pure functions**: `(defn op-name [text] ...) → text`
- State updates use `(swap! app-state ...)` — Reagent auto-rerenders
- Platform detection (`mac?`) drives modifier key display (Cmd vs Ctrl)
- Flash messages auto-dismiss after 2.5 seconds via `js/setTimeout`
- Content Security Policy restricts connections to CDN hosts only (`connect-src 'none'`)
