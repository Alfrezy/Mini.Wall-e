package com.walle.Controler;

import com.walle.robot.RobotService;
import com.walle.robot.RobotService.Modo;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RobotController.java
 *
 * Capa REST del backend. Solo recibe las peticiones y se las pasa a RobotService,
 * que es el que decide y habla con la ESP32. Se mantienen las mismas rutas de antes
 * y se agregan /modo, /detener y /reiniciar.
 */
@RestController
@RequestMapping("/api/robot")
@CrossOrigin(origins = "*")
public class RobotController {

    private static final String DATASET_DIR = "dataset_fotos";
    private static final String ESP32_CAM_CAPTURE_URL = "http://192.168.137.201/capture";

    private final RobotService robot;

    public RobotController(RobotService robot) {
        this.robot = robot;
    }

    // =========================================================================
    // 1. FOTO DE LA ESP32-CAM (la manda sola cada ~1.5 s)
    // =========================================================================
    @PostMapping("/reconocer")
    public ResponseEntity<String> reconocer(HttpEntity<byte[]> requestEntity) {
        try {
            if (!robot.modeloDisponible()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("Error: El modelo de IA no está cargado.");
            }
            byte[] imagen = requestEntity.getBody();
            if (imagen == null || imagen.length == 0) {
                return ResponseEntity.badRequest().body("Error: Imagen vacía.");
            }
            String respuesta = robot.procesarFoto(imagen);
            System.out.println("📸 [CAM] " + respuesta);
            return ResponseEntity.ok(respuesta);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error en inferencia: " + e.getMessage());
        }
    }

    // =========================================================================
    // 2. MODO MANUAL / AUTÓNOMO
    // =========================================================================
    @GetMapping("/modo")
    public ResponseEntity<Map<String, Object>> obtenerModo() {
        Map<String, Object> r = new HashMap<>();
        r.put("modo", robot.getModo().name());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/modo")
    public ResponseEntity<Map<String, Object>> cambiarModo(@RequestParam("valor") String valor) {
        Map<String, Object> r = new HashMap<>();
        try {
            robot.setModo(Modo.valueOf(valor.trim().toUpperCase()));
            r.put("modo", robot.getModo().name());
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException e) {
            r.put("error", "Valor inválido. Usa MANUAL o AUTONOMO.");
            return ResponseEntity.badRequest().body(r);
        }
    }

    /** Botón de emergencia: pasa a MANUAL y frena los motores. */
    @PostMapping("/detener")
    public ResponseEntity<Map<String, Object>> detener() {
        robot.detener();
        Map<String, Object> r = new HashMap<>();
        r.put("modo", robot.getModo().name());
        r.put("mensaje", "Robot detenido. Modo MANUAL.");
        return ResponseEntity.ok(r);
    }

    /** Contadores en 0 y estado PATRULLANDO (útil si se quedó en IR_A_SEMBRAR). */
    @PostMapping("/reiniciar")
    public ResponseEntity<Map<String, Object>> reiniciar() {
        robot.reiniciar();
        return ResponseEntity.ok(robot.metricas());
    }

    // =========================================================================
    // 3. MOVIMIENTO Y SERVOS (ahora sí llegan a la ESP32)
    // =========================================================================
    @PostMapping("/mover")
    public ResponseEntity<String> mover(@RequestParam String direccion,
                                        @RequestParam(defaultValue = "150") int pwm) {
        boolean ok = robot.moverManual(direccion, pwm);
        return ok ? ResponseEntity.ok("Comando '" + direccion + "' enviado.")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("La ESP32 no está conectada.");
    }

    @PostMapping("/servo")
    public ResponseEntity<String> moverServo(@RequestParam("parte") String parte,
                                             @RequestParam("angulo") int angulo) {
        boolean ok = robot.servoManual(parte, angulo);
        return ok ? ResponseEntity.ok("Servo '" + parte + "' a " + angulo + "°")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("No se pudo mover el servo (¿ESP32 conectada?).");
    }

    // =========================================================================
    // 4. SECUENCIAS COMPLETAS
    // =========================================================================
    @PostMapping("/recolectar-basura")
    public ResponseEntity<Map<String, Object>> recolectarBasura() {
        try {
            return ResponseEntity.ok(robot.recolectarManual());
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Suelta la basura que lleva el robot (mientras no exista el detector de ArUco). */
    @PostMapping("/soltar-basura")
    public ResponseEntity<Map<String, Object>> soltarBasura() {
        try {
            return ResponseEntity.ok(robot.soltarBasuraManual());
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/sembrar-planta")
    public ResponseEntity<Map<String, Object>> sembrarPlanta() {
        try {
            return ResponseEntity.ok(robot.sembrarManual());
        } catch (Exception e) {
            return error(e);
        }
    }

    private ResponseEntity<Map<String, Object>> error(Exception e) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "ERROR");
        r.put("mensaje", String.valueOf(e.getMessage()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(r);
    }

    // =========================================================================
    // 5. FOTOS PARA EL DATASET
    // =========================================================================
    @PostMapping("/capturar-foto")
    public ResponseEntity<String> capturarFoto(
            @RequestParam(value = "etiqueta", defaultValue = "basura") String etiqueta) {
        try {
            Path carpeta = Paths.get(DATASET_DIR, etiqueta);
            Files.createDirectories(carpeta);

            // Primero usa la última foto que mandó la cámara; si no hay, intenta pedirla a /capture
            byte[] imagen = robot.getUltimaFoto();
            if (imagen == null || imagen.length == 0) {
                imagen = obtenerFotoDesdeESP32(ESP32_CAM_CAPTURE_URL);
            }
            if (imagen == null || imagen.length == 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Todavía no hay fotos de la ESP32-CAM.");
            }

            String nombre = "foto_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
                    + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
            Files.write(carpeta.resolve(nombre), imagen);
            return ResponseEntity.ok("📸 Foto guardada en: " + etiqueta + "/" + nombre);
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
            System.err.println("❌ Error ESP32-CAM: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // 6. MÉTRICAS Y ESTADO (las usa el dashboard)
    // =========================================================================
    @GetMapping("/metricas")
    public ResponseEntity<Map<String, Object>> metricas() {
        return ResponseEntity.ok(robot.metricas());
    }

    // =========================================================================
    // 7. MÁQUINA DE ESTADOS (ArUco)
    // =========================================================================
    @PostMapping("/cruce-aruco")
    public ResponseEntity<Map<String, String>> decidirEnCruce(@RequestParam("idMarcador") int idMarcador) {
        return ResponseEntity.ok(robot.decidirEnCruce(idMarcador));
    }
}
