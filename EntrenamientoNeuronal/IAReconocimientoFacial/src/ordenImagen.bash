i=0

for imagen in $(ls -v rostro_*.jpg); do
    mv "$imagen" "temp_$i.jpg"
    ((i++))
done

i=0

for imagen in $(ls -v temp_*.jpg); do
    mv "$imagen" "rostro_$i.jpg"
    ((i++))
done

echo "Se ordenaron $i imágenes correctamente."
#Ejecutar en la ruta raiz despues de limpiar los dataset mal hechos