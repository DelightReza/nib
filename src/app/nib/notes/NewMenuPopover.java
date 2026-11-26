package app.nib.notes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Floating popup menu anchored under the Library "New" button:
 * create a notebook, infinite canvas, or folder; import PDF, Office docs, photos, or Nib backups; or scan documents.
 */
final class NewMenuPopover {
    private NewMenuPopover() {}

    interface Actions {
        void onNewNotebook();
        void onNewInfiniteCanvas();
        void onNewFolder();
        void onImportPdf();
        void onImportDocument();
        void onImportPhoto();
        void onImportNibBackup();
        void onScanDocuments();
    }

    private static final class MaxScroll extends ScrollView {
        int maxH = Integer.MAX_VALUE;
        MaxScroll(Context c) { super(c); }
        @Override protected void onMeasure(int ws, int hs) {
            int mode = MeasureSpec.getMode(hs), size = MeasureSpec.getSize(hs);
            int cap = mode == MeasureSpec.UNSPECIFIED ? maxH : Math.min(size, maxH);
            super.onMeasure(ws, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    static void show(Context c, View anchor, Actions actions) {
        // Hide soft keyboard if the user had focused the search bar before tapping New
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && anchor.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        }

        View root = anchor.getRootView();
        int winW = root != null && root.getWidth() > 0 ? root.getWidth() : c.getResources().getDisplayMetrics().widthPixels;
        int winH = root != null && root.getHeight() > 0 ? root.getHeight() : c.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Ui.dp(c, 268), winW - Ui.dp(c, 24));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8));

        GradientDrawable cardBg = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 16));
        cardBg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));

        MaxScroll scroll = new MaxScroll(c);
        scroll.setBackground(cardBg);
        scroll.setClipToOutline(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        PopupWindow pw = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setElevation(Ui.dp(c, 12));
        pw.setOutsideTouchable(true);
        pw.setAnimationStyle(android.R.style.Animation_Dialog);

        // Creation group
        body.addView(menuRow(c, R.drawable.ic_notebook_new, "New notebook", () -> { pw.dismiss(); actions.onNewNotebook(); }));
        body.addView(menuRow(c, R.drawable.ic_infinity, "New infinite canvas", () -> { pw.dismiss(); actions.onNewInfiniteCanvas(); }));
        body.addView(menuRow(c, R.drawable.ic_folder_plus, "New folder", () -> { pw.dismiss(); actions.onNewFolder(); }));

        // Subtle section divider
        View sep = new View(c);
        sep.setBackgroundColor(Ui.color(c, R.color.outline));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.hairline(c));
        slp.topMargin = Ui.dp(c, 6);
        slp.bottomMargin = Ui.dp(c, 6);
        slp.leftMargin = Ui.dp(c, 10);
        slp.rightMargin = Ui.dp(c, 10);
        body.addView(sep, slp);

        // Import & scan group
        body.addView(menuRow(c, R.drawable.ic_doc_import, "Import PDF", () -> { pw.dismiss(); actions.onImportPdf(); }));
        body.addView(menuRow(c, R.drawable.ic_doc_office, "Import DOCX / PPTX", () -> { pw.dismiss(); actions.onImportDocument(); }));
        body.addView(menuRow(c, R.drawable.ic_image, "Import photo", () -> { pw.dismiss(); actions.onImportPhoto(); }));
        body.addView(menuRow(c, R.drawable.ic_nib_archive, "Import Nib backup", () -> { pw.dismiss(); actions.onImportNibBackup(); }));
        body.addView(menuRow(c, R.drawable.ic_camera_scan, "Scan documents", () -> { pw.dismiss(); actions.onScanDocuments(); }));

        // Anchor relative to window coordinates so multi-window, split-screen, and edge-to-edge all align cleanly
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int margin = Ui.dp(c, 12);
        int x = loc[0] + anchor.getWidth() - width;
        x = Math.max(margin, Math.min(winW - width - margin, x));
        int y = loc[1] + anchor.getHeight() + Ui.dp(c, 8);
        int bottomInset = 0;
        WindowInsets wi = anchor.getRootWindowInsets();
        if (wi != null) {
            bottomInset = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
        }
        scroll.maxH = Math.max(Ui.dp(c, 180), winH - y - margin - bottomInset);

        pw.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    }

    private static View menuRow(Context c, int iconRes, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 48));
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        row.setBackground(Ui.ripple(c, null, Ui.dp(c, 11)));
        row.setContentDescription(label);

        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.on_surface)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        ilp.rightMargin = Ui.dp(c, 16);
        row.addView(iv, ilp);

        TextView tv = Ui.text(c, label, 15f, Ui.color(c, R.color.on_surface), Ui.medium());
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(v -> onClick.run());
        return row;
    }
}
