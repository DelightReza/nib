package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Rich, theme-aware dialogs for folder creation, customization, moving notes/folders,
 * and adding existing notebooks into a folder.
 */
final class FolderDialogs {
    private FolderDialogs() {}

    interface CreateCallback {
        void onCreated(String name, int colorIdx);
    }

    interface PickCallback {
        void onPicked(String folderPath);
    }

    /**
     * Create or edit a folder (name + color swatches + live tinted folder icon preview).
     * If existingPath != null, acts as Rename & Customize; otherwise creates inside parent.
     */
    static void showEditor(Activity a, String parent, String existingPath, CreateCallback cb) {
        boolean editing = existingPath != null && !existingPath.isEmpty();
        final int[] chosenColor = {editing ? FolderStore.meta(a, existingPath).colorIdx : 0};

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 14), Ui.dp(a, 22), Ui.dp(a, 4));

        // Top row: live folder badge preview + name input
        LinearLayout inputRow = new LinearLayout(a);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout badge = new FrameLayout(a);
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.CENTER);
        badge.addView(icon, icLp);
        Runnable updatePreview = () -> {
            int col = FolderStore.resolveColor(a, chosenColor[0]);
            badge.setBackground(Ui.round(FolderStore.tintContainer(a, col), Ui.dp(a, 12)));
            icon.setImageTintList(ColorStateList.valueOf(col));
        };
        updatePreview.run();

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
        badgeLp.setMarginEnd(Ui.dp(a, 12));
        inputRow.addView(badge, badgeLp);

        EditText et = new EditText(a);
        et.setText(editing ? FolderStore.leaf(existingPath) : "");
        et.setHint("Folder name");
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        et.setTextSize(15.5f);
        et.setTextColor(Ui.color(a, R.color.on_surface));
        et.setHintTextColor(Ui.color(a, R.color.on_surface_variant));
        inputRow.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(inputRow);

        TextView colorLabel = Ui.text(a, "Folder colour", 13f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, -2);
        clLp.topMargin = Ui.dp(a, 18);
        clLp.bottomMargin = Ui.dp(a, 8);
        box.addView(colorLabel, clLp);

        box.addView(buildColorGrid(a, chosenColor[0], idx -> {
            chosenColor[0] = idx;
            updatePreview.run();
        }));

        String title = editing ? "Edit folder"
                : (parent == null || parent.isEmpty()) ? "New folder"
                : "New subfolder in " + FolderStore.leaf(parent);

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(editing ? "Save" : "Create", (d, w) -> {
                    String cleaned = FolderStore.cleanName(et.getText().toString());
                    if (cleaned.isEmpty()) return;
                    cb.onCreated(cleaned, chosenColor[0]);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();
        et.requestFocus();
    }

    /** Quick color picker dialog for an existing folder. */
    static void showColorPicker(Activity a, String folderPath, Runnable onChanged) {
        int cur = FolderStore.meta(a, folderPath).colorIdx;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 14), Ui.dp(a, 22), Ui.dp(a, 8));

        final AlertDialog[] holder = new AlertDialog[1];
        box.addView(buildColorGrid(a, cur, idx -> {
            FolderStore.setColor(a, folderPath, idx);
            if (holder[0] != null) holder[0].dismiss();
            if (onChanged != null) onChanged.run();
        }));

        holder[0] = new AlertDialog.Builder(a)
                .setTitle("Colour for “" + FolderStore.leaf(folderPath) + "”")
                .setView(box)
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
    }

    private interface IntConsumer { void accept(int value); }

    private static View buildColorGrid(Activity a, int selectedIdx, IntConsumer onPick) {
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        final int perRow = 5;
        final List<FrameLayout> cells = new ArrayList<>();
        final List<ImageView> checks = new ArrayList<>();

        int total = FolderStore.COLORS_LIGHT.length;
        for (int r = 0; r < total; r += perRow) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (r > 0) rlp.topMargin = Ui.dp(a, 10);
            for (int c = 0; c < perRow && r + c < total; c++) {
                final int idx = r + c;
                int col = FolderStore.resolveColor(a, idx);

                FrameLayout slot = new FrameLayout(a);
                FrameLayout dot = new FrameLayout(a);
                GradientDrawable gd = Ui.round(col, Ui.dp(a, 18));
                if (idx == selectedIdx) {
                    gd.setStroke(Ui.dp(a, 2.5f), Ui.color(a, R.color.on_surface));
                }
                dot.setBackground(gd);

                ImageView chk = new ImageView(a);
                chk.setImageResource(R.drawable.ic_check);
                chk.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                chk.setVisibility(idx == selectedIdx ? View.VISIBLE : View.GONE);
                dot.addView(chk, new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18), Gravity.CENTER));

                cells.add(dot);
                checks.add(chk);

                slot.addView(dot, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));
                slot.setContentDescription(FolderStore.COLOR_NAMES[idx]);
                slot.setTooltipText(FolderStore.COLOR_NAMES[idx]);
                slot.setBackground(Ui.ripple(a, null, Ui.dp(a, 22)));
                slot.setOnClickListener(v -> {
                    for (int k = 0; k < cells.size(); k++) {
                        int kCol = FolderStore.resolveColor(a, k);
                        GradientDrawable kgd = Ui.round(kCol, Ui.dp(a, 18));
                        if (k == idx) kgd.setStroke(Ui.dp(a, 2.5f), Ui.color(a, R.color.on_surface));
                        cells.get(k).setBackground(kgd);
                        checks.get(k).setVisibility(k == idx ? View.VISIBLE : View.GONE);
                    }
                    onPick.accept(idx);
                });
                row.addView(slot, new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f));
            }
            rows.addView(row, rlp);
        }
        return rows;
    }

    /**
     * Visual scrollable Folder Picker used for moving 1 notebook, moving selected notebooks,
     * or moving a folder into another folder. Supports creating a new folder on the fly.
     */
    static void showFolderPicker(
            Activity a,
            String title,
            List<Notebook.Entry> allNotebooks,
            List<String> allFolders,
            String currentFolder,
            String excludeSubtree,
            boolean allowNewFolder,
            Runnable onRequestNewFolder,
            PickCallback cb) {

        LinearLayout container = new LinearLayout(a);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));

        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        final AlertDialog[] holder = new AlertDialog[1];

        // Top-level / Home option (unless excluded because folder is already top-level when moving a folder)
        boolean includeRoot = excludeSubtree == null || !FolderStore.parentOf(excludeSubtree).isEmpty();
        if (includeRoot) {
            boolean isCurrent = currentFolder != null && currentFolder.isEmpty();
            list.addView(pickerRow(a, R.drawable.ic_inbox, Ui.color(a, R.color.icon),
                    "Home (No folder)", 0, false, isCurrent, v -> {
                        if (holder[0] != null) holder[0].dismiss();
                        cb.onPicked("");
                    }));
        }

        int shownFolders = 0;
        for (String f : allFolders) {
            if (excludeSubtree != null && (FolderStore.within(f, excludeSubtree) || f.equals(FolderStore.parentOf(excludeSubtree)))) {
                continue;
            }
            shownFolders++;
            boolean isCurrent = f.equals(currentFolder);
            boolean pinned = FolderStore.isPinned(a, f);
            int color = FolderStore.colorOf(a, f);
            list.addView(pickerRow(a, R.drawable.ic_folder, color,
                    FolderStore.leaf(f), FolderStore.depth(f), pinned, isCurrent, v -> {
                        if (holder[0] != null) holder[0].dismiss();
                        cb.onPicked(f);
                    }));
        }

        if (shownFolders == 0 && !includeRoot) {
            TextView none = Ui.text(a, "No other folders available yet.", 14f,
                    Ui.color(a, R.color.on_surface_variant), null);
            none.setPadding(Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16));
            list.addView(none);
        }

        int maxH = Math.min(Ui.dp(a, 380), a.getResources().getDisplayMetrics().heightPixels / 2);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t > maxH) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) scroll.getLayoutParams();
                lp.height = maxH;
                scroll.setLayoutParams(lp);
            }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(container)
                .setNegativeButton("Cancel", null);
        if (allowNewFolder && onRequestNewFolder != null) {
            builder.setNeutralButton("+ New folder", (d, w) -> onRequestNewFolder.run());
        }
        holder[0] = builder.create();
        holder[0].show();
    }

    private static View pickerRow(Activity a, int iconRes, int tintColor, String label,
                                  int depth, boolean pinned, boolean checked, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 12 + depth * 18), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        row.setBackground(Ui.ripple(a, checked ? Ui.round(Ui.color(a, R.color.accent_container), Ui.dp(a, 10)) : null, Ui.dp(a, 10)));

        FrameLayout badge = new FrameLayout(a);
        badge.setBackground(Ui.round(FolderStore.tintContainer(a, tintColor), Ui.dp(a, 9)));
        ImageView iv = new ImageView(a);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(tintColor));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.addView(iv, new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34));
        blp.setMarginEnd(Ui.dp(a, 12));
        row.addView(badge, blp);

        TextView tv = Ui.text(a, label, 15f,
                Ui.color(a, checked ? R.color.accent : R.color.on_surface), checked ? Ui.bold() : Ui.medium());
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        if (pinned) {
            ImageView pin = new ImageView(a);
            pin.setImageResource(R.drawable.ic_pin);
            pin.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_surface_variant)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(a, 15), Ui.dp(a, 15));
            plp.setMarginEnd(Ui.dp(a, 8));
            row.addView(pin, plp);
        }

        if (checked) {
            ImageView chk = new ImageView(a);
            chk.setImageResource(R.drawable.ic_check);
            chk.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
            row.addView(chk, new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));
        }

        row.setMinimumHeight(Ui.dp(a, 48));
        row.setOnClickListener(onClick);
        return row;
    }

    /**
     * Multi-select checklist dialog to pull existing notebooks from Home or other folders into destFolder.
     */
    static void showAddNotebooksToFolder(Activity a, String destFolder, List<Notebook.Entry> allNotebooks, Runnable onMoved) {
        ArrayList<Notebook.Entry> candidates = new ArrayList<>();
        for (Notebook.Entry e : allNotebooks) {
            if (!e.folder.equals(destFolder)) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            Toast.makeText(a, "All notebooks are already in " + FolderStore.leaf(destFolder), Toast.LENGTH_SHORT).show();
            return;
        }

        HashSet<String> chosen = new HashSet<>();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 4));

        EditText filterBox = new EditText(a);
        filterBox.setHint("Filter notebooks…");
        filterBox.setSingleLine(true);
        filterBox.setTextSize(14f);
        filterBox.setTextColor(Ui.color(a, R.color.on_surface));
        filterBox.setHintTextColor(Ui.color(a, R.color.on_surface_variant));
        filterBox.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        GradientDrawable sbg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 12));
        filterBox.setBackground(sbg);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 40));
        fblp.bottomMargin = Ui.dp(a, 10);
        root.addView(filterBox, fblp);

        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        int maxH = Math.min(Ui.dp(a, 360), a.getResources().getDisplayMetrics().heightPixels / 2);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxH));

        final AlertDialog[] holder = new AlertDialog[1];
        Runnable updatePosBtn = () -> {
            if (holder[0] == null) return;
            android.widget.Button pos = holder[0].getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                int n = chosen.size();
                pos.setEnabled(n > 0);
                pos.setText(n == 0 ? "Move" : "Move " + n);
            }
        };

        Runnable render = () -> {
            list.removeAllViews();
            String q = filterBox.getText().toString().trim().toLowerCase(Locale.getDefault());
            for (Notebook.Entry e : candidates) {
                if (!q.isEmpty() && !e.title.toLowerCase(Locale.getDefault()).contains(q)
                        && !e.folder.toLowerCase(Locale.getDefault()).contains(q)) continue;
                String key = e.dir.getPath();
                boolean sel = chosen.contains(key);

                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
                row.setBackground(Ui.ripple(a, sel ? Ui.round(Ui.color(a, R.color.accent_container), Ui.dp(a, 10)) : null, Ui.dp(a, 10)));

                FrameLayout checkBox = new FrameLayout(a);
                GradientDrawable cbg = Ui.round(sel ? Ui.color(a, R.color.accent) : Color.TRANSPARENT, Ui.dp(a, 11));
                cbg.setStroke(Ui.dp(a, 1.6f), Ui.color(a, sel ? R.color.accent : R.color.on_surface_variant));
                checkBox.setBackground(cbg);
                ImageView chk = new ImageView(a);
                chk.setImageResource(R.drawable.ic_check);
                chk.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_accent)));
                chk.setVisibility(sel ? View.VISIBLE : View.GONE);
                checkBox.addView(chk, new FrameLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14), Gravity.CENTER));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
                clp.setMarginEnd(Ui.dp(a, 12));
                row.addView(checkBox, clp);

                LinearLayout txt = new LinearLayout(a);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView titleView = Ui.text(a, e.title, 14.5f, Ui.color(a, R.color.on_surface), Ui.medium());
                titleView.setSingleLine(true);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(titleView);

                String loc = e.folder.isEmpty() ? "Home" : FolderStore.pretty(e.folder);
                String meta = loc + " · " + (e.canvas ? "Canvas" : e.pages + (e.pages == 1 ? " page" : " pages"));
                TextView sub = Ui.text(a, meta, 12f, Ui.color(a, R.color.on_surface_variant), null);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(sub);
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));

                row.setMinimumHeight(Ui.dp(a, 50));
                row.setOnClickListener(v -> {
                    boolean nowSel = !chosen.remove(key);
                    if (nowSel) chosen.add(key);
                    row.setBackground(Ui.ripple(a, nowSel ? Ui.round(Ui.color(a, R.color.accent_container), Ui.dp(a, 10)) : null, Ui.dp(a, 10)));
                    GradientDrawable bg2 = Ui.round(nowSel ? Ui.color(a, R.color.accent) : Color.TRANSPARENT, Ui.dp(a, 11));
                    bg2.setStroke(Ui.dp(a, 1.6f), Ui.color(a, nowSel ? R.color.accent : R.color.on_surface_variant));
                    checkBox.setBackground(bg2);
                    chk.setVisibility(nowSel ? View.VISIBLE : View.GONE);
                    updatePosBtn.run();
                });
                list.addView(row);
            }
        };

        filterBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render.run(); }
        });
        render.run();

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Move notebooks to “" + FolderStore.leaf(destFolder) + "”")
                .setView(root)
                .setPositiveButton("Move", (d, w) -> {
                    ArrayList<Notebook.Entry> toMove = new ArrayList<>();
                    for (Notebook.Entry e : candidates) {
                        if (chosen.contains(e.dir.getPath())) toMove.add(e);
                    }
                    if (toMove.isEmpty()) return;
                    int n = FolderStore.moveNotebooks(toMove, destFolder);
                    Toast.makeText(a, "Moved " + n + (n == 1 ? " notebook" : " notebooks")
                            + " to " + FolderStore.leaf(destFolder), Toast.LENGTH_SHORT).show();
                    if (onMoved != null) onMoved.run();
                })
                .setNegativeButton("Cancel", null)
                .create();
        holder[0] = dlg;
        dlg.show();
        updatePosBtn.run();
    }

    /** Confirmation dialog for removing a folder (keep notes vs move notes to trash). */
    static void showDeleteFolder(Activity a, String folderPath, FolderStore.Stats st,
                                 Runnable onKeepNotes, Runnable onTrashAll) {
        String name = FolderStore.leaf(folderPath);
        String parent = FolderStore.parentOf(folderPath);
        String where = parent.isEmpty() ? "Home" : FolderStore.leaf(parent);

        if (st.totalNotebooks == 0) {
            new AlertDialog.Builder(a)
                    .setTitle("Delete “" + name + "”?")
                    .setMessage(st.directSubfolders == 0
                            ? "This folder is empty."
                            : "This folder has no notebooks. Any empty subfolders will move to " + where + ".")
                    .setPositiveButton("Delete", (d, w) -> onKeepNotes.run())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        String msg = "Contains " + st.totalNotebooks + (st.totalNotebooks == 1 ? " notebook" : " notebooks")
                + (st.directSubfolders > 0 ? " and " + st.directSubfolders + (st.directSubfolders == 1 ? " subfolder" : " subfolders") : "")
                + ".\n\n• Remove folder only: moves notebooks to " + where + ".\n• Trash all: moves notebooks to Trash (kept 30 days).";

        new AlertDialog.Builder(a)
                .setTitle("Delete “" + name + "”?")
                .setMessage(msg)
                .setPositiveButton("Remove folder only", (d, w) -> onKeepNotes.run())
                .setNeutralButton("Trash all", (d, w) -> onTrashAll.run())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
