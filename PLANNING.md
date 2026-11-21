# Nib: where we left off (2026-11-21)

Latest release: **v2.3.0** (tag v2.3.0, commit 32b0839, versionCode 26). Release notes: `docs/announcements/v2.3.0.md`.
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
- **Move tools between basic and advanced.** The mockup promised "long-press a tool to move it"; not built yet
  (long-press still opens the tool's panel). Needs a place for it: e.g. an "Edit tool bar" entry in Settings or
  in the shelf, with drag between the two rows; save the layout in prefs.
- **Glass look.** The layout is decided (option A, mockups at https://claude.ai/artifact/5kibc8S318u4LFz9uFQcQ3);
  the earlier "Liquid Glass" styling idea (https://claude.ai/artifact/QiaT3nVZUmrzR4DRC6Wpd8) is still open if wanted.

## Backlog
- **Resize a shape keeping its shape** (owner, 2026-11-14): a light bounding box with corner handles that scales a
  snapped shape proportionally (maybe a rotate handle), next to the existing vertex handles. Line widths stay.
- **Tape that looks like real tape:** masking (default), washi and kraft looks; torn ends, fibre texture, soft shadow.
- **Strokes across a page edge in exports:** each page is clipped on export, so the part of a stroke that runs onto
  the next page is cut off in PDFs. Fix: draw neighbours' overflow when exporting a page (or split on commit).
- **"Date/time moved off the page, undo/redo in their place"** (feedback from Ffaru1111): unclear. Best guess: new
  notebooks are named by date/time, which fills the tab. Ask them for a screenshot.
- **Finger writing with a resting palm** (Pad 6): blocked in the touch chip firmware, not the app. Research and the
  only remaining route (patched firmware loaded as the driver's debug firmware) in `~/nib-research/pad6-touch/NOTES.md`.
  Undecided. The touch-chip palm-rest experiment is in `git stash` (2026-11-16).

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

## Testing notes
- Tablet: adb serial `fc4476d`, rooted (Magisk); `adb shell "su -c '...'"` reaches app files. It locks with the owner's
  PIN: ask before driving its UI. `svc power stayon usb` while testing, `false` after.
- Install every build straight away (owner's standing request) and reopen with
  `am start -n app.nib.notes/.LibraryActivity --ez reopen true`.
- The x86_64 emulator can't run Nib (arm64-only libs); test on the tablet.
