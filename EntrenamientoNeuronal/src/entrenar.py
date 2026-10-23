
from pathlib import Path
from datetime import datetime
import json
import shutil

import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers


# ============================================================
# 1. CONFIGURACION GENERAL
# ============================================================

BASE_DIR = Path(__file__).resolve().parent.parent

TRAIN_DIR = BASE_DIR / "dataset" / "entrenamiento"
VAL_DIR = BASE_DIR / "dataset" / "validacion"

MODELOS_DIR = BASE_DIR / "modelos"
LOGS_DIR = BASE_DIR / "logs" / "entrenamiento"

CLASES = [
    "Caja",
    "bota",
    "boteAluminio",
    "papelHigienico",
]

NUM_CLASES = len(CLASES)

IMG_SIZE = (96, 96)
BATCH_SIZE = 8
EPOCAS = 100
SEMILLA = 42

AUTOTUNE = tf.data.AUTOTUNE

# Reproducibilidad
keras.utils.set_random_seed(SEMILLA)

MODELOS_DIR.mkdir(parents=True, exist_ok=True)
LOGS_DIR.mkdir(parents=True, exist_ok=True)

# Cada entrenamiento tendrá su propia carpeta
FECHA = datetime.now().strftime("%Y%m%d_%H%M%S")

RUN_DIR = MODELOS_DIR / f"run_{FECHA}"
LOG_DIR = LOGS_DIR / f"run_{FECHA}"

RUN_DIR.mkdir(parents=True, exist_ok=True)
LOG_DIR.mkdir(parents=True, exist_ok=True)

MEJOR_MODELO_PATH = RUN_DIR / "mejor_modelo.keras"
MODELO_FINAL_PATH = RUN_DIR / "modelo_final.keras"
RESULTADOS_PATH = RUN_DIR / "resultados_entrenamiento.json"


# ============================================================
# 2. VALIDACION DEL DATASET
# ============================================================

EXTENSIONES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def contar_imagenes(carpeta):
    return sum(
        1
        for archivo in carpeta.iterdir()
        if archivo.is_file() and archivo.suffix.lower() in EXTENSIONES
    )


def validar_dataset():
    for carpeta in (TRAIN_DIR, VAL_DIR):
        if not carpeta.exists():
            raise FileNotFoundError(
                f"No existe la carpeta del dataset: {carpeta}"
            )

        for clase in CLASES:
            ruta_clase = carpeta / clase

            if not ruta_clase.exists():
                raise FileNotFoundError(
                    f"Falta la carpeta de la clase: {ruta_clase}"
                )

            cantidad = contar_imagenes(ruta_clase)

            if cantidad == 0:
                raise ValueError(
                    f"No hay imagenes en la carpeta: {ruta_clase}"
                )

    print("\n" + "=" * 60)
    print("DATASET")
    print("=" * 60)

    conteos = {}

    for nombre, carpeta in (
        ("ENTRENAMIENTO", TRAIN_DIR),
        ("VALIDACION", VAL_DIR),
    ):
        print(f"\n{nombre}:")

        conteos[nombre.lower()] = {}

        for clase in CLASES:
            cantidad = contar_imagenes(carpeta / clase)
            conteos[nombre.lower()][clase] = cantidad

            print(f"  {clase}: {cantidad} imagenes")

    return conteos


# ============================================================
# 3. CARGA DE IMAGENES EN ESCALA DE GRISES
# ============================================================

def cargar_dataset(carpeta, entrenamiento=False):
    return keras.utils.image_dataset_from_directory(
        str(carpeta),
        labels="inferred",
        label_mode="int",
        class_names=CLASES,
        color_mode="grayscale",
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        shuffle=entrenamiento,
        seed=SEMILLA if entrenamiento else None,
    )


conteos = validar_dataset()

# Dataset original de entrenamiento: puede tener barajado.
train_original = cargar_dataset(
    TRAIN_DIR,
    entrenamiento=True,
)

# Se utiliza para evaluar el entrenamiento SIN aumentos.
train_eval = cargar_dataset(
    TRAIN_DIR,
    entrenamiento=False,
)

# Validacion: siempre con imagenes originales.
val_ds = cargar_dataset(
    VAL_DIR,
    entrenamiento=False,
)

print("\nClases detectadas:", train_original.class_names)
print("Forma de entrada esperada: (96, 96, 1)")


# ============================================================
# 4. AUMENTO DE DATOS
# ============================================================

# Las transformaciones se aplican durante el entrenamiento.
# No se aplican al evaluar train_eval ni val_ds.

aumento_datos = keras.Sequential(
    [
        layers.RandomFlip("horizontal"),
        layers.RandomRotation(0.06),
        layers.RandomZoom(0.12),
        layers.RandomTranslation(0.08, 0.08),
        layers.RandomContrast(0.12),
    ],
    name="aumento_datos",
)

train_ds = train_original.map(
    lambda imagenes, etiquetas: (
        aumento_datos(imagenes, training=True),
        etiquetas,
    ),
    num_parallel_calls=AUTOTUNE,
)

train_ds = train_ds.prefetch(AUTOTUNE)
train_eval = train_eval.prefetch(AUTOTUNE)
val_ds = val_ds.prefetch(AUTOTUNE)


# ============================================================
# 5. PESOS PARA CLASES DESBALANCEADAS
# ============================================================

cantidades_train = conteos["entrenamiento"]

total_train = sum(cantidades_train.values())

class_weights = {
    indice: total_train / (NUM_CLASES * cantidades_train[clase])
    for indice, clase in enumerate(CLASES)
}

print("\nPesos de las clases:")

for indice, clase in enumerate(CLASES):
    print(f"  {clase}: {class_weights[indice]:.4f}")


# ============================================================
# 6. CONSTRUCCION DE LA RED NEURONAL
# ============================================================

entrada = keras.Input(
    shape=(96, 96, 1),
    name="imagen_gris",
)

# Los pixeles pasan de [0, 255] a [0, 1].
x = layers.Rescaling(1.0 / 255.0)(entrada)

# Bloque convolucional 1: 16 filtros
x = layers.Conv2D(
    16,
    kernel_size=3,
    padding="same",
    activation="relu",
    name="conv_16",
)(x)

x = layers.MaxPooling2D(pool_size=2)(x)

# Bloque convolucional 2: 32 filtros
x = layers.SeparableConv2D(
    32,
    kernel_size=3,
    padding="same",
    activation="relu",
    name="conv_32",
)(x)

x = layers.MaxPooling2D(pool_size=2)(x)

# Bloque convolucional 3: 48 filtros
x = layers.SeparableConv2D(
    48,
    kernel_size=3,
    padding="same",
    activation="relu",
    name="conv_48",
)(x)

x = layers.MaxPooling2D(pool_size=2)(x)

# Bloque convolucional 4: 64 filtros
x = layers.SeparableConv2D(
    64,
    kernel_size=3,
    padding="same",
    activation="relu",
    name="conv_64",
)(x)

# Resume cada mapa de caracteristicas.
x = layers.GlobalAveragePooling2D()(x)

# Clasificador
x = layers.Dense(
    32,
    activation="relu",
    name="capa_densa",
)(x)

x = layers.Dropout(0.30)(x)

salida = layers.Dense(
    NUM_CLASES,
    activation="softmax",
    name="clasificacion",
)(x)

modelo = keras.Model(
    inputs=entrada,
    outputs=salida,
    name="Mini_Wall_e_CNN_Grises",
)


# ============================================================
# 7. COMPILACION
# ============================================================

modelo.compile(
    optimizer=keras.optimizers.Adam(
        learning_rate=0.0003,
    ),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

print("\n" + "=" * 60)
print("ARQUITECTURA DE LA RED")
print("=" * 60)

modelo.summary()

print(
    "\nParametros entrenables:",
    f"{modelo.count_params():,}",
)


# ============================================================
# 8. CALLBACKS
# ============================================================

callbacks = [
    keras.callbacks.ModelCheckpoint(
        filepath=str(MEJOR_MODELO_PATH),
        monitor="val_loss",
        mode="min",
        save_best_only=True,
        verbose=1,
    ),

    keras.callbacks.TensorBoard(
        log_dir=str(LOG_DIR),
        histogram_freq=0,
        write_graph=True,
        update_freq="epoch",
    ),

    keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss",
        factor=0.5,
        patience=6,
        min_lr=0.00001,
        verbose=1,
    ),

    keras.callbacks.EarlyStopping(
        monitor="val_loss",
        patience=20,
        restore_best_weights=True,
        verbose=1,
    ),

    keras.callbacks.TerminateOnNaN(),
]


# ============================================================
# 9. ENTRENAMIENTO
# ============================================================

print("\n" + "=" * 60)
print("INICIANDO ENTRENAMIENTO")
print("=" * 60)

historial = modelo.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCAS,
    class_weight=class_weights,
    callbacks=callbacks,
    verbose=1,
)

# EarlyStopping restaura los mejores pesos de la ejecucion.
# Se guardan como modelo final de este experimento.
modelo.save(MODELO_FINAL_PATH)


# ============================================================
# 10. EVALUACION SIN AUMENTO DE DATOS
# ============================================================

print("\n" + "=" * 60)
print("EVALUACION DEL MODELO")
print("=" * 60)

train_loss, train_accuracy = modelo.evaluate(
    train_eval,
    verbose=0,
)

val_loss, val_accuracy = modelo.evaluate(
    val_ds,
    verbose=0,
)

print("\nEntrenamiento sin aumento:")
print(f"  Loss:     {train_loss:.4f}")
print(f"  Accuracy: {train_accuracy * 100:.2f}%")

print("\nValidacion:")
print(f"  Loss:     {val_loss:.4f}")
print(f"  Accuracy: {val_accuracy * 100:.2f}%")


# ============================================================
# 11. MATRIZ DE CONFUSION
# ============================================================

etiquetas_reales = []
etiquetas_predichas = []
probabilidades = []

for imagenes, etiquetas in val_ds:
    predicciones = modelo.predict(imagenes, verbose=0)

    etiquetas_reales.extend(etiquetas.numpy().tolist())
    etiquetas_predichas.extend(
        np.argmax(predicciones, axis=1).tolist()
    )
    probabilidades.extend(predicciones.tolist())

matriz = np.zeros(
    (NUM_CLASES, NUM_CLASES),
    dtype=int,
)

for real, predicha in zip(
    etiquetas_reales,
    etiquetas_predichas,
):
    matriz[real, predicha] += 1

print("\nMatriz de confusion:")
print("Filas = clase real; columnas = clase predicha")
print("Orden:", CLASES)
print(matriz)

print("\nAciertos por clase:")

for indice, clase in enumerate(CLASES):
    total_clase = int(matriz[indice].sum())
    aciertos_clase = int(matriz[indice, indice])

    precision_clase = (
        aciertos_clase / total_clase * 100
        if total_clase > 0
        else 0.0
    )

    print(
        f"  {clase}: {aciertos_clase}/{total_clase} "
        f"({precision_clase:.2f}%)"
    )


# ============================================================
# 12. GUARDAR RESULTADOS DEL EXPERIMENTO
# ============================================================

mejor_epoca = (
    int(np.argmin(historial.history["val_loss"])) + 1
)

resultados = {
    "experimento": RUN_DIR.name,
    "clases": CLASES,
    "imagen": {
        "ancho": IMG_SIZE[0],
        "alto": IMG_SIZE[1],
        "canales": 1,
        "formato": "grayscale",
    },
    "filtros_convolucionales": [16, 32, 48, 64],
    "batch_size": BATCH_SIZE,
    "epocas_maximas": EPOCAS,
    "epocas_ejecutadas": len(historial.history["loss"]),
    "mejor_epoca_por_val_loss": mejor_epoca,
    "mejor_val_loss": float(
        min(historial.history["val_loss"])
    ),
    "train_loss_sin_aumento": float(train_loss),
    "train_accuracy_sin_aumento": float(train_accuracy),
    "val_loss": float(val_loss),
    "val_accuracy": float(val_accuracy),
    "parametros": int(modelo.count_params()),
    "conteos": conteos,
    "matriz_confusion": matriz.tolist(),
    "log_dir": str(LOG_DIR),
}

with open(RESULTADOS_PATH, "w", encoding="utf-8") as archivo:
    json.dump(
        resultados,
        archivo,
        ensure_ascii=False,
        indent=4,
    )


# ============================================================
# 13. COPIAS DE ACCESO RAPIDO
# ============================================================

# Los experimentos anteriores se conservan en sus carpetas.
# Estas copias apuntan a la ejecucion mas reciente.

shutil.copy2(
    MEJOR_MODELO_PATH,
    MODELOS_DIR / "mejor_modelo.keras",
)

shutil.copy2(
    MODELO_FINAL_PATH,
    MODELOS_DIR / "modelo_final.keras",
)

with open(
    MODELOS_DIR / "resultados_entrenamiento.json",
    "w",
    encoding="utf-8",
) as archivo:
    json.dump(
        resultados,
        archivo,
        ensure_ascii=False,
        indent=4,
    )


# ============================================================
# 14. RESUMEN FINAL
# ============================================================

print("\n" + "=" * 60)
print("ENTRENAMIENTO FINALIZADO")
print("=" * 60)

print(f"Experimento:       {RUN_DIR}")
print(f"Mejor modelo:      {MEJOR_MODELO_PATH}")
print(f"Modelo final:      {MODELO_FINAL_PATH}")
print(f"Resultados:        {RESULTADOS_PATH}")
print(f"TensorBoard:       {LOG_DIR}")

print(f"\nAccuracy validacion: {val_accuracy * 100:.2f}%")
print(f"Mejor epoca:         {mejor_epoca}")
print(f"Parametros:          {modelo.count_params():,}")

print("\nModelos de acceso rapido:")
print(f"  {MODELOS_DIR / 'mejor_modelo.keras'}")
print(f"  {MODELOS_DIR / 'modelo_final.keras'}")

print("\nImportante: este modelo clasifica imagenes completas.")
print("Todavia no genera cajas delimitadoras ni coordenadas.")
