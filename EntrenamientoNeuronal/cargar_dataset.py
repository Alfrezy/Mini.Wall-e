import tensorflow as tf

IMG_SIZE = (128, 128)
BATCH_SIZE = 16

datos_entrenamiento = tf.keras.utils.image_dataset_from_directory(
    "dataset/entrenamiento",
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    shuffle=True
)

datos_validacion = tf.keras.utils.image_dataset_from_directory(
    "dataset/validacion",
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    shuffle=False
)

print("\nClases:")
print(datos_entrenamiento.class_names)

print("\nCantidad de lotes de entrenamiento:")
print(tf.data.experimental.cardinality(datos_entrenamiento).numpy())

print("\nCantidad de lotes de validación:")
print(tf.data.experimental.cardinality(datos_validacion).numpy())