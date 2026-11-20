# Fase 2C — Executive Corporate

Base autorizada: `develop`, `1ab25026c3fab4bcc2f0108e94ecafdd970045b3`.
Rama: `feat/notify-phase-2c-executive-corporate`.

## Implementación

Un renderer Java puro produce asunto, HTML y texto plano desde un modelo visible
normalizado. No introduce dependencias. SmtpEmailSender conserva el transporte,
auditoría, destinatario y adjuntos; delega únicamente la presentación/composición.
El constructor existente se conserva para los consumidores y fixtures 2B.

- CONFIRMADO: «Tu producción está confirmada», azul #1D4ED8 sobre #EFF6FF.
- EN_MONTAJE: «El montaje está en curso», ámbar #92400E sobre #FFFBEB.
- CERRADO: «Tu producción ha sido cerrada», verde #047857 sobre #ECFDF5.
- Otros estados: actualización neutral, sin afirmaciones de cargos, reservas o documentos.

Todos comparten cabecera navy #10223B, fondo #F1F5F9, superficie #FFFFFF,
texto #0F172A, metadatos #64748B, bordes #E2E8F0 y Arial/Segoe UI/sans-serif.
Título 24 px, cuerpo 16 px, metadatos 14 px. Tablas de presentación, estilos inline,
ancho fluido máximo 600 px y tabla condicional MSO de 600 px.
Estado siempre textual; preheader oculto; sin scripts, fuentes remotas ni tracking.
La pérdida de bordes decorativos no compromete lectura ni acciones.

El logo CID existente se muestra sobre una superficie blanca para conservar contraste.
La marca textual permanece aun con imágenes bloqueadas. Si el recurso no existe,
se omiten tanto img/cid como la parte inline, sin modificar los hechos del correo.

## Componentes

- `EmailPresentationModel`: folio, nombre, fecha, recinto y estado. No recibe identidad
  de organizador, destinatario, trazas, UUID documentales ni rutas de almacenamiento.
- `EmailStatusPresentation`: textos y tokens de variantes y fallback neutral.
- `EmailTemplateRenderer`: asunto y alternativas coherentes, sin I/O ni acceso a DB.
- `PortalLinkPolicy`: validación exacta de host/ruta y aprobación operativa E2E.

`EmailPresentationModel.from` exige coherencia CERRADO/documento verificado.
El sender llama con verifiedPdf=true únicamente después de la verificación defensiva
existente de ClosurePdf. No se obtiene esta condición del payload ni se consulta storage
desde el renderer. Se adjuntan los mismos bytes con el mismo nombre que en 2B.
No se afirma disponibilidad de descarga en el portal ni carácter legal/firmable del PDF.
EN_MONTAJE no incluye ticket: el contrato no acredita su disponibilidad.

Ordinario: multipart/related con multipart/alternative (plain/html) y CID opcional.
Cierre: multipart/mixed con related/alternative/CID y el PDF como attachment.
Se llama setText antes de addInline y addAttachment. El Message-ID durable, cabeceras
de auditoría y punto exacto de despacho/registro conservan la semántica 2B.

## Fechas y entradas

Locale es-CL. Los timestamps con offset/Z se convierten mediante atZoneSameInstant a
America/Santiago, incluyendo zona y offset efectivos. Los LocalDateTime conservan
su hora y muestran «zona no informada». Nulos/ilegibles: «Fecha no disponible».
Nombre/recinto ausentes tienen textos explícitos de información no disponible.
El contenido dinámico se escapa en HTML; controles se eliminan del contenido visible.
Asuntos estáticos por estado y folio evitan incorporar nombres como cabeceras.
Se rechazan controles en cabeceras antes de SMTP; se mantienen los mecanismos de
auditoría y clasificación de errores existentes. No se amplían reglas de destinatario.

## CTA seguro y deshabilitado

Configuración pública (sin valores operacionales ni secretos):

| Variable | Predeterminado | Significado |
|---|---|---|
| NOTIFY_PORTAL_URL | vacío | URL absoluta cuya ruta exacta debe ser /productions |
| NOTIFY_PORTAL_ALLOWED_HOSTS | vacío | Hosts exactos, separados por coma; sin comodines |
| NOTIFY_PORTAL_E2E_APPROVED | false | Declaración operativa de aprobación E2E previa |

Se exige HTTPS, host DNS permitido, ruta exacta sin codificaciones ni segmentos
alternativos, ausencia de credenciales, puerto explícito, query y fragmento.
Se rechazan localhost, subdominios .localhost, IP y formas numéricas conocidas.
Único parámetro construido: productionId numérico positivo. No incluye action,
tokens, JWT, URL de objetos, organizador ni identificadores documentales.

Una URL correcta/HTTP 200 no habilita el CTA. Sin aprobación E2E, o con configuración
inválida, se omite el enlace en HTML y plain y se indica acceso habitual a EventoMax.
La aprobación es una configuración confiable de operación, no una certificación
automática: registrar evidencia, versión del portal y autorización antes de habilitarla.
No se habilitó esta variable ni se cambiaron variables privadas en esta entrega.

Pendiente: navegador sin sesión → MSAL → regreso al folio → API Gateway/BFF →
autorización de propiedad/rol y descarga. Probar también otra producción, 401/403/404
y documento pendiente. No modificar Angular/BFF/Productions sin autorización separada.

## Archivos de implementación

Modificados:
- .gitattributes (solo fixtures EML: conservar bytes/CRLF y espacios de cabeceras plegadas)
- src/main/java/cl/duoc/eventomax/notify/sender/SmtpEmailSender.java
- src/main/resources/application.yml
- src/test/java/cl/duoc/eventomax/notify/sender/SmtpEmailSenderTest.java

Nuevos:
- src/main/java/cl/duoc/eventomax/notify/email/presentation/EmailPresentationModel.java
- src/main/java/cl/duoc/eventomax/notify/email/presentation/EmailStatusPresentation.java
- src/main/java/cl/duoc/eventomax/notify/email/presentation/EmailTemplateRenderer.java
- src/main/java/cl/duoc/eventomax/notify/email/presentation/PortalLinkPolicy.java
- src/test/java/cl/duoc/eventomax/notify/email/presentation/EmailTemplateRendererTest.java
- src/test/java/cl/duoc/eventomax/notify/email/presentation/PortalLinkPolicyTest.java
- src/test/java/cl/duoc/eventomax/notify/sender/ExecutiveEmailMimeTest.java
- docs/NOTIFY-2C-DESIGN.md, docs/NOTIFY-2C-VALIDATION.md
- docs/notify-2c/previews/: tres HTML, tres EML, tres TXT y copia del logo existente.
- docs/notify-2c/evidence/: capturas Chromium, comprobaciones y resumen de suites.

Sin cambios en ClosureStore, ClosureWorker, ClosurePdf, ClosureEmailTransport,
contratos, ACK/NACK, topología, idempotencia, migraciones, pom.xml u otros repositorios.

## Activación y rollback futuros

Esta entrega implementa código; no activa el dispatcher, no despliega y no altera
trabajos durables. Esperar revisión del PR y autorización de merge independiente.
La futura revisión de despliegue debe conservar storage/DB y resolver la activación 2B.

Para retirar el CTA basta deshabilitar su aprobación E2E en un cambio operativo autorizado;
eso no pausa SMTP ni el worker. Para un defecto visual preferir fix forward del renderer.
Una reversión selectiva debe conservar la política segura del CTA y toda la coordinación
2B. Revertir este PR completo también restaura el fallback localhost anterior: no usarlo
como rollback operativo sin mitigar esa regresión y revisar configuración.
No revertir V2, eliminar PDFs/filas, reclasificar UNKNOWN ni reenviar históricos por estilo.
Nunca usar correo directo CERRADO como alternativa al transporte durable.

El rollback de despliegue requiere aprobación independiente y debe considerar los trabajos
ya ACKeados; una reversión de imagen por sí sola no recupera ni cancela esas intenciones.
