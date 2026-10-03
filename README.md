# ms-eventomax-notify

**DSY1107 – Desarrollo Cloud Native I · EventoMax**

Microservicio interno de EventoMax encargado de procesar trabajos/notificaciones asíncronas desde RabbitMQ.

> **Requerimiento:** RF-NOT-001
> **Caso de uso:** CU-07

---

## Responsabilidad

Este microservicio **no es público** (no expone API REST de negocio).
Su única responsabilidad en este incremento es consumir comandos de notificación de email desde RabbitMQ y procesarlos de forma asíncrona.

---

## Flujo de mensajería

```
ms-eventomax-productions
  ├─► Exchange: cmd.direct / routing key: email.send
  │     └─► Queue: q.cmd.email
  │           └─► ms-eventomax-notify (EmailCommandListener)
  │                 └─► EmailNotificationService
  │                       └─► LoggingEmailSender (simulado)
  └─► Exchange: cmd.direct / routing key: crew.ticket
        └─► Queue: q.cmd.crew
              └─► ms-eventomax-notify (CrewCommandListener)
                    └─► CrewTicketNotificationService
                          └─► LoggingCrewTicketSender (simulado)
```

### Dead Letter Queue (DLQ)

Los mensajes rechazados (NACK sin requeue) son enviados automáticamente por RabbitMQ a:

```
q.cmd.email ──(DLX: cmd.dead.dlx)──► q.cmd.email.dlq
q.cmd.crew  ──(DLX: cmd.dead.dlx)──► q.cmd.crew.dlq
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

> **Nota:** El valor de `"type"` depende del comando. Para notificaciones de email es `"SendProductionStatusEmail"`, y para generación de tickets de cuadrilla es `"GenerateCrewTicket"`.

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

El envelope completo viaja en el **body JSON** del mensaje RabbitMQ.
No se depende de headers Java personalizados del publisher.

---

## ACK/NACK manual

| Escenario | Acción |
|---|---|
| Mensaje válido y procesado correctamente | `basicAck(deliveryTag, false)` |
| Mensaje duplicado (eventId ya procesado) | `basicAck(deliveryTag, false)` — sin reprocesar |
| Envelope inválido (campos null/blank) | `basicNack(deliveryTag, false, false)` → DLQ |
| Type no soportado (≠ `SendProductionStatusEmail`) | `basicNack(deliveryTag, false, false)` → DLQ |
| Error de procesamiento no recuperable | `basicNack(deliveryTag, false, false)` → DLQ |

- **`requeue = false`** siempre en NACK → evita requeue infinito.
- El mensaje rechazado va a la DLQ gracias a la DLX configurada en la infraestructura.

### Retries controlados

Se utiliza **Spring Retry** de forma programática.
- **Intentos máximos:** 3 por defecto (`NOTIFY_RETRY_MAX_ATTEMPTS`).
- **Backoff fijo:** 500 ms por defecto (`NOTIFY_RETRY_INITIAL_INTERVAL_MS`).
- Si un intento funciona, se marca el evento como procesado y se ejecuta `basicAck`.
- Si se agotan los intentos, el mensaje no se marca como procesado y recibe un `basicNack` con `requeue=false`, derivando el mensaje a la DLQ (`q.cmd.email.dlq`).
- Mensajes con JSON inválido o tipo no soportado no se reintentan y van directo a DLQ.

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

| Variable | Descripción | Default (local) |
|---|---|---|
| `RABBITMQ_HOST` | Host de RabbitMQ | `localhost` |
| `RABBITMQ_PORT` | Puerto de RabbitMQ | `5672` |
| `RABBITMQ_USERNAME` | Usuario de RabbitMQ | `guest` |
| `RABBITMQ_PASSWORD` | Contraseña de RabbitMQ | `guest` |

Ver `.env.example` para la configuración Docker.

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

## Arquitectura

```
src/main/java/cl/duoc/eventomax/notify/
├── MsEventomaxNotifyApplication.java       # Entrada Spring Boot
├── config/
│   └── RabbitMQConfig.java                 # Topología RabbitMQ (idempotente)
├── messaging/
│   ├── common/
│   │   └── MessageEnvelope.java            # Envelope genérico
│   ├── email/
│   │   ├── EmailProductionStatusPayload.java   # Payload de email
│   │   └── EmailCommandListener.java       # Consumidor q.cmd.email
│   └── crew/
│       ├── CrewTicketPayload.java          # Payload de ticket de cuadrilla
│       └── CrewCommandListener.java        # Consumidor q.cmd.crew
├── service/
│   ├── EmailNotificationService.java       # Servicio de email
│   └── CrewTicketNotificationService.java  # Servicio de cuadrilla
├── sender/
│   ├── EmailSender.java                    # Interfaz de envío email
│   ├── LoggingEmailSender.java             # Implementación simulada email
│   ├── CrewTicketSender.java               # Interfaz de envío cuadrilla
│   └── LoggingCrewTicketSender.java        # Implementación simulada cuadrilla
└── idempotency/
    ├── ProcessedEventStore.java            # Interfaz de idempotencia
    └── InMemoryProcessedEventStore.java    # Implementación in-memory
```

---

## Tests

| Test | Qué valida |
|---|---|
| **A.** Mensaje válido | Procesa, marca idempotencia, ACK |
| **B.** Duplicado | No reprocesa, ACK |
| **C.** Type inválido | No procesa, NACK → DLQ |
| **D.** Envelope inválido | No procesa, NACK → DLQ |
| **E.** Error del servicio | No marca procesado, NACK → DLQ |
| **F.** Servicio | Delega correctamente a Sender |
| **G.** Idempotencia | InMemoryProcessedEventStore: initial false, after mark true, thread-safety |

Todos los tests son **unitarios** y no requieren RabbitMQ real.

---

## Fuera de alcance (este incremento)

- ❌ Envío de email real (SMTP, SES, SendGrid, etc.)
- ❌ `q.cmd.quote` — cola de cotizaciones
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
