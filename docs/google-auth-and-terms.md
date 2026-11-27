# Registro con Google y términos de cuenta

Las rutas de esta guía usan el prefijo `/api/v1/auth`. La web y la app móvil envían
las solicitudes a Yuyay por HTTPS.

## Consultar los términos

`GET /terms` es público y devuelve `200 OK` con `version` y `text` en JSON.
`text` contiene los términos de uso y el aviso de privacidad íntegros en español.
La versión vigente es `1.2`. El cliente presenta el texto y solicita una confirmación
explícita antes de enviar un alta. Conviene volver a consultar el endpoint si la
versión resulta obsoleta.

## Alta por correo

`POST /register` devuelve `201 Created`. Conserva los campos anteriores y exige
la confirmación booleana `termsAccepted: true` y la cadena `termsVersion` vigente.
`dni` es obligatorio (ocho dígitos) y `gender` se puede omitir. `name` es el **nombre de usuario**
que se muestra en la aplicación; puede repetirse y no se contrasta con el documento.

`POST /dni-lookup` con `{"dni":"…"}` devuelve `{"fullName":"…"}` para mostrarlo antes de crear la
cuenta. El alta vuelve a consultar y guarda ese nombre en `fullName`, aparte de `name`; no usa el
que envíe el cliente. El DNI no se guarda. Un documento inexistente devuelve 400 en el campo `dni`;
un fallo del proveedor o una respuesta ilegible, 503 `DNI_LOOKUP_UNAVAILABLE`.

Sin `RENIEC_API_TOKEN` la consulta responde 503 `DNI_LOOKUP_NOT_CONFIGURED`: el cliente deja
escribir el nombre completo y el alta lo guarda tal cual. El DNI sigue siendo obligatorio.

Como la consulta es anónima y devuelve nombres, quien conozca un DNI puede saber a quién pertenece.
El presupuesto de consultas limita ese riesgo, pero no lo elimina.

El presupuesto del registro por día UTC se comparte entre las instancias mediante la base de datos.
Por defecto se permiten 10 consultas por origen y 100 en total (cada alta usa dos), configurables con
`REGISTRATION_DNI_PER_ORIGIN` y `REGISTRATION_DNI_DAILY_LIMIT`. Al agotarse un cupo se responde 429
antes de llamar al proveedor. Sin proveedor configurado no se descuenta cupo. Los contadores por origen usan
una clave derivada que cambia diariamente y se purgan cada hora después de vencer.
Tomcat obtiene el origen del proxy inmediato de confianza. Con el proxy de Vercel varios usuarios
pueden compartir una dirección de salida. Si cambia la red de Docker, configurar las direcciones
de Caddy en `YUYAY_TRUSTED_PROXY_CIDRS`. El puerto del backend debe seguir ligado a loopback.

```json
{
  "email": "cuenta@example.invalid",
  "password": "Ejemplo1234",
  "name": "Persona de prueba",
  "dni": "00000000",
  "termsAccepted": true,
  "termsVersion": "1.2"
}
```

## Alta e ingreso con Google

`POST /google` recibe el **ID token** que obtiene el cliente web o móvil de Google.
El primer intento puede enviar `{"idToken":"<ID token>"}`. Para una cuenta nueva,
el backend devuelve `428` con `currentVersion`. El cliente consulta `GET /terms`,
muestra el texto y espera la confirmación explícita del usuario. Al confirmar,
envía `acceptedTermsVersion` con la versión que mostró.

```json
{
  "idToken": "<ID token>",
  "acceptedTermsVersion": "1.2"
}
```

Si el usuario cancela, el cliente descarta el intento de registro. Si la versión
cambió, el cliente obtiene y presenta el texto vigente antes de solicitar una
nueva confirmación. Una cuenta vinculada puede iniciar sesión sin enviar una
versión ni repetir su aceptación.

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
| 400 en `/register`, 428 en `/google` | `TERMS_ACCEPTANCE_REQUIRED` | Presentar el texto vigente y solicitar confirmación para crear la cuenta. |
| 400 en `/register`, 428 en `/google` | `TERMS_VERSION_OUTDATED` | Volver a obtener el texto y solicitar confirmación de la versión nueva. |
| 400 en `/google` | `TERMS_VERSION_INVALID` | Enviar la versión como una cadena de hasta 32 caracteres, con contenido y sin espacios en los extremos. |
| 401 | `GOOGLE_TOKEN_INVALID` | Reiniciar el flujo de Google y obtener un ID token nuevo. |
| 409 | `GOOGLE_EMAIL_CONFLICT` | Mostrar el mensaje del servidor, que indica el método de ingreso de la cuenta existente. |
| 503 | `GOOGLE_AUTH_UNAVAILABLE` | Mostrar que Google no está disponible en este momento. |

El `428` es la señal acordada por Yuyay para el alta con Google. Incluye
`currentVersion` con la versión vigente y `Cache-Control: no-store`.
`acceptedTermsVersion` omitido o `null` indica que aún falta la confirmación.

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
  transacción. La versión 1.2 se sirve desde `terms/yuyay-1.2.txt`. Una nueva
  versión del documento debe tener su propio recurso y actualizar la versión
  vigente. Una eventual nueva aceptación de cuentas existentes tendrá un flujo
  explícito.
- La aceptación queda asociada a la cuenta. La declaración de autorización de
  cada perfil de cuidado tiene su propio contrato y registro.

La verificación sigue las [guías oficiales de Google](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
