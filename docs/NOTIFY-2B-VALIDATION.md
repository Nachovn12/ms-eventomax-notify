# Fase 2B — implementación y evidencia de validación

Fecha: 20 de noviembre de 2026, America/Santiago.
Base exacta: `f7859611a6e515b92c159ca965b9f8c9674c4b62` (`develop`, Fase 2A).
Rama independiente: `feat/notify-phase-2b-durable-closure`.

## Resultado real

**185 pruebas, 29 suites, 0 failures, 0 errors, 0 skipped**, en las dos ejecuciones
finales detalladas abajo. Se compiló y empaquetó el JAR; no se creó ni desplegó una
imagen de producción. El PR se prepara como borrador hacia develop, sin fusionar.

Entorno: Windows, Java 25, Maven 3.9.16, Docker Desktop Linux engine 29.8.2.
Contenedores de prueba: PostgreSQL `postgres:17-alpine` y RabbitMQ `rabbitmq:4.3.6-management`,
creados y retirados por Testcontainers 2.0.5. Las migraciones y las operaciones SQL de
fallo/truncado se limitaron a estas bases descartables. SMTP de prueba es un servidor
en memoria enlazado a 127.0.0.1 y puerto efímero: recibe fixtures y nunca retransmite.

### Ejecuciones finales

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn -o '-Dmaven.repo.local=C:\Users\Nacho\.m2\repository' `
  '-Dtest=*Test,EmailRetryRabbitIT' verify
# BUILD SUCCESS, 184 pruebas, finalizó 18:28:54 -03:00 (1 min 33 s).

mvn -o '-Dmaven.repo.local=C:\Users\Nacho\.m2\repository' `
  '-Dtest=MsEventomaxNotifyApplicationTests' test
# BUILD SUCCESS, 1 prueba, finalizó 18:31:01 -03:00.
```

La segunda ejecución cubre la clase cuyo nombre termina en Tests, que no selecciona
el patrón *Test. No cambió código entre ambas. En cualquier entorno con Docker se
puede reproducir la selección conjunta con `-Dtest=*Test,*Tests,EmailRetryRabbitIT`.
Los comandos se ejecutaron con permisos de lectura de la caché Maven necesarios
fuera del sandbox; no se cambiaron credenciales, PATH permanente ni Docker settings.

Reportes originales locales: `target/surefire-reports`. Los logs de ejecución están
en `target/phase2b-final-verification.log` y `target/phase2b-context-verification.log`.
No se publican sus properties, system-out o system-err.
[Resumen JUnit sanitizado](evidence/notify-2b-tests.xml): conteos y nombres de casos
extraídos de los 29 reportes recientes, no de los XML históricos de pruebas live.

SHA-256 del JAR local verificado:
`5818e39938721822d4d07376655b5c0c58b05da3571cb00b212009ea3725b894`.
Esto identifica el build local; no es una evidencia de despliegue ni un compromiso
de reproducibilidad binaria de un build futuro.

### Iteraciones previas

La primera compilación reveló que Angus estaba solo en el classpath de runtime.
Se declaró el mismo Angus Mail administrado por Spring Boot en compile para clasificar
respuestas SMTP sin analizar texto de excepciones. Otro error de compilación afectó
solo al constructor de una excepción sintética de test y se corrigió.

Después pasaron 43 pruebas enfocadas, 26 integraciones iniciales y una regresión
intermedia de 182 pruebas. Esos conteos no se suman a los 185 casos finales.
Los stack traces de fallos inyectados en tests legacy y advertencias de Mockito/FontBox
no se cuentan como fallos de la suite: se comprobaron los resultados JUnit y exit code.

## Matriz de cobertura

| Escenario | Evidencia final |
| --- | --- |
| Email antes del PDF | WAITING_DOCUMENT, cero SMTP; READY posterior y adjunto verificado |
| PDF antes del email | Registro READY conservado, unión posterior y aceptación SMTP |
| Duplicados de eventId y UUID diferentes | Una intención y documento por cierre; Inbox conserva comandos distintos |
| Dos consumidores y workers | Casos simultáneos con mismo/distinto eventId; un claim y una transmisión |
| Caída antes del commit | Failpoint del transaction manager revierte Inbox/intención |
| Caída después del commit | Failpoint posterior al commit; redelivery encuentra Inbox existente |
| ACK de email y documento | RabbitMQ real; callback de ACK observa filas ya comprometidas; documento aún PENDING |
| Mensaje inválido | NACK a la DLQ del broker efímero |
| Contención DB | Insert bloqueado por otra transacción falla dentro del límite de espera |
| Reinicio con trabajo pendiente | Nuevas instancias de store/worker recuperan la misma base durable |
| Caída entre almacenamiento y READY | Recupera y verifica el objeto preparado sin usar latest |
| Worker antiguo tras vencimiento | Token anterior no puede preparar ni publicar READY |
| PDF ausente/corrupto/otra producción/otro documento | FAILED_DOCUMENT y cero invocaciones SMTP; incluye checksum coincidente con identidad equivocada |
| Fallo de almacenamiento | RETRY_WAIT durable; recuperación posterior sin fallback legacy |
| Aceptación SMTP | SMTP_ACCEPTED, intento y auditoría persistidos |
| Rechazo 550 / 451 | 550 terminal; 451 con reintento acotado y Message-ID estable |
| Límite de intentos | Rechazos transitorios dejan FAILED_PERMANENT al agotarse |
| Resultado SMTP ambiguo | UNKNOWN y ninguna retransmisión automática |
| Auditoría fallida después de SMTP | Constraint de prueba y fallo de conexión DB simulado; UNKNOWN sin reenvío |
| Checksum del adjunto | Se extrae el MIME recibido por el SMTP local y se compara con DB y objeto almacenado |
| Contacto ausente/conflictivo | Bloqueo durable o rechazo de conflicto, sin fallback ni sustitución |
| Dispatch pausado | READY permanece sin reclamar; cero SMTP |
| Descarga existente | Registry READY exacto; histórico conservado; PENDING no entrega alias anterior |
| Migración aditiva | V1 -> V2 en PostgreSQL preserva marcador histórico; segunda ejecución no duplica historial |
| Escritura inmutable local | Creadores concurrentes: uno publica; otro no reemplaza los bytes |
| S3 condicional | Mock del SDK comprueba If-None-Match, 412 y propagación de 503; ninguna llamada AWS |
| CONFIRMADO/EN_MONTAJE | SMTP de prueba real y tests MIME existentes |
| Fase 2A y cuadrilla | Cliente/config Catalog, inventario PDF, destinatario, contratos, listeners, tickets y servicios |

Grupos destacados: DurableClosureIntegrationTest **33**, ClosurePdfTest **5**,
ImmutableStorageTest **3**, EmailRetryRabbitIT **19**, SmtpEmailSenderTest **9**.
Las otras 24 suites aportan 116 casos. Total: 185.

## Aislamiento de pruebas históricas

- FlywayNotifyPreflightTest usa H2 en memoria y ya no lee `.local-cert` ni prueba una
  conexión PostgreSQL local preexistente. La prueba real de permisos/startup usa Testcontainers.
- GenerateRealClosureDocumentsTest escribe fixtures y previews únicamente en target,
  nunca sobre `.local-cert` ni directorios de otros agentes.
- Los contextos de regresión tienen storage/output en target. El test RabbitMQ legacy
  deshabilita el coordinador nuevo explícitamente para conservar su cobertura histórica;
  los 33 casos durables ejercitan el flujo nuevo. El transporte SMTP real bloquea siempre
  un CERRADO directo sin adjunto validado, incluso si el coordinador está deshabilitado.
- QuoteCommandDlqLiveIT sigue fuera de la selección: apunta a un broker persistente.
  Su escenario de DLQ se verifica con el broker efímero de la nueva suite.

V1, DatabaseProcessedEventStore, cliente Catalog de 2A y consumidores de cuadrilla no
fueron modificados. No cambiaron los envelopes, exchanges, colas, rutas BFF ni template HTML.
La migración, workers, metadatos PDF y transporte durable aplican al cierre.

## Implementado y pendiente

Implementado: Inbox, deduplicación de negocio, ACK después del commit, documento inmutable,
hash/identidad, claims y fencing, recuperación, estados requeridos, clasificación SMTP,
auditoría durable y descarga compatible. Configuración de despacho pausada por defecto.

Probado localmente: matriz anterior con infraestructura aislada y fixtures sintéticos.
Las pruebas simulan fronteras de commit/caídas y reconstruyen componentes; no matan el JVM
ni simulan pérdida física del disco. S3 se prueba con mock; no se comprobó S3 real ni Brevo.
La seguridad Angular/BFF se conserva por ausencia de cambios, no por un nuevo E2E desplegado.

Pendiente y sin autorización: merge, despliegue, V2 en RDS, activación SMTP, cohorte de corte,
reconciliación de mensajes históricos, prueba E2E AWS y Fase 2C.
El diseño operativo, límites y rollback están en [NOTIFY-2B-DESIGN.md](NOTIFY-2B-DESIGN.md).
