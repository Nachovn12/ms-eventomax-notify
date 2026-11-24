# Validación de Fase 2C

## Estado de entrega

Implementado y probado localmente en la rama independiente desde la base autorizada.
No integrado en develop/main, no desplegado, CTA sin habilitar, sin servicios productivos.
No se declara compatibilidad de clientes de correo por la sola generación del HTML.

## Pruebas reales

Ejecución local de presentación/MIME: 60 casos; 0 failures, 0 errors, 0 skipped.
Suite completa: **267 pruebas en 33 suites**, 0 failures, 0 errors, 0 skipped.
Maven verify finalizó BUILD SUCCESS y empaquetó el JAR.

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn -o '-Dmaven.repo.local=C:\Users\Nacho\.m2\repository' '-Dtest=*Test,*Tests,EmailRetryRabbitIT' verify
```

La ruta de caché Maven es local, sin credenciales. Los 19 casos EmailRetryRabbitIT
se incluyen explícitamente: la selección normal de CI ejecutará 248 pruebas.
El resumen sanitizado por suite está en notify-2c/evidence/test-summary.json.
No incluye propiedades del sistema, variables privadas ni logs con configuración.

Cobertura nueva: tres asuntos/variantes, fallback neutral, equivalencia de hechos
plain/HTML, omisión del CTA aun con URL permitida sin aprobación, enlace aprobado
en fixtures, URLs manipuladas, folio inválido, escape HTML, controles en cabeceras,
UTC/offset/cambio de día/verano/invierno, local sin zona y fechas nulas/ilegibles,
campos ausentes, texto largo y MIME con/sin logo.

Se verifica PDF con bytes exactos, nombre conservado y cabeceras técnicas/ID durable.
La regresión durable existente prueba el ID estable en el SMTP real de loopback,
UNKNOWN sin retransmisión, fencing, auditoría y adjunto. Permanecen los 31 casos de
regresión de bloqueos y los 33 casos durables. El control positivo antiguo sigue
exigiendo 40P01 exclusivamente en su SQL obsoleto de prueba.
Flyway V1 → V2 y arranque PostgreSQL/Hibernate se validan en bases descartables.
La regresión Catalog/Fase 2A y los flujos ordinarios también están incluidos.

Se mantuvieron intactos los archivos protegidos 2B y las reglas de destinatario.
No se agregaron dependencias ni se modificó la configuración del dispatcher.

## Fixtures y evidencias visuales

ExecutiveEmailMimeTest genera en target/notify-2c-preview los mensajes compuestos por
el sender real usando un capturador sin sockets. Todos los contactos son .invalid,
los eventos/UUID son sintéticos y la fecha de exportación es fija. El PDF de ejemplo
carece de datos operacionales. El Message-ID ordinario exportado usa example.invalid,
sin hostname del equipo; el durable conserva el formato closure del fixture.

Copias revisables en notify-2c/previews: confirmado, en_montaje y cerrado, cada uno
con HTML, texto y EML. En HTML de navegador se sustituye únicamente la referencia CID
por el PNG local copiado; el EML conserva el CID real. No se altera la imagen.

Previsualización mediante Edge/Chromium headless instalado, sin dependencias nuevas
en el proyecto y con acceso HTTP(S) bloqueado. Se revisaron visualmente capturas de
confirmación desktop y cierre móvil. Las comprobaciones DOM y capturas para los tres
estados a 800 y 390 px confirman: sin overflow horizontal, título UTF-8 correcto,
logo cargado y cero enlaces CTA. Imagen bloqueada simulada con recurso inexistente:
marca textual presente. Escala CSS 200 %: sin overflow. No equivale a zoom de Outlook.

| Cliente / condición | Resultado acreditado |
|---|---|
| Edge/Chromium, 800/390 px | Previsualización local correcta, capturas y DOM |
| Imagen no disponible en Chromium | Alternativa textual legible |
| Escala CSS 200 % en Chromium | Comprobación local correcta |
| Gmail web | Pendiente, no probado |
| Gmail móvil | Pendiente, no probado |
| Outlook web | Pendiente, no probado |
| Outlook clásico Windows | Pendiente, no probado |
| Zoom real 200 % en clientes de correo | Pendiente |
| Modo oscuro en clientes de correo | Pendiente |
| CTA desde correo sin sesión / autorización | Pendiente; CTA deshabilitado |

Las capturas/browser-checks.json están en notify-2c/evidence. La revisión de clientes
deberá comprobar CID/alt, MSO, anchos, nombres largos sin espacios, lectura, contraste,
modo oscuro y conservación de las dos alternativas. No enviar por Brevo para obtener
evidencia sin autorización separada. No se accedió a buzones reales.

## CI y dictamen

El estado vigente de GitHub Actions, SHA y URL del run se consignan en la descripción
del PR borrador, después de publicar el commit. Este documento registra la ejecución local.

GO técnico local para revisión del código; la fusión requiere además CI exitoso y aprobación.
La certificación visual Gmail/Outlook y la habilitación E2E del CTA permanecen pendientes
y no se confunden con la aprobación técnica del código. No autoriza merge ni despliegue.
Rollback y límites operativos: NOTIFY-2C-DESIGN.md.
