FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/mini-walle-1.0.0.jar app.jar
ENV MODEL_PATH=/app/model/model.onnx
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
