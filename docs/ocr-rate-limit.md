# Límite de OCR por usuario

Limita cuántos documentos puede mandar a leer cada usuario. Protege el costo de Textract y la cola de OCR compartida frente a ráfagas (un cliente con errores, un script, subidas masivas). Usa solo quién sube y cuándo: nunca mira el contenido del documento.

## Reglas

Se evalúan en este orden. Un valor `0` (o menor) apaga la regla; con las tres apagadas el límite no hace nada.

| Regla | Variable de entorno | Por defecto | Qué hace |
|---|---|---|---|
| Intervalo mínimo | `OCR_MIN_INTERVAL_SECONDS` | `5` | Segundos que deben pasar entre un documento aceptado y el siguiente |
| Documentos en proceso | `OCR_MAX_PENDING` | `3` | Máximo de documentos del usuario con OCR `PENDING` subidos en los últimos 10 minutos |
| Cupo diario | `OCR_DAILY_LIMIT` | `30` | Máximo de solicitudes de OCR del usuario en una ventana móvil de 24 horas |

Solo una solicitud aceptada gasta turno: si se rechaza por cualquiera de las tres reglas, no cambia el intervalo ni el cupo diario.

## Dónde se aplica

- Subida de imágenes y PDF (`POST` de adjuntos).
- Reintento de OCR de un adjunto que falló.
- Los audios no pasan por el OCR y no cuentan.
- Un archivo inválido (vacío, formato no soportado, firma que no coincide, consulta ajena) responde 4xx antes del límite y no gasta turno. Tampoco se guarda nada en el almacenamiento si el límite rechaza la solicitud.

## Respuesta

`429 Too Many Requests` con el encabezado `Retry-After` (segundos) y el mismo cuerpo de error del resto de la API:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
```

```json
{
  "timestamp": "2026-11-14T15:30:00.123Z",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Espera 3 segundos antes de subir otro documento.",
  "path": "/api/v1/care-subjects/7/attachments",
  "fieldErrors": null
}
```

Para documentos en proceso, `Retry-After` es 10. Para el cupo diario es el tiempo hasta que la solicitud más antigua salga de la ventana de 24 horas.

## Límites conocidos

- El intervalo mínimo vive en memoria: se reinicia con la aplicación y es por instancia. Con varias instancias, cada una lleva su propio conteo.
- El conteo de 24 horas vive en la tabla `ocr_request_log` (solo `user_id` y `requested_at`); las filas viejas de un usuario se borran cuando ese usuario sube un documento nuevo.
- La regla de documentos en proceso solo mira los últimos 10 minutos: un documento que queda `PENDING` más tiempo deja de contar.
