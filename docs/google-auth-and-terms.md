# Registro con Google y términos de cuenta

Las rutas de esta guía usan el prefijo `/api/v1/auth`. La web y la app móvil envían
las solicitudes a Yuyay por HTTPS.

## Consultar los términos

`GET /terms` es público y devuelve `200 OK` con `version` y `text` en JSON.
`text` contiene los términos de uso y el aviso de privacidad íntegros en español.
La versión vigente es `1.0`. El cliente presenta el texto y solicita una confirmación
explícita antes de enviar un alta. Conviene volver a consultar el endpoint si la
versión resulta obsoleta.

## Alta por correo

`POST /register` devuelve `201 Created`. Conserva los campos anteriores y exige
la confirmación booleana `termsAccepted: true` y la cadena `termsVersion` vigente.
`gender` sigue siendo opcional.

```json
{
  "email": "cuenta@example.invalid",
  "password": "Ejemplo1234",
  "name": "Persona de prueba",
  "termsAccepted": true,
  "termsVersion": "1.0"
}
```

## Alta e ingreso con Google

`POST /google` recibe el **ID token** que obtiene el cliente web o móvil de Google.
Para intentar ingresar con una cuenta existente basta enviar `{"idToken":"<ID token>"}`.
Para el alta se agregan los campos de términos.

```json
{
  "idToken": "<ID token>",
  "termsAccepted": true,
  "termsVersion": "1.0"
}
```

Yuyay valida la firma RS256 con las claves públicas de Google, el emisor, las
audiencias admitidas, la caducidad, el correo verificado y el `sub`. La cuenta
se identifica por `sub`. Una cuenta ya vinculada conserva su identificador aun
si Google comunica otro correo en un acceso posterior. Una coincidencia de correo
con otra cuenta de Yuyay produce un conflicto. Si la cuenta usa contraseña, el
mensaje indica que debe ingresar con correo y contraseña.

El alta y el ingreso por Google devuelven `200 OK`. Ambos métodos reciben la
misma sesión de Yuyay que el login por correo.

```json
{
  "accessToken": "<JWT de Yuyay>",
  "refreshToken": "<refresh token de Yuyay>",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "user": {
    "id": 123,
    "email": "cuenta@example.invalid",
    "name": "Persona de prueba",
    "role": "USER",
    "gender": null,
    "createdAt": "2026-11-20T18:00:00Z"
  }
}
```

`POST /refresh` y `POST /logout` siguen recibiendo `{"refreshToken":"..."}`.
Las cuentas Google usan el acceso con Google. El login con contraseña falla y
el flujo de recuperación por correo responde como ante una cuenta sin credencial
de contraseña. Las cuentas de correo preexistentes pueden seguir iniciando sesión,
renovando su sesión y recuperando su contraseña sin enviar aceptación.

## Errores para los clientes

Los siguientes errores agregan `code` al objeto habitual de error
(`timestamp`, `status`, `error`, `message`, `path` y `fieldErrors`).

| HTTP | `code` | Comportamiento |
| --- | --- | --- |
| 400 | `TERMS_ACCEPTANCE_REQUIRED` | Presentar el texto vigente y solicitar confirmación para crear la cuenta. |
| 400 | `TERMS_VERSION_OUTDATED` | Volver a obtener el texto y solicitar confirmación de la versión nueva. |
| 401 | `GOOGLE_TOKEN_INVALID` | Reiniciar el flujo de Google y obtener un ID token nuevo. |
| 409 | `GOOGLE_EMAIL_CONFLICT` | Mostrar el mensaje del servidor, que indica el método de ingreso de la cuenta existente. |
| 503 | `GOOGLE_AUTH_UNAVAILABLE` | Mostrar que Google no está disponible en este momento. |

Un ID token ausente, vacío o mayor a 8192 caracteres produce un 400 de validación.
El registro duplicado por correo conserva su 409. Ante un alta desde un APK
anterior que omite la aceptación, el servidor devuelve `TERMS_ACCEPTANCE_REQUIRED`
y el mensaje indica actualizar Yuyay si la opción no aparece.

## Configuración y despliegue

- Configurar `GOOGLE_CLIENT_IDS` como lista de identificadores OAuth públicos
  separados por comas. Vacío deshabilita `POST /google` con respuesta 503.
  Usar el identificador del cliente web de Google, con formato
  `google-web-client-id.apps.googleusercontent.com`.
  En móvil se configura como `webClientId` y debe coincidir con el `aud` que envía
  el SDK. Los identificadores nativos y firmas de Android/iOS se configuran en
  Google Cloud. El backend necesita acceso HTTPS a `www.googleapis.com` para
  obtener las claves públicas.
- Hibernate incorpora `google_subject`, `terms_version` y `terms_accepted_at` a
  `users` con el esquema actual. En el arranque se elimina el `NOT NULL`
  histórico de `password_hash` y se crea el índice único
  `uq_users_google_subject`. El usuario de base debe poder modificar el esquema,
  igual que con `ddl-auto=update`. Se conservan los hashes y las cuentas anteriores.
- El alta guarda versión y fecha del servidor con la cuenta en la misma
  transacción. La versión 1.0 se sirve desde `terms/yuyay-1.0.txt`. Una nueva
  versión del documento debe tener su propio recurso y actualizar la versión
  vigente. Una eventual nueva aceptación de cuentas existentes tendrá un flujo
  explícito.
- La aceptación queda asociada a la cuenta. La declaración de autorización de
  cada perfil de cuidado tiene su propio contrato y registro.

La verificación sigue las [guías oficiales de Google](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
