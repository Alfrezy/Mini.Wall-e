const tarjetas = document.querySelectorAll('.card-btn');
let indiceProcesoActual = 0; 

function actualizarInterfaz() {
    tarjetas.forEach((tarjeta, index) => {
        // Buscamos el formulario de los ojos y el loader dentro de cada tarjeta
        const outerFace = tarjeta.querySelector('.outer-face');
        const loader = tarjeta.querySelector('.lds-facebook');

        if (index === indiceProcesoActual) {
            // Tarjeta activa: Muestra los ojos de WALL-E y oculta el loader
            tarjeta.classList.add('activo');
            if (outerFace) outerFace.classList.remove('oculto');
            if (loader) loader.classList.add('oculto');
        } else {
            // Tarjetas inactivas: Oculta los ojos y muestra el loader de cargando
            tarjeta.classList.remove('activo');
            if (outerFace) outerFace.classList.add('oculto');
            if (loader) loader.classList.remove('oculto');
        }
    });
}

// Ejecutar al cargar la página
actualizarInterfaz();

// Cambiar de tarjeta automáticamente cada 5 segundos
setInterval(() => {
    indiceProcesoActual = (indiceProcesoActual + 1) % tarjetas.length;
    actualizarInterfaz();
}, 5000);