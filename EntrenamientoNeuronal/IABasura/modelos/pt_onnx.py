from ultralytics import YOLO
modelo = YOLO('CNN_Basura.pt')
modelo.export(format='onnx', imgsz=640)