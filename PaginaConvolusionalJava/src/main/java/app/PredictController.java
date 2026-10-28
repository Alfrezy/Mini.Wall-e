package app;

import app.InferenceService.Box;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class PredictController {

    record Result(List<Box> detecciones, int total, String mensaje) {}

    private static final Map<String, String> SING = Map.of(
            "Caja", "una caja",
            "bota", "una bota",
            "boteAluminio", "un bote de aluminio",
            "papelHigienico", "un rollo de papel higiénico");
    private static final Map<String, String> PLUR = Map.of(
            "Caja", "cajas",
            "bota", "botas",
            "boteAluminio", "botes de aluminio",
            "papelHigienico", "rollos de papel higiénico");

    private final InferenceService svc;

    public PredictController(InferenceService svc) { this.svc = svc; }

    @PostMapping(value = "/predict", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result predict(@RequestParam("file") MultipartFile file) throws Exception {
        List<Box> boxes = svc.detect(file.getBytes());
        return new Result(boxes, boxes.size(), mensaje(boxes));
    }

    private static String mensaje(List<Box> boxes) {
        if (boxes.isEmpty()) return "No veo nada que pueda reconocer";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Box b : boxes) counts.merge(b.clase(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        counts.forEach((k, n) -> parts.add(n == 1 ? SING.getOrDefault(k, k) : n + " " + PLUR.getOrDefault(k, k)));
        String lista = parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.get(parts.size() - 1);
        return "Estoy viendo " + lista;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> fail(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("error", "No pude analizar la imagen: " + e.getMessage()));
    }
}
