package com.walle.robot;

import com.walle.model.RegistroActividad;
import com.walle.repository.RegistroActividadRepository;
import com.walle.vision.DetectorYolo;
import com.walle.vision.DetectorYolo.Deteccion;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RobotService.java
 *
 * Cerebro del robot. Aquí vive todo lo que antes estaba mezclado en RobotController:
 *  - el estado (modo, estado actual, contadores),
 *  - la decisión autónoma: con cada foto de la ESP32-CAM detecta, se centra, se acerca y recoge,
 *  - las secuencias de brazos y compuertas (ahora de verdad, hablando con la ESP32),
 *  - la máquina de estados de los marcadores ArUco.
 *
 * Flujo autónomo (una vuelta por cada foto que llega):
 *   foto -> YOLO -> ¿hay basura? -> ¿centrada? -> ¿cerca? -> avanzar / girar / recoger
 *
 * Reglas (una cosa a la vez, porque el almacenamiento es pequeño):
 *  - Al empezar a ir por una basura se FIJA ese objetivo: ignora otras basuras y las plantas.
 *  - Al recogerla pasa a LLEVANDO_BASURA y no busca nada más hasta soltarla
 *    (marcador ArUco 1 o el botón/endpoint 'soltar-basura').
 */
@Service
public class RobotService {

    public enum Modo { MANUAL, AUTONOMO }

    // ---- Canales y ángulos de los servos (los mismos que usa teclado.js) ----
    private static final int BRAZO_DER = 0, BRAZO_IZQ = 1, COMPUERTA_DER = 2, COMPUERTA_IZQ = 3;
    private static final int BRAZO_DER_BAJO = 80, BRAZO_IZQ_BAJO = 20, BRAZO_REPOSO = 50;
    private static final int COMP_DER_ABIERTA = 10, COMP_DER_CERRADA = 90;
    private static final int COMP_IZQ_ABIERTA = 85, COMP_IZQ_CERRADA = 5;

    // ---- Ajustes (application.properties) ----
    @Value("${walle.modo-inicial:MANUAL}") private String modoInicial;
    @Value("${walle.autonomo.buscar:true}") private boolean buscarActivo;
    @Value("${walle.autonomo.basura-maxima:1}") private int basuraMaxima;
    @Value("${walle.autonomo.pwm-avance:150}") private int pwmAvance;
    @Value("${walle.autonomo.pwm-giro:150}") private int pwmGiro;
    @Value("${walle.autonomo.ms-avance:450}") private int msAvance;
    @Value("${walle.autonomo.ms-giro:250}") private int msGiro;
    @Value("${walle.autonomo.ms-estabilizar:700}") private int msEstabilizar;
    @Value("${walle.autonomo.ms-empujar:800}") private int msEmpujar;
    @Value("${walle.autonomo.ms-servo:1000}") private int msServo;
    @Value("${walle.autonomo.tolerancia-centro:0.12}") private double toleranciaCentro;
    @Value("${walle.autonomo.umbral-borde-inferior:0.85}") private double umbralBordeInferior;
    @Value("${walle.autonomo.umbral-alto:0.55}") private double umbralAlto;
    @Value("${walle.autonomo.invertir-giro:false}") private boolean invertirGiro;

    private final DetectorYolo detector;
    private final Esp32Cliente esp32;
    private final RegistroActividadRepository repositorio;

    // ---- Estado del robot ----
    private volatile Modo modo = Modo.MANUAL;
    private volatile String estadoActual = "PATRULLANDO";
    private volatile int basuraRecolectada = 0;
    private volatile int plantasSembradas = 0;
    private volatile String ultimaDeteccion = "ninguna";
    private volatile String ultimaAccion = "ninguna";
    private volatile byte[] ultimaFoto;
    private volatile int sinVer = 0;
    private volatile boolean siguiendoObjetivo = false;
    private volatile String objetivoClase = null;   // clase de la basura fijada como objetivo
    private volatile int perdidas = 0;              // fotos seguidas sin ver el objetivo

    /** true mientras el robot se está moviendo o ejecutando una secuencia (las fotos nuevas se ignoran). */
    private final AtomicBoolean ocupado = new AtomicBoolean(false);
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor();

    public RobotService(DetectorYolo detector, Esp32Cliente esp32, RegistroActividadRepository repositorio) {
        this.detector = detector;
        this.esp32 = esp32;
        this.repositorio = repositorio;
    }

    @PostConstruct
    public void iniciar() {
        modo = "AUTONOMO".equalsIgnoreCase(modoInicial) ? Modo.AUTONOMO : Modo.MANUAL;
        System.out.println("🤖 [ROBOT] Modo inicial: " + modo);
    }

    @PreDestroy
    public void cerrar() {
        ejecutor.shutdownNow();
    }

    // =========================================================================
    // 1. MODO MANUAL / AUTÓNOMO
    // =========================================================================
    public Modo getModo() {
        return modo;
    }

    public void setModo(Modo nuevo) {
        this.modo = nuevo;
        if (nuevo == Modo.MANUAL) {
            esp32.parar();
        }
        sinVer = 0;
        siguiendoObjetivo = false;
        objetivoClase = null;
        perdidas = 0;
        System.out.println("🤖 [ROBOT] Modo cambiado a: " + nuevo);
    }

    /** Botón de emergencia: pasa a manual y detiene los motores. */
    public void detener() {
        setModo(Modo.MANUAL);
        esp32.parar();
    }

    /** Vuelve a empezar: contadores en 0 y estado PATRULLANDO. */
    public void reiniciar() {
        basuraRecolectada = 0;
        plantasSembradas = 0;
        estadoActual = "PATRULLANDO";
        sinVer = 0;
        siguiendoObjetivo = false;
        objetivoClase = null;
        perdidas = 0;
        guardar("REINICIO_MANUAL");
    }

    // =========================================================================
    // 2. FOTO DE LA ESP32-CAM  ->  DETECCIÓN  ->  DECISIÓN
    // =========================================================================
    public String procesarFoto(byte[] imagen) throws Exception {
        ultimaFoto = imagen;

        List<Deteccion> detecciones = detector.detectar(imagen);
        Deteccion basura = mejor(detecciones, false);
        Deteccion planta = mejor(detecciones, true);
        ultimaDeteccion = describir(detecciones);

        if (modo != Modo.AUTONOMO) {
            return ultimaDeteccion + " | MANUAL";
        }
        if (ocupado.get()) {
            return ultimaDeteccion + " | OCUPADO";
        }
        if (!esp32.estaConectado()) {
            return ultimaDeteccion + " | SIN_CONEXION_ESP32";
        }

        // 1) Ya lleva la basura (o hay que ir a sembrar): no mira nada más hasta soltarla
        if ("LLEVANDO_BASURA".equals(estadoActual) || "IR_A_SEMBRAR".equals(estadoActual)) {
            siguiendoObjetivo = false;
            objetivoClase = null;
            ultimaAccion = "ESPERANDO_ARUCO";
            return ultimaDeteccion + " | " + ("LLEVANDO_BASURA".equals(estadoActual)
                    ? "LLEVANDO_BASURA (esperando ArUco o boton Soltar basura)" : "ESPERANDO_ARUCO");
        }

        // 2) Ya tiene un objetivo fijado: solo sigue ESE objeto, ignora otras basuras y plantas
        if (siguiendoObjetivo) {
            Deteccion objetivo = mejorDeClase(detecciones, objetivoClase);
            if (objetivo == null) {
                perdidas++;
                if (perdidas >= 3) {
                    System.out.println("🔎 [AUTÓNOMO] Objetivo perdido. Vuelve a buscar.");
                    siguiendoObjetivo = false;
                    objetivoClase = null;
                    perdidas = 0;
                    ultimaAccion = "OBJETIVO_PERDIDO";
                    return ultimaDeteccion + " | OBJETIVO_PERDIDO";
                }
                ultimaAccion = "ESPERANDO_OBJETIVO_" + perdidas;
                return ultimaDeteccion + " | " + ultimaAccion;
            }
            perdidas = 0;
            return ultimaDeteccion + " | " + acercarse(objetivo);
        }

        // 3) Sin objetivo: primero la basura; si no hay basura pero ve la planta ('bota'), toca sembrar
        if (basura != null) {
            siguiendoObjetivo = true;
            objetivoClase = basura.clase();
            perdidas = 0;
            sinVer = 0;
            System.out.println("🎯 [AUTÓNOMO] Objetivo fijado: " + objetivoClase);
            guardar("DETECCION_" + objetivoClase.toUpperCase(Locale.ROOT));
            return ultimaDeteccion + " | " + acercarse(basura);
        }
        if (planta != null) {
            estadoActual = "IR_A_SEMBRAR";
            System.out.println("🌱 [AUTÓNOMO] Planta ('bota') encontrada. Estado: IR_A_SEMBRAR");
            guardar("DETECCION_BOTA");
            ultimaAccion = "ESPERANDO_ARUCO";
            return ultimaDeteccion + " | PLANTA_VISTA";
        }
        return ultimaDeteccion + " | " + buscar();
    }

    /** La detección de más confianza de una clase concreta (null si no aparece). */
    private Deteccion mejorDeClase(List<Deteccion> lista, String clase) {
        Deteccion elegida = null;
        for (Deteccion d : lista) {
            if (!d.clase().equalsIgnoreCase(clase)) continue;
            if (elegida == null || d.confianza() > elegida.confianza()) elegida = d;
        }
        return elegida;
    }

    /** Elige la detección con más confianza: de basura (cualquier clase menos 'bota') o de planta ('bota'). */
    private Deteccion mejor(List<Deteccion> lista, boolean esPlanta) {
        Deteccion elegida = null;
        for (Deteccion d : lista) {
            boolean planta = "bota".equalsIgnoreCase(d.clase());
            if (planta != esPlanta) continue;
            if (elegida == null || d.confianza() > elegida.confianza()) elegida = d;
        }
        return elegida;
    }

    private String describir(List<Deteccion> lista) {
        if (lista.isEmpty()) return "nada";
        List<String> partes = new ArrayList<>();
        for (Deteccion d : lista) {
            partes.add(String.format(Locale.ROOT, "%s %.0f%% (centro=%.2f alto=%.2f bajo=%.2f)",
                    d.clase(), d.confianza() * 100, d.centroX(), d.h(), d.bordeInferior()));
        }
        return String.join(", ", partes);
    }

    /** Decide el siguiente paso hacia la basura detectada: alinear, avanzar o recoger. */
    private String acercarse(Deteccion basura) {
        double error = basura.centroX() - 0.5;

        if (Math.abs(error) > toleranciaCentro) {
            boolean aLaIzquierda = error < 0;
            if (invertirGiro) aLaIzquierda = !aLaIzquierda;
            pulso(aLaIzquierda ? 1 : 3, pwmGiro, msGiro);
            ultimaAccion = aLaIzquierda ? "ALINEAR_IZQUIERDA" : "ALINEAR_DERECHA";
            return ultimaAccion;
        }

        boolean cerca = basura.bordeInferior() >= umbralBordeInferior || basura.h() >= umbralAlto;
        if (cerca) {
            final String clase = basura.clase();
            ocupado.set(true);
            ejecutor.submit(() -> {
                try {
                    recolectarSecuencia(clase);
                } finally {
                    ocupado.set(false);
                }
            });
            ultimaAccion = "RECOGER_" + clase.toUpperCase(Locale.ROOT);
            return ultimaAccion;
        }

        pulso(2, pwmAvance, msAvance);
        ultimaAccion = "AVANZAR";
        return ultimaAccion;
    }

    /** No hay basura a la vista: gira para buscar y de vez en cuando avanza. */
    private String buscar() {
        if (!buscarActivo) {
            ultimaAccion = "SIN_OBJETIVO";
            return ultimaAccion;
        }
        sinVer++;
        if (sinVer % 4 == 0) {
            pulso(2, pwmAvance, msAvance);
            ultimaAccion = "BUSCAR_AVANZAR";
        } else {
            pulso(3, pwmGiro, msGiro);
            ultimaAccion = "BUSCAR_GIRAR";
        }
        return ultimaAccion;
    }

    /** Un empujón corto: mueve, espera, frena y deja que la imagen se estabilice antes de la próxima foto. */
    private void pulso(int accion, int pwm, int ms) {
        ocupado.set(true);
        ejecutor.submit(() -> {
            try {
                esp32.mover(accion, pwm);
                esperar(ms);
                esp32.parar();
                esperar(msEstabilizar);
            } finally {
                ocupado.set(false);
            }
        });
    }

    // =========================================================================
    // 3. SECUENCIAS DE BRAZOS Y COMPUERTAS
    // =========================================================================
    private void recolectarSecuencia(String clase) {
        System.out.println("🤖 [RECOLECCIÓN] Iniciando secuencia para: " + clase);
        estadoActual = "RECOGIENDO_BASURA";
        esp32.parar();

        abrirCompuertas();
        esperar(msServo);
        bajarBrazos();
        esperar(msServo);

        esp32.mover(2, pwmAvance);          // avanza para meter el objeto
        esperar(msEmpujar);
        esp32.parar();

        cerrarCompuertas();
        esperar(msServo);
        brazosReposo();
        esperar(msServo);

        basuraRecolectada++;
        siguiendoObjetivo = false;
        objetivoClase = null;
        perdidas = 0;
        sinVer = 0;
        estadoActual = basuraRecolectada >= basuraMaxima ? "LLEVANDO_BASURA" : "PATRULLANDO";
        System.out.printf("🗑️ Basura asegurada (%s). Total: %d/%d | Estado: %s%n",
                clase, basuraRecolectada, basuraMaxima, estadoActual);
        guardar("RECOLECCION_" + clase.toUpperCase(Locale.ROOT));
    }

    private void sembrarSecuencia() {
        System.out.println("🌱 [SIEMBRA] Iniciando secuencia de depósito de planta...");
        estadoActual = "PLANTANDO";
        esp32.parar();

        abrirCompuertas();
        esperar(msServo);
        bajarBrazos();
        esperar(msServo);

        esp32.mover(8, pwmAvance);          // retrocede para dejar la planta en su lugar
        esperar(msEmpujar);
        esp32.parar();

        cerrarCompuertas();
        esperar(msServo);
        brazosReposo();
        esperar(msServo);

        plantasSembradas++;
        estadoActual = "PATRULLANDO";
        System.out.println("✅ ¡Planta sembrada! Estado: PATRULLANDO");
        guardar("SIEMBRA_PLANTA");
    }

    /** Suelta la basura que lleva (misma mecánica que la siembra: compuertas, brazos y retroceso). */
    private void soltarBasuraSecuencia() {
        System.out.println("🗑️ [DESCARGA] Soltando la basura...");
        esp32.parar();

        abrirCompuertas();
        esperar(msServo);
        bajarBrazos();
        esperar(msServo);

        esp32.mover(8, pwmAvance);          // retrocede para dejar la basura en su lugar
        esperar(msEmpujar);
        esp32.parar();

        cerrarCompuertas();
        esperar(msServo);
        brazosReposo();
        esperar(msServo);

        basuraRecolectada = 0;
        siguiendoObjetivo = false;
        objetivoClase = null;
        perdidas = 0;
        estadoActual = "PATRULLANDO";
        System.out.println("✅ Basura soltada. Estado: PATRULLANDO");
        guardar("DESCARGA_BASURA_MANUAL");
    }

    private void abrirCompuertas() {
        esp32.servo(COMPUERTA_DER, COMP_DER_ABIERTA);
        esp32.servo(COMPUERTA_IZQ, COMP_IZQ_ABIERTA);
    }

    private void cerrarCompuertas() {
        esp32.servo(COMPUERTA_DER, COMP_DER_CERRADA);
        esp32.servo(COMPUERTA_IZQ, COMP_IZQ_CERRADA);
    }

    private void bajarBrazos() {
        esp32.servo(BRAZO_DER, BRAZO_DER_BAJO);
        esp32.servo(BRAZO_IZQ, BRAZO_IZQ_BAJO);
    }

    private void brazosReposo() {
        esp32.servo(BRAZO_DER, BRAZO_REPOSO);
        esp32.servo(BRAZO_IZQ, BRAZO_REPOSO);
    }

    /** Las usa el panel/endpoint manual: ejecuta la secuencia completa y espera a que termine. */
    public Map<String, Object> recolectarManual() throws Exception {
        Future<?> tarea = ejecutor.submit(() -> {
            ocupado.set(true);
            try {
                recolectarSecuencia("MANUAL");
            } finally {
                ocupado.set(false);
            }
        });
        tarea.get(40, TimeUnit.SECONDS);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "SUCCESS");
        r.put("basuraRecolectada", basuraRecolectada);
        r.put("estadoActual", estadoActual);
        return r;
    }

    public Map<String, Object> soltarBasuraManual() throws Exception {
        Future<?> tarea = ejecutor.submit(() -> {
            ocupado.set(true);
            try {
                soltarBasuraSecuencia();
            } finally {
                ocupado.set(false);
            }
        });
        tarea.get(40, TimeUnit.SECONDS);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "SUCCESS");
        r.put("basuraRecolectada", basuraRecolectada);
        r.put("estadoActual", estadoActual);
        return r;
    }

    public Map<String, Object> sembrarManual() throws Exception {
        Future<?> tarea = ejecutor.submit(() -> {
            ocupado.set(true);
            try {
                sembrarSecuencia();
            } finally {
                ocupado.set(false);
            }
        });
        tarea.get(40, TimeUnit.SECONDS);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "SUCCESS");
        r.put("plantasSembradas", plantasSembradas);
        r.put("estadoActual", estadoActual);
        return r;
    }

    // =========================================================================
    // 4. MOVIMIENTO Y SERVOS MANUALES POR API
    // =========================================================================
    public boolean moverManual(String direccion, int pwm) {
        int accion;
        switch (direccion.toLowerCase(Locale.ROOT)) {
            case "adelante": accion = 2; break;
            case "atras": case "atrás": accion = 8; break;
            case "izquierda": accion = 1; break;
            case "derecha": accion = 3; break;
            default: accion = 0; break;
        }
        return esp32.mover(accion, pwm);
    }

    public boolean servoManual(String parte, int angulo) {
        int canal;
        switch (parte.toLowerCase(Locale.ROOT)) {
            case "brazo_der": canal = BRAZO_DER; break;
            case "brazo_izq": canal = BRAZO_IZQ; break;
            case "compuerta_der": canal = COMPUERTA_DER; break;
            case "compuerta_izq": canal = COMPUERTA_IZQ; break;
            default: return false;
        }
        return esp32.servo(canal, angulo);
    }

    // =========================================================================
    // 5. MARCADORES ArUco (misma lógica de antes)
    // =========================================================================
    public Map<String, String> decidirEnCruce(int idMarcador) {
        Map<String, String> decision = new HashMap<>();
        String pwmAutonomo = "130";

        System.out.println("🧭 [AUTÓNOMO] ArUco ID detectado: " + idMarcador + " | Estado actual: " + estadoActual);

        switch (idMarcador) {
            case 10: // Cruce principal
                if ("IR_A_SEMBRAR".equals(estadoActual)) {
                    decision.put("accion", "GIRAR_IZQUIERDA");
                    decision.put("destino", "Zona de Plantas");
                } else {
                    decision.put("accion", "GIRAR_DERECHA");
                    decision.put("destino", "Depósito de Basura");
                }
                decision.put("pwm", pwmAutonomo);
                break;

            case 1: // Depósito de basura
                decision.put("accion", "DEPOSITAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                basuraRecolectada = 0;
                estadoActual = "PATRULLANDO";
                guardar("DESCARGA_BASURA_COMPLETA");
                break;

            case 2: // Depósito de plantas
                decision.put("accion", "SEMBRAR_Y_GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                plantasSembradas++;
                estadoActual = "PATRULLANDO";
                guardar("SIEMBRA_COMPLETA");
                break;

            case 5: // Tope
                decision.put("accion", "GIRAR_180");
                decision.put("pwm", pwmAutonomo);
                break;

            default:
                decision.put("accion", "SEGUIR_RECTO");
                decision.put("pwm", pwmAutonomo);
                break;
        }
        return decision;
    }

    // =========================================================================
    // 6. MÉTRICAS Y UTILIDADES
    // =========================================================================
    public Map<String, Object> metricas() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("basuraRecolectada", basuraRecolectada);
        m.put("plantasSembradas", plantasSembradas);
        m.put("estadoActual", estadoActual);
        m.put("modo", modo.name());
        m.put("esp32Conectado", esp32.estaConectado());
        m.put("modeloCargado", detector.disponible());
        m.put("ocupado", ocupado.get());
        m.put("ultimaDeteccion", ultimaDeteccion);
        m.put("ultimaAccion", ultimaAccion);
        return m;
    }

    public byte[] getUltimaFoto() {
        return ultimaFoto;
    }

    public boolean modeloDisponible() {
        return detector.disponible();
    }

    private void guardar(String evento) {
        try {
            repositorio.save(new RegistroActividad(basuraRecolectada, plantasSembradas, evento));
        } catch (Exception e) {
            System.err.println("⚠️ [BD] No se pudo guardar el evento " + evento + ": " + e.getMessage());
        }
    }

    private void esperar(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
