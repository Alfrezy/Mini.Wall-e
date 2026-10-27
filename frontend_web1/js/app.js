// URL del backend de Java para consultar el estado y las métricas en tiempo real
const URL_BACKEND = "http://localhost:8080/api/robot/metricas";
const tarjetas = document.querySelectorAll('.card-btn');

function actualizarInterfazPorEstado(estadoServidor) {
    // Mapeamos el texto del estado de Java con el índice de tus tarjetas HTML:
    // 0: Patrullando | 1: Recogiendo Basura | 2: Plantando Plantas / Ir a Sembrar
    let indiceProcesoActual = 0; 

    if (estadoServidor === "PATRULLANDO") {
        indiceProcesoActual = 0;
    } else if (estadoServidor === "RECOGIENDO_BASURA" || estadoServidor === "BASURA") {
        indiceProcesoActual = 1;
    } else if (estadoServidor === "IR_A_SEMBRAR" || estadoServidor === "PLANTANDO") {
        indiceProcesoActual = 2;
    }

    tarjetas.forEach((tarjeta, index) => {
        // Buscamos los elementos visuales dentro de cada tarjeta
        const outerFace = tarjeta.querySelector('.outer-face');
        const loader = tarjeta.querySelector('.lds-facebook');

        if (index === indiceProcesoActual) {
            // Tarjeta activa: Muestra los ojos de WALL-E y oculta el loader
            tarjeta.classList.add('activo');
            if (outerFace) outerFace.classList.remove('oculto');
            if (loader) loader.classList.add('oculto');
        } else {
            // Tarjetas inactivas: Oculta los ojos y muestra el loader de carga
            tarjeta.classList.remove('activo');
            if (outerFace) outerFace.classList.add('oculto');
            if (loader) loader.classList.remove('oculto');
        }
    });
}

// Función que consulta el estado real directamente al servidor Spring Boot en Java
async function sincronizarEstadoConJava() {
    try {
        const respuesta = await fetch(URL_BACKEND);
        if (!respuesta.ok) throw new Error('No se pudo conectar con el servidor Java');
        
        const datos = await respuesta.json();
        
        // datos.estadoActual viene directamente de la máquina de estados en RobotController.java
        console.log("🤖 Estado actual del robot recibido de Java:", datos.estadoActual);
        
        actualizarInterfazPorEstado(datos.estadoActual);
    } catch (error) {
        console.warn("⚠️ Servidor Java no disponible temporalmente. Manteniendo estado visual predeterminado.", error);
    }
}

// Ejecutar sincronización al cargar la página por primera vez
sincronizarEstadoConJava();

// Consultar al servidor Java cada 1.5 segundos para reflejar los cambios instantáneamente en la web
setInterval(sincronizarEstadoConJava, 1500);