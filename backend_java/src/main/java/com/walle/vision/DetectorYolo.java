package com.walle.vision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DetectorYolo.java
 *
 * Ejecuta el modelo YOLOv8 (CNN_Basura.onnx) dentro del backend.
 * Es la misma lógica que ya te funciona en PaginaConvolusionalJava (InferenceService):
 * letterbox 640x640, RGB, valores /255, formato NCHW, salida [1, 4+clases, 8400].
 *
 * A diferencia del clasificador anterior, YOLO dice DÓNDE está cada objeto (caja),
 * y eso es lo que permite al robot centrarse y acercarse.
 */
@Service
public class DetectorYolo {

    /** Una detección. x, y, w, h están normalizados de 0 a 1 (x, y = esquina superior izquierda). */
    public record Deteccion(String clase, double confianza, double x, double y, double w, double h) {
        public double centroX() { return x + w / 2.0; }
        public double bordeInferior() { return y + h; }
    }

    private record Cand(int cls, float score, float x1, float y1, float x2, float y2) {}

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private String inputName;
    private final int size;
    private final float conf;
    private final float iouThr;
    private final List<String> clases;

    public DetectorYolo(@Value("${walle.modelo.ruta}") String rutaConfigurada,
                        @Value("${walle.modelo.tamano}") int size,
                        @Value("${walle.modelo.confianza}") float conf,
                        @Value("${walle.modelo.iou}") float iouThr,
                        @Value("${walle.modelo.clases}") List<String> clases) {
        this.size = size;
        this.conf = conf;
        this.iouThr = iouThr;
        this.clases = clases;

        try {
            String ruta = rutaConfigurada;
            if (!Files.exists(Paths.get(ruta))) {
                ruta = "EntrenamientoNeuronal/modelos/CNN_Basura.onnx"; // por si corres el backend fuera de Docker
            }
            if (Files.exists(Paths.get(ruta))) {
                session = env.createSession(ruta, new OrtSession.SessionOptions());
                inputName = session.getInputNames().iterator().next();
                System.out.println("🧠 [IA] Modelo YOLO cargado: " + ruta + " | clases: " + clases);
            } else {
                System.err.println("⚠️ [IA] No se encontró el modelo ONNX en: " + rutaConfigurada);
            }
        } catch (Exception e) {
            System.err.println("❌ [IA] Error al cargar el modelo: " + e.getMessage());
        }
    }

    public boolean disponible() {
        return session != null;
    }

    public synchronized List<Deteccion> detectar(byte[] bytes) throws Exception {
        if (session == null) throw new IllegalStateException("El modelo de IA no está cargado.");

        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new IllegalArgumentException("Los bytes recibidos no son una imagen válida.");
        int W = src.getWidth(), H = src.getHeight();

        // Letterbox: mantiene la proporción y rellena con gris 114 (igual que Ultralytics)
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
            return decodificar(m, r, padX, padY, W, H);
        }
    }

    private List<Deteccion> decodificar(float[][] m, float r, int padX, int padY, int W, int H) {
        boolean cf = m.length < m[0].length;           // [4+nc, N] o [N, 4+nc]
        int canales = cf ? m.length : m[0].length;
        int n = cf ? m[0].length : m.length;
        int nc = canales - 4;

        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int mejor = 0;
            float mejorScore = -1;
            for (int c = 0; c < nc; c++) {
                float s = en(m, cf, 4 + c, i);
                if (s > mejorScore) { mejorScore = s; mejor = c; }
            }
            if (mejorScore < conf) continue;
            float cx = en(m, cf, 0, i), cy = en(m, cf, 1, i), w = en(m, cf, 2, i), h = en(m, cf, 3, i);
            float x1 = limitar((cx - w / 2 - padX) / r, W), y1 = limitar((cy - h / 2 - padY) / r, H);
            float x2 = limitar((cx + w / 2 - padX) / r, W), y2 = limitar((cy + h / 2 - padY) / r, H);
            if (x2 - x1 < 1 || y2 - y1 < 1) continue;
            cands.add(new Cand(mejor, mejorScore, x1, y1, x2, y2));
        }

        // Supresión de no-máximos (NMS): quita cajas repetidas del mismo objeto
        cands.sort((a, b) -> Float.compare(b.score(), a.score()));
        List<Cand> quedan = new ArrayList<>();
        for (Cand c : cands) {
            boolean ok = true;
            for (Cand k : quedan) {
                if (k.cls() == c.cls() && iou(k, c) > iouThr) { ok = false; break; }
            }
            if (ok) quedan.add(c);
            if (quedan.size() >= 50) break;
        }

        List<Deteccion> salida = new ArrayList<>();
        for (Cand c : quedan) {
            String nombre = c.cls() < clases.size() ? clases.get(c.cls()) : "clase" + c.cls();
            salida.add(new Deteccion(nombre, c.score(),
                    c.x1() / W, c.y1() / H, (c.x2() - c.x1()) / W, (c.y2() - c.y1()) / H));
        }
        return salida;
    }

    private static float en(float[][] m, boolean cf, int canal, int i) { return cf ? m[canal][i] : m[i][canal]; }

    private static float limitar(float v, float max) { return Math.max(0, Math.min(max, v)); }

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
