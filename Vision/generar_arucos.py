import cv2
import os

# Carpeta de salida dentro de 'vision'
output_dir = "vision/marcadores_aruco"
os.makedirs(output_dir, exist_ok=True)

# Usar el diccionario ArUco estándar 4x4 (ideal para lectura rápida y nítida)
aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)

# Definición de los marcadores para tu pista de Mini Wall-E
marcadores = {
    10: "Cruce_Principal_ID10",
    1:  "Deposito_Basura_ID1",
    2:  "Deposito_Plantas_ID2",
    5:  "Tope_Giro180_ID5",
    6:  "Tope_Condicional_ID6"
}

print("Generando marcadores ArUco...")

for marker_id, nombre in marcadores.items():
    # Generar la imagen del marcador en alta resolución (500x500 píxeles)
    marker_image = cv2.aruco.generateImageMarker(aruco_dict, marker_id, 500)
    
    # Guardar la imagen en la carpeta vision/marcadores_aruco
    ruta_archivo = os.path.join(output_dir, f"marcador_{nombre}.png")
    cv2.imwrite(ruta_archivo, marker_image)
    print(f"✅ [ID {marker_id}] Guardado en: {ruta_archivo}")

print(f"\n🎉 ¡Listo! Revisa la ruta '{output_dir}'. Ya puedes abrir las imágenes, mandarlas a imprimir y pegarlas en tu pista.")