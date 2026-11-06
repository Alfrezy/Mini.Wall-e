import cv2
import os
import imutils

compañeros = ["Roberto", "Rafael", "Fredi", "Adonay", "Odir"]
compaN = 4 #Cambiar por compañero jaja
pathCompañeros = []
path = "./Data"

#Creación de la data
for vichito in compañeros:
    personaPath = path + "/" + vichito 
    pathCompañeros.append(personaPath)
    #print(personaPath)
    if not os.path.exists(personaPath):
        os.makedirs(personaPath)

cap = cv2.VideoCapture( "./VideosdePrueba/"+compañeros[compaN] + ".mp4")
faceClassif = cv2.CascadeClassifier('./modelos/haarcascade_frontalface_default.xml')
count = 0
while True:
    ret,frame = cap.read()
    if ret == False: break
    frame = imutils.resize(frame, width=640)
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    auxFrame = frame.copy()
    k = cv2.waitKey(1)

    faces = faceClassif.detectMultiScale(gray, 1.2, 5)

    for(x,y,w,h) in faces:
        cv2.rectangle(frame, (x,y), (x+w,y+h), (0,255,0),2)
        rostro = auxFrame[y:y+h, x:x+w]
        rostro = cv2.resize(rostro, (96,96), interpolation = cv2.INTER_CUBIC)
        cv2.imwrite(pathCompañeros[compaN] +"/"+ "rostro_{}.jpg".format(count), rostro)
        count = count +1

    cv2.imshow("frame",frame)

    if k == 27 or count == 80:
        break


cap.release()
cv2.destroyAllWindows()