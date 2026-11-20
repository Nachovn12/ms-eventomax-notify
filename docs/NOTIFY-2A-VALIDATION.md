# Notify Fase 2A — entrega y validación

Fecha: 20 de noviembre de 2026, America/Santiago.
Rama: `fix/notify-phase-2a-catalog-document-contact`.
Base: `develop` remoto en `9848dbee2a928e0a8fa0cbaa335d5659c2855aef`.
No se modificó main ni otro repositorio. No se desplegó, migró una base de datos,
usó infraestructura productiva ni envió correo real.

## Resultado

- URL canónica `CATALOG_BASE_URL`, alias compatible `CATALOG_SERVICE_URL`.
  La propiedad Spring explícita tiene precedencia sobre ambos aliases.
- Endpoint contractual conservado: `GET /api/catalog/reservations/production/{id}`.
- `200` con lista válida representa reservas; `200 []` representa ausencia informada.
  `401/403` son UNAUTHORIZED, y `404`, otros estados, JSON inválido, timeout y error
  de conexión representan indisponibilidad, nunca una lista vacía confirmada.
- Se preservan nombre y estado ausentes como null, sin inventar RESERVED. Una fila
  con identificador/cantidad inválidos invalida la consulta completa, sin presentar
  un inventario parcial como completo.
- PDF diferencia inventario vacío, no consultado, no disponible y consulta rechazada.
  Diagnósticos de transporte o cuerpos remotos no se imprimen en el acta ni en logs.
- Cabeceras X-Trace-Id y X-Correlation-Id propagadas desde MDC.
- Eliminados fallback global de destinatario en SMTP/logging y propiedad de Compose.
  Comandos sin destinatario siguen bloqueados antes de construir o enviar SMTP.
  Configurar la propiedad antigua ya no restaura el fallback.
- No se cambian los comandos RabbitMQ, ACK, reintentos del listener, idempotencia,
  rutas públicas, formato HTML ni política de espera del PDF.

El enum UNAUTHORIZED es interno al cliente/DTO de renderizado; no modifica el
payload RabbitMQ ni agrega campos a respuestas públicas. Logging conserva su
semántica de simulación sin despacho SMTP, usando exclusivamente el destinatario
del comando si existe.

## Ejecución real de pruebas

Herramientas: Java 25 y Maven 3.9.16, caché local, modo offline.
Regresión final: **119 pruebas en 21 suites; 0 failures, 0 errors, 0 skipped**.
BUILD SUCCESS, finalizó el 20/11/2026 a las 17:32:54, America/Santiago.
Compiló 42 archivos de producción y 27 de pruebas.

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\Nacho\.m2\repository' `
  '-Dtest=*Test,!GenerateRealClosureDocumentsTest,!FlywayNotifyPreflightTest,!FlywayPostgresStartupIntegrationTest,!MsEventomaxNotifyApplicationTests' `
  '-Dspring.flyway.enabled=false' `
  '-Dspring.rabbitmq.listener.simple.auto-startup=false' test
```

El comando ejecutado añadió `-Dsurefire.reportsDirectory=target/phase2a-reports`,
pero este build no aplicó ese parámetro: los reportes se generaron realmente en
`target/surefire-reports`. El resumen adjunto se extrajo solo de los 21 XML de esta
ejecución, seleccionados por fecha y verificados por suite y total; no incluye XML
históricos. [Evidencia sanitizada](evidence/notify-2a-tests.xml) conserva conteos y
nombres de casos, sin system-out, system-err ni propiedades del proceso.

La primera ejecución enfocada pasó 70 pruebas. La final amplía la regresión e
incluye una prueba adicional de propiedad antigua de destinatario ignorada.
Los intentos iniciales dentro del sandbox fallaron por acceso de javac a la caché
Maven; la ejecución autorizada fuera del sandbox pasó. No se cambiaron dependencias
ni credenciales para resolverlo. Advertencias de Mockito/agent dinámico y FontBox
preexistentes no produjeron fallos.

| Grupo clave | Pruebas | Verificación |
| --- | ---: | --- |
| CatalogConfigurationTest | 4 | Variable canónica, alias, propiedad Spring y default local |
| RestCatalogReservationClientTest | 21 | Contrato HTTP, estados, datos incompletos, timeout y trazas |
| CatalogClosureDocumentIntegrationTest | 5 | HTTP local -> builder -> OpenPDF -> almacenamiento temporal; mensajes de ausencia |
| SmtpEmailSenderTest | 8 | MIME existente, adjunto simulado, bloqueo sin contacto y fallback antiguo ignorado |
| ProductionClosureDocumentBuilderTest | 26 | Compatibilidad documental existente |
| Otras 16 suites | 55 | Listeners con mocks, contratos, servicios, controllers y almacenamiento local |
| Total | 119 | Sin fallos, errores ni omisiones dentro de la selección |

La fixture de integración representa la reserva #4 para producción #8, una unidad
del Subwoofer Activo JBL VTX G28. Usa estado RELEASED deliberadamente para demostrar
que no se convierte en activo: no afirma que ese sea el estado actual de producción.
Los bytes recuperados del almacenamiento temporal coinciden con el PDF generado;
su texto contiene equipo, código y estado reales de la fixture.

Todas las llamadas HTTP nuevas usan loopback y puerto efímero. SMTP usa un sender
capturador en memoria; no abre conexiones de correo. Los nuevos tests de contexto
solo registran los beans específicos: no levantan la aplicación ni JPA/Flyway.

Exclusiones deliberadas:

- GenerateRealClosureDocumentsTest: escribe evidencias fuera de los directorios de prueba.
- FlywayNotifyPreflightTest y FlywayPostgresStartupIntegrationTest: aplican migraciones.
- MsEventomaxNotifyApplicationTests: arranca el contexto completo, innecesario para esta fase acotada.
- Clases *IT: requieren broker/containers y no forman parte del patrón *Test.
  Docker no está disponible; no se ejecutaron pruebas RabbitMQ ni PostgreSQL reales.

Esta validación no acredita Gmail/Outlook, Brevo, RDS, broker productivo, Compose
en ejecución ni E2E desplegado. La sintaxis de interpolación Compose no se ejecutó
con Docker; sí se verificó el YAML de aplicación y su resolución real con Spring.

## Configuración revisada, sin valores privados

```yaml
# application.yml
base-url: ${CATALOG_BASE_URL:${CATALOG_SERVICE_URL:http://localhost:8081}}

# docker-compose.prod.yml
CATALOG_BASE_URL: ${CATALOG_BASE_URL:-${CATALOG_SERVICE_URL:-http://ms-eventomax-catalog:8080}}
```

`NOTIFY_DEFAULT_RECIPIENT` ya no se publica en Compose ni se consume en Notify.
No se modificó ningún .env. Antes de un despliegue futuro, verificar que Productions
resuelva el contacto autorizado; si dependía del fallback, el correo se bloqueará
de forma observable en lugar de dirigirse a otra persona.

## Hipótesis pendientes y riesgos

El procedimiento [READ-ONLY EC2](NOTIFY-2A-READONLY-EC2.md) se preparó antes de
modificar código. No pudo ejecutarse sobre EC2: siguen pendientes commit/imagen/JAR,
configuración efectiva, MIME original y permisos de red. La frase del acta y el
HTML básico reportados no se atribuyen a un commit concreto sin esa evidencia.

404 ahora produce indisponibilidad: un proxy mal configurado dejará de parecer una
producción sin reservas. Respuestas con datos inválidos no se mostrarán parcialmente.
La eliminación del fallback puede revelar contactos ausentes antes ocultos.
El cambio se considera compatible con comandos que traen destinatario autorizado,
incluido el flujo SMTP demostrado, sujeto a validar esa precondición en EC2.

La carrera PDF/correo, latest.pdf y la ventana de duplicación SMTP permanecen;
se documentan en [la propuesta 2B/2C](NOTIFY-2B-2C-PROPOSAL.md), no se implementan.

## Rollback y revisión

Antes de merge: descartar el PR conserva develop/main y producción intactos.
Después de un eventual merge autorizado: crear un revert del commit de esta fase
en otra rama/PR. No hay migraciones a revertir ni datos operacionales que restaurar.
Un despliegue posterior requeriría autorización, digest previo y configuración
documentada. Revertir reintroduce el problema de Catalog y el fallback inseguro;
no hacerlo como respuesta automática a un contacto ausente.

El diff se revisa contra develop, no contra main. No incluye la incorporación del
workflow anterior de integración como cambio propio de esta fase. No fusionar ni
desplegar sin aprobación explícita después de revisar estas evidencias.
