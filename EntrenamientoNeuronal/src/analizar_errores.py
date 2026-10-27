
from pathlib import Path
import numpy as np
import tensorflow as tf
import matplotlib.pyplot as plt
from PIL import Image, ImageOps


# Rutas del proyecto
BASE = Path(__file__).resolve().parent.parent
DATASET = BASE / "dataset" / "validacion"
MODELO = BASE / "modelos" / "aug0_bn0.9_s2_mejor_val_loss.keras"
SALIDA = BASE / "resultados"

CLASES = [
    "Caja",
    "bota",
    "boteAluminio",
    "papelHigienico",
]

TAMANO = (96, 96)
EXTENSIONES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def preparar_imagen(ruta):
    with Image.open(ruta) as img:
        img = ImageOps.exif_transpose(img)
        img = img.convert("L")

        img = ImageOps.pad(
            img,
            (96, 96),
            method=Image.LANCZOS,
            color=128
        )

        return np.asarray(img, dtype=np.float32)[..., None]

def cargar_imagen(ruta):
    imagen = tf.keras.utils.load_img(
        ruta,
        color_mode="grayscale",
        target_size=TAMANO,
    )
    array = tf.keras.utils.img_to_array(imagen)
    return array


def main():
    if not MODELO.exists():
        raise FileNotFoundError(f"No existe el modelo: {MODELO}")

    if not DATASET.exists():
        raise FileNotFoundError(f"No existe el dataset: {DATASET}")

    SALIDA.mkdir(parents=True, exist_ok=True)

    print("Cargando modelo...")
    modelo = tf.keras.models.load_model(MODELO)

    errores = []
    total = 0
    aciertos = 0

    for indice_real, clase_real in enumerate(CLASES):
        carpeta = DATASET / clase_real

        if not carpeta.exists():
            print(f"Advertencia: no existe {carpeta}")
            continue

        for ruta in sorted(carpeta.iterdir()):
            if ruta.suffix.lower() not in EXTENSIONES:
                continue

            try:
                imagen = preparar_imagen(ruta)
                entrada = np.expand_dims(imagen, axis=0)

                probabilidades = modelo.predict(entrada, verbose=0)[0]
                indice_predicho = int(np.argmax(probabilidades))
                confianza = float(probabilidades[indice_predicho])

                total += 1

                if indice_predicho == indice_real:
                    aciertos += 1
                else:
                    errores.append({
                        "ruta": ruta,
                        "real": clase_real,
                        "predicha": CLASES[indice_predicho],
                        "confianza": confianza,
                        "probabilidades": probabilidades,
                        "imagen": imagen,
                    })

            except Exception as error:
                print(f"Error leyendo {ruta}: {error}")

    print("\n========== RESUMEN ==========")
    print(f"Imágenes evaluadas: {total}")
    print(f"Aciertos: {aciertos}")
    print(f"Errores: {len(errores)}")

    if total:
        print(f"Accuracy: {aciertos / total:.2%}")

    informe = SALIDA / "errores_prediccion.txt"

    with informe.open("w", encoding="utf-8") as archivo:
        archivo.write("ANÁLISIS DE ERRORES - MINIWALL-E\n\n")
        archivo.write(f"Modelo: {MODELO.name}\n")
        archivo.write(f"Total evaluado: {total}\n")
        archivo.write(f"Aciertos: {aciertos}\n")
        archivo.write(f"Errores: {len(errores)}\n\n")

        for error in errores:
            archivo.write(f"Archivo: {error['ruta'].name}\n")
            archivo.write(f"Real: {error['real']}\n")
            archivo.write(f"Predicción: {error['predicha']}\n")
            archivo.write(
                f"Confianza predicha: {error['confianza']:.2%}\n"
            )

            for clase, prob in zip(
                CLASES, error["probabilidades"]
            ):
                archivo.write(f"  {clase}: {prob:.2%}\n")

            archivo.write("\n")

    if not errores:
        print("No se encontraron errores.")
        return

    # Mostrar como máximo 20 errores por imagen
    por_pagina = 20

    for inicio in range(0, len(errores), por_pagina):
        grupo = errores[inicio:inicio + por_pagina]
        columnas = 4
        filas = (len(grupo) + columnas - 1) // columnas

        fig, ejes = plt.subplots(
            filas,
            columnas,
            figsize=(14, 3.8 * filas),
        )
        ejes = np.array(ejes, dtype=object).reshape(-1)

        for eje, error in zip(ejes, grupo):
            eje.imshow(
                error["imagen"].squeeze(),
                cmap="gray",
                vmin=0,
                vmax=255,
            )
            eje.set_title(
                f"Real: {error['real']}\n"
                f"Pred: {error['predicha']} "
                f"({error['confianza']:.0%})",
                fontsize=9,
            )
            eje.axis("off")

        for eje in ejes[len(grupo):]:
            eje.axis("off")

        fig.tight_layout()
        numero = inicio // por_pagina + 1
        destino = SALIDA / f"errores_{numero}.png"
        fig.savefig(destino, dpi=140)
        plt.close(fig)

        print(f"Cuadrícula guardada: {destino}")

    print(f"Informe guardado: {informe}")


if __name__ == "__main__":
    main()
