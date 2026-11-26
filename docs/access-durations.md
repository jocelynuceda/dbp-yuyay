# Duración del acceso compartido

## Enlaces de resumen

`POST /api/v1/handoffs/{handoffId}/sessions` requiere exactamente uno de estos campos de vigencia.

| Campo | Valores |
| --- | --- |
| `expiresInMinutes` | Entero de 1 a 4320 |
| `expiresInHours` | Entero de 1 a 72 para clientes anteriores |

El campo `sessionWindowMinutes` es obligatorio y acepta enteros de 1 a 4320.
La ventana debe ser menor o igual a la vigencia del enlace. Los clientes nuevos
convierten la cantidad y unidad seleccionadas a minutos antes de enviar la
solicitud. La interfaz comienza con 30 minutos de lectura.

```json
{
  "expiresInMinutes": 30,
  "sessionWindowMinutes": 25,
  "requireAccessCode": true
}
```

El servidor calcula `expiresAt` sumando la vigencia al instante de creación.
La ventana de lectura es común a todos los visitantes y empieza tras la primera
apertura válida del enlace. El acceso vence en el menor de los instantes
`expiresAt` y `firstOpenedAt + sessionWindowMinutes`. Las aperturas posteriores
conservan la primera apertura.

Los valores inválidos devuelven HTTP 400. Los errores de rango y de relación
entre campos identifican el campo afectado en `fieldErrors`.

`GET /api/v1/public/handoff-sessions/{token}/access-requirements` devuelve solo
`accessCodeRequired` para un enlace vigente. No abre la sesión, no devuelve
datos de salud y permite conservar la lectura de enlaces antiguos sin código.

## Delegaciones

`POST /api/v1/care-subjects/{subjectId}/delegations` recibe un `validUntil` futuro
en formato ISO 8601, calculado al sumar la duración elegida al momento de
otorgar el acceso. El servidor conserva el instante elegido hasta un máximo de
365 días desde la creación. Si el dispositivo adelanta el vencimiento hasta
una hora sobre ese máximo, el servidor acepta la solicitud y guarda el límite
de 365 días. Un exceso mayor devuelve HTTP 400. La respuesta incluye el
vencimiento realmente guardado.

## Despliegue

Desplegar primero el backend con soporte para `expiresInMinutes`, luego los
clientes web y móvil. Las aplicaciones instaladas pueden seguir enviando
`expiresInHours` dentro de los límites descritos. La actualización usa el
esquema de base de datos actual.
