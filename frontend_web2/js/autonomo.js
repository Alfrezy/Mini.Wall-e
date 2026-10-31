// autonomo.js
// Botón MANUAL / AUTÓNOMO + botón de emergencia + estado en vivo.
// Habla con el backend de Java (puerto 9090). No toca nada de teclado.js.

const URL_ROBOT = "http://localhost:9090/api/robot";
let modoActual = "MANUAL";

function pintarModo() {
    const etiqueta = document.getElementById('modo-actual');
    const boton = document.getElementById('btn-modo');
    if (!etiqueta || !boton) return;

    if (modoActual === "AUTONOMO") {
        etiqueta.textContent = "🤖 AUTÓNOMO";
        etiqueta.style.color = "#22c55e";
        boton.textContent = "Pasar a MANUAL";
    } else {
        etiqueta.textContent = "🎮 MANUAL";
        etiqueta.style.color = "#f59e0b";
        boton.textContent = "Activar AUTÓNOMO";
    }
}

async function cambiarModo() {
    const nuevo = (modoActual === "AUTONOMO") ? "MANUAL" : "AUTONOMO";
    try {
        const res = await fetch(`${URL_ROBOT}/modo?valor=${nuevo}`, { method: 'POST' });
        const data = await res.json();
        modoActual = data.modo || modoActual;
        pintarModo();
    } catch (e) {
        alert("No se pudo conectar con el backend (puerto 9090).");
    }
}

async function detenerRobot() {
    try {
        await fetch(`${URL_ROBOT}/detener`, { method: 'POST' });
        modoActual = "MANUAL";
        pintarModo();
    } catch (e) {
        alert("No se pudo conectar con el backend (puerto 9090).");
    }
}

async function soltarBasura() {
    const boton = document.getElementById('btn-soltar');
    if (boton) { boton.disabled = true; boton.textContent = "Soltando..."; }
    try {
        await fetch(`${URL_ROBOT}/soltar-basura`, { method: 'POST' });
    } catch (e) {
        alert("No se pudo conectar con el backend (puerto 9090).");
    }
    if (boton) { boton.disabled = false; boton.textContent = "🗑️ Soltar basura"; }
}

async function refrescarEstado() {
    try {
        const res = await fetch(`${URL_ROBOT}/metricas`);
        const d = await res.json();
        modoActual = d.modo || modoActual;
        pintarModo();

        const estado = document.getElementById('estado-robot');
        if (estado) {
            estado.innerHTML =
                `ESP32 (motores): <b>${d.esp32Conectado ? '🟢 conectada' : '🔴 sin conexión'}</b><br>` +
                `Modelo IA: <b>${d.modeloCargado ? '🟢 cargado' : '🔴 no cargado'}</b><br>` +
                `Estado: <b>${d.estadoActual}</b> · Basura: <b>${d.basuraRecolectada}</b> · Plantas: <b>${d.plantasSembradas}</b><br>` +
                `Vi: <b>${d.ultimaDeteccion}</b><br>` +
                `Acción: <b>${d.ultimaAccion}</b>`;
        }
    } catch (e) {
        const estado = document.getElementById('estado-robot');
        if (estado) estado.innerHTML = "🔴 Backend sin conexión (puerto 9090)";
    }
}

// En modo AUTÓNOMO se bloquea el teclado para que no choque con las órdenes del backend.
// Se registra en la fase de captura (true) para ejecutarse antes que los listeners de teclado.js.
const TECLAS_BLOQUEADAS = ['w', 'a', 's', 'd', 'z', 'x', 'c', 'v'];
function bloquearTeclado(e) {
    if (modoActual === "AUTONOMO" && TECLAS_BLOQUEADAS.includes(e.key.toLowerCase())) {
        e.stopPropagation();
        e.preventDefault();
    }
}
window.addEventListener('keydown', bloquearTeclado, true);
window.addEventListener('keyup', bloquearTeclado, true);

refrescarEstado();
setInterval(refrescarEstado, 1500);
