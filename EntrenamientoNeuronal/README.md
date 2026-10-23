# VisionArtificial

### Recuerda siempre actualiza la lista de paquetes
```sudo apt update```

---
### Creación de entorno virtual
Para trabajar es neseario la versión de python 3.12

``` apt install python3.12-venv``` <br>

```python3.12 -m venv venv```

```source venv/bin/activate```

---

### Modulo CV2 <br>

Para utilizar el modulo de CV2 es nesesario instalar libgl1 <br>

 ```sudo apt install -y libgl1```

---

### tf2onnx

La compilación de modelos densos/convolucionales nesecitan ser transformados, para ello son convertidos a ONNX que son archivos generales como json de las redes neuronales.

```python -m tf2onnx.convert --saved-model <NombreModelo> --output <NombreModelo>.onnx --opset 15```