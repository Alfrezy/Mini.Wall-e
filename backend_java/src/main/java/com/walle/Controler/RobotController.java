package com.walle.Controler;

import ai.onnxruntime.*;
import com.walle.model.RegistroActividad;
import com.walle.repository.RegistroActividadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * RobotController.java
 * 
 * Cerebro central del backend en Spring Boot para el Mini Wall-E.
 * Coordina motores, servos (brazos, compuertas, cámara), navegación ArUco, 
 * captura de dataset, motor de inferencia ONNX y la persistencia en PostgreSQL mediante JPA.
 */
@RestController
@RequestMapping("/api/robot")
@CrossOrigin(origins = "*")
public class RobotController {

    private static final String DATASET_DIR = "dataset_fotos";
    private static final String ESP32_CAM_CAPTURE_URL = "http://192.168.137.201/capture";

    private final String[] CLASES = {"Caja", "bota", "boteAluminio", "papelHigienico"};

    private OrtEnvironment env;
    private OrtSession session;

    private int basuraRecolectada = 0;
    private int plantasSembradas = 0;
    private String estadoActual = "PATRULLANDO";

    @Autowired
    private RegistroActividadRepository actividadRepository;

    public RobotController() {
        try {
            env = OrtEnvironment.getEnvironment();
            String modelPath = "modelos/MiniWallE.onnx"; 
            if (!Files.exists(Paths.get(modelPath))) {
                modelPath = "src/main/resources/modelos/MiniWallE.onnx";
            }
            if (Files.exists(Paths.get(modelPath))) {
                session = env.createSession(modelPath);
                System.out.println("🧠 [IA] Modelo MiniWallE.onnx cargado exitosamente en Java.");
            } else {
                System.err.println("⚠️ [IA] No se encontró el archivo MiniWallE.onnx.");
            }
        } catch (Exception e) {
            System.err.println("❌ [IA] Error al inicializar ONNX Runtime: " + e.getMessage());
        }
    }

    // =========================================================================
    // 1. ENDPOINT DE RECONOCIMIENTO EN TIEMPO REAL (IA ONNX)
    // =========================================================================
    @PostMapping("/reconocer")
    public ResponseEntity<String> reconocerObjeto(org.springframework.http.HttpEntity<byte[]> requestEntity) {
        try {
            if (session == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("Error: El modelo de IA no está cargado.");
            }

            byte[] imagenBytes = requestEntity.getBody();
            if (imagenBytes == null || imagenBytes.length == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Imagen vacía.");
            }

            float[] inputTensorValues = preprocesarImagenParaCNN(imagenBytes);

            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputTensorValues), new long[]{1, 96, 96, 1});
                 OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {

                float[][] output = (float[][]) result.get(0).getValue();
                float[] probabilidades = output[0];

                int mejorClaseIdx = 0;
                float maxProb = probabilidades[0];
                for (int i = 1; i < probabilidades.length; i++) {
                    if (probabilidades[i] > maxProb) {
                        maxProb = probabilidades[i];
                        mejorClaseIdx = i;
                    }
                }

                String claseDetectada = CLASES[mejorClaseIdx];
                float confianzaPorcentaje = maxProb * 100;

                System.out.printf("🤖 [RECONOCIMIENTO] Objeto: %s (%.1f%%)\n", claseDetectada, confianzaPorcentaje);

                // Regla de negocio: 'bota' = PLANTA (Siembra de inmediato). Las demás = BASURA (hasta 3).
                if ("bota".equalsIgnoreCase(claseDetectada)) {
                    estadoActual = "IR_A_SEMBRAR";
                    System.out.println("🌱 ¡Planta ('bota') encontrada! Estado cambiado a: IR_A_SEMBRAR");
                } else {
                    basuraRecolectada++;
                    System.out.printf("🗑️ Basura detectada (%s). Total: %d/3\n", claseDetectada, basuraRecolectada);
                    
                    if (basuraRecolectada >= 3) {
                        estadoActual = "IR_A_SEMBRAR";
                        System.out.println("⚠️ Límite de 3 basuras alcanzado. Estado cambiado a: IR_A_SEMBRAR");
                    }
                }

                // Guardar evento en PostgreSQL
                actividadRepository.save(new RegistroActividad(basuraRecolectada, plantasSembradas, "DETECCION_" + claseDetectada.toUpperCase()));

                return ResponseEntity.ok(claseDetectada);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error en inferencia: " + e.getMessage());
        }
    }

    private float[] preprocesarImagenParaCNN(byte[] imageBytes) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
        BufferedImage originalImage = ImageIO.read(bais);

        int targetSize = 96;

        BufferedImage grayImage = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = grayImage.createGraphics();
        g2d.drawImage(originalImage, 0, 0, null);
        g2d.dispose();

        int w = grayImage.getWidth();
        int h = grayImage.getHeight();
        double scale = (double) targetSize / Math.max(w, h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        BufferedImage paddedImage = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gCanvas = paddedImage.createGraphics();
        gCanvas.setColor(new Color(128, 128, 128));
        gCanvas.fillRect(0, 0, targetSize, targetSize);

        int xOffset = (targetSize - newW) / 2;
        int yOffset = (targetSize - newH) / 2;
        gCanvas.drawImage(grayImage.getScaledInstance(newW, newH, Image.SCALE_SMOOTH), xOffset, yOffset, null);
        gCanvas.dispose();

        float[] tensorValues = new float[targetSize * targetSize];
        int idx = 0;
        for (int y = 0; y < targetSize; y++) {
            for (int x = 0; x < targetSize; x++) {
                int rgb = paddedImage.getRGB(x, y);
                int grayVal = rgb & 0xFF;
                tensorValues[idx++] = (float) grayVal;
            }
        }

        return tensorValues;
    }

    // =========================================================================
    // 2. ENDPOINT DE MOVIMIENTO (MOTORES)
    // =========================================================================
    @PostMapping("/mover")
    public ResponseEntity<String> mover(@RequestParam String direccion) {
        System.out.println("🤖 [MOVIMIENTO] " + direccion);
        return ResponseEntity.ok("Comando '" + direccion + "' procesado.");
    }

    // =========================================================================
    // 3. ENDPOINT DE CONTROL DE SERVOMOTORES
    // =========================================================================
    @PostMapping("/servo")
    public ResponseEntity<String> moverServo(
            @RequestParam("parte") String parte, 
            @RequestParam("angulo") int angulo) {
        System.out.println("🦾 [SERVO] Parte: " + parte + " | Ángulo: " + angulo + "°");
        return ResponseEntity.ok("Servo '" + parte + "' a " + angulo + "°");
    }

    // =========================================================================
    // 4. SECUENCIA DE RECOLECCIÓN DE BASURA (Cámara a 45°, Levantar Manos, Compuerta, Bajar Manos)
    // =========================================================================
    @PostMapping("/recolectar-basura")
    public ResponseEntity<Map<String, Object>> ejecutarSecuenciaRecoleccion() {
        Map<String, Object> respuesta = new HashMap<>();
        try {
            System.out.println("🤖 [RECOLECCIÓN] Iniciando secuencia de recogida de basura...");

            // 1. Cámara a 45°
            System.out.println("🦾 [SERVO] Cámara a 45°...");
            Thread.sleep(400);

            // 2. LEVANTAR MANOS
            System.out.println("🙌 [SERVO] Levantando manos...");
            Thread.sleep(500);

            // 3. Abrir compuertas
            System.out.println("🔓 [SERVO] Abriendo compuertas...");
            Thread.sleep(500);

            // 4. Avanzar
            System.out.println("🚜 [MOTORES] Avanzando hacia la basura...");
            Thread.sleep(1500);

            // 5. Cerrar compuertas
            System.out.println("🔒 [SERVO] Cerrando compuertas...");
            Thread.sleep(500);

            // 6. BAJAR MANOS
            System.out.println("🙇 [SERVO] Bajando manos...");
            Thread.sleep(500);

            basuraRecolectada++;
            System.out.printf("🗑️ Basura asegurada. Total: %d/3\n", basuraRecolectada);

            if (basuraRecolectada >= 3) {
                estadoActual = "IR_A_SEMBRAR";
            }

            // Guardar en la BD
            actividadRepository.save(new RegistroActividad(basuraRecolectada, plantasSembradas, "RECOLECCION_BASURA"));

            respuesta.put("status", "SUCCESS");
            respuesta.put("basuraRecolectada", basuraRecolectada);
            respuesta.put("estadoActual", estadoActual);
            return ResponseEntity.ok(respuesta);

        } catch (Exception e) {
            e.printStackTrace();
            respuesta.put("status", "ERROR");
            respuesta.put("mensaje", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    // =========================================================================
    // 5. SECUENCIA DE SIEMBRA / DEPÓSITO DE PLANTA
    // =========================================================================
    @PostMapping("/sembrar-planta")
    public ResponseEntity<Map<String, Object>> ejecutarSecuenciaSiembra() {
        Map<String, Object> respuesta = new HashMap<>();
        try {
            System.out.println("🌱 [SIEMBRA] Iniciando secuencia de depósito de planta (bota)...");

            // 1. Cámara a 45°
            System.out.println("🦾 [SERVO] Cámara a 45°...");
            Thread.sleep(400);

            // 2. LEVANTAR MANOS
            System.out.println("🙌 [SERVO] Levantando manos...");
            Thread.sleep(500);

            // 3. Abrir compuertas
            System.out.println("🔓 [SERVO] Abriendo compuertas...");
            Thread.sleep(500);

            // 4. Maniobra de descarga
            System.out.println("🚜 [MOTORES] Dejando la planta en su lugar...");
            Thread.sleep(1200);

            // 5. Cerrar compuertas
            System.out.println("🔒 [SERVO] Cerrando compuertas...");
            Thread.sleep(500);

            // 6. BAJAR MANOS
            System.out.println("🙇 [SERVO] Bajando manos...");
            Thread.sleep(500);

            plantasSembradas++;
            estadoActual = "PATRULLANDO";
            System.out.println("✅ ¡Planta sembrada! Estado cambiado a PATRULLANDO.");

            // Guardar en la BD
            actividadRepository.save(new RegistroActividad(basuraRecolectada, plantasSembradas, "SIEMBRA_PLANTA"));

            respuesta.put("status", "SUCCESS");
            respuesta.put("plantasSembradas", plantasSembradas);
            respuesta.put("estadoActual", estadoActual);
            return ResponseEntity.ok(respuesta);

        } catch (Exception e) {
            e.printStackTrace();
            respuesta.put("status", "ERROR");
            respuesta.put("mensaje", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
        }
    }

    // =========================================================================
    // 6. CAPTURA DE FOTOS PARA DATASET
    // =========================================================================
    @PostMapping("/capturar-foto")
    public ResponseEntity<String> capturarFoto(
            @RequestParam(value = "etiqueta", defaultValue = "basura") String etiqueta) {
        try {
            Path rutaCarpeta = Paths.get(DATASET_DIR, etiqueta);
            if (!Files.exists(rutaCarpeta)) {
                Files.createDirectories(rutaCarpeta);
            }

            byte[] imagenBytes = obtenerFotoDesdeESP32(ESP32_CAM_CAPTURE_URL);
            if (imagenBytes == null || imagenBytes.length == 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Error de conexión con ESP32-CAM.");
            }

            String nombreArchivo = "foto_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
            Files.write(rutaCarpeta.resolve(nombreArchivo), imagenBytes);

            return ResponseEntity.ok("📸 Foto guardada en: " + etiqueta + "/" + nombreArchivo);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    private byte[] obtenerFotoDesdeESP32(String urlFoto) {
        try {
            URLConnection conn = URI.create(urlFoto).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            System.err.println("❌ Error ESP32: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // 7. MÉTRICAS EN TIEMPO REAL
    // =========================================================================
    @GetMapping("/metricas")
    public ResponseEntity<Map<String, Object>> obtenerMetricas() {
        Map<String, Object> metricas = new HashMap<>();
        metricas.put("basuraRecolectada", basuraRecolectada);
        metricas.put("plantasSembradas", plantasSembradas);
        metricas.put("estadoActual", estadoActual);
        return ResponseEntity.ok(metricas);
    }

    // =========================================================================
    // 8. MÁQUINA DE ESTADOS (ArUco)
    // =========================================================================
    @PostMapping("/cruce-aruco")
    public ResponseEntity<Map<String, String>> decidirEnCruce(@RequestParam("idMarcador") int idMarcador) {
        Map<String, String> decision = new HashMap<>();
        String pwmAutonomo = "130"; 

        System.out.println("🧭 [AUTÓNOMO] ArUco ID detectado: " + idMarcador + " | Estado actual: " + estadoActual);

        switch (idMarcador) {
            case 10: // Cruce Principal
                if ("IR_A_SEMBRAR".equals(estadoActual)) {
                    decision.put("accion", "GIRAR_IZQUIERDA");
                    decision.put("destino", "Zona de Plantas");
                } else {
                    decision.put("accion", "GIRAR_DERECHA");
                    decision.put("destino", "Depósito de Basura");
                }
                decision.put("pwm", pwmAutonomo);
                break;
                
            case 1: // Depósito de Basura
                decision.put("accion", "DEPOSITAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                basuraRecolectada = 0;
                estadoActual = "PATRULLANDO";
                actividadRepository.save(new RegistroActividad(basuraRecolectada, plantasSembradas, "DESCARGA_BASURA_COMPLETA"));
                break;
                
            case 2: // Depósito de Plantas
                decision.put("accion", "SEMBRAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                plantasSembradas++;
                estadoActual = "PATRULLANDO";
                actividadRepository.save(new RegistroActividad(basuraRecolectada, plantasSembradas, "SIEMBRA_COMPLETA"));
                break;
                
            case 5: // Tope
                decision.put("accion", "GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                break;
                
            default: // Avanzar recto por defecto si no hay marcador visible
                decision.put("accion", "SEGUIR_RECTO");
                decision.put("pwm", pwmAutonomo);
                break;
        }

        return ResponseEntity.ok(decision);
    }
}