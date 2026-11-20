# Propuestas de fases 2B y 2C — pendientes de autorización

Este documento es diseño para revisión. No implementa migraciones, workers, ACK,
idempotencia ni una nueva plantilla HTML. La fase 2A no resuelve la carrera correo/PDF.

## 2B. Coordinación durable del cierre

### Contratos y clave de negocio

Conservar `SendProductionStatusEmail`, `GenerateClosingQuote`, sus payloads y las
colas actuales. Mantener separado el eventId de cada comando del correlationId.
Los UUID de ambos comandos son distintos: no se pueden unir por eventId.

Para el modelo actual, CERRADO es terminal y hay un cierre por productionId.
La clave propuesta de entrega es `(productionId, CERRADO, EMAIL)`. La clave de inbox
es `(consumer, commandEventId)`. Repetir un comando con otro UUID tampoco debe crear
otro correo del mismo cierre. Si se autorizan reaperturas, se necesita una revisión
de cierre compartida en ambos contratos: requeriría un cambio separado en Productions.
No inferir revisión a partir de timestamps o correlationId.

### Persistencia aditiva propuesta, solo en Notify

| Entidad lógica | Responsabilidad y restricciones |
| --- | --- |
| Inbox de comandos | Unicidad por consumidor/eventId; payload mínimo necesario, hash, trace y correlation, retención limitada |
| Documento de cierre | ProductionId, comando documental, clave inmutable, SHA-256, MIME, tamaño, versión y estado |
| Trabajo de entrega | Clave de negocio única, estado, destinatario autorizado, documento exacto, intentos, próximo intento y lease |
| Intento SMTP | Identificador único, Message-ID estable, comienzo, resultado y diagnóstico sanitizado |

Los nombres SQL y la migración se revisarían en 2B. No reutilizar `processed_events`
como una máquina de estados ni alterar V1. Guardar datos personales solo donde sean
necesarios para el despacho, con acceso restringido y política de eliminación.

### Secuencia transaccional y recuperación

1. El listener de correo valida el envelope y persiste inbox + intención en una
   transacción. Solo después del commit confirma ACK. Redelivery no repite intención.
   CONFIRMADO/EN_MONTAJE mantienen inicialmente su flujo existente.
2. El listener documental reclama una generación mediante unicidad y estado durable.
   Renderiza sin mantener una transacción o un bloqueo DB abiertos durante el trabajo.
3. Guarda el PDF en una clave inmutable y verifica tamaño/checksum. Backend local:
   volumen compartido entre workers, publicación atómica con temp único en el mismo
   filesystem. Backend S3: clave inmutable; persistir referencia solo tras PUT exitoso.
4. Registra READY y procesa el comando en una transacción; ACK después del commit.
   La caída después de guardar el objeto y antes del commit deja un objeto recuperable:
   un reconciliador valida su hash; no lo confunde con un documento de otro cierre.
5. El coordinador une intención y documento. Una transición WAITING_DOCUMENT -> READY
   sucede solo si coinciden producción/cierre y existe el documento exacto. Funciona
   con cualquiera de los dos órdenes de llegada y después de reinicios.
6. Un worker reclama READY con operación atómica y lease. Valida destinatario,
   checksum, MIME y límite de tamaño antes de construir el correo. No usa latest.pdf.
7. Antes del efecto SMTP registra el intento y SENDING. Tras respuesta inequívoca
   registra SMTP_ACCEPTED. No mantiene una transacción DB abierta durante SMTP.
8. Si cae el proceso o vence el lease de SENDING, pasar a UNKNOWN para reconciliación.
   No devolverlo automáticamente a READY: el proveedor podría haber aceptado el correo.

Una regeneración documental actualiza la versión disponible para descarga según
política, pero no crea otro correo. Un correo ya aceptado conserva el hash del PDF
que adjuntó. Fallback legacy no equivale automáticamente a acta empresarial READY.

### Estados y política propuesta

| Estado | Salida permitida |
| --- | --- |
| WAITING_DOCUMENT | READY o FAILED_DOCUMENT al agotar el plazo aprobado |
| READY | SENDING mediante claim exclusivo |
| SENDING | SMTP_ACCEPTED, RETRY_WAIT, FAILED_PERMANENT o UNKNOWN |
| RETRY_WAIT | READY al llegar nextAttemptAt y quedar intentos |
| UNKNOWN | Reconciliación operativa antes de cualquier reenvío |
| SMTP_ACCEPTED | Terminal; un redrive no envía otra vez |
| FAILED_DOCUMENT / FAILED_PERMANENT | Redrive autorizado que conserva historial y deduplicación |

Propuesta inicial para revisar: cinco intentos totales, con cuatro intervalos de
backoff de 1 s, 5 s, 30 s y 2 min, jitter ±20 %. Plazo documental
inicial de 15 min, configurable. No son pausas dentro del consumidor: se persiste
nextAttemptAt y se libera el hilo. Ajustar valores con métricas antes de producción.

Un rechazo permanente o ausencia de contacto no se reintenta automáticamente.
Un fallo de conexión inequívoco previo a transmisión puede reintentarse. Un timeout
tras DATA, aceptación seguida de fallo de auditoría o pérdida de proceso queda UNKNOWN.
Un Message-ID estable facilita correlación; no garantiza deduplicación de SMTP.

Política propuesta de fallo documental: no enviar cierre sin acta; registrar
FAILED_DOCUMENT y alerta operativa sin correo adicional al cliente. Requiere aprobación.
Con SMTP no se promete exactly-once; priorizar no duplicación implica reconciliar
resultados ambiguos y aceptar una posible entrega pendiente.

### Compatibilidad, operación y pruebas de aceptación

No requieren nuevas colas como primer paso. Los comandos inválidos mantienen NACK
sin requeue hacia DLQ. Una vez persistido el comando, los fallos del trabajo se
gestionan en el registro durable, con alerta y redrive; no fingir que estarán en la
DLQ original después del ACK. La topología RabbitMQ y credenciales no cambian.

Métricas: antigüedad en WAITING_DOCUMENT, trabajos por estado, retries agotados,
UNKNOWN, duplicados evitados, fallos de almacenamiento y latencia de generación.
Trazas: commandEventId, documentId, deliveryId, traceId y correlationId separados;
no incluir destinatarios completos, cuerpos, claves SMTP ni tokens en logs.

Pruebas requeridas en infraestructura efímera: ambos órdenes de mensajes, dos
réplicas, duplicados con UUID igual/distinto, caída antes/después de cada commit y
del ACK, fallo PUT, documento corrupto/equivocado, regeneración, timeout SMTP después
de DATA y aceptación seguida de caída DB. Confirmar que UNKNOWN no reenvía y que la
política terminal queda observable. Docker/Testcontainers se necesita para RabbitMQ
y PostgreSQL aislados; ninguna prueba apuntará a producción.

Despliegue/rollback futuros: migración aditiva, modo pausa, habilitación por cohorte,
exclusión entre flujo nuevo/antiguo, drenado de trabajos, reconciliación de SENDING,
conservación de tablas y documentos. Volver a la imagen antigua no recupera trabajos
cuyos comandos ya se confirmaron por el nuevo inbox; preparar adaptador o drenado
antes de permitir ese rollback. No borrar deduplicación ni purgar DLQ.

## 2C. Propuesta visual para revisión

### Composición prevista

```text
┌────────────────────────────────────────────┐
│ EventoMax                         Folio #8 │
│                                            │
│ [Estado con texto y color]                 │
│ Nombre del evento                          │
│ Mensaje breve del cambio                   │
│                                            │
│ Fecha y hora · zona explícita              │
│ Recinto                                    │
│                                            │
│ [Acta de cierre PDF · adjunta, si verificada]│
│                                            │
│ [ Consultar producción en EventoMax ]      │
│                                            │
│ Notificación automática · referencia corta │
└────────────────────────────────────────────┘
```

| Estado | Acento propuesto | Mensaje y acción |
| --- | --- | --- |
| CONFIRMADO | Azul #1D4ED8 sobre #EFF6FF | Producción confirmada; consultar detalle |
| EN_MONTAJE | Ámbar #92400E sobre #FFFBEB | Montaje en curso; consultar producción. Mostrar ticket solo si su disponibilidad está verificada |
| CERRADO | Verde #047857 sobre #ECFDF5 | Producción cerrada; bloque PDF solo si READY y adjunto validado |

Cabecera con logo PNG CID existente y alternativa textual. Tipografía Arial/Segoe UI
sin descargas externas, título 24 px, cuerpo 16 px y referencia 12 px. Fondo #F1F5F9,
superficie blanca y texto principal #0F172A; verificar contraste antes de aprobar.
Contenedor fluido de 600 px, padding móvil de 16 px, tablas role=presentation y
estilos inline. Para Outlook clásico: tabla condicional MSO de ancho fijo y botón
con tabla; no depender de flex, grid, sombras, max-width o bordes redondeados.

Una sola columna para los datos esenciales, botón con área cómoda para toque,
enlaces descriptivos, idioma español y estado identificable sin color. Sin píxeles
de seguimiento, identificadores OID o teléfono del organizador. Preheader breve
coherente con la disponibilidad real del documento. Texto plano equivalente.

Fechas con Locale es-CL. Instants y offsets se convierten explícitamente a
America/Santiago con indicación de zona; fechas locales sin offset mantienen su
semántica histórica y no reciben una zona inventada.

El CTA utiliza exclusivamente el portal HTTPS configurado y aprobado, con
productionId y acción no sensible. No incluye tokens, enlaces públicos de Notify
ni URLs firmadas de S3. Probar MSAL al abrir desde correo sin sesión y autorización
por propietario en BFF. No reemplazar el portal por un dominio supuesto.

Separar renderer de transporte en 2C, después de revisar el impacto: modelo de
presentación sin acceso a credenciales, HTML/texto de la misma fuente y versión
de plantilla en cabecera/auditoría. La fase 2A no realiza esta refactorización.

### Condiciones de aprobación de 2C

- Completar antes la identificación del JAR/imagen y comparación privada del MIME.
- Aprobar remitente y dominio mediante evidencia DKIM/DMARC y Authentication-Results;
  STARTTLS por sí solo no acredita la identidad del dominio. No cambiar credenciales.
- Aprobar copy y variantes, incluida la política documental de 2B.
- Probar Gmail web/móvil, Outlook web y Outlook clásico Windows; imágenes bloqueadas,
  texto plano, zoom 200 %, nombres extensos, campos ausentes y caracteres especiales.
- Registrar capturas de fixtures sintéticos y MIME sanitizado; no publicar correos reales.

Referencias de diseño operativo: [RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms),
[SMTP RFC 5321](https://datatracker.ietf.org/doc/html/rfc5321),
[autenticación de dominio Brevo](https://help.brevo.com/hc/en-us/articles/12163873383186-Authenticate-your-domain-with-Brevo-Brevo-code-DKIM-DMARC).
