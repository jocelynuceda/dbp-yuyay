# Clasificador de alcance de documentos

Etiqueta el texto que Textract leyó de un documento como `MEDICAL` (uso adecuado), `REVIEW` (no seguro) o `NONMEDICAL` (posible uso ajeno). Es una señal informativa: no bloquea cuentas, no cambia permisos y no escribe en la base de datos.

## Interruptor

| Nivel | Cómo | Efecto |
|---|---|---|
| Arranque | `DOCUMENT_SCOPE_ENABLED` en el `.env` (por defecto `false`) | Estado inicial del clasificador al iniciar el contenedor |
| En caliente | `PUT /api/v1/admin/document-scope` con `{"enabled": true \| false}` (solo ADMIN) | Enciende o apaga sin reiniciar |
| Consulta | `GET /api/v1/admin/document-scope` (solo ADMIN) | Devuelve `enabled` y `modelLoaded` |

- Apagado, el backend responde igual que antes de esta función: `scopeLabel` y `scopeScore` salen en `null` y el resumen por cuenta devuelve `enabled: false` con ceros, sin leer documentos.
- El estado en caliente vive en memoria. Un reinicio vuelve al valor de `DOCUMENT_SCOPE_ENABLED`, y con más de una instancia habría que cambiarlo en cada una.
- Cada cambio queda en el log con el id del administrador y el estado resultante.

Ejemplo para apagarlo:

```bash
curl -X PUT https://<api>/api/v1/admin/document-scope \
  -H "Authorization: Bearer <token de admin>" -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

## Qué expone

- `scopeLabel` y `scopeScore` en las respuestas de adjuntos (subir, listar, ver y reintentar OCR). Solo para documentos con OCR terminado; los audios no se clasifican.
- `GET /api/v1/users/me/document-scope`: `{ enabled, appropriate, unsure, strikes }`, el conteo de los documentos que subió el usuario actual.

La etiqueta se calcula al leer, a partir de `ocrText`. No hay columnas nuevas ni migración: apagar el interruptor no deja datos residuales y cambiar el modelo vuelve a etiquetar todo.

## Comportamiento ante fallos

- Si el modelo no carga (archivo ausente, formato inesperado), el clasificador queda apagado, se registra un aviso y la aplicación arranca igual.
- Si clasificar un documento lanza un error, ese documento sale sin etiqueta. La subida, el OCR y la lectura del documento no dependen del clasificador.
- El texto clínico nunca se escribe en los logs.

## Cómo decide

Modelo: regresión logística sobre TF-IDF de n-gramas de caracteres (3 a 5), exportado a `src/main/resources/document-scope/model.json`; los umbrales están en `classifier.json`.

1. Texto con menos de 12 caracteres alfanuméricos o sin vocabulario conocido: `REVIEW`.
2. Cobertura menor a `DOCUMENT_SCOPE_MIN_COVERAGE` (0,50): `REVIEW`. La cobertura es la fracción de n-gramas del texto que el modelo conoce.
3. Puntaje mayor o igual a `medical_min`: `MEDICAL`. Menor o igual a `nonmedical_max`: `NONMEDICAL`. En medio: `REVIEW`.
4. Un `NONMEDICAL` exige además una cobertura de al menos `DOCUMENT_SCOPE_STRIKE_MIN_COVERAGE` (0,65); si no, queda en `REVIEW`.

El criterio es asimétrico a propósito: marcar como uso ajeno un documento de salud es el error grave, así que ante la duda o un OCR ilegible el resultado es `REVIEW`.

## Verificación

- `DocumentScopeServiceTest` compara el puntaje de Java con el de la referencia en Python para 30 textos (tolerancia 1e-9).
- La misma clase recorre 72 textos que el modelo no vio al entrenar (`held-out.tsv`): ningún documento de salud debe salir `NONMEDICAL`.
- `DocumentScopeSwitchIntegrationTest` cubre el interruptor: apagado por defecto, encendido y apagado en caliente, y acceso solo para administradores.

## Límites conocidos

- El modelo incluido se entrenó con 488 textos (245 de salud y 243 ajenos). Solo 6 derivan de documentos reales; el resto son inventados. No hay una medición con documentos reales de Textract. Por eso viene apagado.
- En los 72 textos no vistos: de 35 documentos de salud, 29 salen `MEDICAL`, 6 `REVIEW` y ninguno `NONMEDICAL`; de 37 ajenos, 20 salen `NONMEDICAL`, 13 `REVIEW` y 4 `MEDICAL`.
- Con ruido de OCR simulado sobre documentos de salud (de 10 % a 60 % de caracteres dañados), ninguno de 975 casos salió `NONMEDICAL`; a partir de 30 % casi todos quedan en `REVIEW`.
- Es un modelo de n-gramas: no entiende el tipo de documento. Un comprobante de farmacia, un tarifario de laboratorio o un texto que repite términos médicos puede salir `MEDICAL`.
- El resumen por cuenta clasifica todos los documentos del usuario en cada consulta; con miles de documentos convendría guardar el resultado.

## Cambiar el modelo

Reemplazar `model.json` y `classifier.json` por los que exporta el notebook de entrenamiento y volver a generar `parity.tsv` y `held-out.tsv` en `src/test/resources/document-scope/` con la referencia en Python. Para evaluar un modelo nuevo hacen falta textos que no se hayan usado para entrenarlo.
