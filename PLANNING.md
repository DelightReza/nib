# Nib: where we left off (2026-11-21)

Latest release: **v2.4.1** (tag v2.4.1, commit 653237d, versionCode 31). Release notes: `docs/announcements/v2.4.1.md`.
versionCode = git commit count; bump `VERSION_NAME` in `build-apk.sh` for a release. Release builds use the key in
`~/.nib-release` (`NIB_KEYSTORE` / `NIB_KS_PASS` / `NIB_KEY_ALIAS=nib`); the Magisk zip comes from
`tools/build-magisk-module.sh` (always build it fresh, never reuse an old zip).

## Releases since 2.1
- **2.1.2:** no slowdown over time (GPU leak, per-page ink buffers), phone layouts, very long text boxes save (NIB7),
  smooth ink without front buffer (POCO X7 Pro), builds in Termux.
- **2.2.1:** New notebook screen, covers, more templates, stickers, zoom limit and lock, reopen where you left off.
- **2.2.2:** RateKeeper (asks for the fastest refresh rate while writing); palm rejection removed.
- **2.3.0:** big notebooks stay light (NIB8, see below), basic + advanced tool bar (option A), Shapes tool,
  true-size thickness dots + log slider, partial lasso + closing line, erase/select strokes across a page edge,
  slim split grip, zoom chip over the zoomed pane.
- **2.4.0:** move tools between bar and shelf, proportional shape resize, paper tape looks, overflow ink in exports,
  segment hit tests, tabs by real path.
- **2.4.1:** tape patterns drawn only over the strip (dots took ~0.5 s), front-buffer re-check at pen-down + queue on
  lift (split-view pane stopped showing ink, 2026-11-21 13:50, cause unconfirmed), Diag log (files/diag.log, read with
  `adb shell "su -c 'cat /data/data/app.nib.notes/files/diag.log'"`), New notebook in the tab + and split pickers.

## How notebooks are stored (NIB8, since 2.3.0)
- `ink.bin` is an index: page order, each page's look (template, height, PDF page, bookmark, style) and file id.
- Each page's strokes and items are in `pages/<id>.bin` ("NIBP"), read when the page comes near the screen
  (`Page.strokes()` / `items()`) and let go once saved and more than 4 pages from the view (`Engine.unloadFar`).
- A save writes only dirty pages (a page is dirty when its lists hold other objects than its file), then the index.
- NIB1–7 notebooks are read whole and converted on first open; `ink.bin.old` stays until the next open
  (`Notebook.tidy` also removes page files no page refers to). Older Nib can't read NIB8.
- Stress test: `tools/stress/make_notebook.py OUT PAGES [STROKES]` writes an old-format notebook (300 pages ≈ 100 MB).
  On the Pad 6: cold open 451 ms / 18 MB heap, ~33 MB scrolling all pages, saves 16–20 ms (2.2.2: 192 MB, full rewrite).

## Next: tool bar follow-ups
- **Move tools between basic and advanced:** released in 2.4.0. Long-press a tool: its panel ends
  with "Move to the tool bar / Move to more tools"; buttons without a panel (image, ruler, finger, beautify, to
  text) get a small menu with just that. Each side keeps a fixed order (`TOOL_IDS`); pref `toolBasic` lists the
  bar's. Not done: reordering within a side.
- **Glass look.** The layout is decided (option A, mockups at https://claude.ai/artifact/5kibc8S318u4LFz9uFQcQ3);
  the earlier "Liquid Glass" styling idea (https://claude.ai/artifact/QiaT3nVZUmrzR4DRC6Wpd8) is still open if wanted.

## Done in 2.4.0
- Move tools between bar and shelf (above).
- **Resize a shape keeping its shape:** a selected shape (not a line) shows a light dashed box with square corner
  handles; dragging one scales the shape from the opposite corner, same proportions, same line width.
- **Paper tape:** looks Flat (as before) / Masking (default) / Washi / Kraft: torn ends, paper fibres, kraft specks,
  washi sheen, soft shadow; still fully covering. Stored as "look~pattern" in the tape's pattern string (flat tape
  keeps the bare pattern, so older Nib reads it as plain tape). New tape colour Cream.
- **Exports:** ink that runs over a page edge is drawn on the next page too (PDF, PNG, page overview).
- Taps and the eraser find strokes by their lines, not just their points (scaled shapes have far-apart points).
- Tabs compare a notebook folder's real path (the same folder opened as /data/data/… and /data/user/0/… made two tabs).

## Backlog
- **Finger writing with a resting palm** (Pad 6): blocked in the touch chip firmware, not the app. Research and the
  only remaining route (patched firmware loaded as the driver's debug firmware) in `~/nib-research/pad6-touch/NOTES.md`.
  Undecided. The touch-chip palm-rest experiment is in `git stash` (2026-11-16).
- Reordering tools within the bar or the shelf.

## Decided / rejected
- **Never override HyperOS's Smartpen refresh rule** (`ro.vendor.display.smartpen_vrr_fps`): turning it off broke the
  pen system-wide (2026-11-20). Writing tops out at 120 Hz on the Pad 6.
- Keep the no-front-buffer fallback for non-Snapdragon tablets (521ffc2).
- Software palm rejection: removed in 2.2.2 (owner's call).
- Android split windows: rejected; split view is in-app (tabs + two panes).
- Inspiration, not copies (a Noteshelf near-copy was rejected); Inter everywhere, no serif or hand-drawn UI.
- Finger tap to undo while erasing: rejected (HyperOS drops finger touches while the pen is down). Two-finger tap
  undo removed (fired by accident).
- Subfolders are created only by long-pressing a folder → "Add subfolder".
- The thickness dot was removed from the tool bar (tap the active tool for its panel).
- Notebook names stay as they are (the "date/time off the top bar" feedback: it's just the default title; owner, 2026-11-21).

## Testing notes
- Tablet: adb serial `fc4476d`, rooted (Magisk); `adb shell "su -c '...'"` reaches app files. It locks with the owner's
  PIN: ask before driving its UI. `svc power stayon usb` while testing, `false` after.
- Install every build straight away (owner's standing request) and reopen with
  `am start -n app.nib.notes/.LibraryActivity --ez reopen true`.
- The x86_64 emulator can't run Nib (arm64-only libs); test on the tablet.
