package com.walle.Controler;

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
 * Cerebro central del backend en Spring Boot para el Mini Wall-E.
 * Coordina el movimiento, los servos, la captura de dataset desde la ESP32-CAM,
 * las métricas en tiempo real para el panel web y la navegación autónoma ArUco a velocidad segura.
 */
@RestController
@RequestMapping("/api/robot")
@CrossOrigin(origins = "*") // Permite la comunicación libre con la interfaz web y los ESP32
public class RobotController {

    // --- CONSTANTES DE CONFIGURACIÓN ---
    private static final String DATASET_DIR = "dataset_fotos";
    private static final String ESP32_CAM_CAPTURE_URL = "http://192.168.137.201/capture"; // IP fija de la ESP32-CAM

    // --- MÁQUINA DE ESTADOS Y CONTADORES INTERNOS ---
    private int basuraRecolectada = 0;
    private int plantasSembradas = 0;
    private String estadoActual = "PATRULLANDO"; // Estados: PATRULLANDO, IR_A_SEMBRAR, etc.

    // =========================================================================
    // 1. ENDPOINT DE MOVIMIENTO (ORUGAS / MOTORES)
    // =========================================================================
    @PostMapping("/mover")
    public ResponseEntity<String> mover(@RequestParam String direccion) {
        System.out.println("🤖 [MOVIMIENTO] Comando recibido desde la interfaz: " + direccion);
        // Aquí puedes reenviar la instrucción por red al ESP32 principal de motores si lo deseas
        return ResponseEntity.ok("Comando de movimiento '" + direccion + "' procesado.");
    }

    // =========================================================================
    // 2. ENDPOINT DE CONTROL DE SERVOMOTORES (BRAZOS, CÁMARA, COMPUERTAS)
    // =========================================================================
    @PostMapping("/servo")
    public ResponseEntity<String> moverServo(
            @RequestParam("parte") String parte, 
            @RequestParam("angulo") int angulo) {
        System.out.println("🦾 [SERVO] Parte: " + parte + " | Ángulo: " + angulo + "°");
        return ResponseEntity.ok("Servo '" + parte + "' ajustado a " + angulo + "°");
    }

    // =========================================================================
    // 3. ENDPOINT DE CAPTURA DE FOTOS PARA EL DATASET DE IA
    // =========================================================================
    @PostMapping("/capturar-foto")
    public ResponseEntity<String> capturarFoto(
            @RequestParam(value = "etiqueta", defaultValue = "basura") String etiqueta) {
        try {
            // Crea la carpeta física de la categoría si no existe (ej: dataset_fotos/basura)
            Path rutaCarpeta = Paths.get(DATASET_DIR, etiqueta);
            if (!Files.exists(rutaCarpeta)) {
                Files.createDirectories(rutaCarpeta);
            }

            // Descarga la imagen JPG directamente desde la ESP32-CAM
            byte[] imagenBytes = obtenerFotoDesdeESP32(ESP32_CAM_CAPTURE_URL);

            if (imagenBytes == null || imagenBytes.length == 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("Error: No se pudo conectar a la ESP32-CAM en " + ESP32_CAM_CAPTURE_URL);
            }

            // Genera un nombre único con fecha, hora y UUID corto
            String nombreArchivo = "foto_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
            Path rutaArchivo = rutaCarpeta.resolve(nombreArchivo);
            Files.write(rutaArchivo, imagenBytes);

            // Regla de negocio: Al acumular 3 basuras, cambia el objetivo del robot
            if ("basura".equalsIgnoreCase(etiqueta)) {
                basuraRecolectada++;
                if (basuraRecolectada >= 3) {
                    estadoActual = "IR_A_SEMBRAR";
                    System.out.println("⚠️ Límite de 3 basuras alcanzado. Estado cambiado a: IR_A_SEMBRAR");
                }
            }

            return ResponseEntity.ok("📸 Foto guardada exitosamente en: " + DATASET_DIR + "/" + etiqueta + "/" + nombreArchivo);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error al guardar la foto: " + e.getMessage());
        }
    }

    // Método auxiliar para descargar los bytes de la foto desde la ESP32-CAM
    private byte[] obtenerFotoDesdeESP32(String urlFoto) {
        try {
            URLConnection conn = URI.create(urlFoto).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            System.err.println("❌ Error al conectar con la ESP32-CAM: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // 4. ENDPOINT DE MÉTRICAS EN TIEMPO REAL (PARA EL PANEL WEB)
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
    // 5. MÁQUINA DE ESTADOS INTELIGENTE (NAVEGACIÓN AUTÓNOMA ArUco A BAJA VELOCIDAD)
    // =========================================================================
    @PostMapping("/cruce-aruco")
    public ResponseEntity<Map<String, String>> decidirEnCruce(@RequestParam("idMarcador") int idMarcador) {
        Map<String, String> decision = new HashMap<>();
        
        // PWM reducido (~50% de velocidad) para modo autónomo: más tiempo de reacción y ahorro de batería
        String pwmAutonomo = "130"; 

        System.out.println("🧭 [AUTÓNOMO] ArUco ID detectado: " + idMarcador + " | Estado actual: " + estadoActual);

        switch (idMarcador) {
            case 10: // Cruce Principal (Decide ruta según memoria de Java)
                if ("IR_A_SEMBRAR".equals(estadoActual)) {
                    decision.put("accion", "GIRAR_IZQUIERDA");
                    decision.put("destino", "Zona de Plantas");
                } else {
                    decision.put("accion", "GIRAR_DERECHA");
                    decision.put("destino", "Depósito de Basura");
                }
                decision.put("pwm", pwmAutonomo);
                break;
                
            case 1: // Depósito de Basura (Fondo izquierdo)
                decision.put("accion", "DEPOSITAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                basuraRecolectada = 0;          // Reinicia contadores
                estadoActual = "PATRULLANDO";   // Retorna a patrulla
                System.out.println("🗑️ Depósito de basura alcanzado. Ciclo reiniciado a PATRULLANDO.");
                break;
                
            case 2: // Depósito de Plantas (Fondo derecho)
                decision.put("accion", "SEMBRAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                plantasSembradas++;
                estadoActual = "PATRULLANDO";   // Retorna a patrulla
                System.out.println("🌱 Zona de plantas alcanzada. Planta registrada y volviendo a patrullar.");
                break;
                
            case 5: // Tope / Fin de pasillo
                decision.put("accion", "GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                System.out.println("🚧 Tope detectado. Ejecutando giro de 180° a baja velocidad.");
                break;
                
            default:
                decision.put("accion", "SEGUIR_RECTO");
                decision.put("pwm", pwmAutonomo);
                break;
        }

        return ResponseEntity.ok(decision);
    }
}