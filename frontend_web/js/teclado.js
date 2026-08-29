const URL_BACKEND = "http://localhost:8080/api/robot";

// 1. Control del robot con teclado
document.addEventListener('keydown', (e) => {
    const tecla = e.key.toLowerCase();
    
    // Movimiento (Orugas)
    if (tecla === 'w') enviarComando('/mover?direccion=ADELANTE');
    if (tecla === 's') enviarComando('/mover?direccion=ATRAS');
    if (tecla === 'a') enviarComando('/mover?direccion=IZQUIERDA');
    if (tecla === 'd') enviarComando('/mover?direccion=DERECHA');
    
    // Cámara
    if (tecla === 'q') enviarComando('/servo?parte=camara&angulo=120'); // Arriba
    if (tecla === 'e') enviarComando('/servo?parte=camara&angulo=60');  // Abajo
    
    // Brazos
    if (tecla === 'z') enviarComando('/servo?parte=brazo_izq&angulo=180');
    if (tecla === 'x') enviarComando('/servo?parte=brazo_der&angulo=180');
});

// Función para enviar la orden al servidor Java
function enviarComando(ruta) {
    fetch(URL_BACKEND + ruta, { method: 'POST' })
        .catch(err => console.error("Error enviando comando", err));
}

// 2. Botón de capturar foto
document.getElementById('btn-foto').addEventListener('click', () => {
    fetch(URL_BACKEND + '/capturar-foto?etiqueta=dataset', { method: 'POST' })
        .then(res => res.text())
        .then(msg => alert("¡Foto guardada para la IA!"));
});

// 3. Actualizar contadores de Basura y Plantas cada 2 segundos
setInterval(async () => {
    try {
        const res = await fetch(URL_BACKEND + '/metricas');
        const data = await res.json();
        document.getElementById('basura-count').innerText = data.basuraRecolectada;
        document.getElementById('plantas-count').innerText = data.plantasSembradas;
    } catch (error) {
        console.log("Esperando conexión con Java...");
    }
}, 2000);