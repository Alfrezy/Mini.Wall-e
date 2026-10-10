package com.walle.Controler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/robot")
@CrossOrigin(origins = "*")
public class RobotController {

    private static final String DATASET_DIR = "dataset_fotos";

    // --- Endpoint 1: Movimiento de orugas ---
    @PostMapping("/mover")
    public ResponseEntity<String> mover(@RequestParam String direccion) {
        System.out.println("Comando movimiento recibido: " + direccion);
        return ResponseEntity.ok("Movimiento: " + direccion);
    }

    // --- Endpoint 2: Control de Servomotores ---
    @PostMapping("/servo")
    public ResponseEntity<String> moverServo(@RequestParam String parte, @RequestParam int angulo) {
        System.out.println("Comando servo recibido - Parte: " + parte + ", Angulo: " + angulo);
        return ResponseEntity.ok("Servo " + parte + " movido a " + angulo + "°");
    }

    // --- Endpoint 3: Guardar foto recibida desde el Navegador ---
    @PostMapping("/guardar-foto")
    public ResponseEntity<String> guardarFoto(
            @RequestParam("imagen") MultipartFile imagen,
            @RequestParam(defaultValue = "basura") String etiqueta) {
        try {
            if (imagen.isEmpty()) {
                return ResponseEntity.badRequest().body("Error: La imagen recibida está vacía.");
            }

            Path rutaCarpeta = Paths.get(DATASET_DIR, etiqueta);
            if (!Files.exists(rutaCarpeta)) {
                Files.createDirectories(rutaCarpeta);
            }

            String nombreArchivo = "foto_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
            Path rutaArchivo = rutaCarpeta.resolve(nombreArchivo);

            // Guardar los bytes de la imagen enviados por el frontend
            Files.copy(imagen.getInputStream(), rutaArchivo, StandardCopyOption.REPLACE_EXISTING);

            return ResponseEntity.ok("📸 Foto guardada exitosamente en: " + DATASET_DIR + "/" + etiqueta + "/" + nombreArchivo);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error guardando la foto: " + e.getMessage());
        }
    }

    // --- Endpoint 4: Métricas periódicas ---
    @GetMapping("/metricas")
    public ResponseEntity<Map<String, Integer>> obtenerMetricas() {
        Map<String, Integer> metricas = new HashMap<>();
        metricas.put("basuraRecolectada", 12);
        metricas.put("plantasSembradas", 5);
        return ResponseEntity.ok(metricas);
    }
}