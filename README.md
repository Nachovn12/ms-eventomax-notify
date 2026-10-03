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
  └─► Exchange: cmd.direct / routing key: email.send
        └─► Queue: q.cmd.email
              └─► ms-eventomax-notify (EmailCommandListener)
                    └─► EmailNotificationService
                          └─► LoggingEmailSender (simulado)
```

### Dead Letter Queue (DLQ)

Los mensajes rechazados (NACK sin requeue) son enviados automáticamente por RabbitMQ a:

```
q.cmd.email ──(DLX: cmd.dead.dlx)──► q.cmd.email.dlq
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

### Payload (`EmailProductionStatusPayload`)

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
│   └── email/
│       ├── EmailProductionStatusPayload.java   # Payload de email
│       └── EmailCommandListener.java       # Consumidor q.cmd.email
├── service/
│   └── EmailNotificationService.java       # Servicio de aplicación
├── sender/
│   ├── EmailSender.java                    # Interfaz de envío
│   └── LoggingEmailSender.java             # Implementación simulada
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
| **F.** Servicio | Delega correctamente a EmailSender |
| **G.** Idempotencia | InMemoryProcessedEventStore: initial false, after mark true, thread-safety |

Todos los tests son **unitarios** y no requieren RabbitMQ real.

---

## Fuera de alcance (este incremento)

- ❌ Envío de email real (SMTP, SES, SendGrid, etc.)
- ❌ `q.cmd.crew` — cola de notificaciones de equipo técnico
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
