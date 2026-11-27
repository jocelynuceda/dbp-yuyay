# Horario de medicamentos y tomas

Un medicamento (registro de categoría `MEDICATION`) puede tener un horario: las horas de cada toma, los
días y desde y hasta cuándo. Con eso el inicio de la web y de la app arma la agenda «Hoy» y cualquier
cuidador puede marcar una toma como tomada o no tomada. Yuyay no propone dosis ni horarios: se guardan
los que escribe la persona a partir de su receta. La dosis sigue siendo la del registro.

## Horario

`PUT /api/v1/care-subjects/{subjectId}/health-entries/{entryId}/schedule` crea o reemplaza el horario:

```json
{ "times": ["08:00", "20:00"], "daysOfWeek": ["MONDAY", "THURSDAY"], "startDate": "2026-11-27", "endDate": null }
```

- `times`: de 1 a 8 horas; se ordenan y se quitan las repetidas.
- `daysOfWeek`: vacío u omitido = todos los días.
- `endDate`: omitido = tratamiento continuo. No puede ser anterior a `startDate` (409).
- Solo para medicamentos (409 en otras categorías). Reemplazar el horario conserva las tomas marcadas.

`GET` del mismo recurso devuelve el horario (404 si no tiene) y `DELETE` lo quita junto con sus tomas.
`GET /api/v1/care-subjects/{subjectId}/medication-schedules` lista los horarios de una persona.

## Agenda

`GET /api/v1/me/medication-agenda?from=2026-11-27&to=2026-12-03` (`to` opcional, máximo 42 días) devuelve
las tomas de todas las personas que cuido, en orden de día y hora:

```json
{ "scheduleId": 4, "healthEntryId": 12, "careSubjectId": 1, "careSubjectName": "Rosa",
  "medication": "Losartán", "dose": "50 mg", "date": "2026-11-27", "time": "08:00:00",
  "status": "TAKEN", "markedByName": "Ana Torres", "markedAt": "2026-11-27T13:05:00Z" }
```

`status` es `null` si nadie marcó la toma. Un medicamento eliminado sale de la agenda.

## Marcar una toma

- `PUT /api/v1/medication-schedules/{scheduleId}/doses` con `{ "date", "time", "status" }`. Si ya estaba
  marcada, se reemplaza y queda quién la cambió. La toma debe estar en el horario. Plazos (si no, 409):
  - Antes de **2 horas** de su hora (o en días siguientes): no se puede marcar.
  - Desde 2 h antes hasta **30 minutos** después: `TAKEN` (tomada) o `SKIPPED` (no tomada).
  - Pasados los 30 minutos: solo `LATE` («se tomó tarde»). Así una toma que sí se dio no queda como
    no tomada y nadie repite la dosis por error.
- **Sin marcar y pasados los 30 minutos, una toma cuenta como no tomada.** La API la devuelve con
  `status: null` y los clientes la muestran como «No tomada».
- `DELETE /api/v1/medication-schedules/{scheduleId}/doses?date=…&time=…` deshace la marca.

Cualquier cuidador con acceso activo a la persona puede ver, cambiar y marcar. Las tablas nuevas
(`medication_schedules`, `medication_schedule_times`, `medication_schedule_days`, `dose_logs`) las crea
Hibernate (`ddl-auto=update`).

## Recordatorios

Los avisos de cada toma suenan en la app móvil con notificaciones locales, a partir de la agenda, y se
pueden posponer desde la notificación. La web no avisa: sus notificaciones solo llegan con la pestaña
abierta.
