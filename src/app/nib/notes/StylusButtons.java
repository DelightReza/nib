package app.nib.notes;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent configuration, action catalogue, tool-cycling sequence, one-time migration,
 * and picker dialogs for physical Primary and Secondary stylus buttons.
 */
final class StylusButtons {

    // ---- Stable persisted action identifiers -----------------------------------------------

    static final String ACT_CYCLE_PEN_COLOR = "cycle_pen_color";
    static final String ACT_NEXT_PEN_COLOR = "next_pen_color";
    static final String ACT_PREV_PEN_COLOR = "prev_pen_color";
    static final String ACT_SELECT_PEN = "select_pen";
    static final String ACT_SELECT_HL = "select_highlighter";
    static final String ACT_SELECT_ERASER = "select_eraser";
    static final String ACT_TOGGLE_PEN_ERASER = "toggle_pen_eraser";
    static final String ACT_CYCLE_TOOLS = "cycle_tools";
    static final String ACT_NEXT_TOOL = "next_tool";
    static final String ACT_PREV_TOOL_CYCLE = "prev_tool_cycle";
    static final String ACT_PREVIOUS_TOOL = "previous_tool";
    static final String ACT_SELECT_LASSO = "select_lasso";
    static final String ACT_TOGGLE_LASSO = "toggle_lasso";
    static final String ACT_SELECT_SHAPE = "select_shape";
    static final String ACT_SELECT_LASER = "select_laser";
    static final String ACT_SELECT_TAPE = "select_tape";
    static final String ACT_SELECT_TEXT = "select_text";
    static final String ACT_UNDO = "undo";
    static final String ACT_REDO = "redo";
    static final String ACT_NONE = "none";

    // Defaults for fresh installations and Reset to Default
    static final String DEFAULT_PRIMARY = ACT_CYCLE_PEN_COLOR;
    static final String DEFAULT_SECONDARY = ACT_CYCLE_TOOLS;

    static final class ActionDef {
        final String id;
        final String label;
        ActionDef(String id, String label) { this.id = id; this.label = label; }
    }

    /** Complete list of supported assignable actions (shared by Primary and Secondary buttons). */
    static final ActionDef[] ACTIONS = {
            new ActionDef(ACT_CYCLE_PEN_COLOR, "Cycle Pen Colours"),
            new ActionDef(ACT_NEXT_PEN_COLOR, "Next Pen Colour"),
            new ActionDef(ACT_PREV_PEN_COLOR, "Previous Pen Colour"),
            new ActionDef(ACT_SELECT_PEN, "Select Pen"),
            new ActionDef(ACT_SELECT_HL, "Select Highlighter"),
            new ActionDef(ACT_SELECT_ERASER, "Select Eraser"),
            new ActionDef(ACT_TOGGLE_PEN_ERASER, "Toggle Pen / Eraser"),
            new ActionDef(ACT_CYCLE_TOOLS, "Cycle Drawing Tools"),
            new ActionDef(ACT_NEXT_TOOL, "Next Drawing Tool"),
            new ActionDef(ACT_PREV_TOOL_CYCLE, "Previous Drawing Tool"),
            new ActionDef(ACT_PREVIOUS_TOOL, "Previous Tool"),
            new ActionDef(ACT_SELECT_LASSO, "Select Lasso"),
            new ActionDef(ACT_TOGGLE_LASSO, "Toggle Lasso"),
            new ActionDef(ACT_SELECT_SHAPE, "Select Shape"),
            new ActionDef(ACT_SELECT_LASER, "Select Laser Pointer"),
            new ActionDef(ACT_SELECT_TAPE, "Select Tape"),
            new ActionDef(ACT_SELECT_TEXT, "Select Text"),
            new ActionDef(ACT_UNDO, "Undo"),
            new ActionDef(ACT_REDO, "Redo"),
            new ActionDef(ACT_NONE, "None (Disable Button)")
    };

    // ---- Cycleable drawing tools -----------------------------------------------------------

    static final class CycleTool {
        final String key;
        final int toolId;
        final String label;
        CycleTool(String key, int toolId, String label) {
            this.key = key;
            this.toolId = toolId;
            this.label = label;
        }
    }

    static final CycleTool[] CYCLE_TOOLS = {
            new CycleTool("pen", Engine.TOOL_PEN, "Pen"),
            new CycleTool("highlighter", Engine.TOOL_HL, "Highlighter"),
            new CycleTool("eraser", Engine.TOOL_ERASER, "Eraser"),
            new CycleTool("lasso", Engine.TOOL_LASSO, "Lasso"),
            new CycleTool("shape", Engine.TOOL_SHAPE, "Shape"),
            new CycleTool("laser", Engine.TOOL_LASER, "Laser Pointer"),
            new CycleTool("tape", Engine.TOOL_TAPE, "Tape"),
            new CycleTool("text", Engine.TOOL_TEXT, "Text")
    };

    static final String DEFAULT_CYCLE_CSV = "pen,highlighter,eraser";

    // ---- SharedPreferences keys ------------------------------------------------------------

    static final String KEY_PRIMARY = "stylusPrimaryAction";
    static final String KEY_SECONDARY = "stylusSecondaryAction";
    static final String KEY_CYCLE = "stylusToolCycleSequence";
    private static final String KEY_MIGRATED = "stylusButtonsMigrated_v1";
    private static final String KEY_LEGACY_PEN_BUTTON = "penButton";

    // ---- State -----------------------------------------------------------------------------

    String primaryAction = DEFAULT_PRIMARY;
    String secondaryAction = DEFAULT_SECONDARY;
    final ArrayList<Integer> toolCycle = new ArrayList<>();

    StylusButtons() {
        applyDefaultCycle();
    }

    private void applyDefaultCycle() {
        toolCycle.clear();
        toolCycle.add(Engine.TOOL_PEN);
        toolCycle.add(Engine.TOOL_HL);
        toolCycle.add(Engine.TOOL_ERASER);
    }

    /**
     * Loads stylus button assignments and cycle sequence, performing a safe one-time migration
     * from the legacy boolean {@code penButton} preference if present.
     */
    static StylusButtons load(SharedPreferences p) {
        StylusButtons sb = new StylusButtons();
        if (p == null) return sb;
        boolean migrated = p.getBoolean(KEY_MIGRATED, false) || p.contains(KEY_PRIMARY) || p.contains(KEY_SECONDARY);
        if (!migrated) {
            if (p.contains(KEY_LEGACY_PEN_BUTTON)) {
                boolean legacyOn = p.getBoolean(KEY_LEGACY_PEN_BUTTON, true);
                if (legacyOn) {
                    sb.primaryAction = ACT_TOGGLE_PEN_ERASER;
                    sb.secondaryAction = ACT_TOGGLE_LASSO;
                } else {
                    sb.primaryAction = ACT_NONE;
                    sb.secondaryAction = ACT_NONE;
                }
            } else {
                // Fresh installation: new defaults
                sb.primaryAction = DEFAULT_PRIMARY;
                sb.secondaryAction = DEFAULT_SECONDARY;
            }
            sb.applyDefaultCycle();
            sb.save(p);
            return sb;
        }
        sb.primaryAction = sanitizeAction(p.getString(KEY_PRIMARY, DEFAULT_PRIMARY), DEFAULT_PRIMARY);
        sb.secondaryAction = sanitizeAction(p.getString(KEY_SECONDARY, DEFAULT_SECONDARY), DEFAULT_SECONDARY);
        sb.parseCycleCsv(p.getString(KEY_CYCLE, DEFAULT_CYCLE_CSV));
        return sb;
    }

    void save(SharedPreferences p) {
        if (p == null) return;
        p.edit()
                .putString(KEY_PRIMARY, primaryAction)
                .putString(KEY_SECONDARY, secondaryAction)
                .putString(KEY_CYCLE, encodeCycleCsv())
                .putBoolean(KEY_MIGRATED, true)
                .apply();
    }

    void resetToDefaults(SharedPreferences p) {
        primaryAction = DEFAULT_PRIMARY;
        secondaryAction = DEFAULT_SECONDARY;
        applyDefaultCycle();
        save(p);
    }

    /**
     * Computes the next (delta = +1) or previous (delta = -1) tool in the configured cycle sequence.
     * If {@code currentTool} is not part of the cycle, returns {@link Engine#TOOL_PEN} if present,
     * otherwise the first tool in the cycle.
     */
    int stepToolCycle(int currentTool, int delta) {
        if (toolCycle.isEmpty()) applyDefaultCycle();
        int n = toolCycle.size();
        int idx = toolCycle.indexOf(currentTool);
        if (idx < 0) {
            return toolCycle.contains(Engine.TOOL_PEN) ? Engine.TOOL_PEN : toolCycle.get(0);
        }
        int next = ((idx + delta) % n + n) % n;
        return toolCycle.get(next);
    }

    String cycleSummary() {
        if (toolCycle.isEmpty()) applyDefaultCycle();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCycle.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(toolName(toolCycle.get(i)));
        }
        return sb.toString();
    }

    String encodeCycleCsv() {
        if (toolCycle.isEmpty()) applyDefaultCycle();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCycle.size(); i++) {
            String key = keyForTool(toolCycle.get(i));
            if (key == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(key);
        }
        return sb.length() > 0 ? sb.toString() : DEFAULT_CYCLE_CSV;
    }

    private void parseCycleCsv(String csv) {
        toolCycle.clear();
        if (csv != null && !csv.trim().isEmpty()) {
            String[] parts = csv.split(",");
            for (String raw : parts) {
                int tid = toolForKey(raw.trim());
                if (tid >= 0 && !toolCycle.contains(tid)) toolCycle.add(tid);
            }
        }
        if (toolCycle.size() < 2) applyDefaultCycle();
    }

    static String sanitizeAction(String id, String fallback) {
        if (id != null) {
            for (ActionDef a : ACTIONS) if (a.id.equals(id)) return a.id;
        }
        return fallback;
    }

    static String labelForAction(String id) {
        if (id != null) {
            for (ActionDef a : ACTIONS) if (a.id.equals(id)) return a.label;
        }
        return "None (Disable Button)";
    }

    static String toolName(int toolId) {
        for (CycleTool t : CYCLE_TOOLS) if (t.toolId == toolId) return t.label;
        return "Pen";
    }

    private static String keyForTool(int toolId) {
        for (CycleTool t : CYCLE_TOOLS) if (t.toolId == toolId) return t.key;
        return null;
    }

    private static int toolForKey(String key) {
        for (CycleTool t : CYCLE_TOOLS) if (t.key.equals(key)) return t.toolId;
        return -1;
    }

    // ---- Settings Picker Dialogs -----------------------------------------------------------

    interface ActionSelected { void onSelected(String actionId); }

    static void showActionPicker(Context c, String title, String currentActionId, ActionSelected cb) {
        String[] labels = new String[ACTIONS.length];
        int checked = 0;
        for (int i = 0; i < ACTIONS.length; i++) {
            labels[i] = ACTIONS[i].label;
            if (ACTIONS[i].id.equals(currentActionId)) checked = i;
        }
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which >= 0 && which < ACTIONS.length && cb != null) {
                        cb.onSelected(ACTIONS[which].id);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void showCyclePicker(Context c, StylusButtons sb, SharedPreferences prefs, Runnable onChanged) {
        String[] labels = new String[CYCLE_TOOLS.length];
        boolean[] checked = new boolean[CYCLE_TOOLS.length];
        for (int i = 0; i < CYCLE_TOOLS.length; i++) {
            labels[i] = CYCLE_TOOLS[i].label;
            checked[i] = sb.toolCycle.contains(CYCLE_TOOLS[i].toolId);
        }
        new AlertDialog.Builder(c)
                .setTitle("Drawing tool cycle")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (dialog, which) -> {
                    List<Integer> next = new ArrayList<>();
                    for (int i = 0; i < CYCLE_TOOLS.length; i++) {
                        if (checked[i]) next.add(CYCLE_TOOLS[i].toolId);
                    }
                    if (next.size() < 2) {
                        Toast.makeText(c, "Select at least 2 tools for cycling", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sb.toolCycle.clear();
                    sb.toolCycle.addAll(next);
                    sb.save(prefs);
                    if (onChanged != null) onChanged.run();
                })
                .setNeutralButton("Default", (dialog, which) -> {
                    sb.applyDefaultCycle();
                    sb.save(prefs);
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
