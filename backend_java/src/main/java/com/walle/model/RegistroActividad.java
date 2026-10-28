package com.walle.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "registro_actividades")
public class RegistroActividad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(name = "basura_recolectada")
    private Integer basuraRecolectada;

    @Column(name = "plantas_plantadas")
    private Integer plantasPlantadas;

    @Column(name = "evento")
    private String evento;

    public RegistroActividad() {
        this.fechaHora = LocalDateTime.now();
    }

    public RegistroActividad(Integer basuraRecolectada, Integer plantasPlantadas, String evento) {
        this.basuraRecolectada = basuraRecolectada;
        this.plantasPlantadas = plantasPlantadas;
        this.evento = evento;
        this.fechaHora = LocalDateTime.now();
    }

    // Getters y Setters
    public Long getId() { return id; }
    public LocalDateTime getFechaHora() { return fechaHora; }
    public Integer getBasuraRecolectada() { return basuraRecolectada; }
    public Integer getPlantasPlantadas() { return plantasPlantadas; }
    public String getEvento() { return evento; }

    public void setId(Long id) { this.id = id; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
    public void setBasuraRecolectada(Integer basuraRecolectada) { this.basuraRecolectada = basuraRecolectada; }
    public void setPlantasPlantadas(Integer plantasPlantadas) { this.plantasPlantadas = plantasPlantadas; }
    public void setEvento(String evento) { this.evento = evento; }
}