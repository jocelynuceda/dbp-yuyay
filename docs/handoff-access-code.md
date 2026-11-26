# Código temporal para enlaces de resumen

La protección es opcional por sesión y se activa al crearla con `requireAccessCode: true`.
Los enlaces existentes y los clientes que omiten ese campo conservan su contrato. No hay
un endpoint para quitar la protección de una sesión ya creada. Esta verificación añade
un secreto de corta duración al enlace; no autentica la identidad profesional declarada,
no es MFA y no se presenta como equivalente a la seguridad de una aplicación bancaria.

## Contrato HTTP

- `POST /api/v1/handoffs/{handoffId}/sessions` acepta `requireAccessCode?: boolean` junto
  con `expiresInHours` y `sessionWindowMinutes`. La respuesta y el listado de sesiones
  añaden `accessCodeRequired: boolean`; nunca incluyen semilla o código.
- `GET /api/v1/handoff-sessions/{sessionId}/access-code` requiere JWT del creador con
  relación de cuidado activa o del PRINCIPAL activo. Devuelve
  `{code: "012345", consumed: false, expiresAt, serverTime, periodSeconds: 30}`.
  Después de usar el código de ese periodo devuelve `code: null, consumed: true` hasta
  el periodo siguiente. `expiresAt` es el menor entre el fin del periodo y el fin de
  vigencia de la sesión. Ambas horas son ISO UTC. Responde 409 para sesiones sin código,
  410 al caducar o revocarse, 401 sin JWT y 403 sin autorización activa.
- `POST /api/v1/public/handoff-sessions/{token}/open` conserva `visitorName` y
  `visitorRole` y añade `accessCode?: string`. Una sesión protegida exige el código
  actual de seis dígitos. El éxito devuelve el resumen y `accessGrant` opaco,
  `accessExpiresAt` y `serverTime`. En sesiones antiguas los dos primeros son `null`.
  La ventana de lectura empieza sólo tras verificar un código correcto y no se
  extiende al abrir otra vez. La rotación del código no acorta un permiso ya emitido.
- Para una sesión protegida, el GET original
  `/api/v1/public/handoff-sessions/{token}/versions/{versionId}/source/content` requiere
  además `X-Handoff-Access: <accessGrant>`. CORS permite esa cabecera. Haber abierto
  el enlace desde otro navegador no autoriza el GET: el permiso debe pertenecer a esa
  misma sesión. Continúan las comprobaciones de versión incluida, persona y adjunto.

GET del código, POST de apertura y GET del original devuelven `Cache-Control: no-store`
y `Referrer-Policy: no-referrer`. El cliente mantiene código y permiso sólo en memoria,
no los guarda en almacenamiento persistente, URLs, logs ni analítica. Para mostrar el
documento usa fetch con cabecera y un visor local/blob; no coloca el permiso en la URL.
Revocar una sesión o vencer su ventana impide nuevos GET, sin retirar bytes descargados.

Errores de apertura/documento con el campo JSON `code`:

| HTTP | code | Acción del cliente |
| --- | --- | --- |
| 403 | `HANDOFF_CODE_REQUIRED` | Pedir el código al cuidador |
| 403 | `HANDOFF_CODE_INVALID` | Pedir el código actual; no conservar el anterior |
| 403 | `HANDOFF_CODE_USED` | Esperar el siguiente periodo; no reusar el mismo código |
| 429 | `HANDOFF_CODE_RATE_LIMITED` | Esperar que termine la ventana de intentos |
| 403 | `HANDOFF_ACCESS_GRANT_REQUIRED` | Abrir el resumen con código primero |
| 403 | `HANDOFF_ACCESS_GRANT_INVALID` | Reabrir con un código vigente |

El token de enlace desconocido da 404; la caducidad absoluta, de lectura o revocación
da 410 antes de verificar código/permiso. Un dato de visitante inválido sigue dando 400.

## Implementación y concurrencia

La generación, verificación, limitación y permisos residen en `com.yuyay.security.handoff`.
Consulta sólo coordina autorización del cuidador, contenido y auditoría. Cada sesión
protegida tiene una semilla independiente de 32 bytes de `SecureRandom`, persistida en
`handoff_code_states`; no se transmite al cliente ni se registra en logs. Deben protegerse
los permisos de esa tabla, las copias y el almacenamiento de la base de datos.

El generador usa TOTP HMAC-SHA256, truncamiento dinámico y seis dígitos conservando ceros
iniciales. El paso es exactamente 30 segundos según el `Clock` inyectado del servidor.
No se admiten los periodos anterior o siguiente. La comparación de un código con formato
válido usa `MessageDigest.isEqual`. El servidor y sus instancias necesitan un reloj UTC
sincronizado; el frontend calcula el contador con `serverTime`, no presupone reloj local.

La transacción bloquea primero la fila de sesión y después su estado de código. Refresca
la sesión bajo bloqueo para no utilizar una primera apertura/revocación precargada antes
de esperar. `lastAcceptedStep` impide aceptar dos veces el mismo paso, incluso en solicitudes
simultáneas, y rechaza pasos anteriores al último aceptado si el reloj retrocede.
Al acertar se guardan consumo, primera apertura, hash del permiso y su vencimiento
en la misma transacción de apertura. Otros errores revierten todos esos cambios.
La bitácora se escribe de forma asíncrona después del commit, en otra transacción;
un fallo posterior de auditoría no revierte el consumo del código ni el permiso emitido.

Cinco códigos incorrectos por sesión en una ventana de cinco minutos se responden con 403.
Desde el siguiente intento se devuelve 429, incluso para un código correcto, hasta el fin
de los cinco minutos contados desde el primer fallo. La ventana no se reinicia por un acierto.
Código ausente y repetición de un código ya consumido no suman adivinaciones. Tanto el
coordinador `/open` como el servicio de seguridad usan `noRollbackFor=HandoffCodeException`
específico: sólo los errores esperados de código confirman los contadores pese al 403/429.
No se abren transacciones anidadas que retengan dos conexiones por petición. Los contadores
son persistentes y compartidos por todas las instancias, no límites en memoria por IP.

El permiso se genera con otros 32 bytes aleatorios; sólo SHA-256 se almacena en
`handoff_access_grants`. Su vencimiento es el menor entre la fecha absoluta del enlace y
la primera apertura más la ventana de lectura. Cada GET vuelve a validar la sesión y el
permiso, por lo que un permiso no sobrevive a la revocación. La misma sesión puede emitir
otro permiso en un periodo posterior sin prolongar su ventana global.

## Esquema y orden de despliegue

No se han ejecutado migraciones en servicios externos. En PostgreSQL aplicar mediante el
procedimiento de migraciones del entorno, antes del despliegue, el esquema equivalente:

```sql
ALTER TABLE handoff_sessions
  ADD COLUMN IF NOT EXISTS access_code_required boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS handoff_code_states (
  session_id bigint PRIMARY KEY REFERENCES handoff_sessions(id) ON DELETE CASCADE,
  secret bytea NOT NULL,
  failed_attempts integer NOT NULL DEFAULT 0,
  failure_window_started_at timestamptz,
  last_accepted_step bigint
);

CREATE TABLE IF NOT EXISTS handoff_access_grants (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  session_id bigint NOT NULL REFERENCES handoff_sessions(id) ON DELETE CASCADE,
  token_hash varchar(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_grant_session ON handoff_access_grants(session_id);
```

Los datos antiguos se interpretan como `accessCodeRequired=false`; no se fabrican semillas
para ellos. La creación protegida guarda flag y estado de seguridad en una transacción.
Una sesión marcada protegida sin estado no se abre: devuelve error y no pasa a modo legado.

Primero aplicar esquema compatible, después desplegar y retirar **todas** las instancias
backend anteriores, y sólo entonces habilitar `requireAccessCode:true` en web/móvil. Un
backend antiguo ignora la nueva bandera: no debe seguir atendiendo rutas públicas cuando
ya existen sesiones protegidas. Para volver a un backend antiguo hay que revocar antes
las sesiones protegidas o cerrar el acceso público; bajar sólo el frontend mantiene la
protección del backend actual. No borrar la columna/tablas mientras existan sesiones.

Los permisos vencidos pueden purgarse como mantenimiento por `expires_at`, sin recuperar
su texto original. La FK elimina estados y permisos cuando se elimina su sesión. No hay
llamadas a AWS para generar, verificar ni mostrar códigos.

## Verificación local

`HandoffCodeGeneratorTest` verifica vectores RFC 6238 SHA256, ceros iniciales, límites y
ventanas estrictas. `HandoffAccessCodeIntegrationTest` utiliza HTTP/JPA/bloqueos reales,
reloj controlado y almacenamiento de documentos simulado; comprueba código y grant,
replay, ocho aperturas simultáneas, ocho fallos simultáneos, persistencia del límite,
permisos de creador/principal activo, revocación, caducidad, legado y acceso al original.
Los tests anteriores de snapshot y sesión conservan cobertura del contenido OCR.
