# Documentación de las Redes Neuronales

Este proyecto utiliza el modelo **YOLO (You Only Look Once)** para realizar la detección automática de objetos en imágenes, videos y transmisiones de cámaras en tiempo real.

## ¿Cómo funciona?

* **Una sola pasada:** A diferencia de otros métodos antiguos que escanean la imagen por partes o en varias etapas, YOLO analiza la imagen completa una sola vez usando una red neuronal convolucional (CNN).

* **Predicción simultánea:** Mientras procesa la imagen, predice al mismo tiempo dos cosas para cada objeto:
  1. La **clase** del objeto (por ejemplo: persona, auto, perro).

  2. Las **coordenadas** o caja delimitadora (*bounding box*) que marca exactamente dónde se encuentra en la imagen, junto con su nivel de probabilidad.
  
* **Alta velocidad:** Al hacerlo todo en un solo cálculo, el sistema es extremadamente rápido, lo que permite analizar video en directo con gran fluidez.
