# ms-eventomax-notify — Microservicio Consumidor RabbitMQ, Generación Documental OpenPDF y Notificaciones (EP2)

**Asignatura:** Desarrollo Cloud Native I (`DSY1107`)
**Caso de Estudio:** Caso 8 — Plataforma de Producción y Montaje de Eventos (**EventoMax**)
**Equipo:** Ignacio Valeria, Benjamín Flores, Benjamín Espinoza

---

## 1. Propósito del Repositorio y Rol en EventoMax

`ms-eventomax-notify` es el microservicio especializado en el procesamiento asíncrono de comandos operativos recibidos a través de **RabbitMQ** (`cmd.direct`) y en la generación de artefactos documentales y notificaciones de **EventoMax EP2**:

1. **Consumidor de Comandos de Cierre (`q.cmd.quote` / `quote.gen`):** Ensambla información real de dominio (producción, contacto del organizador con correo enmascarado, servicios contratados, disponibilidad referencial de catálogo y línea de tiempo transaccional Outbox) y genera un **Acta de Cierre y Resumen Económico en PDF empresarial** utilizando **OpenPDF (`com.github.librepdf:openpdf`)** con tipografías embebidas (`Roboto`) y branding corporativo.
2. **Consumidor de Tickets de Cuadrilla (`q.cmd.crew` / `crew.ticket`):** Genera y persiste en PostgreSQL (`crew_tickets`) los tickets técnicos de montaje asignados cuando una producción entra en estado `EN_EJECUCION`, exponiendo su consulta vía REST para el BFF y el frontend.
3. **Consumidor de Notificaciones por Correo (`q.cmd.email` / `email.send`):** Procesa notificaciones de transición de estado con reintentos exponenciales acotados (`ExponentialBackOffPolicy`), auditoría de entregas (`notification_delivery_audit`) y soporte dual explícito:
   - **Modo académico de simulación (`EVENTOMAX_NOTIFY_EMAIL_PROVIDER=logging`):** Registra la operación con la etiqueta `[EMAIL SIMULADO - MODO ACADEMICO]` y estado de auditoría `SIMULATED_LOGGING`, sin declarar falsamente un envío SMTP real.
   - **Modo SMTP real (`EVENTOMAX_NOTIFY_EMAIL_PROVIDER=smtp`):** Envía el correo mediante `JavaMailSender` hacia un servidor SMTP real o Mailpit accesible en red Docker (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`).
4. **Control Manual de Confirmaciones (`Channel.basicAck` / `Channel.basicNack`) e Idempotencia Persistente:** Garantiza procesamiento *at-least-once* efectivamente único mediante la tabla `processed_events` en PostgreSQL y derivación inmediata de mensajes corruptos o inválidos (`requeue=false`) hacia las **Dead Letter Queues (`*.dlq`)** a través de `cmd.dead.dlx`.

---

## 2. Arquitectura del Módulo y Diagrama

```mermaid
flowchart LR
    subgraph RabbitMQ Broker
        EX["Exchange: cmd.direct"]
        Q_QUOTE["q.cmd.quote"]
        Q_CREW["q.cmd.crew"]
        Q_EMAIL["q.cmd.email"]
        DLX["DLX: cmd.dead.dlx"]
        DLQ_QUOTE["q.cmd.quote.dlq"]
        DLQ_CREW["q.cmd.crew.dlq"]
        DLQ_EMAIL["q.cmd.email.dlq"]
    end

    subgraph ms-eventomax-notify
        L_QUOTE["QuoteCommandListener\n(MANUAL ACK/NACK)"]
        L_CREW["CrewTicketCommandListener\n(MANUAL ACK/NACK)"]
        L_EMAIL["EmailCommandListener\n(MANUAL ACK/NACK + Retry)"]
        IDEMP["DatabaseProcessedEventStore\n(processed_events)"]
        BUILDER["ProductionClosureDocumentBuilder\n+ CatalogEquipmentClient"]
        PDF["OpenPdfClosureDocumentGenerator\n(OpenPDF + Roboto + Branding)"]
        REST["NotifyQuoteController\nNotifyTicketController"]
        PG_NOTIFY[("PostgreSQL (Flyway V1)\nprocessed_events\ncrew_tickets\nnotification_delivery_audit")]
    end

    EX -->|quote.gen| Q_QUOTE --> L_QUOTE
    EX -->|crew.ticket| Q_CREW --> L_CREW
    EX -->|email.send| Q_EMAIL --> L_EMAIL
    L_QUOTE -.->|basicNack(requeue=false)| DLX --> DLQ_QUOTE
    L_CREW -.->|basicNack(requeue=false)| DLX --> DLQ_CREW
    L_EMAIL -.->|basicNack(requeue=false)| DLX --> DLQ_EMAIL
    L_QUOTE --> IDEMP --> PG_NOTIFY
    L_QUOTE --> BUILDER --> PDF
    L_CREW --> PG_NOTIFY
    L_EMAIL --> PG_NOTIFY
    REST --> PG_NOTIFY
```

---

## 3. Stack Tecnológico y Versiones Reales (`pom.xml`)

| Componente | Tecnología / Dependencia | Versión |
|---|---|---|
| Lenguaje | Java (Eclipse Temurin) | `25` |
| Framework Base | Spring Boot (`spring-boot-starter-parent`) | `4.1.1` |
| Mensajería AMQP | `spring-boot-starter-amqp` (RabbitMQ) | Boot `4.1.1` |
| Generación PDF | OpenPDF (`com.github.librepdf:openpdf`) | `2.0.3` |
| Persistencia | Spring Data JPA + PostgreSQL + Flyway (`V1`) | Boot `4.1.1` |
| Correo Electrónico | `spring-boot-starter-mail` (JavaMailSender) | Boot `4.1.1` |
| Almacenamiento Cloud | AWS SDK v2 S3 (`software.amazon.awssdk:s3`) | `2.31.7` |
| Testing | JUnit 5, Testcontainers (`rabbitmq:4.3.6-management`), PDFBox (`3.0.4`), Mockito, H2 | Boot `4.1.1` |

---

## 4. Cumplimiento de Rúbrica EP2 (Matriz Caso 8)

| Criterio de Evaluación EP2 | Implementación en `ms-eventomax-notify` | Estado |
|---|---|---|
| **Consumidor RabbitMQ (`@RabbitListener`)** | Tres consumidores activos (`EmailCommandListener`, `CrewTicketCommandListener`, `QuoteCommandListener`) escuchando `q.cmd.email`, `q.cmd.crew` y `q.cmd.quote`. | **CUMPLIDO** |
| **ACK y NACK Manual (`acknowledge-mode: manual`)** | Cada listener recibe `Channel` y `DELIVERY_TAG`. Confirma con `basicAck(tag, false)` tras persistir/generar, o rechaza con `basicNack(tag, false, false)` ante errores de validación o fallos definitivos. | **CUMPLIDO** |
| **Dead Letter Exchange (`cmd.dead.dlx`) y Colas DLQ** | Topología declarada en `RabbitMQTopologyConfig` con `x-dead-letter-exchange: cmd.dead.dlx` y routing keys dedicadas hacia `q.cmd.email.dlq`, `q.cmd.crew.dlq` y `q.cmd.quote.dlq`. Certificada con Testcontainers en `EmailRetryRabbitIT` (19 tests) y `QuoteCommandDlqLiveIT`. | **CUMPLIDO** |
| **Idempotencia ante Reentregas** | `DatabaseProcessedEventStore` persiste cada `eventId` procesado en `processed_events` (PostgreSQL). Duplicados reciben `basicAck` inmediato sin regenerar PDF ni reenviar correos. | **CUMPLIDO** |
| **Generación Documental Real (OpenPDF)** | `OpenPdfClosureDocumentGenerator` produce un documento PDF empresarial multipágina (>130 KB) con fuentes embebidas, resumen económico en USD, contacto minimizado y trazabilidad técnica Kafka/RabbitMQ/SHA-256 (`eventomax.notify.quote.provider=enterprise`). | **CUMPLIDO** |

---

## 5. Endpoints Expuestos (Consumo Interno desde BFF)

| Método | Ruta | Consumidor Principal | Descripción |
|---|---|---|---|
| `GET` | `/api/notify/quotes/{productionId}/pdf` | `ms-eventomax-bff` (`GET /api/productions/{id}/quote-pdf`) | Descarga el binario `application/pdf` del Acta de Cierre generada para la producción. |
| `GET` | `/api/notify/tickets/{productionId}` | `ms-eventomax-bff` (`GET /api/productions/{id}/crew-ticket`) | Devuelve el ticket de cuadrilla técnica emitido al pasar a `EN_EJECUCION`. |
| `GET` | `/actuator/health`, `/actuator/info` | Docker Healthcheck / Monitoreo | Estado de salud del microservicio y conectividad AMQP/DB. |

---

## 6. Variables de Entorno Requeridas (`.env.prod.example`)

| Variable | Ejemplo Placeholder | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://<POSTGRES_HOST>:5432/eventomax?currentSchema=notify` | URL JDBC PostgreSQL |
| `DB_USER` | `eventomax_prod` | Usuario PostgreSQL |
| `DB_PASSWORD` | `<SECRET_DB_PASSWORD>` | Contraseña PostgreSQL |
| `SPRING_RABBITMQ_ADDRESSES` | `rabbitmq-1:5672,rabbitmq-2:5672` | Nodos del clúster RabbitMQ en Docker/AWS |
| `RABBITMQ_USERNAME` | `<RABBITMQ_APP_USER>` | Usuario de aplicación RabbitMQ |
| `RABBITMQ_PASSWORD` | `<SECRET_RABBITMQ_PASSWORD>` | Contraseña RabbitMQ |
| `CATALOG_BASE_URL` | `http://ms-eventomax-catalog:8080` | URL de Catalog para enriquecimiento referencial de inventario |
| `EVENTOMAX_NOTIFY_EMAIL_PROVIDER` | `logging` (simulación académica) o `smtp` (envío SMTP real) | Proveedor de notificaciones por correo |
| `MAIL_HOST` / `MAIL_PORT` | `mailpit` / `1025` (o servidor SMTP real) | Servidor y puerto SMTP (nunca usar `127.0.0.1` dentro del contenedor salvo network host) |
| `NOTIFY_QUOTE_PROVIDER` | `enterprise` | Proveedor documental (`enterprise` para OpenPDF empresarial) |
| `NOTIFY_QUOTE_OUTPUT_DIR` | `/data/quotes` | Directorio de salida para actas PDF generadas |
| `STORAGE_LOCAL_BASE_DIR` | `/data/quotes` | Directorio base del proveedor de almacenamiento local |

---

## 7. Ejecución de Pruebas Automatizadas

```bash
# Ejecutar suite completa de pruebas unitarias, contractuales, PDF e integración RabbitMQ (102 tests)
./mvnw verify "-Dtest=*Test,*Tests,EmailRetryRabbitIT" -B
```

**Resultado certificado en EP2:** `Tests run: 102, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` (incluyendo los **19/19** casos de `EmailRetryRabbitIT` con Testcontainers `rabbitmq:4.3.6-management`).

---

## 8. Construcción y Despliegue Docker / Preparación Cloud

```bash
docker build -t ms-eventomax-notify:ep2 .
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Presupuesto de memoria configurado en `docker-compose.prod.yml`: `mem_limit: 640m` con `MaxRAMPercentage=65.0` y `HEALTHCHECK` activo contra `/actuator/health`.
