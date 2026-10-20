# ms-eventomax-notify — Microservicio Consumidor RabbitMQ, Generación Documental OpenPDF y Notificaciones (EP2)

**Asignatura:** Desarrollo Cloud Native I (`DSY1107`)
**Caso de Estudio:** Caso 8 — Plataforma de Producción y Montaje de Eventos (**EventoMax**)
**Equipo:** Ignacio Valeria, Benjamín Flores, Benjamín Espinoza

---

## 1. Propósito del Repositorio y Rol en EventoMax

`ms-eventomax-notify` es el microservicio especializado en el procesamiento asíncrono de comandos operativos recibidos a través de **RabbitMQ** (`cmd.direct`) y en la generación de artefactos documentales y notificaciones de **EventoMax EP2**:

1. **Consumidor de Comandos de Cierre (`q.cmd.quote` / `quote.gen`):** Ensambla información real de dominio (producción, contacto del organizador con correo enmascarado, servicios contratados, disponibilidad referencial de catálogo y línea de tiempo transaccional Outbox) y genera un **Acta de Cierre y Resumen Económico en PDF empresarial** utilizando **OpenPDF (`com.github.librepdf:openpdf`)** con tipografías embebidas (`Roboto`) y branding corporativo.
2. **Consumidor de Tickets de Cuadrilla (`q.cmd.crew` / `crew.ticket`):** Genera y persiste en PostgreSQL (`crew_tickets`) los tickets técnicos de montaje asignados cuando una producción entra en estado `EN_EJECUCION`, exponiendo su consulta vía REST para el BFF y el frontend.
3. **Consumidor de Notificaciones por Correo (`q.cmd.email` / `email.send`):** Procesa notificaciones de transición de estado con reintentos exponenciales acotados (`ExponentialBackOffPolicy`), auditoría de entregas (`notification_delivery_audit`) y soporte dual (`logging` seguro por defecto o `smtp` real).
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
| Testing | JUnit 5, PDFBox (`3.0.4`, verificación de texto PDF en tests), Mockito, H2 | Boot `4.1.1` |

---

## 4. Cumplimiento de Rúbrica EP2 (Matriz Caso 8)

| Criterio de Evaluación EP2 | Implementación en `ms-eventomax-notify` | Estado |
|---|---|---|
| **Consumidor RabbitMQ (`@RabbitListener`)** | Tres consumidores activos (`EmailCommandListener`, `CrewTicketCommandListener`, `QuoteCommandListener`) escuchando `q.cmd.email`, `q.cmd.crew` y `q.cmd.quote`. | **CUMPLIDO** |
| **ACK y NACK Manual (`acknowledge-mode: manual`)** | Cada listener recibe `Channel` y `DELIVERY_TAG`. Confirma con `basicAck(tag, false)` tras persistir/generar, o rechaza con `basicNack(tag, false, false)` ante errores de validación o fallos definitivos. | **CUMPLIDO** |
| **Dead Letter Exchange (`cmd.dead.dlx`) y Colas DLQ** | Topología declarada en `RabbitMQTopologyConfig` con `x-dead-letter-exchange: cmd.dead.dlx` y routing keys dedicadas hacia `q.cmd.email.dlq`, `q.cmd.crew.dlq` y `q.cmd.quote.dlq`. Certificada con broker real en `QuoteCommandDlqLiveIT`. | **CUMPLIDO** |
| **Idempotencia ante Reentregas** | `DatabaseProcessedEventStore` persiste cada `eventId` procesado en `processed_events` (PostgreSQL). Duplicados reciben `basicAck` inmediato sin regenerar PDF ni reenviar correos. | **CUMPLIDO** |
| **Generación Documental Real (OpenPDF)** | `OpenPdfClosureDocumentGenerator` produce un documento PDF empresarial multipágina (>130 KB) con fuentes embebidas, resumen económico en USD, contacto minimizado y trazabilidad técnica Kafka/RabbitMQ/SHA-256. | **CUMPLIDO** |

---

## 5. Endpoints Expuestos (Consumo Interno desde BFF)

| Método | Ruta | Consumidor Principal | Descripción |
|---|---|---|---|
| `GET` | `/api/notify/quotes/{productionId}/pdf` | `ms-eventomax-bff` (`GET /api/productions/{id}/quote-pdf`) | Descarga el binario `application/pdf` del Acta de Cierre generada para la producción. |
| `GET` | `/api/notify/tickets/{productionId}` | `ms-eventomax-bff` (`GET /api/productions/{id}/crew-ticket`) | Devuelve el ticket de cuadrilla técnica emitido al pasar a `EN_EJECUCION`. |
| `GET` | `/actuator/health`, `/actuator/info` | Docker Healthcheck / Monitoreo | Estado de salud del microservicio y conectividad AMQP/DB. |

---

## 6. Seguridad y Privacidad Documental

- `ms-eventomax-notify` opera en la red interna `eventomax-net` sin exposición directa a Internet; todo acceso HTTP externo pasa por **AWS API Gateway (JWT Authorizer)** y **`ms-eventomax-bff`**, el cual valida el token JWT de Microsoft Entra ID y verifica la propiedad de la producción (`checkProductionOwnership`) antes de solicitar el PDF o el ticket.
- El Acta PDF aplica **minimización de datos personales**: el correo del organizador se renderiza enmascarado (`cl****************@eventomax.cl`) y los montos financieros se expresan consistentemente en `USD`.

---

## 7. Integración con RabbitMQ

### Topología Declarada (`RabbitMQTopologyConfig`)
- **Exchanges:**
  - `cmd.direct` (`DirectExchange`, durable)
  - `cmd.dead.dlx` (`DirectExchange`, durable)
- **Colas Operativas y Bindings (`cmd.direct`):**
  - `q.cmd.email` ← `email.send` (DLX: `cmd.dead.dlx`, DLK: `email.send.dlq`)
  - `q.cmd.crew` ← `crew.ticket` (DLX: `cmd.dead.dlx`, DLK: `crew.ticket.dlq`)
  - `q.cmd.quote` ← `quote.gen` (DLX: `cmd.dead.dlx`, DLK: `quote.gen.dlq`)
- **Colas Dead Letter (`cmd.dead.dlx`):**
  - `q.cmd.email.dlq` ← `email.send.dlq`
  - `q.cmd.crew.dlq` ← `crew.ticket.dlq`
  - `q.cmd.quote.dlq` ← `quote.gen.dlq`

### Flujo de ACK / NACK y DLQ
1. El listener deserializa el sobre JSON `MessageEnvelope<T>` usando Jackson 3 (`tools.jackson.databind.ObjectMapper`).
2. Si el JSON es inválido, el `type` no coincide con el esperado o faltan campos obligatorios (`eventId`, `productionId`, `status`), el listener invoca `channel.basicNack(deliveryTag, false, false)`.
3. RabbitMQ encamina automáticamente el mensaje rechazado hacia `cmd.dead.dlx`, depositándolo en la cola `*.dlq` correspondiente con la cabecera `x-death` (`reason: rejected`).

---

## 8. Base de Datos y Migraciones Flyway

Archivo: `src/main/resources/db/migration/V1__create_notify_tables.sql`

| Tabla | Propósito |
|---|---|
| `processed_events` | Registro de idempotencia (`event_id` PK, `consumer_name`, `processed_at`) para evitar efectos duplicados. |
| `crew_tickets` | Almacena tickets técnicos generados por `q.cmd.crew` (`ticket_code`, `production_id`, `production_name`, `scheduled_at`, `location`, `event_id`). |
| `notification_delivery_audit` | Bitácora de envíos y fallos de notificaciones por correo (`event_id`, `production_id`, `recipient`, `status`, `provider`, `error_message`). |

---

## 9. Variables de Entorno Requeridas

| Variable | Ejemplo Placeholder | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/eventomax_notify` | URL JDBC PostgreSQL |
| `DB_USER` | `notify_user` | Usuario PostgreSQL |
| `DB_PASSWORD` | `<SECRET_DB_PASSWORD>` | Contraseña PostgreSQL |
| `SPRING_RABBITMQ_ADDRESSES` | `rabbitmq-1:5672,rabbitmq-2:5672` | Nodos del clúster RabbitMQ en Docker/AWS |
| `RABBITMQ_HOST` | `localhost` | Host RabbitMQ individual (fallback local) |
| `RABBITMQ_PORT` | `5672` (o `25692` en demo local) | Puerto AMQP |
| `RABBITMQ_VHOST` | `/` | Virtual host RabbitMQ |
| `RABBITMQ_USERNAME` | `<RABBITMQ_APP_USER>` | Usuario de aplicación RabbitMQ |
| `RABBITMQ_PASSWORD` | `<SECRET_RABBITMQ_PASSWORD>` | Contraseña RabbitMQ |
| `CATALOG_BASE_URL` | `http://ms-eventomax-catalog:8080` | URL de Catalog para enriquecimiento referencial de inventario |
| `NOTIFY_QUOTE_OUTPUT_DIR` | `/data/quotes` | Directorio de salida para actas PDF generadas |
| `STORAGE_LOCAL_BASE_DIR` | `/data/quotes` | Directorio base del proveedor de almacenamiento local |

---

## 10. Ejecución Local Paso a Paso

```bash
export DB_URL="jdbc:postgresql://localhost:5432/eventomax_notify"
export DB_USER="postgres"
export DB_PASSWORD="<TU_PASSWORD_LOCAL>"
export RABBITMQ_HOST="localhost"
export RABBITMQ_PORT="25692"
export RABBITMQ_USERNAME="eventomax-app"
export RABBITMQ_PASSWORD="<TU_PASSWORD_RABBIT>"
export CATALOG_BASE_URL="http://localhost:8080"
export NOTIFY_QUOTE_OUTPUT_DIR="./data/quotes"
export STORAGE_LOCAL_BASE_DIR="./data/quotes"

mvn spring-boot:run
```

---

## 11. Ejecución de Pruebas Automatizadas

```bash
# Ejecutar suite completa de pruebas unitarias, contractuales y de generación PDF (82 tests)
mvn clean test

# Ejecutar prueba de integración en vivo de NACK y DLQ contra broker real
mvn test -Dtest=QuoteCommandDlqLiveIT
```

**Resultado certificado en EP2:** `Tests run: 82, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS`.

---

## 12. Construcción y Despliegue Docker / Preparación Cloud

```bash
# Construir imagen multi-stage con usuario no-root y volumen /data/quotes
docker build -t ms-eventomax-notify:ep2 .

# Levantar en la red compartida eventomax-net
docker compose -f docker-compose.prod.yml up -d --build
```

---

## 13. Estructura del Proyecto

```text
ms-eventomax-notify/
├── src/main/java/cl/duoc/eventomax/notify/
│   ├── client/               # CatalogEquipmentClient (consulta resiliente de stock)
│   ├── config/               # RabbitMQTopologyConfig (exchanges, colas, DLX/DLQ, RetryTemplate)
│   ├── controller/           # NotifyQuoteController, NotifyTicketController
│   ├── document/             # ProductionClosureDocumentBuilder, OpenPdfClosureDocumentGenerator
│   ├── dto/document/         # ProductionClosureDocumentDTO
│   ├── idempotency/          # ProcessedEventStore, DatabaseProcessedEventStore
│   ├── messaging/
│   │   ├── common/           # MessageEnvelope<T>
│   │   ├── crew/             # CrewTicketCommandListener, CrewTicketPayload
│   │   ├── email/            # EmailCommandListener, EmailProductionStatusPayload
│   │   └── quote/            # QuoteCommandListener, QuoteCommandPayload
│   ├── model/                # CrewTicketEntity, ProcessedEventEntity, NotificationDeliveryAuditEntity
│   ├── repository/           # Repositorios Spring Data JPA
│   ├── sender/               # SmtpEmailSender, LoggingEmailSender, DatabaseCrewTicketSender
│   ├── service/              # QuoteNotificationService, CrewTicketNotificationService, EmailNotificationService
│   └── storage/              # DocumentStorageService (Local / S3)
├── src/main/resources/
│   ├── branding/             # Logo corporativo EventoMax
│   ├── db/migration/         # V1__create_notify_tables.sql
│   ├── fonts/                # Roboto-Regular.ttf, Roboto-Bold.ttf
│   └── application.yml
├── Dockerfile
└── docker-compose.prod.yml
```

---

## 14. Flujo Funcional Demostrado en EP2

1. Al cerrar una producción en `ms-eventomax-productions`, se publica el comando `GenerateClosingQuote` en `cmd.direct` (`quote.gen`).
2. `QuoteCommandListener` recibe el mensaje desde `q.cmd.quote`, verifica en `processed_events` que el `eventId` no haya sido procesado y delega en `QuoteNotificationService`.
3. `ProductionClosureDocumentBuilder` combina los datos del payload con una consulta resiliente a `ms-eventomax-catalog` (marcando explícitamente `CONSULTADO`, `CATALOGO_SIN_EQUIPOS` o `NO_DISPONIBLE_TEMPORALMENTE` sin inventar cifras).
4. `OpenPdfClosureDocumentGenerator` renderiza el PDF de 2 páginas (`quote-production-{id}-{eventId}.pdf` y `quote-production-{id}-latest.pdf`), calcula su huella `SHA-256`, persiste la marca de idempotencia y emite `channel.basicAck(deliveryTag, false)`.

---

## 15. Troubleshooting y Errores Comunes

- **Mensaje derivado a `q.cmd.quote.dlq`:** Inspeccione las cabeceras `x-death` y el cuerpo del mensaje en RabbitMQ Management; ocurre cuando `type != GenerateClosingQuote` o faltan campos obligatorios (`productionId`, `status`).
- **Estado `NO_DISPONIBLE_TEMPORALMENTE` en la sección de catálogo del PDF:** Indica que `ms-eventomax-catalog` no respondió dentro del timeout configurado (3 s). El generador degrada de forma controlada sin abortar el cierre ni enviar el mensaje a DLQ.

---

## 16. Estado Actual de Certificación EP2

- **Pruebas Automatizadas:** `82/82` aprobadas (`QuoteCommandCrossContractTest`, `OpenPdfClosureDocumentGeneratorTest`, `ProductionClosureDocumentBuilderTest`, `QuoteNotificationServiceTest`, `EmailCommandListenerTest`, `CrewTicketCommandListenerTest`, `FlywayNotifyPreflightTest`).
- **Certificación en Vivo (B3/B4):** Generación PDF real verificada (`139,158 bytes`, `%PDF-1.5`) y derivación DLQ real verificada (`QuoteCommandDlqLiveIT`).

---

## 17. Trazabilidad Jira / Historias Relacionadas

- **EP2-FIX-PDF-01-B1:** Persistencia de tickets de cuadrilla, descarga de actas PDF e idempotencia en base de datos (`V1`).
- **EP2-FIX-PDF-01-B2.1 / PRE-B3:** Ensamblador documental, semántica UTC de `closedAt` y motor OpenPDF empresarial.
- **EP2-FIX-PDF-01-B3 / B4:** Integración end-to-end RabbitMQ → Notify → OpenPDF y certificación de NACK/DLQ en broker real.
