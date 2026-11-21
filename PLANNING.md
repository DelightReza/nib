# Nib: where we left off (2026-11-21)

Latest release: **v2.5.0** (tag v2.5.0, commit 9e48578, versionCode 34). Release notes: `docs/announcements/v2.5.0.md`.
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
- **2.5.0:** layers (whole notebook; eraser/lasso on the active layer only); fix for 2.4.1's black patches while erasing
  (it swapped front-buffer surfaces; never do that); shelf closes on any tool pick or pen-down.

## How notebooks are stored (NIB8, since 2.3.0)
- `ink.bin` is an index: page order, each page's look (template, height, PDF page, bookmark, style) and file id.
- Each page's strokes and items are in `pages/<id>.bin` ("NIBP"), read when the page comes near the screen
  (`Page.strokes()` / `items()`) and let go once saved and more than 4 pages from the view (`Engine.unloadFar`).
- A save writes only dirty pages (a page is dirty when its lists hold other objects than its file), then the index.
- NIB1–7 notebooks are read whole and converted on first open; `ink.bin.old` stays until the next open
  (`Notebook.tidy` also removes page files no page refers to). Older Nib can't read NIB8.
- Stress test: `tools/stress/make_notebook.py OUT PAGES [STROKES]` writes an old-format notebook (300 pages ≈ 100 MB).
  On the Pad 6: cold open 451 ms / 18 MB heap, ~33 MB scrolling all pages, saves 16–20 ms (2.2.2: 192 MB, full rewrite).

## Watch
- **Split-view pane stopped showing ink** (2026-11-21 ~13:50, infinite canvas in one pane): strokes were saved but not
  shown until a finger touch; the other pane kept working. Android's log had rolled over, so the trigger is unknown.
  2.4.1 re-checks front-buffer mode at every pen-down and queues the buffer on lift. If it happens again, read
  `files/diag.log` around the time (front buffer ON/off/lost, surfaces, the pane in use).

## Next
- **Reorder tools** within the bar or the shelf (today each side keeps the fixed `TOOL_IDS` order).
- **Glass look** (optional): the layout is decided (option A, https://claude.ai/artifact/5kibc8S318u4LFz9uFQcQ3); the
  earlier "Liquid Glass" styling idea (https://claude.ai/artifact/QiaT3nVZUmrzR4DRC6Wpd8) is still open if wanted.

## Backlog
- **Finger writing with a resting palm** (Pad 6): blocked in the touch chip firmware, not the app. Research and the
  only remaining route (patched firmware loaded as the driver's debug firmware) in `~/nib-research/pad6-touch/NOTES.md`.
  Undecided. The touch-chip palm-rest experiment is in `git stash` (2026-11-16).

## How some things work (2.3–2.4)
- **Tool bar:** basic bar + advanced shelf (`capTools` / `capAdv`). Long-press a tool (or tap it again) for its panel,
  which ends with "Move to the tool bar / Move to more tools"; buttons without a panel get a small move menu. Pref
  `toolBasic` lists the bar's tools (default `pen,hl,eraser,lasso`); prefs `shelfOpen`, `shelfPin`.
- **Shapes:** `Engine.TOOL_SHAPE` snaps each stroke on lift. A selected shape has its own handles plus a dashed box
  whose square corners scale it proportionally (`allHandlesLocal`, `shapeBox`).
- **Tape looks:** stored as `look~pattern` in the tape's pattern text (`Render.TAPE_LOOKS`: flat, masking, washi,
  kraft); flat tape keeps the bare pattern so older Nib reads it. Patterns are drawn only over the strip.
- **Overflowing ink:** a stroke belongs to the page it started on; erase, lasso and taps check neighbouring pages
  (`Engine.inkPages`), a lasso moves such strokes into its page (`adopt`, undone if the selection is dropped
  untouched), and exports draw the neighbours' overflow (`Render.page` with prev / next).
- **Front-buffer surfaces are only ever flushed, never swapped:** on the Pad 6 an eglSwapBuffers on a single-buffer
  surface flips to a stale buffer (2.4.1: black patches while erasing). `kickDisplay` / `showFront` are no-ops now.
- **Layers:** `Notebook.layers` (bottom to top) + `activeLayer`, stored after the pages in the index; a stroke's layer is
  the top byte of its tool word, items' layers a block after a page file's items. Pages draw in `inDrawOrder`;
  hidden strokes get `LOOK_HIDDEN`. Delete / merge are undoable (`LayersEdit` + page edits); visibility, order,
  rename and the active layer are saved but not undo steps.

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
- Test notebooks: make one with `tools/stress/make_notebook.py` (or a hand-written NIB6 file), push it into
  `files/notebooks/` with root (chown to the app's uid, restorecon), open it with
  `su -c 'am start -n app.nib.notes/.EditorActivity --es dir /data/user/0/app.nib.notes/files/notebooks/<dir>'`
  (always the `/data/user/0` path), and afterwards delete it and take it out of the `tabs` / `tabCur` / `tabSplit`
  prefs. Never use the owner's notebooks for tests.
- Nib's own log: `adb shell "su -c 'cat /data/data/app.nib.notes/files/diag.log'"` (logcat's main buffer only keeps
  minutes on this tablet).
