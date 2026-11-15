package app.nib.notes;

import static android.opengl.GLES30.*;

import android.graphics.RectF;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * All GPU work. Ink is drawn as instanced "tapered capsules" (one quad per segment, the
 * fragment shader computes exact anti-aliased distance), so strokes are resolution independent.
 *
 * Latency trick: the window surface is put in EGL single-buffer ("front buffer") mode with
 * auto-refresh, so new ink is drawn straight into the buffer the display is scanning out, with
 * no queue/swap round-trip. A cache FBO holds the committed scene; the window = cache + the
 * predicted tail, which gets wiped each event by re-blitting the dirty rect from the cache.
 */
final class Gl {
    static final String TAG = "nib";
    static final int EGL_MUTABLE_RENDER_BUFFER_BIT_KHR = 0x1000;
    static final int EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID = 0x314C;
    static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100;
    static final int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;

    EGLDisplay dpy;
    EGLConfig cfg;
    EGLContext ctx;
    EGLSurface surf = EGL14.EGL_NO_SURFACE;
    boolean mutable;
    int W, H;
    float ox, oy, z = 1f;

    private int quad, segProg, pageProg, blitProg, texProg;
    private int sView, sSize, sMode, sXf, sXt, sClip, pView, pSize, pPage, pBg, pShadow, pPal, pOrigin, pStyle, pLook, pLine, pMarg, bTex;
    // page look for the next page() call (Notebook.Page spacing / strength / lineColor / margins)
    private float lookK = 1, lookS = 1;
    private int lookColor, lookMargins;

    void setLook(float spacing, float strength, int lineColor, int margins) {
        lookK = spacing; lookS = strength; lookColor = lineColor; lookMargins = margins;
    }
    private int tView, tSize, tRect, tXf, tXt, tTex;
    private int fbo, tex;
    final SegBuf pen = new SegBuf(), hl = new SegBuf(), pred = new SegBuf(), selPen = new SegBuf(), selHl = new SegBuf(), laser = new SegBuf();
    /** Ink is hidden outside [clipX0, clipX1] (the page's left/right edges; unbounded on an infinite canvas). */
    float clipX0 = 0, clipX1 = Notebook.PW;

    void init() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        cfg = choose(true);
        mutable = cfg != null;
        if (cfg == null) cfg = choose(false);
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[]{
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE}, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[]{
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        }
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx);
        buildPrograms();
        Log.i(TAG, "EGL ready, mutable render buffer=" + mutable);
    }

    private EGLConfig choose(boolean mut) {
        int[] a = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | (mut ? EGL_MUTABLE_RENDER_BUFFER_BIT_KHR : 0),
                EGL14.EGL_NONE};
        EGLConfig[] c = new EGLConfig[16];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, a, 0, c, 0, c.length, n, 0) || n[0] == 0) return null;
        int[] q = new int[1];
        for (int i = 0; i < n[0]; i++) {
            EGL14.eglGetConfigAttrib(dpy, c[i], EGL14.EGL_RED_SIZE, q, 0);
            int r = q[0];
            EGL14.eglGetConfigAttrib(dpy, c[i], EGL14.EGL_ALPHA_SIZE, q, 0);
            if (r == 8 && q[0] == 8) return c[i];
        }
        return c[0];
    }

    void setSurface(Surface s, int w, int h) {
        dropSurface();
        surf = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        EGL14.eglSwapInterval(dpy, 1);
        W = w;
        H = h;
        makeFbo();
    }

    void dropSurface() {
        if (surf != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx);
            EGL14.eglDestroySurface(dpy, surf);
            surf = EGL14.EGL_NO_SURFACE;
        }
    }

    boolean hasSurface() { return surf != EGL14.EGL_NO_SURFACE; }

    /** Request single-buffer (front) or normal mode. Takes effect on the next swap(). */
    void requestFront(boolean on) {
        if (!mutable || !hasSurface()) return;
        if (on) {
            EGL14.eglSurfaceAttrib(dpy, surf, EGL14.EGL_RENDER_BUFFER, EGL14.EGL_SINGLE_BUFFER);
            EGL14.eglSurfaceAttrib(dpy, surf, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL14.EGL_TRUE);
        } else {
            EGL14.eglSurfaceAttrib(dpy, surf, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL14.EGL_FALSE);
            EGL14.eglSurfaceAttrib(dpy, surf, EGL14.EGL_RENDER_BUFFER, EGL14.EGL_BACK_BUFFER);
        }
    }

    boolean isFront() {
        int[] v = new int[1];
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_RENDER_BUFFER, v, 0);
        return v[0] == EGL14.EGL_SINGLE_BUFFER;
    }

    void swap() { EGL14.eglSwapBuffers(dpy, surf); }

    void flush() { glFlush(); }

    void release() {
        dropSurface();
        if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
        EGL14.eglTerminate(dpy);
    }

    // ---- targets -------------------------------------------------------------------------

    private void makeFbo() {
        if (fbo != 0) {
            glDeleteFramebuffers(1, new int[]{fbo}, 0);
            glDeleteTextures(1, new int[]{tex}, 0);
        }
        int[] t = new int[1];
        glGenTextures(1, t, 0);
        tex = t[0];
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, W, H);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glGenFramebuffers(1, t, 0);
        fbo = t[0];
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        int st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "FBO incomplete " + st);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void bindCache() { glBindFramebuffer(GL_FRAMEBUFFER, fbo); glViewport(0, 0, W, H); }

    void bindWindow() { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, W, H); }

    void scissor(RectF r) {
        glEnable(GL_SCISSOR_TEST);
        int l = Math.max(0, (int) Math.floor(r.left)), t = Math.max(0, (int) Math.floor(r.top));
        int rr = Math.min(W, (int) Math.ceil(r.right)), bb = Math.min(H, (int) Math.ceil(r.bottom));
        if (r.isEmpty() || rr <= l || bb <= t) glScissor(0, 0, 0, 0);
        else glScissor(l, H - bb, rr - l, bb - t);
    }

    void noScissor() { glDisable(GL_SCISSOR_TEST); }

    void setView(float ox, float oy, float z) { this.ox = ox; this.oy = oy; this.z = z; }

    // ---- drawing -------------------------------------------------------------------------

    void clear(int deskColor) {
        glClearColor(((deskColor >> 16) & 255) / 255f, ((deskColor >> 8) & 255) / 255f, (deskColor & 255) / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    // paper palette (paper, line, grid, dot) as rgb triplets
    private final float[] pal = new float[12];
    private float shadow = 0.1f;
    boolean darkPaper;

    void setPaper(int[] argb, float shadowAlpha, boolean dark) {
        for (int i = 0; i < 4; i++) {
            pal[3 * i] = ((argb[i] >> 16) & 255) / 255f;
            pal[3 * i + 1] = ((argb[i] >> 8) & 255) / 255f;
            pal[3 * i + 2] = (argb[i] & 255) / 255f;
        }
        shadow = shadowAlpha;
        darkPaper = dark;
    }

    void page(float top, float h, int bg) { page(0, top, Notebook.PW, h, 0, top, bg, false, false); }

    /**
     * A sheet at doc rect (x, y, w, h); the pattern is anchored at (ax, ay). flat = square corners and no shadow
     * (continuous pages filling the screen); sep = hairline along the top edge (boundary with the previous page).
     */
    void page(float x, float y, float w, float h, float ax, float ay, int bg, boolean flat, boolean sep) {
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(pageProg);
        glUniform3f(pView, ox, oy, z);
        glUniform2f(pSize, W, H);
        glUniform1f(pShadow, flat ? 0f : shadow);
        glUniform3fv(pPal, 4, pal, 0);
        glUniform4f(pPage, x, y, w, h);
        glUniform2f(pOrigin, ax, ay);
        glUniform2f(pStyle, flat ? 0f : 5f, sep ? 1f : 0f);
        glUniform4f(pLook, lookK, lookS, lookColor != 0 ? 1f : 0f, lookMargins);
        glUniform3f(pLine, ((lookColor >> 16) & 255) / 255f, ((lookColor >> 8) & 255) / 255f, (lookColor & 255) / 255f);
        if (darkPaper) glUniform3f(pMarg, 0.62f, 0.33f, 0.36f); else glUniform3f(pMarg, 0.90f, 0.47f, 0.47f);
        glUniform1i(pBg, bg);
        bindQuad();
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // selection transform: p' = (p - pivot) * s + pivot + t
    private float xpx, xpy, xs = 1, xtx, xty;

    void setXform(float px, float py, float s, float tx, float ty) { xpx = px; xpy = py; xs = s; xtx = tx; xty = ty; }

    void resetXform() { xpx = xpy = xtx = xty = 0; xs = 1; }

    /** Textured quad (premultiplied) covering doc rect x,y,w,h, honouring the selection transform. */
    void tex(int texId, float x, float y, float w, float h, float alpha) {
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(texProg);
        glUniform3f(tView, ox, oy, z);
        glUniform2f(tSize, W, H);
        glUniform4f(tRect, x, y, w, h);
        glUniform4f(tXf, xpx, xpy, xs, alpha);
        glUniform2f(tXt, xtx, xty);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);
        glUniform1i(tTex, 0);
        bindQuad();
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    int upload(android.graphics.Bitmap b) {
        int[] t = new int[1];
        glGenTextures(1, t, 0);
        glBindTexture(GL_TEXTURE_2D, t[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GL_TEXTURE_2D, 0, b, 0);
        glGenerateMipmap(GL_TEXTURE_2D);
        return t[0];
    }

    void deleteTex(int id) { glDeleteTextures(1, new int[]{id}, 0); }

    void blit() {
        glDisable(GL_BLEND);
        glUseProgram(blitProg);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glUniform1i(bTex, 0);
        bindQuad();
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    /** mode 0 = ink (premultiplied over), 1 = highlighter (min/max blend: never darkens itself). */
    void drawSegs(SegBuf b, int first, int n, int mode) {
        if (n <= 0) return;
        b.sync();
        glEnable(GL_BLEND);
        if (mode == 1) {
            glBlendEquation(darkPaper ? GL_MAX : GL_MIN);
        } else {
            glBlendEquation(GL_FUNC_ADD);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }
        glUseProgram(segProg);
        glUniform3f(sView, ox, oy, z);
        glUniform2f(sSize, W, H);
        glUniform1i(sMode, mode == 1 ? (darkPaper ? 2 : 1) : (darkPaper ? 3 : 0));
        glUniform4f(sXf, xpx, xpy, xs, 0);
        glUniform2f(sXt, xtx, xty);
        glUniform2f(sClip, clipX0, clipX1);
        bindQuad();
        glBindBuffer(GL_ARRAY_BUFFER, b.vbo);
        int off = first * SegBuf.STRIDE;
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, SegBuf.STRIDE, off);
        glVertexAttribDivisor(1, 1);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, SegBuf.STRIDE, off + 16);
        glVertexAttribDivisor(2, 1);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, SegBuf.STRIDE, off + 24);
        glVertexAttribDivisor(3, 1);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n);
        for (int i = 1; i <= 3; i++) {
            glVertexAttribDivisor(i, 0);
            glDisableVertexAttribArray(i);
        }
        glBlendEquation(GL_FUNC_ADD);
    }

    private void bindQuad() {
        glBindBuffer(GL_ARRAY_BUFFER, quad);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0);
        glVertexAttribDivisor(0, 0);
    }

    // ---- shaders -------------------------------------------------------------------------

    private static final String SEG_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 aCorner;\n"
            + "layout(location=1) in vec4 aSeg;\n"
            + "layout(location=2) in vec2 aRad;\n"
            + "layout(location=3) in vec4 aColor;\n"
            + "uniform vec3 uView; uniform vec2 uSize; uniform vec4 uXf; uniform vec2 uXt;\n"
            + "out vec2 vP; flat out vec4 vSeg; flat out vec2 vRad; flat out vec4 vColor;\n"
            + "vec2 xf(vec2 p){ return (p-uXf.xy)*uXf.z+uXf.xy+uXt; }\n"
            + "void main(){\n"
            + "  if(aColor==vec4(0.0)){ gl_Position=vec4(2.0,2.0,2.0,1.0); vP=vec2(0.0); vSeg=vec4(0.0); vRad=vec2(0.0); vColor=vec4(0.0); return; }\n" // SegBuf.HIDDEN
            + "  vec2 a=(xf(aSeg.xy)-uView.xy)*uView.z; vec2 b=(xf(aSeg.zw)-uView.xy)*uView.z;\n"
            + "  bool bx=aRad.y<0.0;\n" // chisel highlighter: a box (half sizes) swept along the segment
            + "  vec2 r=max(abs(aRad)*uXf.z*uView.z, vec2(0.6)); float R=(bx? length(r) : max(r.x,r.y))+1.5;\n"
            + "  vec2 d=b-a; float L=length(d); vec2 dir=L>1e-4? d/L : vec2(1.0,0.0);\n"
            + "  vec2 n=vec2(-dir.y,dir.x);\n"
            + "  vec2 p=(a+b)*0.5 + dir*(aCorner.x*(L*0.5+R)) + n*(aCorner.y*R);\n"
            + "  vP=p; vSeg=vec4(a,b); vRad=bx? vec2(r.x,-r.y) : r; vColor=aColor;\n"
            + "  gl_Position=vec4(p.x/uSize.x*2.0-1.0, 1.0-p.y/uSize.y*2.0, 0.0, 1.0);\n"
            + "}\n";

    // modes: 0 ink, 1 highlighter (MIN), 2 highlighter on dark paper (MAX), 3 ink on dark paper
    private static final String SEG_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vP; flat in vec4 vSeg; flat in vec2 vRad; flat in vec4 vColor;\n"
            + "uniform int uMode; uniform vec3 uView; uniform vec2 uClip; out vec4 o;\n"
            + "void main(){\n"
            + "  float wx=vP.x/uView.z+uView.x; if(wx<uClip.x||wx>uClip.y) discard;\n" // ink stays on the page
            + "  vec2 a=vSeg.xy; vec2 ba=vSeg.zw-a; vec2 pa=vP-a;\n"
            + "  float h=clamp(dot(pa,ba)/max(dot(ba,ba),1e-6),0.0,1.0);\n"
            + "  float d;\n"
            + "  if(vRad.y<0.0){\n" // swept box = box at a ∪ box at b ∪ the parallelogram between them
            + "    vec2 hb=vec2(vRad.x,-vRad.y);\n"
            + "    vec2 q=abs(pa)-hb; d=length(max(q,0.0))+min(max(q.x,q.y),0.0);\n"
            + "    q=abs(vP-vSeg.zw)-hb; d=min(d, length(max(q,0.0))+min(max(q.x,q.y),0.0));\n"
            + "    float L=length(ba);\n"
            + "    if(L>1e-3){\n"
            + "      vec2 n=vec2(-ba.y,ba.x)/L; vec2 cm=vec2(n.x>=0.0?hb.x:-hb.x, n.y>=0.0?hb.y:-hb.y); vec2 D=2.0*cm;\n"
            + "      vec2 rel=pa+cm; float det=ba.x*D.y-ba.y*D.x;\n"
            + "      if(abs(det)>1e-4){\n"
            + "        float s=(rel.x*D.y-rel.y*D.x)/det, t=(ba.x*rel.y-ba.y*rel.x)/det;\n"
            + "        float hs=abs(det)/length(D), ht=abs(det)/L;\n"
            + "        d=min(d, max(max(-s,s-1.0)*hs, max(-t,t-1.0)*ht));\n"
            + "      }\n"
            + "    }\n"
            + "  } else d=length(pa-ba*h)-mix(vRad.x,vRad.y,h);\n"
            + "  float cov=clamp(0.5-d,0.0,1.0);\n"
            + "  if(cov<=0.0) discard;\n"
            + "  vec3 c=vColor.rgb;\n"
            + "  if(uMode==1){ o=vec4(mix(vec3(1.0),c,cov),1.0); return; }\n"
            + "  if(uMode==2){ o=vec4(c*0.6*cov,0.0); return; }\n"
            + "  if(uMode==3 && dot(c,vec3(0.2126,0.7152,0.0722))<0.22) c=vec3(0.91,0.918,0.929);\n"
            + "  o=vec4(c*vColor.a*cov, vColor.a*cov);\n"
            + "}\n";

    private static final String PAGE_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 aCorner;\n"
            + "uniform vec3 uView; uniform vec2 uSize; uniform vec4 uPage;\n"
            + "out vec2 vDoc;\n"
            + "void main(){\n"
            + "  vec2 hs=uPage.zw*0.5+vec2(48.0); vec2 c=uPage.xy+uPage.zw*0.5;\n"
            + "  vec2 d=c+aCorner*hs; vDoc=d; vec2 p=(d-uView.xy)*uView.z;\n"
            + "  gl_Position=vec4(p.x/uSize.x*2.0-1.0, 1.0-p.y/uSize.y*2.0, 0.0, 1.0);\n"
            + "}\n";

    // bg: 0 blank 1 lined 2 grid 3 dots 4 narrow 5 cornell 6 music. uPal: paper, line, grid, dot
    // uLook: x spacing (×template default), y line strength, z 1 = use uLine for all lines, w margin bits
    private static final String PAGE_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vDoc; uniform vec3 uView; uniform vec4 uPage; uniform vec2 uOrigin; uniform vec2 uStyle; uniform int uBg; uniform float uShadow; uniform vec3 uPal[4];\n"
            + "uniform vec4 uLook; uniform vec3 uLine; uniform vec3 uMarg;\n"
            + "out vec4 o;\n"
            + "float box(vec2 p, vec2 c, vec2 hs, float r){ vec2 q=abs(p-c)-hs+vec2(r);"
            + " return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r; }\n"
            + "float ln(float d, float w, float px){ return clamp((0.5*w-d)/px+0.5,0.0,1.0); }\n"
            + "float rows(float y, float sp, float w, float px){ return ln(abs(mod(y+sp*0.5,sp)-sp*0.5),w,px); }\n"
            + "void main(){\n"
            + "  float px=1.0/uView.z;\n"
            + "  vec2 c=uPage.xy+uPage.zw*0.5; vec2 hs=uPage.zw*0.5;\n"
            + "  float d=box(vDoc,c,hs,uStyle.x);\n"
            + "  float inside=clamp(0.5-d/px,0.0,1.0);\n"
            + "  float ds=box(vDoc,c+vec2(0.0,5.0),hs,uStyle.x);\n"
            + "  float sh=uShadow*exp(-max(ds,0.0)/11.0);\n"
            + "  vec2 lp=vDoc-uOrigin; float H=uPage.w+uPage.y-uOrigin.y; vec3 col=uPal[0];\n"
            + "  float k=uLook.x; float S=uLook.y; float sa=min(S,1.0); float bold=max(1.0,S);\n"   // strength: fainter below 1, thicker above
            + "  float w=max(1.0,px)*bold; float W2=max(2.0,px)*bold;\n"
            + "  vec3 L1=uLook.z>0.5?uLine:uPal[1]; vec3 L2=uLook.z>0.5?uLine:uPal[2]; vec3 L3=uLook.z>0.5?uLine:uPal[3];\n"
            + "  if(uBg==1||uBg==4||uBg==5){ float sp=(uBg==4?30.0:40.0)*k; float y=lp.y-120.0;\n"
            + "    float a=(y>-sp*0.5 && lp.y<H-40.0)? rows(y,sp,w,px):0.0;\n"
            + "    a*=clamp(sp/(px*5.0),0.0,1.0); col=mix(col,L1,a*sa);\n"
            + "    if(uBg==5){ float b=max(lp.y<H-260.0? ln(abs(lp.x-250.0),W2,px):0.0, ln(abs(lp.y-(H-260.0)),W2,px));\n"
            + "      col=mix(col,L1,b*sa); } }\n"
            + "  else if(uBg==2){ float sp=25.0*k; vec2 g=abs(mod(lp+sp*0.5,sp)-sp*0.5);\n"
            + "    float a=max(ln(g.x,w*0.9,px),ln(g.y,w*0.9,px))*clamp(sp/(px*5.0),0.0,1.0);\n"
            + "    col=mix(col,L2,a*sa); }\n"
            + "  else if(uBg==3){ float sp=25.0*k; vec2 g=mod(lp+sp*0.5,sp)-sp*0.5;\n"
            + "    float a=clamp((max(1.5,px*0.7)*bold-length(g))/px+0.5,0.0,1.0)*clamp(sp/(px*5.0),0.0,1.0);\n"
            + "    col=mix(col,L3,a*sa); }\n"
            + "  else if(uBg==7){ float sp=23.81*k; vec2 g=abs(mod(lp+sp*0.5,sp)-sp*0.5);\n" // engineering: 5 mm, bold every 5th
            + "    float a=max(ln(g.x,w*0.8,px),ln(g.y,w*0.8,px))*clamp(sp/(px*5.0),0.0,1.0);\n"
            + "    vec2 G=abs(mod(lp+sp*2.5,sp*5.0)-sp*2.5); float b=max(ln(G.x,max(1.8,px)*bold,px),ln(G.y,max(1.8,px)*bold,px));\n"
            + "    col=mix(col,L2,a*sa); col=mix(col,L1,b*sa); }\n"
            + "  else if(uBg==8){ float hh=26.0*k; vec2 n2=vec2(0.5,0.8660254); vec2 n3=vec2(-0.5,0.8660254);\n" // isometric
            + "    float d1=abs(mod(lp.x+hh*0.5,hh)-hh*0.5); float d2=abs(mod(dot(lp,n2)+hh*0.5,hh)-hh*0.5); float d3=abs(mod(dot(lp,n3)+hh*0.5,hh)-hh*0.5);\n"
            + "    float a=max(ln(d1,w*0.8,px),max(ln(d2,w*0.8,px),ln(d3,w*0.8,px)))*clamp(hh/(px*5.0),0.0,1.0); col=mix(col,L2,a*sa); }\n"
            + "  else if(uBg==9){ float a=0.0; bool inX=lp.x>40.0&&lp.x<960.0;\n" // site inspection
            + "    if(inX&&lp.y>38.0&&lp.y<302.0){ a=max(a,ln(abs(lp.y-40.0),W2,px)); a=max(a,ln(abs(lp.y-300.0),W2,px));\n"
            + "      a=max(a,ln(abs(lp.y-100.0),w,px)); a=max(a,ln(abs(lp.y-200.0),w,px)); }\n"
            + "    if(lp.y>40.0&&lp.y<300.0){ a=max(a,ln(abs(lp.x-40.0),W2,px)); a=max(a,ln(abs(lp.x-960.0),W2,px)); }\n"
            + "    if(lp.y>100.0&&lp.y<300.0){ a=max(a,ln(abs(lp.x-347.0),w,px)); a=max(a,ln(abs(lp.x-653.0),w,px)); }\n"
            + "    float sp=40.0*k; if(inX&&lp.y>380.0+sp*0.25&&lp.y<H-300.0) a=max(a,rows(lp.y-380.0,sp,w,px)*clamp(sp/(px*5.0),0.0,1.0));\n"
            + "    float f0=H-260.0; float f1=H-40.0;\n"
            + "    if(inX&&lp.y>f0-2.0&&lp.y<f1+2.0){ a=max(a,ln(abs(lp.y-f0),W2,px)); a=max(a,ln(abs(lp.y-f1),W2,px)); }\n"
            + "    if(lp.y>f0&&lp.y<f1){ a=max(a,ln(abs(lp.x-40.0),W2,px)); a=max(a,ln(abs(lp.x-960.0),W2,px)); a=max(a,ln(abs(lp.x-653.0),w,px)); }\n"
            + "    col=mix(col,L1,a*sa); }\n"
            + "  else if(uBg==6){ float y=lp.y-120.0; float st=10.0*k; float m=mod(y,110.0*k);\n"
            + "    float a=(y>-2.0 && lp.y<H-60.0 && m<st*4.5 && lp.x>60.0 && lp.x<uPage.z-60.0)? rows(m,st,w,px):0.0;\n"
            + "    col=mix(col,L1,a*sa); }\n"
            + "  int mg=int(uLook.w+0.5); float mw=max(1.6,px)*bold;\n"                         // margins
            + "  if((mg&1)!=0) col=mix(col,uMarg,ln(abs(lp.x-125.0),mw,px)*sa);\n"                 // left margin (red rule)
            + "  if((mg&2)!=0) col=mix(col,L1,ln(abs(lp.y-90.0),W2,px)*sa);\n"                     // header line
            + "  if((mg&4)!=0){ float bx=abs(lp.x-uPage.z*0.5)-(uPage.z*0.5-30.0); float by=abs(lp.y-H*0.5)-(H*0.5-30.0);\n"
            + "    col=mix(col,L1,ln(abs(max(bx,by)),W2,px)*sa); }\n"                              // border
            + "  if(uStyle.y>0.5){ float t=vDoc.y-uPage.y; col=mix(col,col*0.86,clamp((max(1.0,px)-t)/px,0.0,1.0)); }\n" // page boundary
            + "  o=vec4(col*inside, inside+sh*(1.0-inside));\n"
            + "}\n";

    private static final String TEX_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 aCorner;\n"
            + "uniform vec3 uView; uniform vec2 uSize; uniform vec4 uRect; uniform vec4 uXf; uniform vec2 uXt;\n"
            + "out vec2 vUv;\n"
            + "void main(){\n"
            + "  vUv=aCorner*0.5+0.5; vec2 d=uRect.xy+vUv*uRect.zw;\n"
            + "  d=(d-uXf.xy)*uXf.z+uXf.xy+uXt; vec2 p=(d-uView.xy)*uView.z;\n"
            + "  gl_Position=vec4(p.x/uSize.x*2.0-1.0, 1.0-p.y/uSize.y*2.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String TEX_FS = "#version 300 es\n"
            + "precision highp float; uniform sampler2D uTex; uniform vec4 uXf; in vec2 vUv; out vec4 o;\n"
            + "void main(){ o=texture(uTex, vUv)*uXf.w; }\n";

    private static final String BLIT_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 aCorner; void main(){ gl_Position=vec4(aCorner,0.0,1.0); }\n";

    private static final String BLIT_FS = "#version 300 es\n"
            + "precision mediump float; uniform sampler2D uTex; out vec4 o;\n"
            + "void main(){ o=texelFetch(uTex, ivec2(gl_FragCoord.xy), 0); }\n";

    private void buildPrograms() {
        segProg = program(SEG_VS, SEG_FS);
        sView = glGetUniformLocation(segProg, "uView");
        sSize = glGetUniformLocation(segProg, "uSize");
        sMode = glGetUniformLocation(segProg, "uMode");
        sXf = glGetUniformLocation(segProg, "uXf");
        sXt = glGetUniformLocation(segProg, "uXt");
        sClip = glGetUniformLocation(segProg, "uClip");
        texProg = program(TEX_VS, TEX_FS);
        tView = glGetUniformLocation(texProg, "uView");
        tSize = glGetUniformLocation(texProg, "uSize");
        tRect = glGetUniformLocation(texProg, "uRect");
        tXf = glGetUniformLocation(texProg, "uXf");
        tXt = glGetUniformLocation(texProg, "uXt");
        tTex = glGetUniformLocation(texProg, "uTex");
        pageProg = program(PAGE_VS, PAGE_FS);
        pView = glGetUniformLocation(pageProg, "uView");
        pSize = glGetUniformLocation(pageProg, "uSize");
        pPage = glGetUniformLocation(pageProg, "uPage");
        pBg = glGetUniformLocation(pageProg, "uBg");
        pShadow = glGetUniformLocation(pageProg, "uShadow");
        pPal = glGetUniformLocation(pageProg, "uPal");
        pOrigin = glGetUniformLocation(pageProg, "uOrigin");
        pStyle = glGetUniformLocation(pageProg, "uStyle");
        pLook = glGetUniformLocation(pageProg, "uLook");
        pLine = glGetUniformLocation(pageProg, "uLine");
        pMarg = glGetUniformLocation(pageProg, "uMarg");
        blitProg = program(BLIT_VS, BLIT_FS);
        bTex = glGetUniformLocation(blitProg, "uTex");
        int[] b = new int[1];
        glGenBuffers(1, b, 0);
        quad = b[0];
        FloatBuffer q = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        q.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        glBindBuffer(GL_ARRAY_BUFFER, quad);
        glBufferData(GL_ARRAY_BUFFER, 32, q, GL_STATIC_DRAW);
    }

    private static int program(String vs, String fs) {
        int p = glCreateProgram();
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs));
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs));
        glLinkProgram(p);
        int[] ok = new int[1];
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        int[] ok = new int[1];
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + glGetShaderInfoLog(s));
        return s;
    }
}
