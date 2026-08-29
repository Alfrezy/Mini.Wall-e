CREATE TABLE registro_actividades (
    id SERIAL PRIMARY KEY,
    fecha_hora TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    basura_recolectada INT DEFAULT 0,
    plantas_plantadas INT DEFAULT 0
);
INSERT INTO registro_actividades (basura_recolectada, plantas_plantadas) VALUES (0, 0);