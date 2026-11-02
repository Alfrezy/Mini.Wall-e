# VisionArtificial

Este proyecto contiene las herramientas necesarias para preparar datasets, entrenar modelos de detección de objetos con YOLO y realizar pruebas de visión artificial para el robot **Mini.Wall-e**.

## 1. Requisitos

- Python 3.12
- pip
- Entorno virtual de Python
- Git

Se recomienda utilizar Python 3.12 para mantener un entorno compatible con las librerías del proyecto.

## 2. Actualizar paquetes del sistema

En Ubuntu o GitHub Codespaces:

```bash
sudo apt update
```

## 3. Instalar dependencias del sistema

OpenCV puede necesitar la librería `libgl1` en entornos Linux.

```bash
sudo apt install -y python3.12-venv libgl1
```

## 4. Crear el entorno virtual

Desde la carpeta principal del proyecto:

```bash
python3.12 -m venv venv
```

Activar el entorno virtual:

```bash
source venv/bin/activate
```

Actualizar pip:

```bash
python -m pip install --upgrade pip
```

## 5. Instalar las dependencias de Python

El archivo `requirements.txt` contiene las librerías principales utilizadas por el proyecto.

```bash
pip install -r requirements.txt
```

## 6. Conversión de modelos

El proyecto utiliza modelos de YOLO en formato `.pt`.

Ultralytics permite exportar el modelo a otros formatos, como TensorFlow SavedModel y ONNX.

El proyecto cuenta con dos scripts para realizar estas conversiones:

Ubicaciones:
```
IABasura/modelos/pt_onnx.py
```
```
IAReconocimientoFacial/modelos/pt_onnx.py
```