package com.walle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Clase de entrada principal para el Backend de Spring Boot del proyecto Mini WALL-E.
 * Se encarga de inicializar el servidor web embebido (Tomcat) y configurar 
 * automáticamente los componentes del framework.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // Arranca la aplicación Spring Boot y pone en marcha el servidor en el puerto 8080
        SpringApplication.run(BackendApplication.class, args);
    }
}