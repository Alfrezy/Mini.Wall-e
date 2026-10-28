const URL_BACKEND = "http://localhost:8080/api/robot/metricas";
const tarjetas = document.querySelectorAll('.card-btn');

function actualizarInterfazPorEstado(estadoServidor) {
    let indiceProcesoActual = 0; 

    if (estadoServidor === "PATRULLANDO" || !estadoServidor) {
        indiceProcesoActual = 0;
    } else if (estadoServidor === "RECOGIENDO_BASURA" || estadoServidor === "BASURA") {
        indiceProcesoActual = 1;
    } else if (estadoServidor === "IR_A_SEMBRAR" || estadoServidor === "PLANTANDO") {
        indiceProcesoActual = 2;
    }

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
        console.warn("⚠️ Servidor Java no disponible temporalmente. Manteniendo Patrullando por defecto.", error);
    }
}

// Sincronizar al cargar y cada 1.5 segundos
sincronizarEstadoConJava();
setInterval(sincronizarEstadoConJava, 1500);