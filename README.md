# ms-eventomax-notify

**DSY1107 – Desarrollo Cloud Native I · EventoMax**

Microservicio interno de EventoMax encargado de procesar trabajos/notificaciones asíncronas desde RabbitMQ.

> **Requerimiento:** RF-NOT-001
> **Caso de uso:** CU-07

---

## Responsabilidad

Este microservicio **no es público** (no expone API REST de negocio).
Su responsabilidad en este incremento es consumir comandos de notificación de email (`q.cmd.email`), tickets de cuadrilla (`q.cmd.crew`) y cotizaciones/resúmenes de cierre en PDF (`q.cmd.quote`) desde RabbitMQ y procesarlos de forma asíncrona.

---

## Flujo de mensajería

```
ms-eventomax-productions
  ├─► Exchange: cmd.direct / routing key: email.send (y cmd.topic / email.*)
  │     └─► Queue: q.cmd.email
  │           └─► ms-eventomax-notify (EmailCommandListener)
  │                 └─► EmailNotificationService
  │                       └─► LoggingEmailSender (simulado)
  ├─► Exchange: cmd.direct / routing key: crew.ticket (y cmd.topic / crew.#)
  │     └─► Queue: q.cmd.crew
  │           └─► ms-eventomax-notify (CrewCommandListener)
  │                 └─► CrewTicketNotificationService
  │                       └─► LoggingCrewTicketSender (simulado)
  └─► Exchange: cmd.direct / routing key: quote.gen (y cmd.topic / quote.*)
        └─► Queue: q.cmd.quote
              └─► ms-eventomax-notify (QuoteCommandListener)
                    └─► QuoteNotificationService
                          └─► PdfQuoteGenerator (generación real de PDF 1.4 en disco)
```

### Dead Letter Queue (DLQ)

Los mensajes rechazados (NACK sin requeue) son enviados automáticamente por RabbitMQ a:

```
q.cmd.email ──(DLX: cmd.dead.dlx)──► q.cmd.email.dlq
q.cmd.crew  ──(DLX: cmd.dead.dlx)──► q.cmd.crew.dlq
q.cmd.quote ──(DLX: cmd.dead.dlx)──► q.cmd.quote.dlq
```

---

## Contrato del mensaje

### Envelope (`MessageEnvelope<T>`)

```json
{
  "type": "SendProductionStatusEmail",
  "eventId": "uuid-único",
  "timestamp": "2026-10-03T00:00:00Z",
  "traceId": "trace-uuid",
  "correlationId": "corr-uuid",
  "payload": { ... }
}
```

> **Nota:** El valor de `"type"` depende del comando:
> - `"SendProductionStatusEmail"` para notificaciones de email (`q.cmd.email`).
> - `"GenerateCrewTicket"` para generación de tickets de cuadrilla (`q.cmd.crew`).
> - `"GenerateClosingQuote"` para generación de cotización/resumen PDF de cierre (`q.cmd.quote`).

### Payload Email (`EmailProductionStatusPayload`)

```json
{
  "productionId": 100,
  "organizerId": "org-001",
  "productionName": "Concierto Rock",
  "status": "CONFIRMED",
  "scheduledAt": "2026-12-01T20:00:00",
  "location": "Santiago, Chile"
}
```

### Payload Crew (`CrewTicketPayload`) (V1)

```json
{
  "productionId": 100,
  "productionName": "Concierto Rock",
  "scheduledAt": "2026-12-01T20:00:00",
  "location": "Santiago, Chile",
  "status": "EN_MONTAJE"
}
```

> **Nota:** Por ahora el ticket de cuadrilla se simula mediante logging. El contrato V1 incluye los datos mostrados arriba. La asignación detallada de la cuadrilla y sus integrantes se incorporará en el futuro cuando exista ese dominio/contrato.

### Payload Quote (`QuoteCommandPayload`) (V1)

```json
{
  "productionId": 100,
  "organizerId": "org-001",
  "productionName": "Concierto Rock",
  "status": "CERRADO",
  "scheduledAt": "2026-12-01T20:00:00",
  "location": "Santiago, Chile",
  "closedAt": "2026-12-02T02:15:00Z"
}
```

### Generación Real y Recuperación del PDF (`PdfQuoteGenerator`)

Al consumir un comando `GenerateClosingQuote` en `q.cmd.quote`, `PdfQuoteGenerator` construye un documento **PDF 1.4 real y verificable** (`%PDF-1.4`, `/Type /Catalog`, `/Type /Page`, tabla `xref` y `%%EOF`) y lo almacena de forma persistente en el directorio configurado por `eventomax.notify.quote.output-dir` (variable de entorno `NOTIFY_QUOTE_OUTPUT_DIR`, por defecto `./data/quotes`):

- **Archivo por evento (trazabilidad histórica):** `./data/quotes/quote-production-{productionId}-{eventId}.pdf`
- **Puntero al último cierre de la producción (DEMO):** `./data/quotes/quote-production-{productionId}-latest.pdf`

El documento PDF incluye únicamente los campos reales del dominio de Producción (`productionId`, `productionName`, `organizerId`, `status`, `scheduledAt`, `location`, `closedAt`, `eventId`, `traceId`, `correlationId`) y deja constancia explícita de que no simula montos financieros inexistentes.

El envelope completo viaja en el **body JSON** del mensaje RabbitMQ.
No se depende de headers Java personalizados del publisher.

---

## ACK/NACK manual

| Escenario | Acción |
|---|---|
| Mensaje válido y procesado correctamente | `basicAck(deliveryTag, false)` |
| Mensaje duplicado (eventId ya procesado) | `basicAck(deliveryTag, false)` — sin reprocesar |
| Envelope inválido (campos null/blank) | `basicNack(deliveryTag, false, false)` → DLQ |
| Type no soportado | `basicNack(deliveryTag, false, false)` → DLQ |
| Error de procesamiento tras agotar reintentos | `basicNack(deliveryTag, false, false)` → DLQ |

- **`requeue = false`** siempre en NACK → evita requeue infinito.
- El mensaje rechazado va a la DLQ correspondiente (`q.cmd.email.dlq`, `q.cmd.crew.dlq`, `q.cmd.quote.dlq`) gracias a la DLX (`cmd.dead.dlx`) configurada en los beans y en `definitions.json`.

### Retries controlados

Se utiliza **Spring Retry** (`RetryTemplate`) de forma programática:
- **`maxAttempts = 3` intentos totales** por defecto (`NOTIFY_RETRY_MAX_ATTEMPTS`), compuestos por **1 intento inicial + 2 reintentos adicionales** (no confundir con 3 reintentos adicionales).
- **Backoff fijo:** 500 ms por defecto (`NOTIFY_RETRY_INITIAL_INTERVAL_MS`).
- Si un intento funciona, se marca el `eventId` como procesado y se ejecuta `basicAck`.
- Si se agotan los 3 intentos totales, el mensaje no se marca como procesado y recibe `basicNack(deliveryTag, false, false)`, derivando el mensaje a su DLQ (`q.cmd.email.dlq`, `q.cmd.crew.dlq` o `q.cmd.quote.dlq`).
- Mensajes con JSON inválido, sobre incompleto o `type` no soportado no se reintentan y van directo a DLQ.

---

## Idempotencia

Se implementa idempotencia por **`eventId`** mediante la abstracción `ProcessedEventStore`.

**Implementación actual:** `InMemoryProcessedEventStore` (basada en `ConcurrentHashMap.newKeySet()`).

**Reglas:**
1. Si el `eventId` NO está procesado → ejecutar servicio.
2. Marcar como procesado **SOLO** después de éxito.
3. Si el servicio falla → NO marcar como procesado → NACK.
4. Si llega un `eventId` ya procesado → log de duplicado ignorado → ACK.

> **⚠️ Limitación:** La idempotencia in-memory **NO es durable**. Se pierde al reiniciar el contenedor.
> Una solución persistente/distribuida (Redis, base de datos) queda fuera de este incremento.
> **NO** se agrega una base de datos solo para resolver esto.

---

## Trazabilidad (MDC)

Durante el procesamiento de cada mensaje:

- `traceId` → colocado en MDC
- `correlationId` → colocado en MDC

Se limpia con `try/finally` al terminar cada mensaje para evitar contaminación entre mensajes.

---

## Variables de entorno

| Variable | Descripción |
|---|---|
| `RABBITMQ_HOST` | Host de RabbitMQ |
| `RABBITMQ_PORT` | Puerto de RabbitMQ |
| `RABBITMQ_USERNAME` | Usuario de RabbitMQ |
| `RABBITMQ_PASSWORD` | Contraseña de RabbitMQ |

**Nota:** `application.yml` mantiene defaults orientados al desarrollo local para `RABBITMQ_HOST` (`localhost`) y `RABBITMQ_PORT` (`5672`), mientras que usuario y contraseña quedan vacíos si no se proporcionan. En producción, `docker-compose.prod.yml` exige explícitamente `RABBITMQ_HOST`, `RABBITMQ_USERNAME` y `RABBITMQ_PASSWORD`, evitando que el despliegue cloud dependa de los defaults locales.

Ver `.env.example` para la configuración Docker local y `.env.prod.example` para la plantilla de producción.

---

## Ejecución local

### Prerrequisitos

- Java 25
- RabbitMQ corriendo (desde `infra-eventomax`)

### Compilar y ejecutar tests

```bash
.\mvnw.cmd clean package
```

### Ejecutar la aplicación

```bash
.\mvnw.cmd spring-boot:run
```

La aplicación se levanta en el puerto **8080** y se conecta a RabbitMQ en `localhost:5672`.

### Actuator

```
GET http://localhost:8080/actuator/health
```

Devuelve `UP` cuando RabbitMQ está disponible y autenticado correctamente.

---

## Ejecución Docker

### Build

```bash
docker compose build
```

### Run

```bash
docker compose up -d
```

- Host port: **8083** → container port: **8080**
- Requiere que RabbitMQ esté corriendo en la red Docker (provisto por `infra-eventomax`).

### Variables

Configurar en archivo `.env` (no versionado):

```
RABBITMQ_HOST=host.docker.internal
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=<tu-usuario>
RABBITMQ_PASSWORD=<tu-password>
```

---

## Producción / AWS

- Notify se ejecuta en AWS EC2 mediante Docker.
- Es un consumidor interno y NO es público.
- No debe existir port mapping público.
- RabbitMQ se alcanza mediante dirección privada/configurada con `RABBITMQ_HOST`.
- No utilizar `localhost` ni `host.docker.internal` en cloud.
- Credenciales mediante variables de entorno / mecanismo seguro.
- Usa `docker-compose.prod.yml`.
- Usa `.env.prod.example` únicamente como plantilla sin secretos.
- Pertenece a `eventomax-net`.

**Flujo conceptual:**

```text
ms-eventomax-productions
        |
        v
RabbitMQ AWS
   |            |            |
   v            v            v
q.cmd.email  q.cmd.crew  q.cmd.quote
   \            |            /
    v           v           v
       ms-eventomax-notify
```

Aclaración: RabbitMQ puede estar en infraestructura EC2 separada, pero la comunicación debe ocurrir mediante conectividad privada de AWS/VPC.

---

## Arquitectura

```
src/main/java/cl/duoc/eventomax/notify/
├── MsEventomaxNotifyApplication.java       # Entrada Spring Boot
├── config/
│   ├── RabbitMQConfig.java                 # Topología RabbitMQ (3 exchanges, 6 colas, 9 bindings)
│   └── NotificationRetryConfig.java        # RetryTemplate (3 intentos totales = 1 inicial + 2 reintentos)
├── messaging/
│   ├── common/
│   │   └── MessageEnvelope.java            # Envelope genérico
│   ├── email/
│   │   ├── EmailProductionStatusPayload.java   # Payload de email
│   │   └── EmailCommandListener.java       # Consumidor q.cmd.email
│   ├── crew/
│   │   ├── CrewTicketPayload.java          # Payload de ticket de cuadrilla
│   │   └── CrewCommandListener.java        # Consumidor q.cmd.crew
│   └── quote/
│       ├── QuoteCommandPayload.java        # Payload de cotización/resumen PDF de cierre
│       └── QuoteCommandListener.java       # Consumidor q.cmd.quote
├── service/
│   ├── EmailNotificationService.java       # Servicio de email
│   ├── CrewTicketNotificationService.java  # Servicio de cuadrilla
│   └── QuoteNotificationService.java       # Servicio de cotización PDF
├── sender/
│   ├── EmailSender.java                    # Interfaz de envío email
│   ├── LoggingEmailSender.java             # Implementación simulada email
│   ├── CrewTicketSender.java               # Interfaz de envío cuadrilla
│   ├── LoggingCrewTicketSender.java        # Implementación simulada cuadrilla
│   ├── QuoteGenerator.java                 # Interfaz de generación PDF
│   └── PdfQuoteGenerator.java              # Generador real de PDF 1.4 en disco
└── idempotency/
    ├── ProcessedEventStore.java            # Interfaz de idempotencia
    └── InMemoryProcessedEventStore.java    # Implementación in-memory
```

---

## Tests

| Test | Qué valida |
|---|---|
| **A.** Mensaje válido | Procesa, marca idempotencia, ACK (`email`, `crew`, `quote`) |
| **B.** Duplicado | No reprocesa, ACK |
| **C.** Type inválido | No procesa, NACK → DLQ |
| **D.** Envelope inválido | No procesa, NACK → DLQ |
| **E.** Error del servicio | Reintenta hasta 3 intentos totales, no marca procesado, NACK → DLQ |
| **F.** Servicio / PDF | Delega a Sender y valida generación real de PDF 1.4 (`%PDF-1.4`, `xref`, `%%EOF`) |
| **G.** Idempotencia | `InMemoryProcessedEventStore`: atomicidad `processOnce`, thread-safety |
| **H.** Integración RabbitMQ | `EmailRetryRabbitIT` con Testcontainers (`rabbitmq:4.3.6-management`, 19 escenarios reales aislados) |

---

## Fuera de alcance (este incremento)

- ❌ Envío de email real (SMTP, SES, SendGrid, etc.)
- ❌ Persistencia de idempotencia (Redis, base de datos)
- ❌ API pública / controllers REST
- ❌ Base de datos
- ❌ Spring Security
- ❌ OpenAPI

---

## Stack técnico

- Java 25
- Spring Boot 4.1.1
- Spring AMQP (RabbitMQ)
- Spring Actuator
- Maven
