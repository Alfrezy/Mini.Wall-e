package com.walle.robot;

import com.walle.model.RegistroActividad;
import com.walle.repository.RegistroActividadRepository;
import com.walle.vision.ArucoCliente;
import com.walle.vision.ArucoCliente.Marcador;
import com.walle.vision.DetectorYolo;
import com.walle.vision.DetectorYolo.Deteccion;
import com.walle.vision.RostrosCliente;
import com.walle.vision.RostrosCliente.Rostro;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Patrulla: si no ve nada avanza varios pasos y luego gira un poco (no se queda quieto).
 * Los brazos SUBEN para abrir las compuertas (si bajaran, la puerta no abre).
 *
 * Reglas (una cosa a la vez, porque el almacenamiento es pequeño):
 *  - Al empezar a ir por una basura se FIJA ese objetivo: ignora otras basuras y las plantas.
 *  - Distancia: el robot recoge cuando el borde de abajo del bounding box llega al valor calibrado
 *    a 11 cm (botón 'Calibrar 11 cm'). Como los objetos están en el piso, ese borde indica la
 *    distancia sin importar el tamaño del objeto.
 *  - Al recogerla pasa a LLEVANDO_BASURA: BUSCA un marcador ArUco (patrulla hasta verlo), se centra,
 *    se acerca (con el bounding box del marcador) y al llegar suelta la basura (ID 1) o siembra (ID 2).
 *  - Si ve a una persona (y está patrullando sin tarea), la reconoce con la red de caras y la saluda
 *    moviendo los brazos arriba y abajo.
 */
@Service
public class RobotService {

    public enum Modo { MANUAL, AUTONOMO }

    // ---- Canales y ángulos de los servos (los mismos que usa teclado.js) ----
    private static final int BRAZO_DER = 0, BRAZO_IZQ = 1, COMPUERTA_DER = 2, COMPUERTA_IZQ = 3;
    private static final int BRAZO_REPOSO = 50;
    private static final int COMP_DER_ABIERTA = 10, COMP_DER_CERRADA = 90;
    private static final int COMP_IZQ_ABIERTA = 85, COMP_IZQ_CERRADA = 5;

    // ---- Ajustes (application.properties) ----
    @Value("${walle.modo-inicial:MANUAL}") private String modoInicial;
    @Value("${walle.autonomo.buscar:true}") private boolean buscarActivo;
    @Value("${walle.autonomo.basura-maxima:1}") private int basuraMaxima;
    @Value("${walle.autonomo.pwm-avance:130}") private int pwmAvance;
    @Value("${walle.autonomo.pwm-giro:140}") private int pwmGiro;
    @Value("${walle.autonomo.pwm-acercamiento:140}") private int pwmAcercamiento;
    @Value("${walle.autonomo.pwm-arranque:180}") private int pwmArranque;
    @Value("${walle.autonomo.ms-arranque:80}") private int msArranque;
    @Value("${walle.autonomo.ms-avance:300}") private int msAvance;
    @Value("${walle.autonomo.ms-giro:200}") private int msGiro;
    @Value("${walle.autonomo.ms-estabilizar:700}") private int msEstabilizar;
    @Value("${walle.autonomo.ms-empujar:600}") private int msEmpujar;
    @Value("${walle.autonomo.ms-servo:1000}") private int msServo;
    @Value("${walle.autonomo.tolerancia-centro:0.15}") private double toleranciaCentro;
    @Value("${walle.autonomo.umbral-borde-inferior:0.85}") private volatile double umbralBordeInferior;
    @Value("${walle.autonomo.invertir-giro:false}") private boolean invertirGiro;
    @Value("${walle.autonomo.patrulla-avances:3}") private int patrullaAvances;
    @Value("${walle.autonomo.ms-saludo:700}") private int msSaludo;
    @Value("${walle.autonomo.saludo-repeticiones:3}") private int saludoRepeticiones;
    @Value("${walle.camara.rotacion-grados:0}") private int rotacionGrados;
    @Value("${walle.aruco.umbral-alto:0.30}") private volatile double arucoUmbralAlto;
    @Value("${walle.aruco.tolerancia-centro:0.15}") private double arucoToleranciaCentro;
    @Value("${walle.aruco.ms-giro-90:900}") private int msGiro90;
    @Value("${walle.aruco.ms-cooldown:6000}") private long arucoCooldownMs;
    @Value("${walle.caras.activo:true}") private boolean carasActivo;
    @Value("${walle.caras.cooldown-ms:30000}") private long carasCooldownMs;
    @Value("${walle.caras.saludar-desconocidos:false}") private boolean saludarDesconocidos;
    @Value("${walle.caras.alto-minimo:0.15}") private double carasAltoMinimo;
    @Value("${walle.servos.pasos-cierre:16}") private int pasosCierre;
    @Value("${walle.servos.ms-paso-cierre:100}") private int msPasoCierre;
    @Value("${walle.servos.brazo-der-alto:20}") private int brazoDerAlto;
    @Value("${walle.servos.brazo-izq-alto:80}") private int brazoIzqAlto;

    private final DetectorYolo detector;
    private final Esp32Cliente esp32;
    private final ArucoCliente aruco;
    private final RostrosCliente caras;
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
    private volatile Deteccion ultimaMejor = null;  // para calibrar la distancia
    private volatile long ultimaMejorHora = 0;
    private volatile String ultimoAruco = "ninguno";
    private volatile List<Marcador> ultimosMarcadores = List.of();
    private volatile long ultimosMarcadoresHora = 0;
    private volatile String ultimaCara = "ninguna";
    private volatile List<Deteccion> ultimasDetecciones = List.of();
    private volatile List<Rostro> ultimosRostros = List.of();
    private volatile long ultimosRostrosHora = 0;
    private final Map<String, Long> saludoHora = new ConcurrentHashMap<>();
    private final Map<Integer, Long> arucoAtendidoHora = new ConcurrentHashMap<>();

    /** true mientras el robot se está moviendo o ejecutando una secuencia (las fotos nuevas se ignoran). */
    private final AtomicBoolean ocupado = new AtomicBoolean(false);
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor();

    public RobotService(DetectorYolo detector, Esp32Cliente esp32, ArucoCliente aruco, RostrosCliente caras, RegistroActividadRepository repositorio) {
        this.detector = detector;
        this.esp32 = esp32;
        this.aruco = aruco;
        this.caras = caras;
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
    public String procesarFoto(byte[] imagenOriginal) throws Exception {
        byte[] imagen = rotar(imagenOriginal);   // endereza la foto si la cámara está montada de lado
        ultimaFoto = imagen;

        List<Deteccion> detecciones = detector.detectar(imagen);
        Deteccion basura = mejor(detecciones, false);
        Deteccion planta = mejor(detecciones, true);
        ultimasDetecciones = detecciones;
        ultimaDeteccion = describir(detecciones);
        ultimaMejor = basura != null ? basura : planta;   // para el botón 'Calibrar 11 cm'
        ultimaMejorHora = System.currentTimeMillis();

        if (modo != Modo.AUTONOMO) {
            // En MANUAL el robot no se mueve, pero muestra lo que ve (ArUco y caras): sirve para probar y calibrar
            registrarMarcadores(aruco.detectar(imagen));
            if (carasActivo) {
                registrarRostros(caras.detectar(imagen));
            }
            return ultimaDeteccion + " | MANUAL";
        }
        if (ocupado.get()) {
            return ultimaDeteccion + " | OCUPADO";
        }
        if (!esp32.estaConectado()) {
            return ultimaDeteccion + " | SIN_CONEXION_ESP32";
        }

        // 0) Si ve a una persona y no está en medio de una tarea, la reconoce y la saluda con los brazos
        if (carasActivo && !siguiendoObjetivo && "PATRULLANDO".equals(estadoActual)) {
            List<Rostro> rostros = caras.detectar(imagen);
            registrarRostros(rostros);
            Rostro persona = elegirRostroParaSaludar(rostros);
            if (persona != null) {
                return ultimaDeteccion + " | " + saludarA(persona);
            }
        }

        // 1) Ya lleva la basura (o hay que ir a sembrar): ignora basuras y plantas y BUSCA un marcador ArUco
        if ("LLEVANDO_BASURA".equals(estadoActual) || "IR_A_SEMBRAR".equals(estadoActual)) {
            siguiendoObjetivo = false;
            objetivoClase = null;
            return ultimaDeteccion + " | " + buscarMarcador(imagen);
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
            ultimaAccion = "PLANTA_VISTA";
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

    /**
     * Gira la foto en sentido de las manecillas del reloj (0, 90, 180 o 270 grados).
     * El sensor no puede girar 90° por sí solo, así que se hace aquí, ANTES de detectar,
     * para que el modelo vea los objetos derechos y "izquierda/derecha" sean los reales.
     */
    private byte[] rotar(byte[] jpeg) throws Exception {
        int g = ((rotacionGrados % 360) + 360) % 360;
        if (g != 90 && g != 180 && g != 270) {
            return jpeg;
        }
        BufferedImage origen = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (origen == null) {
            throw new IllegalArgumentException("Los bytes recibidos no son una imagen válida.");
        }
        int w = origen.getWidth(), h = origen.getHeight();
        boolean delado = (g == 90 || g == 270);
        BufferedImage destino = new BufferedImage(delado ? h : w, delado ? w : h, BufferedImage.TYPE_INT_RGB);

        AffineTransform at = new AffineTransform();
        if (g == 90) {
            at.translate(h, 0);
            at.rotate(Math.toRadians(90));
        } else if (g == 180) {
            at.translate(w, h);
            at.rotate(Math.PI);
        } else {
            at.translate(0, w);
            at.rotate(Math.toRadians(270));
        }
        Graphics2D g2 = destino.createGraphics();
        g2.drawImage(origen, at, null);
        g2.dispose();

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(destino, "jpg", salida);
        return salida.toByteArray();
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

        // El borde de abajo del bounding box indica la distancia (el objeto está en el piso).
        // Se calibra a 11 cm con el botón del panel.
        boolean cerca = basura.bordeInferior() >= umbralBordeInferior;
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

        boolean casiCerca = basura.bordeInferior() >= umbralBordeInferior - 0.15;
        pulso(2, casiCerca ? pwmAcercamiento : pwmAvance, msAvance);   // más despacio al llegar
        ultimaAccion = casiCerca ? "AVANZAR_LENTO" : "AVANZAR";
        return ultimaAccion;
    }

    // =========================================================================
    // 2.1 BÚSQUEDA DE MARCADORES ArUco (cuando lleva basura o hay que sembrar)
    // =========================================================================
    private String buscarMarcador(byte[] imagen) {
        List<Marcador> marcadores = aruco.detectar(imagen);
        registrarMarcadores(marcadores);

        Marcador objetivo = elegirMarcador(marcadores);
        if (objetivo == null) {
            // No ve ninguno que le sirva: patrulla (avanza y gira) hasta mirarlo
            return "BUSCANDO_ARUCO/" + buscar();
        }
        sinVer = 0;

        // 1) Centrarse en el marcador usando su bounding box
        double error = objetivo.centroX() - 0.5;
        if (Math.abs(error) > arucoToleranciaCentro) {
            boolean aLaIzquierda = error < 0;
            if (invertirGiro) aLaIzquierda = !aLaIzquierda;
            pulso(aLaIzquierda ? 1 : 3, pwmGiro, msGiro);
            ultimaAccion = "ARUCO_" + objetivo.id() + "_ALINEAR_" + (aLaIzquierda ? "IZQUIERDA" : "DERECHA");
            return ultimaAccion;
        }

        // 2) Llegó: el marcador se ve grande (su bounding box ocupa suficiente de la foto)
        if (objetivo.h() >= arucoUmbralAlto) {
            accionEnMarcador(objetivo.id());
            ultimaAccion = "ARUCO_" + objetivo.id() + "_LLEGUE";
            return ultimaAccion;
        }

        // 3) Todavía lejos: avanza (más despacio al acercarse)
        boolean casi = objetivo.h() >= arucoUmbralAlto - 0.12;
        pulso(2, casi ? pwmAcercamiento : pwmAvance, msAvance);
        ultimaAccion = "ARUCO_" + objetivo.id() + (casi ? "_AVANZAR_LENTO" : "_AVANZAR");
        return ultimaAccion;
    }

    /** Qué marcadores le sirven según lo que esté haciendo. */
    private boolean esRelevante(int id) {
        boolean llevandoBasura = "LLEVANDO_BASURA".equals(estadoActual);
        switch (id) {
            case 1:  return llevandoBasura;    // depósito de basura
            case 2:  return !llevandoBasura;   // depósito de plantas (estado IR_A_SEMBRAR)
            case 10: return true;              // cruce principal
            case 5:  return true;              // tope: dar la vuelta
            default: return false;
        }
    }

    /** El marcador relevante más grande (= más cercano) que no acaba de atender. */
    private Marcador elegirMarcador(List<Marcador> lista) {
        Marcador elegido = null;
        long ahora = System.currentTimeMillis();
        for (Marcador m : lista) {
            if (!esRelevante(m.id())) continue;
            Long hora = arucoAtendidoHora.get(m.id());
            if (hora != null && ahora - hora < arucoCooldownMs) continue;   // ya lo atendió hace poco
            if (elegido == null || m.h() > elegido.h()) elegido = m;
        }
        return elegido;
    }

    /** Qué hacer al llegar a un marcador (se ejecuta en segundo plano). */
    private void accionEnMarcador(int id) {
        arucoAtendidoHora.put(id, System.currentTimeMillis());
        ocupado.set(true);
        ejecutor.submit(() -> {
            try {
                switch (id) {
                    case 1:   // depósito de basura: suelta y da la vuelta
                        soltarBasuraSecuencia();
                        girarMs(3, 2 * msGiro90);
                        break;
                    case 2:   // depósito de plantas: siembra y da la vuelta
                        sembrarSecuencia();
                        girarMs(3, 2 * msGiro90);
                        break;
                    case 10: { // cruce: gira según el estado (misma regla de siempre)
                        Map<String, String> decision = decidirEnCruce(10);
                        boolean izquierda = "GIRAR_IZQUIERDA".equals(decision.get("accion"));
                        girarMs(izquierda ? 1 : 3, msGiro90);
                        break;
                    }
                    case 5:   // tope: media vuelta
                        girarMs(3, 2 * msGiro90);
                        break;
                    default:
                        break;
                }
            } finally {
                ocupado.set(false);
            }
        });
    }

    /** Gira en el lugar durante cierto tiempo (accion 1 = izquierda, 3 = derecha). */
    private void girarMs(int accion, int ms) {
        arrancar(accion, pwmGiro);
        esperar(ms);
        esp32.parar();
        esperar(msEstabilizar);
    }

    private void registrarMarcadores(List<Marcador> lista) {
        ultimosMarcadores = lista;
        ultimosMarcadoresHora = System.currentTimeMillis();
        ultimoAruco = describirMarcadores(lista);
    }

    // =========================================================================
    // 2.2 CARAS: reconocer a la persona y saludarla
    // =========================================================================
    /** La cara más grande (= más cercana) que merece saludo: reconocida, lo bastante cerca y sin saludo reciente. */
    private Rostro elegirRostroParaSaludar(List<Rostro> lista) {
        Rostro elegido = null;
        long ahora = System.currentTimeMillis();
        for (Rostro r : lista) {
            if (!r.reconocido() && !saludarDesconocidos) continue;
            if (r.h() < carasAltoMinimo) continue;                          // está muy lejos
            Long hora = saludoHora.get(r.nombre());
            if (hora != null && ahora - hora < carasCooldownMs) continue;   // ya la saludó hace poco
            if (elegido == null || r.area() > elegido.area()) elegido = r;
        }
        return elegido;
    }

    private String saludarA(Rostro persona) {
        saludoHora.put(persona.nombre(), System.currentTimeMillis());
        ocupado.set(true);
        ejecutor.submit(() -> {
            try {
                saludarSecuencia(persona.nombre());
            } finally {
                ocupado.set(false);
            }
        });
        ultimaAccion = "SALUDAR_" + persona.nombre().toUpperCase(Locale.ROOT);
        return ultimaAccion;
    }

    private void registrarRostros(List<Rostro> lista) {
        ultimosRostros = lista;
        ultimosRostrosHora = System.currentTimeMillis();
        ultimaCara = describirRostros(lista);
    }

    private String describirRostros(List<Rostro> lista) {
        if (lista.isEmpty()) return "ninguna";
        List<String> partes = new ArrayList<>();
        for (Rostro r : lista) {
            partes.add(String.format(Locale.ROOT, "%s (similitud=%.2f alto=%.2f)", r.nombre(), r.similitud(), r.h()));
        }
        return String.join(", ", partes);
    }

    private String describirMarcadores(List<Marcador> lista) {
        if (lista.isEmpty()) return "ninguno";
        List<String> partes = new ArrayList<>();
        for (Marcador m : lista) {
            partes.add(String.format(Locale.ROOT, "ID%d (centro=%.2f alto=%.2f)", m.id(), m.centroX(), m.h()));
        }
        return String.join(", ", partes);
    }

    /** No hay basura a la vista: patrulla. Avanza varios pasos y luego gira un poco para mirar a otro lado. */
    private String buscar() {
        if (!buscarActivo) {
            ultimaAccion = "SIN_OBJETIVO";
            return ultimaAccion;
        }
        sinVer++;
        if (sinVer % (patrullaAvances + 1) == 0) {
            pulso(3, pwmGiro, msGiro);
            ultimaAccion = "PATRULLAR_GIRAR";
        } else {
            pulso(2, pwmAvance, msAvance);
            ultimaAccion = "PATRULLAR_AVANZAR";
        }
        return ultimaAccion;
    }

    /**
     * Arranca los motores con un impulso corto y fuerte y luego baja a la velocidad pedida.
     * Con poca fuerza (PWM bajo) el motor solo "pita" y no vence la fricción de arranque.
     */
    private void arrancar(int accion, int pwm) {
        if (pwm < pwmArranque) {
            esp32.mover(accion, pwmArranque);
            esperar(msArranque);
        }
        esp32.mover(accion, pwm);
    }

    /** Un empujón corto: mueve, espera, frena y deja que la imagen se estabilice antes de la próxima foto. */
    private void pulso(int accion, int pwm, int ms) {
        ocupado.set(true);
        ejecutor.submit(() -> {
            try {
                arrancar(accion, pwm);
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

        subirBrazos();                      // primero los brazos ARRIBA: si no, la puerta no abre
        esperar(msServo);
        abrirCompuertas();
        esperar(msServo);

        arrancar(2, pwmAcercamiento);       // avanza despacio (con impulso de arranque) para meter el objeto
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

        subirBrazos();                      // primero los brazos ARRIBA: si no, la puerta no abre
        esperar(msServo);
        abrirCompuertas();
        esperar(msServo);

        arrancar(8, pwmAcercamiento);       // retrocede despacio (con impulso de arranque) para dejar la planta en su lugar
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

        subirBrazos();                      // primero los brazos ARRIBA: si no, la puerta no abre
        esperar(msServo);
        abrirCompuertas();
        esperar(msServo);

        arrancar(8, pwmAcercamiento);       // retrocede despacio (con impulso de arranque) para dejar la basura en su lugar
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

    /** Saludo: mueve SOLO los brazos, arriba y abajo (la usará el reconocimiento de caras). */
    private void saludarSecuencia(String quien) {
        System.out.println("👋 [SALUDO] Saludando a " + quien + ": brazos arriba y abajo...");
        esp32.parar();
        for (int i = 0; i < saludoRepeticiones; i++) {
            subirBrazos();
            esperar(msSaludo);
            brazosReposo();
            esperar(msSaludo);
        }
        guardar("SALUDO_" + quien.toUpperCase(Locale.ROOT));
    }

    private void abrirCompuertas() {
        esp32.servo(COMPUERTA_DER, COMP_DER_ABIERTA);
        esp32.servo(COMPUERTA_IZQ, COMP_IZQ_ABIERTA);
    }

    /**
     * Cierra las compuertas DESPACIO: en vez de mandar el ángulo final de golpe, manda ángulos
     * intermedios (pasos-cierre pasos, uno cada ms-paso-cierre milisegundos).
     * Con 16 pasos de 100 ms tarda unos 1.6 segundos.
     */
    private void cerrarCompuertas() {
        int pasos = Math.max(1, pasosCierre);
        for (int i = 1; i <= pasos; i++) {
            esp32.servo(COMPUERTA_DER, COMP_DER_ABIERTA + (COMP_DER_CERRADA - COMP_DER_ABIERTA) * i / pasos);
            esp32.servo(COMPUERTA_IZQ, COMP_IZQ_ABIERTA + (COMP_IZQ_CERRADA - COMP_IZQ_ABIERTA) * i / pasos);
            esperar(msPasoCierre);
        }
    }

    /** Brazos hacia ARRIBA (ángulos en application.properties: walle.servos.brazo-*-alto). */
    private void subirBrazos() {
        esp32.servo(BRAZO_DER, brazoDerAlto);
        esp32.servo(BRAZO_IZQ, brazoIzqAlto);
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

    /** Saludo con los brazos. Se puede llamar desde el panel o, más adelante, desde el reconocimiento de caras. */
    public Map<String, Object> saludarManual() throws Exception {
        Future<?> tarea = ejecutor.submit(() -> {
            ocupado.set(true);
            try {
                saludarSecuencia("MANUAL");
            } finally {
                ocupado.set(false);
            }
        });
        tarea.get(40, TimeUnit.SECONDS);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "SUCCESS");
        r.put("mensaje", "Saludo terminado");
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
        m.put("ultimoAruco", ultimoAruco);
        m.put("umbralAruco", Math.round(arucoUmbralAlto * 1000) / 1000.0);
        m.put("ultimaCara", ultimaCara);
        m.put("carasConectadas", caras.disponible());
        m.put("visionConectada", aruco.disponible());
        m.put("umbralBordeInferior", Math.round(umbralBordeInferior * 1000) / 1000.0);
        return m;
    }

    /**
     * Calibra la distancia de recogida. Pon el objeto a 11 cm frente a la cámara (centrado) y
     * llama a esto: guarda el borde de abajo de su bounding box como "ya estoy a distancia".
     */
    public Map<String, Object> calibrarDistancia() {
        Map<String, Object> r = new LinkedHashMap<>();
        Deteccion d = ultimaMejor;
        if (d == null || System.currentTimeMillis() - ultimaMejorHora > 5000) {
            r.put("status", "ERROR");
            r.put("mensaje", "No hay un objeto detectado en los últimos 5 segundos. Ponlo a 11 cm frente a la cámara y vuelve a intentar.");
            return r;
        }
        double medido = d.bordeInferior();
        double nuevo = Math.max(0.30, Math.min(0.98, medido - 0.02));   // un poquito antes, para no pasarse
        umbralBordeInferior = nuevo;
        r.put("status", "SUCCESS");
        r.put("objeto", d.clase());
        r.put("bordeInferiorMedido", Math.round(medido * 1000) / 1000.0);
        r.put("umbralNuevo", Math.round(nuevo * 1000) / 1000.0);
        r.put("mensaje", "Listo (vale hasta que se reinicie el backend). Para dejarlo fijo escribe en application.properties: "
                + "walle.autonomo.umbral-borde-inferior=" + Math.round(nuevo * 1000) / 1000.0);
        System.out.println("📏 [CALIBRACIÓN] Distancia de recogida: borde inferior = " + nuevo + " (" + d.clase() + ")");
        return r;
    }

    /**
     * Calibra a qué distancia el robot "llega" a un marcador ArUco. Pon el marcador frente a la cámara,
     * a la distancia a la que quieres que actúe, y llama a esto (en modo MANUAL): guarda el alto de su
     * bounding box como "ya llegué".
     */
    public Map<String, Object> calibrarAruco() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Marcador> lista = ultimosMarcadores;
        if (lista == null || lista.isEmpty() || System.currentTimeMillis() - ultimosMarcadoresHora > 5000) {
            r.put("status", "ERROR");
            r.put("mensaje", "No veo ningún marcador ArUco en los últimos 5 segundos. Con el robot en modo MANUAL, "
                    + "pon el marcador frente a la cámara (completo, con su borde blanco) a la distancia a la que quieres que actúe y vuelve a intentar.");
            return r;
        }
        Marcador grande = lista.get(0);
        for (Marcador m : lista) {
            if (m.h() > grande.h()) grande = m;
        }
        double nuevo = Math.max(0.05, Math.min(0.95, grande.h() - 0.01));
        arucoUmbralAlto = nuevo;
        r.put("status", "SUCCESS");
        r.put("marcadorId", grande.id());
        r.put("altoMedido", Math.round(grande.h() * 1000) / 1000.0);
        r.put("umbralNuevo", Math.round(nuevo * 1000) / 1000.0);
        r.put("mensaje", "Listo (vale hasta que se reinicie el backend). Para dejarlo fijo escribe en application.properties: "
                + "walle.aruco.umbral-alto=" + Math.round(nuevo * 1000) / 1000.0);
        System.out.println("📏 [CALIBRACIÓN] ArUco: umbral-alto = " + nuevo + " (ID " + grande.id() + ")");
        return r;
    }

    // Colores de los bounding boxes (una clase = un color)
    private static final Color COLOR_ARUCO = new Color(0xFFEB3B);          // amarillo
    private static final Color COLOR_CARA_CONOCIDA = new Color(0x2196F3);  // azul
    private static final Color COLOR_CARA_DESCONOCIDA = new Color(0xF44336); // rojo

    private static Color colorClase(String clase) {
        switch (clase.toLowerCase(Locale.ROOT)) {
            case "caja":           return new Color(0xFF9800);   // naranja
            case "bota":           return new Color(0x4CAF50);   // verde (la planta)
            case "botealuminio":   return new Color(0x00BCD4);   // cian
            case "papelhigienico": return new Color(0xE91E63);   // rosa
            default:               return new Color(0xFFFFFF);
        }
    }

    /**
     * La última foto con los bounding boxes dibujados: cada objeto con su nombre, su porcentaje
     * de confianza y un color distinto por clase (también marcadores ArUco y caras).
     * Se amplía x2 para que el texto se lea bien. Si algo falla, devuelve la foto sin dibujar.
     */
    public byte[] getUltimaFotoAnotada() {
        byte[] foto = ultimaFoto;
        if (foto == null || foto.length == 0) return foto;
        try {
            BufferedImage origen = ImageIO.read(new ByteArrayInputStream(foto));
            if (origen == null) return foto;
            final int escala = 2;
            int W = origen.getWidth() * escala, H = origen.getHeight() * escala;
            BufferedImage lienzo = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = lienzo.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(origen, 0, 0, W, H, null);

            for (Deteccion d : ultimasDetecciones) {
                dibujarCaja(g, W, H, d.x(), d.y(), d.w(), d.h(),
                        d.clase() + " " + Math.round(d.confianza() * 100) + "%", colorClase(d.clase()));
            }
            long ahora = System.currentTimeMillis();
            if (ahora - ultimosMarcadoresHora < 3000) {
                for (Marcador m : ultimosMarcadores) {
                    dibujarCaja(g, W, H, m.x(), m.y(), m.w(), m.h(), "ArUco ID " + m.id(), COLOR_ARUCO);
                }
            }
            if (ahora - ultimosRostrosHora < 3000) {
                for (Rostro r : ultimosRostros) {
                    String texto = r.reconocido() ? r.nombre() + " " + Math.round(r.similitud() * 100) + "%" : "desconocido";
                    dibujarCaja(g, W, H, r.x(), r.y(), r.w(), r.h(), texto,
                            r.reconocido() ? COLOR_CARA_CONOCIDA : COLOR_CARA_DESCONOCIDA);
                }
            }
            g.dispose();

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(lienzo, "jpg", salida);
            return salida.toByteArray();
        } catch (Throwable e) {
            System.err.println("⚠️ [FOTO] No se pudieron dibujar los bounding boxes: " + e);
            return foto;
        }
    }

    /** Dibuja un rectángulo (valores 0 a 1) con una etiqueta de texto sobre fondo del mismo color. */
    private void dibujarCaja(Graphics2D g, int W, int H, double x, double y, double w, double h, String texto, Color color) {
        int bx = (int) Math.round(x * W), by = (int) Math.round(y * H);
        int bw = (int) Math.round(w * W), bh = (int) Math.round(h * H);

        g.setStroke(new BasicStroke(3f));
        g.setColor(color);
        g.drawRect(bx, by, bw, bh);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        int ancho = fm.stringWidth(texto) + 10, alto = fm.getHeight() + 2;
        int ty = by - alto;
        if (ty < 0) ty = by;                                   // si no cabe arriba, va dentro de la caja
        int tx = Math.max(0, Math.min(bx, W - ancho));
        g.setColor(color);
        g.fillRect(tx, ty, ancho, alto);
        double brillo = 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue();
        g.setColor(brillo > 150 ? Color.BLACK : Color.WHITE);  // texto legible sobre cualquier color
        g.drawString(texto, tx + 5, ty + fm.getAscent() + 1);
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
