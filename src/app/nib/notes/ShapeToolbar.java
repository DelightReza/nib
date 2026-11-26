package app.nib.notes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Dedicated expandable Shape & Design toolbar capsule + Shape Properties / Layer Order popover.
 *
 * Toolbar groups (all 14 tools):
 * - A. Freeform: Freeform
 * - B. Basic Geometric: Rectangle, Rounded Rectangle, Circle, Ellipse, Triangle, Polygon
 * - C. Lines & Arrows: Straight Line, Single Arrow, Double Arrow, Curved Line
 * - D. Annotation & Special: Speech Bubble, Bracket, Brace
 * Plus quick Style/Properties button and collapse/expand toggle so the writing area stays uncluttered.
 */
final class ShapeToolbar {
    interface Host {
        void onPickShapeKind(String kind);
        void onOpenShapeStyle(View anchor);
        void onExpandChanged(boolean expanded);
    }

    static final class ToolEntry {
        final String kind;
        final int icon;
        final String label;
        final boolean dividerBefore;
        ToolEntry(String kind, int icon, String label, boolean dividerBefore) {
            this.kind = kind; this.icon = icon; this.label = label; this.dividerBefore = dividerBefore;
        }
    }

    static final ToolEntry[] TOOLS = {
            // A. Freeform
            new ToolEntry(ShapeSpec.KIND_FREEFORM, R.drawable.ic_shape_freeform, "Freeform", false),
            // B. Basic Geometric
            new ToolEntry(ShapeSpec.KIND_RECT, R.drawable.ic_shape_rect, "Rectangle", true),
            new ToolEntry(ShapeSpec.KIND_ROUND_RECT, R.drawable.ic_shape_rrect, "Rounded rect", false),
            new ToolEntry(ShapeSpec.KIND_CIRCLE, R.drawable.ic_shape_circle, "Circle", false),
            new ToolEntry(ShapeSpec.KIND_ELLIPSE, R.drawable.ic_shape_ellipse, "Ellipse", false),
            new ToolEntry(ShapeSpec.KIND_TRIANGLE, R.drawable.ic_shape_triangle, "Triangle", false),
            new ToolEntry(ShapeSpec.KIND_POLYGON, R.drawable.ic_shape_polygon, "Polygon", false),
            // C. Lines & Arrows
            new ToolEntry(ShapeSpec.KIND_LINE, R.drawable.ic_shape_line, "Straight line", true),
            new ToolEntry(ShapeSpec.KIND_ARROW, R.drawable.ic_shape_arrow, "Arrow", false),
            new ToolEntry(ShapeSpec.KIND_DOUBLE_ARROW, R.drawable.ic_shape_darrow, "Double arrow", false),
            new ToolEntry(ShapeSpec.KIND_CURVE, R.drawable.ic_shape_curve, "Curved line", false),
            // D. Annotation & Special
            new ToolEntry(ShapeSpec.KIND_SPEECH_BUBBLE, R.drawable.ic_shape_bubble, "Speech bubble", true),
            new ToolEntry(ShapeSpec.KIND_BRACKET, R.drawable.ic_shape_bracket, "Bracket", false),
            new ToolEntry(ShapeSpec.KIND_BRACE, R.drawable.ic_shape_brace, "Brace", false)
    };

    final LinearLayout container;
    private final HorizontalScrollView scroll;
    private final LinearLayout row;
    private final Ui.IconButton toggleBtn;
    private final Ui.IconButton styleBtn;
    private final Ui.IconButton[] kindBtns = new Ui.IconButton[TOOLS.length];
    private boolean expanded = true;
    private String selectedKind = ShapeSpec.KIND_RECT;

    ShapeToolbar(Context c, Host host) {
        container = new LinearLayout(c);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(Ui.dp(c, 6), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 3));
        Ui.capsule(container, c, 20);

        toggleBtn = new Ui.IconButton(c, R.drawable.ic_shapes, "Expand or collapse shape tools");
        toggleBtn.setTooltipText("Shape tools");
        toggleBtn.setOnClickListener(v -> {
            expanded = !expanded;
            updateExpanded();
            if (host != null) host.onExpandChanged(expanded);
        });
        container.addView(toggleBtn);

        scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < TOOLS.length; i++) {
            final ToolEntry te = TOOLS[i];
            if (te.dividerBefore) row.addView(Ui.divider(c));
            Ui.IconButton b = new Ui.IconButton(c, te.icon, te.label);
            b.setTooltipText(te.label);
            b.setOnClickListener(v -> {
                setSelectedKind(te.kind);
                if (host != null) host.onPickShapeKind(te.kind);
            });
            kindBtns[i] = b;
            row.addView(b);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        container.addView(scroll, slp);

        container.addView(Ui.divider(c));
        styleBtn = new Ui.IconButton(c, R.drawable.ic_palette, "Shape style, fill & opacity");
        styleBtn.setTooltipText("Shape style & fill");
        styleBtn.setOnClickListener(v -> { if (host != null) host.onOpenShapeStyle(v); });
        container.addView(styleBtn);

        setSelectedKind(selectedKind);
        updateExpanded();
    }

    static int iconForKind(String kind) {
        if (kind == null) return R.drawable.ic_shapes;
        for (ToolEntry te : TOOLS) if (te.kind.equals(kind)) return te.icon;
        return R.drawable.ic_shapes;
    }

    void setSelectedKind(String kind) {
        selectedKind = kind != null ? kind : ShapeSpec.KIND_RECT;
        for (int i = 0; i < TOOLS.length; i++) {
            kindBtns[i].setSel(TOOLS[i].kind.equals(selectedKind));
        }
        toggleBtn.setImageResource(expanded ? R.drawable.ic_shapes : iconForKind(selectedKind));
        toggleBtn.setSel(!expanded);
    }

    void setExpanded(boolean exp) {
        expanded = exp;
        updateExpanded();
    }

    boolean isExpanded() { return expanded; }

    private void updateExpanded() {
        scroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggleBtn.setImageResource(expanded ? R.drawable.ic_shapes : iconForKind(selectedKind));
        toggleBtn.setSel(!expanded);
    }

    // ---- Shared Shape Properties & Layer Order Popover -------------------------------------

    interface StyleCallback {
        void onSpecChanged(ShapeSpec updated);
        void onEditSlot(int slotIndex, Runnable afterEdit);
        void onLayerOrder(int op);
    }

    /**
     * Builds the Shape Appearance, Fill, Thickness, Opacity, Shape-Specific & Layer-Order controls
     * inside a {@link Ui.Popover}. Works identically for:
     * - Default Shape tool settings ({@code editingExisting == false})
     * - Editing an already-created selected shape ({@code editingExisting == true})
     */
    static void populateStylePopover(Context c, Ui.Popover pop, DrawPrefs prefs, ShapeSpec initial,
                                     boolean editingExisting, StyleCallback cb) {
        final ShapeSpec cur = initial.copy();
        pop.title(editingExisting ? ShapeSpec.labelOf(cur.kind) + " properties" : "Shape style · " + ShapeSpec.labelOf(cur.kind));

        // 1. Layer stacking order (when editing an existing shape)
        if (editingExisting) {
            TextView zBadge = Ui.text(c, cur.zOrder < 0 ? "Order: Behind handwriting (default)" : "Order: In front of handwriting",
                    12.5f, Ui.color(c, R.color.accent), Ui.medium());
            zBadge.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 6));
            pop.add(zBadge);

            LinearLayout zRow = new LinearLayout(c);
            zRow.setGravity(Gravity.CENTER_VERTICAL);
            int[] zIcons = {R.drawable.ic_layer_front, R.drawable.ic_layer_forward, R.drawable.ic_layer_backward, R.drawable.ic_layer_back};
            String[] zLabels = {"To Front", "Forward", "Backward", "To Back"};
            int[] zOps = {ShapeSpec.Z_BRING_TO_FRONT, ShapeSpec.Z_BRING_FORWARD, ShapeSpec.Z_SEND_BACKWARD, ShapeSpec.Z_SEND_TO_BACK};
            for (int i = 0; i < 4; i++) {
                final int op = zOps[i];
                LinearLayout btn = new LinearLayout(c);
                btn.setOrientation(LinearLayout.VERTICAL);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
                GradientDrawable bg = Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 10));
                btn.setBackground(Ui.ripple(c, bg, Ui.dp(c, 10)));
                ImageView iv = new ImageView(c);
                iv.setImageResource(zIcons[i]);
                iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.on_surface)));
                btn.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
                TextView tv = Ui.text(c, zLabels[i], 11f, Ui.color(c, R.color.on_surface), Ui.medium());
                tv.setPadding(0, Ui.dp(c, 3), 0, 0);
                tv.setSingleLine(true);
                btn.addView(tv);
                btn.setContentDescription(zLabels[i]);
                btn.setOnClickListener(v -> {
                    if (cb != null) cb.onLayerOrder(op);
                    pop.dismiss();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (i < 3) lp.rightMargin = Ui.dp(c, 6);
                zRow.addView(btn, lp);
            }
            pop.add(zRow);
        }

        // 2. Appearance / Fill Style (4 styles: No fill, Solid, Translucent, Custom)
        final LinearLayout fillSection = new LinearLayout(c);
        fillSection.setOrientation(LinearLayout.VERTICAL);

        final TextView fillColHdr = Ui.text(c, "Fill colour", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        final LinearLayout fillOpWrap = new LinearLayout(c);
        fillOpWrap.setOrientation(LinearLayout.VERTICAL);
        final TextView fillOpLabel = Ui.text(c, "Fill opacity · " + cur.fillAlpha + "%", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        final SeekBar[] fillOpBar = new SeekBar[1];
        final LinearLayout[] styleSegRef = new LinearLayout[1];
        final Ui.NoColorSwatch[] noFillSwRef = new Ui.NoColorSwatch[1];
        final Ui.Swatch[] fillSwatches = new Ui.Swatch[DrawPrefs.SLOT_COUNT];

        Runnable updateFillVisibility = () -> {
            boolean canFill = cur.supportsFill();
            boolean hasFill = canFill && cur.fillStyle != ShapeSpec.STYLE_OUTLINE;
            fillSection.setVisibility(canFill ? View.VISIBLE : View.GONE);
            fillColHdr.setText(hasFill ? "Fill colour" : "Fill colour · No colour");
            if (noFillSwRef[0] != null) noFillSwRef[0].setSel(!hasFill);
            int selFillRgb = cur.fillColor | 0xff000000;
            for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) {
                if (fillSwatches[i] != null) {
                    fillSwatches[i].set(prefs.slots[i], hasFill && (prefs.slots[i] | 0xff000000) == selFillRgb);
                }
            }
            if (styleSegRef[0] != null) Ui.setSegmented(styleSegRef[0], cur.fillStyle);
            fillOpWrap.setVisibility(hasFill ? View.VISIBLE : View.GONE);
            if (fillOpBar[0] != null && hasFill) {
                boolean editableAlpha = cur.fillStyle == ShapeSpec.STYLE_TRANSPARENT || cur.fillStyle == ShapeSpec.STYLE_CUSTOM;
                fillOpBar[0].setEnabled(editableAlpha);
                fillOpBar[0].setAlpha(editableAlpha ? 1f : 0.45f);
                int shown = cur.fillStyle == ShapeSpec.STYLE_SOLID ? 100 : cur.fillAlpha;
                fillOpBar[0].setProgress(shown);
                fillOpLabel.setText("Fill opacity · " + shown + "%");
            }
        };

        if (cur.supportsFill() || ShapeSpec.KIND_FREEFORM.equals(cur.kind)) {
            if (ShapeSpec.KIND_FREEFORM.equals(cur.kind) && editingExisting) {
                pop.add(Ui.switchRow(c, "Closed shape (fillable)", "Connects end to start so fill styles apply", cur.closed, on -> {
                    cur.closed = on;
                    updateFillVisibility.run();
                    if (cb != null) cb.onSpecChanged(cur.copy());
                }));
            }
            pop.section("Appearance style");
            LinearLayout styleSeg = Ui.segmented(c, ShapeSpec.STYLE_NAMES, cur.fillStyle, idx -> {
                cur.fillStyle = idx;
                if (idx == ShapeSpec.STYLE_OUTLINE) {
                    // No colour fill (outline only)
                } else if (idx == ShapeSpec.STYLE_SOLID) {
                    cur.fillAlpha = 100;
                } else if (idx == ShapeSpec.STYLE_TRANSPARENT) {
                    if (cur.fillAlpha <= 0 || cur.fillAlpha >= 100) cur.fillAlpha = prefs.shapeFillOpacity > 0 && prefs.shapeFillOpacity < 100 ? prefs.shapeFillOpacity : 35;
                } else if (idx == ShapeSpec.STYLE_CUSTOM) {
                    if (cur.fillAlpha <= 0) cur.fillAlpha = prefs.shapeFillOpacity > 0 ? prefs.shapeFillOpacity : 45;
                }
                updateFillVisibility.run();
                if (cb != null) cb.onSpecChanged(cur.copy());
            });
            styleSegRef[0] = styleSeg;
            pop.add(styleSeg);
        }

        // 3. Border Thickness (3 user-definable thickness slots + slider to customise)
        pop.section("Border thickness (3 custom slots)");
        int initSlot = editingExisting
                ? DrawPrefs.nearestPreset(cur.strokeWidth, prefs.shapeWidths)
                : prefs.shapeThick;
        pop.add(customThicknessControl(c, prefs.shapeWidths, initSlot, cur.strokeWidth,
                DrawPrefs.SHAPE_MIN, DrawPrefs.SHAPE_MAX, cur.strokeColor, editingExisting,
                new ThicknessCallback() {
                    @Override public void onSelectSlot(int slotIdx, float width) {
                        cur.strokeWidth = width;
                        if (!editingExisting) prefs.shapeThick = slotIdx;
                        if (cb != null) cb.onSpecChanged(cur.copy());
                    }
                    @Override public void onEditWidth(int slotIdx, float width) {
                        cur.strokeWidth = width;
                        if (!editingExisting) {
                            prefs.shapeThick = slotIdx;
                            prefs.shapeWidths[slotIdx] = width;
                        }
                        if (cb != null) cb.onSpecChanged(cur.copy());
                    }
                    @Override public void onResetDefaults() {
                        prefs.resetShapeWidths();
                        cur.strokeWidth = prefs.shapeWidths[prefs.shapeThick];
                        if (cb != null) cb.onSpecChanged(cur.copy());
                    }
                }));

        // 4. Border Colour (5 customisable slots) + Border Opacity (0..100%)
        pop.section("Border colour");
        pop.add(fiveSlotRow(c, prefs.slots, cur.strokeColor, (slotIdx, col) -> {
            cur.strokeColor = col;
            if (!editingExisting) prefs.shapeStrokeSlot = slotIdx;
            if (cb != null) cb.onSpecChanged(cur.copy());
        }, slotIdx -> {
            if (cb != null) cb.onEditSlot(slotIdx, () -> {
                cur.strokeColor = prefs.slots[slotIdx];
                if (cb != null) cb.onSpecChanged(cur.copy());
            });
        }));

        TextView borderOpLbl = Ui.text(c, "Border opacity · " + cur.strokeAlpha + "%", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        borderOpLbl.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
        pop.add(borderOpLbl);
        pop.add(Ui.slider(c, 100, cur.strokeAlpha, v -> {
            cur.strokeAlpha = v;
            borderOpLbl.setText("Border opacity · " + v + "%");
            if (!editingExisting) prefs.shapeStrokeOpacity = v;
            if (cb != null) cb.onSpecChanged(cur.copy());
        }));

        // 5. Fill Colour ("No colour" default + 5 customisable slots) + Fill Opacity (0..100%)
        fillColHdr.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 6));
        fillSection.addView(fillColHdr);

        LinearLayout fillRow = new LinearLayout(c);
        fillRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.NoColorSwatch noFill = new Ui.NoColorSwatch(c);
        noFill.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 44)));
        noFill.setOnClickListener(v -> {
            cur.fillStyle = ShapeSpec.STYLE_OUTLINE;
            updateFillVisibility.run();
            if (cb != null) cb.onSpecChanged(cur.copy());
        });
        noFillSwRef[0] = noFill;
        fillRow.addView(noFill);

        for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) {
            final int idx = i;
            Ui.Swatch sw = new Ui.Swatch(c);
            sw.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 44)));
            sw.setContentDescription("Fill colour slot " + (i + 1) + " · long-press to edit");
            sw.setOnClickListener(v -> {
                cur.fillColor = prefs.slots[idx] | 0xff000000;
                if (!editingExisting) prefs.shapeFillSlot = idx;
                if (cur.fillStyle == ShapeSpec.STYLE_OUTLINE) {
                    cur.fillStyle = ShapeSpec.STYLE_TRANSPARENT;
                    if (cur.fillAlpha <= 0) cur.fillAlpha = prefs.shapeFillOpacity > 0 ? prefs.shapeFillOpacity : 35;
                }
                updateFillVisibility.run();
                if (cb != null) cb.onSpecChanged(cur.copy());
            });
            sw.setOnLongClickListener(v -> {
                if (cb != null) cb.onEditSlot(idx, () -> {
                    cur.fillColor = prefs.slots[idx] | 0xff000000;
                    if (!editingExisting) prefs.shapeFillSlot = idx;
                    if (cur.fillStyle == ShapeSpec.STYLE_OUTLINE) {
                        cur.fillStyle = ShapeSpec.STYLE_TRANSPARENT;
                        if (cur.fillAlpha <= 0) cur.fillAlpha = prefs.shapeFillOpacity > 0 ? prefs.shapeFillOpacity : 35;
                    }
                    if (cb != null) cb.onSpecChanged(cur.copy());
                });
                return true;
            });
            fillSwatches[i] = sw;
            fillRow.addView(sw);
        }
        Ui.PaletteDot editFill = new Ui.PaletteDot(c);
        editFill.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 44)));
        editFill.setContentDescription("Customise 5 colour slots");
        editFill.setOnClickListener(v -> {
            int curSlot = 0;
            int selRgb = cur.fillColor | 0xff000000;
            for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) if ((prefs.slots[i] | 0xff000000) == selRgb) { curSlot = i; break; }
            final int editIdx = curSlot;
            if (cb != null) cb.onEditSlot(editIdx, () -> {
                cur.fillColor = prefs.slots[editIdx] | 0xff000000;
                if (!editingExisting) prefs.shapeFillSlot = editIdx;
                if (cur.fillStyle == ShapeSpec.STYLE_OUTLINE) {
                    cur.fillStyle = ShapeSpec.STYLE_TRANSPARENT;
                    if (cur.fillAlpha <= 0) cur.fillAlpha = prefs.shapeFillOpacity > 0 ? prefs.shapeFillOpacity : 35;
                }
                if (cb != null) cb.onSpecChanged(cur.copy());
            });
        });
        fillRow.addView(editFill);
        fillSection.addView(fillRow);

        fillOpLabel.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
        fillOpWrap.addView(fillOpLabel);
        fillOpBar[0] = Ui.slider(c, 100, cur.fillAlpha, v -> {
            cur.fillAlpha = v;
            fillOpLabel.setText("Fill opacity · " + v + "%");
            if (!editingExisting) prefs.shapeFillOpacity = v;
            if (cb != null) cb.onSpecChanged(cur.copy());
        });
        fillOpWrap.addView(fillOpBar[0]);
        fillSection.addView(fillOpWrap);
        pop.add(fillSection);
        updateFillVisibility.run();

        // 6. Shape-specific controls
        if (cur.hasCornerRadius()) {
            TextView crLbl = Ui.text(c, "Corner radius · " + Math.round(cur.cornerRadius * 200) + "%", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            crLbl.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
            pop.add(crLbl);
            pop.add(Ui.slider(c, 100, Math.round(cur.cornerRadius * 200), v -> {
                cur.cornerRadius = v / 200f;
                crLbl.setText("Corner radius · " + v + "%");
                if (!editingExisting) prefs.shapeCornerRadius = cur.cornerRadius;
                if (cb != null) cb.onSpecChanged(cur.copy());
            }));
        }

        if (ShapeSpec.KIND_POLYGON.equals(cur.kind)) {
            TextView sdLbl = Ui.text(c, "Polygon sides · " + cur.sides, 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            sdLbl.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
            pop.add(sdLbl);
            pop.add(Ui.slider(c, 9, cur.sides - 3, v -> {
                cur.sides = 3 + v;
                sdLbl.setText("Polygon sides · " + cur.sides);
                if (!editingExisting) prefs.shapePolygonSides = cur.sides;
                if (cb != null) cb.onSpecChanged(cur.copy());
            }));
        }

        if (cur.isArrow()) {
            TextView arLbl = Ui.text(c, "Arrowhead size · " + Math.round(cur.arrowSize * 100) + "%", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            arLbl.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
            pop.add(arLbl);
            pop.add(Ui.slider(c, 200, Math.round((cur.arrowSize - 0.5f) * 100), v -> {
                cur.arrowSize = 0.5f + v / 100f;
                arLbl.setText("Arrowhead size · " + Math.round(cur.arrowSize * 100) + "%");
                if (!editingExisting) prefs.shapeArrowSize = cur.arrowSize;
                if (cb != null) cb.onSpecChanged(cur.copy());
            }));
        }

        if (ShapeSpec.KIND_CIRCLE.equals(cur.kind) && !editingExisting) {
            pop.add(Ui.switchRow(c, "Maintain perfect circle", "Locks 1:1 aspect while drawing and resizing", prefs.shapeLockCircle, on -> {
                prefs.shapeLockCircle = on;
                if (cb != null) cb.onSpecChanged(cur.copy());
            }));
        }
    }

    interface ThicknessCallback {
        void onSelectSlot(int slotIdx, float width);
        void onEditWidth(int slotIdx, float width);
        void onResetDefaults();
    }

    /**
     * 3 user-definable thickness slots (visual dot + live mm readout) paired with a customisation slider
     * and Reset button so users can define each slot's exact thickness themselves—just like the 5 colour slots.
     */
    static LinearLayout customThicknessControl(Context c, float[] widths, int initialSlot, float initialWidth,
                                               float minW, float maxW, int tintRgb, boolean editingExisting,
                                               ThicknessCallback cb) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);

        final int[] active = {DrawPrefs.clamp(initialSlot, 0, DrawPrefs.THICK_COUNT - 1)};
        final boolean[] syncing = {false};

        LinearLayout slotRow = new LinearLayout(c);
        slotRow.setGravity(Gravity.CENTER_VERTICAL);
        slotRow.setBackground(Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 12)));
        int pad = Ui.dp(c, 4);
        slotRow.setPadding(pad, pad, pad, pad);

        final LinearLayout[] cells = new LinearLayout[DrawPrefs.THICK_COUNT];
        final Ui.SizeDot[] dots = new Ui.SizeDot[DrawPrefs.THICK_COUNT];
        final TextView[] lbls = new TextView[DrawPrefs.THICK_COUNT];

        LinearLayout sliderRow = new LinearLayout(c);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = Ui.dp(c, 8);
        sliderRow.setLayoutParams(srlp);

        final TextView valTv = Ui.text(c, DrawPrefs.formatMm(initialWidth), 12.5f, Ui.color(c, R.color.on_surface), Ui.medium());
        valTv.setGravity(Gravity.END);

        final SeekBar[] barRef = new SeekBar[1];

        Runnable refreshCells = () -> {
            for (int i = 0; i < DrawPrefs.THICK_COUNT; i++) {
                boolean sel = i == active[0];
                cells[i].setBackground(sel ? Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 9)) : null);
                cells[i].setElevation(sel ? Ui.dp(c, 1.5f) : 0f);
                dots[i].set(DrawPrefs.widthFrac(widths[i], minW, maxW));
                dots[i].tint(tintRgb);
                dots[i].chosen(sel);
                lbls[i].setText(DrawPrefs.formatMm(widths[i]));
                lbls[i].setTextColor(Ui.color(c, sel ? R.color.on_surface : R.color.on_surface_variant));
            }
        };

        for (int i = 0; i < DrawPrefs.THICK_COUNT; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 5));
            cell.setContentDescription("Thickness slot " + (i + 1) + " · " + DrawPrefs.formatMm(widths[i]));

            Ui.SizeDot dot = new Ui.SizeDot(c);
            dot.setClickable(false);
            dot.setFocusable(false);
            dot.setBackground(null);
            cell.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 32)));

            TextView lbl = Ui.text(c, DrawPrefs.formatMm(widths[i]), 11.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            lbl.setSingleLine(true);
            cell.addView(lbl);

            cells[i] = cell;
            dots[i] = dot;
            lbls[i] = lbl;

            cell.setOnClickListener(v -> {
                active[0] = idx;
                float w = widths[idx];
                syncing[0] = true;
                if (barRef[0] != null) barRef[0].setProgress(DrawPrefs.widthToSlider(w, minW, maxW));
                valTv.setText(DrawPrefs.formatMm(w));
                syncing[0] = false;
                refreshCells.run();
                if (cb != null) cb.onSelectSlot(idx, w);
            });
            slotRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        SeekBar sb = Ui.slider(c, 100, DrawPrefs.widthToSlider(initialWidth, minW, maxW), prog -> {
            if (syncing[0]) return;
            float w = DrawPrefs.sliderToWidth(prog, minW, maxW);
            if (!editingExisting) widths[active[0]] = w;
            valTv.setText(DrawPrefs.formatMm(w));
            refreshCells.run();
            if (cb != null) cb.onEditWidth(active[0], w);
        });
        barRef[0] = sb;

        sliderRow.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        sliderRow.addView(valTv, new LinearLayout.LayoutParams(Ui.dp(c, 52), -2));

        if (!editingExisting) {
            TextView reset = Ui.text(c, "Reset", 12f, Ui.color(c, R.color.accent), Ui.medium());
            reset.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            reset.setBackground(Ui.ripple(c, null, Ui.dp(c, 8)));
            reset.setContentDescription("Reset thickness slots to defaults");
            reset.setOnClickListener(v -> {
                if (cb != null) cb.onResetDefaults();
                float w = widths[active[0]];
                syncing[0] = true;
                sb.setProgress(DrawPrefs.widthToSlider(w, minW, maxW));
                valTv.setText(DrawPrefs.formatMm(w));
                syncing[0] = false;
                refreshCells.run();
            });
            sliderRow.addView(reset);
        }

        refreshCells.run();
        wrap.addView(slotRow);
        wrap.addView(sliderRow);
        return wrap;
    }

    interface SlotPick { void onPick(int slotIdx, int color); }

    /** 5-slot colour row with active ring + long-press or palette button to edit in ColorPickerDialog. */
    static LinearLayout fiveSlotRow(Context c, int[] slots, int selectedRgb, SlotPick onPick, java.util.function.IntConsumer onEditSlot) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Swatch[] views = new Ui.Swatch[DrawPrefs.SLOT_COUNT];
        int selRgb = selectedRgb | 0xff000000;
        for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) {
            final int idx = i;
            Ui.Swatch sw = new Ui.Swatch(c);
            sw.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
            sw.set(slots[i], (slots[i] | 0xff000000) == selRgb);
            sw.setContentDescription("Colour slot " + (i + 1) + " · long-press to edit");
            sw.setOnClickListener(v -> {
                for (int k = 0; k < DrawPrefs.SLOT_COUNT; k++) views[k].set(slots[k], k == idx);
                onPick.onPick(idx, slots[idx] | 0xff000000);
            });
            sw.setOnLongClickListener(v -> {
                if (onEditSlot != null) onEditSlot.accept(idx);
                return true;
            });
            views[i] = sw;
            row.addView(sw);
        }
        Ui.PaletteDot edit = new Ui.PaletteDot(c);
        edit.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 44)));
        edit.setContentDescription("Customise 5 colour slots");
        edit.setOnClickListener(v -> {
            int curSlot = 0;
            for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) if ((slots[i] | 0xff000000) == selRgb) { curSlot = i; break; }
            if (onEditSlot != null) onEditSlot.accept(curSlot);
        });
        row.addView(edit);
        return row;
    }
}
