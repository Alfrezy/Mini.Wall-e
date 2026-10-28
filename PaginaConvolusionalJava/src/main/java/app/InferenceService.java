package app;

import ai.onnxruntime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** YOLOv8 (detección): letterbox 640, RGB, /255, NCHW. Salida [1, 4+nc, 8400]. */
@Service
public class InferenceService {

    public record Box(String clase, double confianza, double x, double y, double w, double h) {}
    private record Cand(int cls, float score, float x1, float y1, float x2, float y2) {}

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final int size;
    private final float conf, iouThr;
    private final List<String> classes;

    public InferenceService(@Value("${app.model-path}") String path,
                            @Value("${app.img-size}") int size,
                            @Value("${app.conf}") float conf,
                            @Value("${app.iou}") float iouThr,
                            @Value("${app.classes}") List<String> classes) throws OrtException {
        this.size = size;
        this.conf = conf;
        this.iouThr = iouThr;
        this.classes = classes;
        this.session = env.createSession(path, new OrtSession.SessionOptions());
        this.inputName = session.getInputNames().iterator().next();
    }

    public List<Box> detect(byte[] bytes) throws IOException, OrtException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new IllegalArgumentException("El archivo no es una imagen válida.");
        int W = src.getWidth(), H = src.getHeight();

        // Letterbox: mantiene proporción y rellena con gris 114 (como Ultralytics)
        float r = Math.min((float) size / W, (float) size / H);
        int nw = Math.max(1, Math.round(W * r)), nh = Math.max(1, Math.round(H * r));
        int padX = (size - nw) / 2, padY = (size - nh) / 2;
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, size, size);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, padX, padY, nw, nh, null);
        g.dispose();

        int plane = size * size;
        int[] px = canvas.getRGB(0, 0, size, size, null, 0, size);
        float[] data = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            data[i] = ((px[i] >> 16) & 255) / 255f;
            data[plane + i] = ((px[i] >> 8) & 255) / 255f;
            data[2 * plane + i] = (px[i] & 255) / 255f;
        }

        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, size, size});
             OrtSession.Result res = session.run(Map.of(inputName, t))) {
            float[][] m = ((float[][][]) res.get(0).getValue())[0];
            return decode(m, r, padX, padY, W, H);
        }
    }

    private List<Box> decode(float[][] m, float r, int padX, int padY, int W, int H) {
        boolean cf = m.length < m[0].length;           // [4+nc, N] o [N, 4+nc]
        int channels = cf ? m.length : m[0].length;
        int n = cf ? m[0].length : m.length;
        int nc = channels - 4;

        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int best = 0;
            float bs = -1;
            for (int c = 0; c < nc; c++) {
                float s = at(m, cf, 4 + c, i);
                if (s > bs) { bs = s; best = c; }
            }
            if (bs < conf) continue;
            float cx = at(m, cf, 0, i), cy = at(m, cf, 1, i), w = at(m, cf, 2, i), h = at(m, cf, 3, i);
            float x1 = clamp((cx - w / 2 - padX) / r, W), y1 = clamp((cy - h / 2 - padY) / r, H);
            float x2 = clamp((cx + w / 2 - padX) / r, W), y2 = clamp((cy + h / 2 - padY) / r, H);
            if (x2 - x1 < 1 || y2 - y1 < 1) continue;
            cands.add(new Cand(best, bs, x1, y1, x2, y2));
        }

        cands.sort((a, b) -> Float.compare(b.score(), a.score()));
        List<Cand> keep = new ArrayList<>();
        for (Cand c : cands) {
            boolean ok = true;
            for (Cand k : keep) if (k.cls() == c.cls() && iou(k, c) > iouThr) { ok = false; break; }
            if (ok) keep.add(c);
            if (keep.size() >= 50) break;
        }

        List<Box> out = new ArrayList<>();
        for (Cand c : keep) {
            String name = c.cls() < classes.size() ? classes.get(c.cls()) : "clase" + c.cls();
            out.add(new Box(name, c.score(), c.x1() / W, c.y1() / H, (c.x2() - c.x1()) / W, (c.y2() - c.y1()) / H));
        }
        return out;
    }

    private static float at(float[][] m, boolean cf, int ch, int i) { return cf ? m[ch][i] : m[i][ch]; }
    private static float clamp(float v, float max) { return Math.max(0, Math.min(max, v)); }

    private static float iou(Cand a, Cand b) {
        float iw = Math.min(a.x2(), b.x2()) - Math.max(a.x1(), b.x1());
        float ih = Math.min(a.y2(), b.y2()) - Math.max(a.y1(), b.y1());
        if (iw <= 0 || ih <= 0) return 0;
        float inter = iw * ih;
        float areaA = (a.x2() - a.x1()) * (a.y2() - a.y1());
        float areaB = (b.x2() - b.x1()) * (b.y2() - b.y1());
        return inter / (areaA + areaB - inter);
    }
}
