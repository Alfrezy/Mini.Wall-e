// El backend de Java está en el puerto 9090 (el 8080 es el panel manual)
const URL_BACKEND = "http://localhost:9090/api/robot/metricas";
const tarjetas = document.querySelectorAll('.card-btn');

// Qué tarjeta se enciende para cada estado que manda el backend
const TARJETA_POR_ESTADO = {
    "PATRULLANDO": 0,
    "RECOGIENDO_BASURA": 1,
    "BASURA": 1,
    "LLEVANDO_BASURA": 1,      // ya recogió la basura y la lleva al depósito
    "IR_A_SEMBRAR": 2,
    "PLANTANDO": 2
};

// El título de la tarjeta 2 cambia si ya la tiene adentro
const TITULO_BASURA = "Recogiendo Basura";
const TITULO_LLEVANDO = "Llevando Basura";

function actualizarInterfazPorEstado(estadoServidor) {
    const indiceProcesoActual = TARJETA_POR_ESTADO[estadoServidor] ?? 0;

    tarjetas.forEach((tarjeta, index) => {
        const outerFace = tarjeta.querySelector('.outer-face');
        const pacman = tarjeta.querySelector('.pac-man');

        if (index === indiceProcesoActual) {
            tarjeta.classList.add('activo');
            if (outerFace) outerFace.classList.remove('oculto');
            if (pacman) pacman.classList.add('oculto');
        } else {
            tarjeta.classList.remove('activo');
            if (outerFace) outerFace.classList.add('oculto');
            if (pacman) pacman.classList.remove('oculto');
        }
    });

    const tituloBasura = document.querySelector('#tarjeta-basura h3');
    if (tituloBasura) {
        tituloBasura.textContent = (estadoServidor === "LLEVANDO_BASURA") ? TITULO_LLEVANDO : TITULO_BASURA;
    }
}

async function sincronizarEstadoConJava() {
    try {
        const respuesta = await fetch(URL_BACKEND);
        if (!respuesta.ok) throw new Error('No se pudo conectar con el servidor Java');

        const datos = await respuesta.json();
        if (datos && datos.estadoActual) {
            actualizarInterfazPorEstado(datos.estadoActual);
        }
    } catch (error) {
        console.warn("⚠️ Servidor Java no disponible temporalmente. Manteniendo el último estado.", error);
    }
}

// Sincronizar al cargar y cada 1.5 segundos
sincronizarEstadoConJava();
setInterval(sincronizarEstadoConJava, 1500);
