// --- URLS DE LOS SERVICIOS ---
const URL_BACKEND = "http://localhost:8080/api/robot";      // Para Java (Fotos y Métricas)
const ESP32_IP = "192.168.137.200";                         // ESP32 Principal (Motores y Servos) - IP FIJA
 
// --- CÁMARA ESP32-CAM (IP Fija) ---
const ESP32_CAM_IP = "192.168.137.201";
const URL_STREAM  = `http://${ESP32_CAM_IP}/stream`;        // Corregido con /stream para mostrar el video en vivo
const URL_CAPTURE = `http://${ESP32_CAM_IP}/capture`;       // Endpoint para capturar foto individual
 
const imgCamara = document.getElementById('streamCam');
if (imgCamara) imgCamara.src = URL_STREAM;
 
// --- CONEXIÓN WEBSOCKET DIRECTA CON EL ESP32 ---
let ws = null;
let reconectando = false;
 
function conectarWS() {
    reconectando = false;
    ws = new WebSocket(`ws://${ESP32_IP}/ws`);
 
    ws.onopen = () => {
        console.log("🟢 Conectado directamente al WebSocket del ESP32 de motores y servos.");
    };
 
    ws.onclose = () => {
        if (reconectando) return;
        reconectando = true;
        console.log("🔴 Desconectado del ESP32. Reintentando en 2 segundos...");
        setTimeout(conectarWS, 2000);
    };
 
    ws.onerror = (error) => {
        console.error("⚠️ Error en la conexión WebSocket con el ESP32:", error);
        ws.close();
    };
}
 
conectarWS();
 
// --- 1. ESTADOS VISUALES Y ANIMACIONES DE LA INTERFAZ ---
const estadosRobot = [
    { id: 'basura', texto: 'Recogiendo Basura' },
    { id: 'patrulla', texto: 'Patrullando' },
    { id: 'plantas', texto: 'Plantando Plantas' }
];
 
let indiceActual = 1;
let estaEnProceso = true;
 
const contenedorEstados = document.getElementById('contenedor-estados');
const animDino = document.getElementById('animacion-dino');
const animCarga = document.getElementById('animacion-carga');
 
function renderizarEstados() {
    if (!contenedorEstados) return;
    contenedorEstados.innerHTML = '';
 
    const indiceIzquierda = (indiceActual - 1 + estadosRobot.length) % estadosRobot.length;
    const indiceDerecha = (indiceActual + 1) % estadosRobot.length;
 
    const posiciones = [
        { index: indiceIzquierda, clase: 'posicion-izquierda' },
        { index: indiceActual, clase: 'posicion-centro' },
        { index: indiceDerecha, clase: 'posicion-derecha' }
    ];
 
    posiciones.forEach(pos => {
        const elemento = document.createElement('div');
        elemento.className = `tarjeta-estado ${pos.clase}`;
        elemento.textContent = estadosRobot[pos.index].texto;
        contenedorEstados.appendChild(elemento);
    });
}
 
function actualizarAnimacion() {
    if (!animDino || !animCarga) return;
    if (estaEnProceso) {
        animDino.classList.remove('oculto');
        animCarga.classList.add('oculto');
    } else {
        animDino.classList.add('oculto');
        animCarga.classList.remove('oculto');
    }
}
 
// --- 2. COMUNICACIÓN Y CONTROL DE POTENCIA (MOTORES AMARILLOS / ORUGAS) ---
// Reducido a 135 (aproximadamente 50% de potencia) para evitar tirones, 
// ahorrar batería y darle mayor precisión al robot.
const VELOCIDAD_MOTORES = 135;

function enviarComandoMovimiento(accion, pwm = VELOCIDAD_MOTORES) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        const payload = { accion: accion, pwm: pwm };
        ws.send(JSON.stringify(payload));
    } else {
        console.log("⚠️ WebSocket del ESP32 no disponible.");
    }
}

function moverServoDirecto(canal, grados) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        const payload = { canal: canal, angulo: grados };
        ws.send(JSON.stringify(payload));
        console.log(`📡 Enviando -> Canal: ${canal} | Ángulo: ${grados}°`);
    } else {
        console.log("⚠️ WebSocket del ESP32 no disponible para servos.");
    }
}

function bajarBrazo(parte) {
    if (parte === 'brazo_der') {
        moverServoDirecto(0, 80);
    } else if (parte === 'brazo_izq') {
        moverServoDirecto(1, 20);
    }
}

function brazoInicial(parte) {
    if (parte === 'brazo_der') {
        moverServoDirecto(0, 50);
    } else if (parte === 'brazo_izq') {
        moverServoDirecto(1, 50);
    }
}

function enviarServo(parte, estado) {
    if (parte === 'compuerta_der') {
        let angulo = (estado === 0) ? 10 : 90;
        moverServoDirecto(2, angulo);
    } else if (parte === 'compuerta_izq') {
        let angulo = (estado === 0) ? 85 : 5;
        moverServoDirecto(3, angulo);
    } else if (parte === 'compuerta') {
        let anguloDer = (estado === 0) ? 10 : 90;
        let anguloIzq = (estado === 0) ? 85 : 5;
        moverServoDirecto(2, anguloDer);
        moverServoDirecto(3, anguloIzq);
    }
}

// --- 🤖 CAPTURA DE FOTOS PARA EL DATASET ---
function capturarFotoDataset(etiqueta = 'basura') {
    const selectEtiqueta = document.getElementById('etiquetaSelect');
    if (selectEtiqueta && selectEtiqueta.value) {
        etiqueta = selectEtiqueta.value;
    }
 
    // Pide una foto estática a la cámara y la envía al backend de Java
    fetch(URL_CAPTURE, { cache: 'no-store' })
        .then(resp => {
            if (!resp.ok) throw new Error('La cámara respondió con estado ' + resp.status);
            return resp.blob();
        })
        .then(blob => {
            const formData = new FormData();
            formData.append('imagen', blob, 'captura.jpg');
            formData.append('etiqueta', etiqueta);
 
            return fetch(`${URL_BACKEND}/guardar-foto`, {
                method: 'POST',
                body: formData
            });
        })
        .then(response => response.text())
        .then(mensaje => alert(mensaje))
        .catch(error => alert('Error capturando la foto: ' + error));
}
 
// Mapeo de teclas
const teclasMapeo = {
    'w': { btn: 'btn-w', accion: () => enviarComandoMovimiento(2, VELOCIDAD_MOTORES) },
    's': { btn: 'btn-s', accion: () => enviarComandoMovimiento(8, VELOCIDAD_MOTORES) },
    'a': { btn: 'btn-a', accion: () => enviarComandoMovimiento(1, VELOCIDAD_MOTORES) },
    'd': { btn: 'btn-d', accion: () => enviarComandoMovimiento(3, VELOCIDAD_MOTORES) },
    'z': { btn: 'btn-z', accion: () => bajarBrazo('brazo_izq') },
    'x': { btn: 'btn-x', accion: () => bajarBrazo('brazo_der') },
    'c': { btn: 'btn-c', accion: () => enviarServo('compuerta', 0) },
    'v': { btn: 'btn-v', accion: () => enviarServo('compuerta', 90) },
    'f': { btn: 'btn-foto', accion: () => capturarFotoDataset('basura') }
};
 
window.addEventListener('keydown', (e) => {
    if (e.repeat) return;
    const k = e.key.toLowerCase();
    if (teclasMapeo[k]) {
        const item = teclasMapeo[k];
        const elemento = document.getElementById(item.btn);
 
        if (elemento && !elemento.classList.contains('pressed')) {
            elemento.classList.add('pressed');
            item.accion();
        } else if (!elemento) {
            item.accion();
        }
    }
});
 
window.addEventListener('keyup', (e) => {
    const k = e.key.toLowerCase();
    if (teclasMapeo[k]) {
        const item = teclasMapeo[k];
        const elemento = document.getElementById(item.btn);
        if (elemento) {
            elemento.classList.remove('pressed');
        }
        if (['w', 'a', 's', 'd'].includes(k)) {
            enviarComandoMovimiento(0, 0);
        }
        if (k === 'z') brazoInicial('brazo_izq');
        if (k === 'x') brazoInicial('brazo_der');
    }
});
 
window.addEventListener('mouseup', () => enviarComandoMovimiento(0, 0));
window.addEventListener('blur', () => enviarComandoMovimiento(0, 0));
 
setInterval(async () => {
    try {
        const res = await fetch(`${URL_BACKEND}/metricas`);
        const data = await res.json();
        if (document.getElementById('basura-count')) {
            document.getElementById('basura-count').innerText = data.basuraRecolectada;
        }
        if (document.getElementById('plantas-count')) {
            document.getElementById('plantas-count').innerText = data.plantasSembradas;
        }
    } catch (error) {}
}, 2000);
 
if (contenedorEstados) {
    renderizarEstados();
    actualizarAnimacion();
}