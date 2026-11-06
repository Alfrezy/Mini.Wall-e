# Fase 1: Compilar la aplicación con Maven y JDK 17
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Fase 2: Ejecutar la aplicación Java
FROM eclipse-temurin:17-jre
# Fuentes para dibujar el nombre y el porcentaje sobre los bounding boxes de la foto
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
