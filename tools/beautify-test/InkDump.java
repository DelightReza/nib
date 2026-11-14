package app.nib.notes;

import java.io.*;
import java.util.*;

/** Reads a Nib ink.bin (NIB5), prints text boxes, and runs script detection per line of ink; writes an SVG of each page. */
public class InkDump {
    public static void main(String[] a) throws Exception {
        for (String path : a) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)));
            int magic = in.readInt();
            boolean v5 = magic == 0x4E494235, v4 = v5 || magic == 0x4E494234, v3 = v4 || magic == 0x4E494233;
            int pages = in.readInt();
            System.out.println("== " + new File(path).getName() + " · " + pages + " pages");
            StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='1000' height='" + (pages * 1414) + "' style='background:#fff'>");
            for (int pi = 0; pi < pages; pi++) {
                int bg = in.readInt();
                float ph = in.readFloat(); int pdf = in.readInt();
                int ns = in.readInt();
                List<Stroke> strokes = new ArrayList<>();
                for (int s = 0; s < ns; s++) {
                    int tw = in.readInt();
                    Stroke st = new Stroke(tw & 0xffff, in.readInt(), in.readFloat());
                    st.shape = (tw >>> 16) & 0xff;
                    int n = in.readInt();
                    for (int k = 0; k < n; k++) st.add(in.readFloat(), in.readFloat(), in.readFloat());
                    strokes.add(st);
                }
                int ni = in.readInt();
                for (int i = 0; i < ni; i++) {
                    int type = in.readInt();
                    float x = in.readFloat(), y = in.readFloat(), w = in.readFloat(), h = in.readFloat();
                    String text = in.readUTF(); float size = in.readFloat(); int color = in.readInt();
                    int np = in.readInt(); for (int q = 0; q < np; q++) in.readFloat();
                    if (type == 0) System.out.println("  p" + (pi + 1) + " text box: \"" + text.replace("\n", " / ") + "\"" + (np == 1 ? " (converted)" : ""));
                }
                if (v3 && in.readBoolean()) in.readUTF();
                if (v4) { in.readFloat(); in.readInt(); in.readFloat(); in.readInt(); }
                List<List<Stroke>> lines = Beautify.lineGroups(strokes);
                for (List<Stroke> line : lines) {
                    float t = 1e9f, b = -1e9f, l = 1e9f, r = -1e9f;
                    for (Stroke s : line) { t = Math.min(t, s.t); b = Math.max(b, s.b); l = Math.min(l, s.l); r = Math.max(r, s.r); }
                    System.out.printf("  p%d ink line y=%.0f-%.0f x=%.0f-%.0f strokes=%d → %s%n", pi + 1, t, b, l, r, line.size(), Beautify.SCRIPT_NAMES[Beautify.scriptOf(line)]);
                }
                for (Stroke s : strokes) {
                    svg.append("<polyline fill='none' stroke='#222' stroke-width='2' points='");
                    for (int i = 0; i < s.n; i++) svg.append(s.x(i)).append(',').append(s.y(i) + pi * 1414).append(' ');
                    svg.append("'/>");
                }
            }
            svg.append("</svg>");
            try (FileWriter f = new FileWriter(path + ".svg")) { f.write(svg.toString()); }
        }
    }
}
