# Adjuntos privados: integración web y móvil

La API conserva `POST` multipart (campo `file`, `consultationId` opcional), `GET` lista,
`GET /{attachmentId}` y `POST /{attachmentId}/ocr-retry`. Los formatos son JPG, PNG y PDF
de una página, y notas de voz M4A y WebM, hasta 10 MB. El audio no pasa por el OCR: se
guarda directamente como `DONE` y se descarga con `GET /{attachmentId}/content`, que
atiende `Range` para el reproductor. Las respuestas existentes no
añaden URLs ni claves S3. Todos los endpoints requieren JWT de usuario y relación
`ACTIVE` con la persona; tanto `PRINCIPAL` como `CAREGIVER` tienen acceso, igual que en
los registros de salud. Una delegación no autoriza estos endpoints.

Base: `/api/v1/care-subjects/{subjectId}/attachments`.

## Abrir una imagen o documento

`GET /{attachmentId}/access`, con `Authorization: Bearer <accessToken>`:

```json
{
  "url": "https://<host-s3>/<objeto>?<firma-temporal>",
  "expiresAt": "2030-01-01T00:05:00Z"
}
```

La respuesta 200 incluye `Cache-Control: no-store` y `Referrer-Policy: no-referrer`.
La URL firma únicamente un GET del objeto exacto, con duración de cinco minutos y
respuesta S3 `Cache-Control: no-store`, tipo de contenido original y presentación
`inline`. `expiresAt` procede del firmante, no de un cálculo independiente.

Solicitar el enlace al abrir el visor, mantenerlo solo en memoria y solicitar otro si
ha vencido. No persistirlo, incluirlo en analítica, logs, notificaciones ni mensajes de
error. En web usar `referrerPolicy="no-referrer"` o `rel="noreferrer"` al abrirlo. S3
recibe su firma temporal; no enviarle el JWT de Yuyay. El bucket permanece privado y
la API no modifica sus ACL ni políticas. S3 debe permitir `GetObject` al rol firmante.
El visor web de imágenes o PDF puede usar la URL directamente; descargar con `fetch`
desde web requiere CORS del bucket para el origen de la aplicación.

La URL es una credencial: quien la tenga puede abrirla hasta su vencimiento. Revocar
la relación de cuidado impide nuevos enlaces; los ya emitidos pueden seguir siendo
válidos hasta cinco minutos (o menos si caducan antes las credenciales del rol AWS).
No se verifica la existencia del objeto al firmar; si falta o S3 rechaza el GET, el
visor debe mostrar un error y permitir reintentar. El error de S3 no contiene el
contrato JSON de la API.

## Eliminar

`DELETE /{attachmentId}`, con el mismo JWT, devuelve 204 sin cuerpo. Solo puede
eliminarlo quien lo subió o el `PRINCIPAL` de la persona: un cuidador secundario
borra lo suyo, pero no lo que subió otro. Tras el éxito no aparece en la lista y sus
rutas de detalle, acceso, reintento OCR y otro DELETE devuelven 404. Todo borrado
queda asentado en la bitácora con el autor, aunque el archivo ya no exista.

| Estado | Significado |
| --- | --- |
| 401 | JWT ausente, inválido o vencido |
| 403 | Sin relación activa, o no es quien lo subió ni el `PRINCIPAL` |
| 404 | Adjunto ausente o perteneciente a otra persona accesible |
| 409 | El OCR sigue en curso, o alguna versión de un registro de salud referencia el adjunto |
| 502 | No fue posible generar el enlace temporal |

Ante 409 mostrar el mensaje de la API; no eliminar localmente la tarjeta. Son dos casos
distintos: el OCR en curso se resuelve reintentando en unos segundos, la evidencia
referenciada no se resuelve nunca. La
respuesta no expone detalles del SDK, bucket o credenciales. Los errores OCR también
se guardan y se presentan como mensaje seguro fijo, incluso para errores históricos; el estado `FAILED` y el reintento conservan su
contrato. Editar un registro de salud conserva el vínculo de evidencia en su nueva
versión.

## Consistencia y límites operativos

Mientras el OCR corre, el adjunto está en `PENDING` y DELETE responde 409 en lugar de
esperar. Así el listener nunca escribe sobre una fila ya borrada y ninguna petición
HTTP queda bloqueada durante la llamada a Textract. El reintento de OCR vuelve a dejar
el adjunto en `PENDING`, por lo que durante ese lapso tampoco se puede borrar. No se
realiza OCR dentro de la petición HTTP de subida.

DELETE comprueba las referencias clínicas con `existsBySourceAttachmentId` antes de
borrar; la FK queda como última barrera ante una referencia concurrente. La limpieza
del objeto corre **después** del commit y es de mejor esfuerzo: si S3 falla, la fila
ya no vuelve y el objeto queda suelto. Esa es la dirección elegida a propósito, porque
un objeto huérfano es recuperable y una fila que apunta a un archivo inexistente no.

PostgreSQL y S3 no comparten transacción. Con esta estrategia el desajuste posible es
siempre el mismo: objeto en el bucket sin fila que lo referencie. El job periódico de
la issue #42 es el que los recoge. No se promete atomicidad distribuida.
Si el bucket tiene versionado o retención, `DeleteObject` sin versionId puede crear
un marcador de borrado y conservar versiones antiguas según la política del bucket;
204 no certifica una purga histórica. Los clientes que ya descargaron bytes pueden
conservarlos. No se cambian configuraciones AWS desde este código.

## Validación local

Con Java 26, ejecutar `mvn -B verify`. Se usan únicamente H2 bajo el perfil de prueba
existente y mocks para S3/Textract. Las pruebas de firma usan credenciales ficticias
locales y no consultan AWS. La cobertura incluye TTL firmado, no-store, permisos,
aislamiento por persona, borrado y rollback, evidencia referenciada, edición clínica,
subida/listado y concurrencia OCR/DELETE.

## Integración local comprobada — 4 de noviembre de 2026

Se volvió a ejecutar `mvnw.cmd -B verify` con Java 26: 103 pruebas, sin fallos,
errores ni omitidos. Además se ejecutó la aplicación empaquetada contra PostgreSQL
16 local aislado y S3/Textract reales, consumida por Expo web en Chrome sin
interceptar peticiones de la API.

Se verificaron subida PNG/PDF, OCR de imagen en DONE, acceso firmado con no-store,
lectura de los bytes originales, eliminación de metadatos y objeto S3, rechazo
403 de usuarios ajenos/pendientes/revocados y acceso del cuidador activo. El PATCH
conservó sourceAttachment y el DELETE de esa evidencia respondió 409 sin perder
el documento. Archivos falsificados o mayores de 10 MB recibieron 400.

El ejecutor local usa el perfil dev con PostgreSQL y correo simulado; no añade un
perfil H2 ni modifica la configuración de producción. Las credenciales temporales
AWS se pasan únicamente al proceso de prueba y no se escriben en archivos. Todos
los objetos S3 creados en esta prueba se eliminaron y la base temporal se retiró.

Esto verifica los endpoints nuevos en ejecución local; no significa que ya estén
desplegados en EC2. La prueba física de cámara/HEIC en iPhone sigue pendiente.

## Diagnóstico de fallos OCR

El WARN del listener identifica el adjunto, el tipo de excepción y `causeType`, el último
tipo observado dentro de un recorrido limitado de ocho niveles. Este último permite
distinguir, por ejemplo, un timeout detrás de un wrapper sin copiar sus mensajes; en
cadenas más largas o cíclicas no afirma identificar la causa raíz completa. Si encuentra un
`SdkServiceException` entre la excepción y sus primeras siete causas, añade tipo del
error de servicio, estado HTTP y requestId; con `AwsServiceException` añade también el
código AWS. Ese requestId permite correlacionar el fallo con la operación del proveedor.
Los identificadores admiten solo ASCII alfanumérico, punto, guion y guion bajo; se limitan
a 128 caracteres (64 para el código AWS) y un campo inválido se sustituye por
`unavailable` completo. El recorrido de causas se limita a ocho niveles.

El log no incluye mensajes de excepción, stack traces, causas completas, cuerpos HTTP,
claves S3, URLs firmadas ni contenido OCR. Los mensajes del SDK pueden contener esos datos;
por eso se registra contexto seguro en vez de pasar el Throwable al logger. Este cambio
no modifica `ocrError` persistido ni su presentación segura en la API, permisos, acceso
a archivos o comportamiento de reintento.
