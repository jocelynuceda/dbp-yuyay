# Preparación de citas y confirmación revisada (#68)

Ambas operaciones requieren el JWT del cuidador y acceso activo a la persona.
PRINCIPAL y CAREGIVER pueden preparar y confirmar.

## Preparación

GET /api/v1/consultations/{id}/preparation devuelve 200 con consultationId,
careSubjectId, reason, preparationToken y categories. Cada categoría contiene
categoryCode y entries. Cada entrada contiene entryId, versionId y version
(con el contrato HealthEntryVersionResponse existente, incluido su id).

Las categorías se ordenan por código ascendente y las entradas por entryId
ascendente. Sólo se incluyen entradas vigentes y su última versión. Sin
entradas se devuelve categories vacío. No se crean resúmenes ni sesiones.

## Confirmación

POST /api/v1/consultations/{id}/preparation/confirm devuelve 200 tanto en el
primer guardado como en reintentos idénticos. Cuerpo ficticio:

```json
{
  "requestId": "56269860-6175-42b1-829e-4afed9a03f9a",
  "preparationToken": "<copiar el token de 64 caracteres recibido en preparación>",
  "reason": "Control ficticio revisado",
  "questions": "Pregunta ficticia para el médico",
  "entries": [
    {"entryId": 12, "versionId": 31},
    {"entryId": 8, "versionId": 20}
  ]
}
```

requestId es UUID obligatorio. preparationToken es obligatorio y tiene 64
caracteres. reason es obligatorio, hasta 10000 caracteres. questions es opcional,
hasta 10000 caracteres. entries es obligatorio, admite entre 0 y 500 elementos,
cada uno con ids positivos. El orden de entries se conserva al reabrir y compartir.

Respuesta HandoffDetailDTO existente, con id, careSubjectId, createdByUserId,
reason, questions, createdAt e items. Cada item devuelve healthEntryId,
healthEntryVersionId y el contenido congelado de la versión (entre otros campos
title, details, dose, frequency, occurredOn, confidenceLevel y notes).
No se crea ningún enlace al confirmar.

## Errores y reintentos

- 400 ante campos inválidos, ids repetidos o entradas inexistentes/de otra persona.
- 401 ante JWT ausente o inválido.
- 403 cuando falta acceso, también al reintentar un resultado guardado.
- 404 cuando la cita no existe.
- 409 si cambió la cita, una versión elegida o se eliminó una entrada, o si se
  reutiliza requestId con contenido distinto.

Se usa el formato ErrorResponseDTO global. Los errores de selección no devuelven
datos del registro ajeno. El token de preparación detecta cambios de fecha, hora,
motivo y notas. Las versiones se verifican por sus ids, sin sustituirlas.
Una nueva entrada no seleccionada no invalida la selección.

La clave se acota a usuario y operación de confirmación. El fingerprint incluye la
cita, el token y todos los campos de entrada, incluido el orden. Un bloqueo
transaccional de usuario y una restricción única impiden duplicados. La
confirmación bloquea la cita, la persona y las entradas seleccionadas, y vuelve
a comprobar acceso antes de persistir. Los reintentos guardados se recuperan
antes de comparar las versiones actuales. La clave vive mientras exista el
resumen. Tras eliminarlo no se debe reutilizar esa clave.

## Integración del frontend #51

1. Consultar la preparación y mostrar reason y las categorías.
2. Conservar preparationToken, entryId y versionId exactos de la revisión.
3. Enviar motivo/preguntas revisados y la selección ordenada con un UUID nuevo.
4. Conservar la misma clave y cuerpo para reintentos de red. Ante un 409 por
   cambios, recargar la propuesta y revisar antes de confirmar con una clave nueva.
5. Con el id devuelto, compartir mediante POST /api/v1/handoffs/{id}/sessions,
   como acción explícita posterior.

La creación manual POST /api/v1/care-subjects/{id}/handoffs conserva el cuerpo
reason, questions y healthEntryIds. Los enlaces anteriores siguen leyendo las
versiones guardadas. El orden de elementos antiguos sin posición se estabiliza
por id, sin modificar su contenido.

## Persistencia y verificación

Se añaden a handoffs confirmation_request_id y confirmation_fingerprint,
la restricción única por created_by/confirmation_request_id, y
handoff_items.confirmed_position (nullable para los resúmenes antiguos).
Se conserva el mecanismo de esquema del repositorio. No se cambia producción.

Ejecutar mvnw.cmd -B verify en Windows. Las pruebas de integración usan H2 en modo
PostgreSQL y datos sintéticos. Validar además los bloqueos y el DDL en PostgreSQL
local antes de desplegar.
