# Notify 2A: identificación de despliegue en solo lectura

Preparado antes de modificar código. No ejecutado sobre EC2 durante esta fase.
Base de integración: `origin/develop`, commit `9848dbee2a928e0a8fa0cbaa335d5659c2855aef`.

## Límites

Usar una sesión EC2 ya autorizada; no solicitar ni compartir contraseñas o tokens.
No ejecutar pull, checkout, build, restart, compose up, migraciones, re-drive ni
pruebas contra servicios de producción. No mostrar `.env`, `docker compose config`,
`docker inspect` completo, logs completos o diffs de contenido: pueden revelar secretos.
Los comandos siguientes solo leen. No redirigir salidas privadas a archivos.

## 1. Checkout y contenedor

Desde el checkout de Notify en EC2:

```bash
git branch --show-current
git rev-parse HEAD
git status --porcelain=v1
git diff --name-status
git diff --cached --name-status
docker inspect --format '{{.Id}} {{.Image}} {{.Created}} {{.State.StartedAt}}' ms-eventomax-notify
image_id=$(docker inspect --format '{{.Image}}' ms-eventomax-notify)
docker image inspect --format '{{.Id}} {{json .RepoDigests}} {{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id"
docker exec ms-eventomax-notify sha256sum /proc/1/root/app/app.jar
```

La ruta del JAR corresponde al Dockerfile revisado. Si no existe, no asumir otra:
identificar la ruta con el operador sin volcar argumentos JVM que puedan contener secretos.
Un checkout limpio no prueba que su commit haya construido la imagen. Una etiqueta
de imagen tampoco sustituye su ID/digest. Registrar resultados, no inferir coincidencia.

## 2. Configuración permitida, filtrada antes de imprimir

El siguiente código procesa inspect en memoria y solo imprime las claves aprobadas.
URLs con credenciales o query se ocultan. No muestra contraseñas, tokens, remitentes
ni direcciones de destinatarios; para el fallback únicamente informa presencia.

```bash
python3 - <<'PY'
import json, subprocess
from urllib.parse import urlsplit
obj = json.loads(subprocess.check_output(['docker', 'inspect', 'ms-eventomax-notify']))[0]
env = dict(v.split('=', 1) for v in obj['Config'].get('Env', []) if '=' in v)
keys = ['CATALOG_BASE_URL', 'CATALOG_SERVICE_URL', 'EVENTOMAX_NOTIFY_CATALOG_BASE_URL',
        'NOTIFY_CATALOG_ENABLED', 'EVENTOMAX_NOTIFY_CATALOG_ENABLED',
        'NOTIFY_CATALOG_TIMEOUT_MS', 'EVENTOMAX_NOTIFY_CATALOG_TIMEOUT_MS',
        'STORAGE_PROVIDER', 'EVENTOMAX_NOTIFY_STORAGE_PROVIDER',
        'STORAGE_LOCAL_BASE_DIR', 'NOTIFY_QUOTE_OUTPUT_DIR',
        'EVENTOMAX_NOTIFY_STORAGE_LOCAL_BASE_DIR', 'EVENTOMAX_NOTIFY_QUOTE_OUTPUT_DIR',
        'STORAGE_S3_BUCKET', 'STORAGE_S3_REGION', 'STORAGE_S3_PREFIX',
        'EVENTOMAX_NOTIFY_STORAGE_S3_BUCKET_NAME', 'EVENTOMAX_NOTIFY_STORAGE_S3_REGION',
        'EVENTOMAX_NOTIFY_STORAGE_S3_PREFIX']
for key in keys:
    value = env.get(key)
    if value is not None and key.endswith('URL'):
        try:
            u = urlsplit(value)
            if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment:
                value = '[URL omitida: requiere revisión privada]'
        except ValueError:
            value = '[URL inválida]'
    print(key + '=' + (value if value is not None else '[ausente]'))
print('NOTIFY_DEFAULT_RECIPIENT_CONFIGURED=' + str(bool(env.get('NOTIFY_DEFAULT_RECIPIENT', '').strip())))
for key in ['SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
            'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'SPRING_PROFILES_ACTIVE']:
    print(key + '_PRESENT=' + str(bool(env.get(key))))
print('MOUNT_COUNT=' + str(len(obj.get('Mounts', []))))
PY
```

Las variables son evidencia de entrada, no de resolución efectiva. Si existen
propiedades JVM, Spring JSON, perfiles o configuración externa, un operador debe
resolver su precedencia en privado y reportar únicamente las propiedades permitidas:
`eventomax.notify.catalog.{base-url,enabled,timeout-ms}` y
`eventomax.notify.storage.{provider,local.base-dir,s3.bucket-name,s3.region,s3.prefix}`,
además de `eventomax.notify.quote.output-dir`. Revisar el `application.yml` del JAR
sin imprimir secretos. No habilitar `/actuator/env` en producción para esta tarea.
Confirmar montajes persistentes y permisos de lectura sin escribir archivos de prueba.

## 3. HTML del artefacto y correo recibido

- Identificar el JAR por hash y compararlo con el artefacto de build del commit candidato.
- Si existen, consultar exclusivamente `git.commit.id` y `build.version` de los metadatos del JAR.
- Comparar la clase `SmtpEmailSender.class` y el recurso de logo por hash con el mismo
  build candidato; no comparar el hash del HTML personalizado con código fuente.
- Registrar presencia de `buildHtmlContent`, `buildTextContent`, `SMTP_ATTACHMENT_ADDED`
  y `branding/eventomax-logo-email.png`. Estos marcadores no prueban igualdad del build.
- Con autorización del dueño del buzón, inspeccionar localmente el MIME original:
  alternativas plain/html, logo CID y adjuntos. No exportar cuerpo, direcciones,
  tokens, identificadores personales ni cabeceras completas al PR.
- Reportar solo diferencias estructurales: tablas/estilos, estado, logo, texto plano,
  adjunto y versión de plantilla si existe. Una captura no demuestra cuál MIME se envió.

## 4. Registro previo y condición de continuidad

Discrepancias conocidas antes de implementar:

1. `origin/develop` local estaba en `1e974d7`; se actualizó a `9848dbe` sin modificar main.
2. Compose publica `CATALOG_BASE_URL`; el código base solo reconoce `CATALOG_SERVICE_URL`.
3. El texto del acta reportada no coincide con el fallback del generador actual.
4. La plantilla elaborada existe en main; no está probada su identidad con la imagen EC2.
5. No hay acceso EC2 verificado en esta sesión. El JAR local no acredita el desplegado.

Estas discrepancias quedan documentadas antes de los cambios. Se continúa únicamente
con correcciones locales y pruebas aisladas expresamente autorizadas; identidad del
build, configuración efectiva, MIME y permisos de red siguen pendientes antes de fusionar
o desplegar. Este procedimiento no autoriza operaciones sobre producción.
