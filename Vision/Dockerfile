FROM python:3.10-slim

WORKDIR /app

# Instalar dependencias del sistema necesarias para OpenCV
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Instalar dependencias de Python
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copiar el código del módulo de visión
COPY . .

EXPOSE 5000
CMD ["python", "main_detector.py"]