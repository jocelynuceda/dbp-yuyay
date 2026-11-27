# Análisis de negocio — Modelo de autocuidado, perfiles y recordatorios

Propuesta de cambio de modelo. Documento de análisis: describe lo que hay, lo que falta y las
decisiones pendientes. **Implementado al 26-nov-2026:** el contraste del nombre contra el DNI
(§3.5) y la entrega del perfil a su titular (§3.4), incluido el vínculo perfil-cuenta de §3.1.
Siguen siendo propuesta: el perfil propio al registrarse (§3.2), la petición inversa de acceso
(§3.3) y los recordatorios (§4).

Fecha: 2026-11-26 · Alcance: backend Yuyay · Estado: borrador para decisión

---

## 1. Qué cambia en el negocio

Hoy Yuyay es una app **de cuidador**: la cuenta existe para registrar la salud de *otras personas*.
El usuario entra, ve una lista vacía y la primera acción que se le pide es «agregar una persona»,
declarando qué autorización tiene sobre ella.

La propuesta la convierte en una app **de salud personal que además permite cuidar a otros**:

| | Modelo actual | Modelo propuesto |
|---|---|---|
| Sujeto por defecto | ninguno | la propia persona |
| Primera acción | declarar autorización sobre un tercero | registrar lo propio |
| Datos predominantes | de terceros (`WITHIN_DECLARED_AUTHORIZATION`) | propios (`OWN_DATA`) |
| Rol del cuidador | único modo de uso | modo adicional |
| Menor bajo cuidado | queda como perfil del cuidador para siempre | puede volverse titular de su historial |
| Medicación | dato guardado | dato guardado + recordatorio accionable |

Tres consecuencias de negocio que conviene nombrar antes de diseñar:

1. **Mejora la posición legal.** La mayoría del dato pasa a ser dato propio con consentimiento
   del titular, no dato de tercero declarado por un cuidador. Es el cambio que más reduce el
   riesgo abierto de la Ley 29733 para datos sensibles de salud.
2. **Reduce la fricción de alta** y da sentido al uso individual: ya no hay que conocer a alguien
   enfermo para que la app sirva.
3. **Densifica un bucle que ya existe.** Los recordatorios de cita (`AppointmentReminder`,
   `ReminderScheduler`) ya convirtieron el registro pasivo en algo que promete entregarse a una
   hora. La medicación no inaugura ese bucle: lo vuelve diario y repetido en lugar de puntual, y
   sube lo que cuesta fallar, porque una cita perdida se reagenda y una toma perdida no. Lo que
   hay que reusar, no reinventar, es la máquina de entrega; lo que hay que subir es la exigencia
   de fiabilidad (zona horaria, duplicados, reintentos, acumulación de avisos).

---

## 2. Estado actual del modelo (as-is)

```
User (cuenta)  ──< CareRelationship >── CareSubject (perfil de una persona)
                   role: PRINCIPAL|CAREGIVER          │
                   status: PENDING|ACTIVE|REVOKED     ├──< HealthEntry ──< HealthEntryVersion
                                                      │                    (title, dose, frequency,
                   CareAuthorizationDeclaration ──────┤                     occurredOn, confidence)
                   (SELF|FICTITIOUS|HOLDER_PERMISSION ├──< Consultation, Attachment, Handoff
                    |LEGAL_REPRESENTATION, inmutable) └──< Delegation (acceso externo temporal)
```

Hechos relevantes del código actual:

- `CareSubject` **no tiene vínculo con ninguna cuenta**. Un perfil es siempre «una persona de la
  que alguien habla». Que un perfil sea el propio usuario solo se puede inferir del historial de
  declaraciones (`type = SELF`), que es evidencia de lo declarado, no una relación de identidad
  consultable.
- No se crea ningún `CareSubject` al registrarse (`AuthService.registerUser`), ni existe regla que
  impida a una cuenta tener dos perfiles declarados `SELF`.
- `CareRelationship` solo se crea en una dirección: el `PRINCIPAL` invita por email a una cuenta
  existente (`CareRelationshipService.invite`). No existe la petición inversa («quiero cuidar a
  alguien que ya tiene cuenta y él aprueba»).
- `User` no guarda fecha de nacimiento; `CareSubject.birthDate` sí (opcional, lo escribe el cuidador).
- La invariante de autorización es correcta y hay que conservarla: todo acceso se resuelve por
  `CareRelationship` ACTIVA sobre el perfil (`AuthorizationService`), nunca por el DTO.
- `dose` y `frequency` son texto libre de 60 caracteres dentro de `HealthEntryVersion`, que es el
  registro versionado e histórico de la declaración clínica.
- Ya hay planificador y recordatorios, pero **de citas, no de medicación**:
  `ReminderScheduler` (`@EnableScheduling`, revisión cada `app.reminders.interval`, con reenvío de
  atrasados al arrancar) y `AppointmentReminder` (canal `IN_APP | EMAIL`, estado
  `PENDING | SENT | FAILED`, `attempts`, y una restricción única que evita el disparo duplicado).
  No hay push. Hay notificación en app (`Notification`) y correo (`EmailService`, SMTP o Resend).
- El esquema se gestiona con `ddl-auto=update` y parches manuales de constraints
  (`NotificationTypeConstraintUpdater` existe porque `update` no toca el CHECK del enum).

---

## 3. Modelo propuesto (to-be)

### 3.1 Identidad: un perfil puede tener dueño

El cambio estructural mínimo y suficiente es **un vínculo opcional entre perfil y cuenta**:

```
CareSubject
  + accountUser : User?        -- FK única. Si no es null, ese perfil ES esa persona (titular)
  + claimedAt   : Instant?
```

**Implementado.** Ambas columnas existen, con `uk_care_subject_account_user` sobre `account_user_id`
(en PostgreSQL varios NULL conviven en un índice único, así que los perfiles sin dueño no se
estorban). `isSelf` **todavía no se expone** en `CareSubjectResponse`: eso va con §3.2, y el
titular ya distingue su perfil porque aparece en su propio listado. El vínculo es `LAZY` y se lee
solo por su id, así que el listado de personas sigue costando las mismas 3 consultas que antes
(lo comprueba `QueryCountPerListingTest`).

Con eso el dominio queda con tres clases de perfil, sin agregar roles nuevos:

| Clase | `accountUser` | Significado | Declaración típica |
|---|---|---|---|
| **Perfil propio** | la cuenta misma | «mi salud» | `SELF` / `OWN_DATA` |
| **Perfil sin cuenta** | null | persona cuidada que no usa la app | `HOLDER_PERMISSION` / `LEGAL_REPRESENTATION` |
| **Perfil de otra cuenta** | otra cuenta | persona que usa la app y me dio acceso | `SELF` de ella + mi relación |

Reglas nuevas a hacer cumplir en servidor:

- `UNIQUE (account_user_id)` — una cuenta es titular de **a lo sumo un** perfil.
- El titular siempre tiene `CareRelationship` ACTIVA con rol `PRINCIPAL` sobre su perfil.
- **La relación del titular no puede ser revocada por nadie más.** Hoy `revoke` solo protege al
  «único PRINCIPAL»; hay que añadir esta regla, o un cuidador podría echar al dueño del dato.
- Un perfil `FICTITIOUS` nunca puede vincularse a una cuenta.
- `isSelf` se expone en `CareSubjectResponse` como dato derivado, para que el frontend separe
  «Mi salud» de «Personas que cuido» sin heurísticas.

### 3.2 Alta de la cuenta

Al registrarse se crea el perfil propio en la misma transacción (hay ya un
`UserRegisteredEvent` donde engancharlo), con su declaración `SELF` registrada desde el texto que
la pantalla de registro muestre, versionado como las demás declaraciones.

Decisión requerida: la pantalla de registro debe incluir y versionar ese texto. Sin eso habría que
crear el perfil propio *sin* declaración, lo que rompe la invariante «todo perfil nace con
declaración» del contrato de #69.

Cuentas existentes: **no se fabrica la declaración**, coherente con la política ya escrita para
perfiles antiguos. Se les pide una vez, al entrar, que confirmen su perfil propio; si tienen ya un
perfil declarado `SELF`, se ofrece marcarlo como propio (un solo toque, misma pantalla).

### 3.3 Dos direcciones para compartir

| Flujo | Quién inicia | Quién aprueba | Existe hoy |
|---|---|---|---|
| Invitar cuidador a un perfil mío | `PRINCIPAL` | la cuenta invitada | sí |
| Pedir cuidar a alguien con cuenta | cualquiera | el **titular** | **no** |
| Entregar un perfil a su titular (*claim*) | `PRINCIPAL` | la persona, con cuenta nueva o existente | **no** |

Para la petición inversa basta añadir `initiatedBy` a `CareRelationship` y permitir filas `PENDING`
creadas por quien no es PRINCIPAL **solo cuando el perfil tiene titular** — quien no inició es
quien acepta. No hace falta entidad nueva ni estado nuevo.

### 3.4 Transición del menor a titular (16 años)

La entrega de un perfil a su titular es el mismo mecanismo para el menor que cumple 16 y para un
adulto cuidado que decide abrir su cuenta. Un solo flujo, no dos.

```
ClaimInvitation
  careSubject, invitedEmail, tokenHash, validUntil,
  createdBy, exchangedAt?, revokedAt?
```

Se reutiliza el patrón de `Delegation`, ya probado en el proyecto (token hasheado, vigencia,
canje único), en lugar de emparejar por email: la app **no acredita identidad** —doctrina explícita
de `care-authorization.md`— y emparejar por correo invitaría a errores de identidad irreversibles
sobre datos de salud.

Precondiciones para emitir la invitación:
- el emisor es `PRINCIPAL` activo del perfil;
- el perfil **no** es `FICTITIOUS` y **no** tiene titular;
- `birthDate` está registrada y la edad alcanza el mínimo de política. Esa fecha es
  **declarada por el cuidador, no verificada**; ver §3.5.

Al canjear (la persona ya autenticada):
1. se fija `accountUser` y `claimedAt`;
2. se crea o eleva su `CareRelationship` a `PRINCIPAL` ACTIVA;
3. **el cuidador que la emitió queda como `CAREGIVER` activo, no se revoca**;
4. se registra la declaración `SELF` del nuevo titular (su propia voz sobre su dato);
5. se notifica a todos los cuidadores activos del perfil;
6. **el historial no se reescribe**: cada `HealthEntryVersion` conserva su `declaredBy`. Que el
   registro diga «declarado por la madre» es exactamente lo correcto.

Y desde ese momento el titular puede revocar a cualquier cuidador, incluida la persona que le
entregó el perfil. Ese es el punto del cambio: la autonomía se gana con el canje, no se negocia.

**Contrato entregado el 26-nov-2026** (`ClaimInvitation`, 12 pruebas de integración):

| Ruta | Quién | Resultado |
|---|---|---|
| `POST /care-subjects/{id}/claim-invitations` | `PRINCIPAL` activo | 201 con `token` y `claimLink`; invalida el enlace anterior del mismo perfil |
| `GET /care-subjects/{id}/claim-invitations` | `PRINCIPAL` activo | historial de enlaces, con `pending` |
| `DELETE /care-subjects/{id}/claim-invitations/{invitationId}` | `PRINCIPAL` activo | 204 |
| `POST /claim-invitations/exchange` | la cuenta invitada, autenticada | 200 con el perfil, rol `PRINCIPAL` y `claimedAt` |

Emisión: 400 sin fecha de nacimiento, 409 si la edad no alcanza el mínimo, si el perfil es
`FICTITIOUS` o si ya tiene titular, 403 si quien pide no es `PRINCIPAL`. Canje: 404 token
inexistente, 409 ya usado, revocado o vencido, 403 si la cuenta autenticada no es la invitada.

El canje exige **las dos cosas**: el token y que el correo de la sesión sea el invitado. El token
solo no alcanza porque un enlace reenviado por error entregaría un historial de salud; el correo
solo tampoco, porque la app no acredita identidad. `app.care.self-claim-min-age` (16) y
`app.care.claim-invitation-valid-days` (7) son configurables.

Falta de §3.4: **el correo con el enlace no se envía todavía** — la respuesta devuelve `claimLink`
y quien entrega lo comparte a mano, igual que se puede hacer hoy con una delegación. Y la ruta
`/entrega/{token}` tiene que existir en el frontend para que ese enlace abra algo.

El contraste del DNI de §3.5 **no está enganchado a la emisión**: son dos llamadas separadas a
propósito. Exigirlo adentro ataría cada entrega a un tercero que se paga y que puede estar caído,
para una comprobación que no verifica la edad. Queda como paso previo que el cuidador decide hacer,
hasta que se responda la decisión 7.

**Decisiones abiertas (negocio/legal, no técnicas):**

- **¿16 o 18?** Debe ser política configurable (`app.care.self-claim-min-age`), no una constante
  en el código. El piso legal del reglamento vigente para el consentimiento propio de un menor es
  **14** años, y los datos de salud son datos sensibles con requisito de consentimiento escrito;
  ver §3.5. Requiere revisión legal antes del piloto.
- **¿El cuidador conserva acceso tras el canje?** La recomendación es sí, degradado a `CAREGIVER`,
  con revocación en manos del titular. Revocar automáticamente es más «limpio» pero deja a una
  madre sin ver el historial del hijo que todavía gestiona, y no hay forma de deshacerlo.
- **¿Qué pasa si quien canjea ya tiene su propio perfil?** Habrá duplicados (ver §6).

### 3.5 Contraste del nombre y compuerta de edad (RENIEC / APIsPERU)

El dato que decide el canje es `CareSubject.birthDate`, y hoy lo escribe el cuidador a mano. Ese es
el eslabón débil de §3.4: con un año falso se podría entregar el historial a un niño de 12, o
bloquear a alguien de 17 por un error de tipeo. Verificar la fecha contra el registro civil arregla
ese eslabón — y **solo ese**.

**Decisión tomada el 26-nov-2026: opción (a) de la decisión 6.** La compuerta de edad se queda
sobre la fecha declarada en el perfil y la consulta de DNI se usa para **contrastar el nombre**. Es
lo que el proveedor ya contratado permite hacer (hallazgo más abajo) y se entrega sin esperar un
convenio. La consecuencia hay que sostenerla en el producto y en el aviso de privacidad: **la edad
no queda verificada**. El eslabón débil del párrafo anterior sigue abierto; lo que se cierra en
parte es el perfil con un nombre inventado y el error de tipeo en el nombre.

**Qué verifica y qué no.** Consultar un DNI confirma que ese número existe y a qué nombre figura.
No confirma que quien tiene el teléfono sea esa persona. Dentro de una familia el número de
DNI se conoce, así que la consulta no protege de una suplantación familiar: el riesgo R2 sigue
abierto. Lo único que ata a una persona con su DNI es el web service biométrico facial de RENIEC.
El copy nunca debe decir «identidad verificada» ni «edad verificada»; dice «nombre contrastado
con RENIEC».

**Opciones de acceso** (confirmar tarifas y campos antes de decidir: cambian):

| Opción | Devuelve | Costo | Barrera | Resuelve |
|---|---|---|---|---|
| Hoy: fecha escrita por el cuidador | — | 0 | ninguna | nada |
| Pasarela de terceros (apis.net.pe, scrall, factornube, json.pe, endpoint.pe) | depende del proveedor: **no todos devuelven fecha de nacimiento** | gratis con límites, o plan | token y registro | edad |
| Convenio RENIEC — web service de datos | datos de identidad oficiales | S/ 0.40 por consulta | persona jurídica con RUC vigente, Convenio de Suministro de Información, certificados del DNIe, software propio | edad, con fuente oficial |
| Convenio RENIEC — consulta en línea vía internet | según nivel 1/2/3 | S/ 0.90 / 1.20 / 1.60 | convenio; el nivel se otorga según el giro | edad (pensado para uso manual) |
| Convenio RENIEC — biométrico facial | verificación de identidad | S/ 0.70 por consulta | convenio | **identidad** |

Dos hallazgos que cambian la decisión:

- **No existe una API pública y oficial de RENIEC.** Lo que suele llamarse «la API de RENIEC de la
  SUNAT» son pasarelas de terceros que revenden esos datos. La consulta de RUC de SUNAT **no
  devuelve fecha de nacimiento**: para persona natural el RUC es 10 + DNI, lo que da el nombre, no
  la edad. La fecha de nacimiento solo llega por un proveedor que la incluya, y la cobertura varía
  entre proveedores — hay que confirmarla con una llamada de prueba antes de diseñar sobre ella.
- **Un tercero que recibe el DNI es encargado de tratamiento** bajo la Ley 29733: exige contrato de
  encargo, y su propia base legal para redistribuir datos de RENIEC es el punto frágil de esa vía.
  El convenio directo cuesta más en trámite y menos en riesgo.

**Confirmado el 26-nov-2026 con la documentación de APIsPERU** (`dniruc.apisperu.com`, el proveedor
que el equipo ya tiene): su consulta de DNI devuelve `dni`, `nombres`, `apellidoPaterno`,
`apellidoMaterno` y `codVerifica`. **No devuelve fecha de nacimiento.** Sirve para comprobar que un
DNI existe y a qué nombre figura; no alcanza para la compuerta de edad de §3.4. Para la edad hay
que cambiar de proveedor o tramitar el convenio.

La integración vive en `com.yuyay.identity`, aislada detrás de `IdentityDirectory` igual que
`EmailSender`: `ApisPeruIdentityDirectory` cuando hay token, `DisabledIdentityDirectory` cuando no.
El resultado es un `IdentityLookup` de tres casos —`Found`, `NotFound`, `Unavailable`— para que un
proveedor caído deje la operación pendiente en vez de rechazarla. Cambiar de proveedor es escribir
otro adaptador, no tocar el flujo del canje.

**Contrato entregado el 26-nov-2026.** `POST /api/v1/care-subjects/{id}/identity-check` con
`{"dni":"12345678"}` devuelve `{"result":"MATCH|MISMATCH|NOT_FOUND|UNAVAILABLE","checkedAt":"..."}`.

| Caso | HTTP | Por qué |
|---|---|---|
| Los cuatro resultados del contraste | 200 | La consulta se hizo; el resultado lo interpreta quien llama. `MISMATCH` no es un error del cliente |
| DNI que no son ocho dígitos | 400 | Se rechaza antes de gastar una consulta que se paga |
| Quien no es `PRINCIPAL` activo del perfil | 403 | Misma invariante que el resto: el permiso sale de la relación activa, no del cuerpo |
| Perfil declarado `FICTITIOUS` | 409 | Contrastar datos inventados contra el registro civil no tiene sentido y cuesta |

La respuesta **no devuelve el nombre del DNI**, ni siquiera cuando coincide. Si lo devolviera,
cualquier cuenta con un perfil podría usar la API como buscador de personas por número de
documento. Tampoco se persiste el DNI ni el resultado: el resultado se guardará cuando exista la
invitación de entrega, en los campos de más abajo.

Falta para cerrar F2b: caché y límite por usuario (el precedente es `app.ocr.*`), y registrar el
intento en `AccessLog`. Hoy una cuenta con un perfil puede gastar la cuota del plan a mano.

**Regla del contraste** (`IdentityRecord.matchesName`, ya implementada y probada): el nombre del
perfil coincide cuando no agrega ninguna palabra ajena al DNI y contiene al menos un nombre y al
menos un apellido. «Ana Quispe» coincide con «ANA MARIA QUISPE ROJAS», porque escribir el nombre
corto es lo normal; «Ana» o «Quispe Rojas» no alcanzan; «Ana María Pérez» se rechaza. Se ignoran
mayúsculas, tildes, la ñ, la puntuación, el orden y las iniciales de una letra. Qué hace el flujo
con cada resultado: `MISMATCH` y `NotFound` **bloquean** la emisión con mensaje corregible —un
tipeo se arregla en diez segundos—; `Unavailable` deja la invitación *pendiente de contraste*,
según la regla de degradación de abajo.

Para un piloto, la pasarela de terceros es razonable y reversible por eso mismo. Para un piloto con
datos reales, la vía con convenio es la defendible.

**Reglas de diseño, independientes del proveedor:**

- **No guardar el DNI.** Se guarda el *resultado*: fecha verificada, fuente, fecha de la consulta y
  referencia de la respuesta. El DNI solo transita; si hace falta caché o deduplicación, un hash.
  Guardarlo agrega un identificador al inventario de datos, obliga a declararlo en el aviso de
  privacidad y agrava cualquier filtración, sin aportar nada al caso de uso.
- **Nunca registrar el DNI** en logs ni en mensajes de error.
- **Verificar solo donde importa:** al emitir la invitación de entrega. Verificar en cada registro
  gasta una consulta por cuenta nueva para una comprobación que el perfil propio no necesita. Si
  más adelante se quiere una edad mínima para abrir cuenta, es otra decisión y otro presupuesto.
- **Caché y límite por usuario.** Cada consulta se paga; ya hay precedente de límites por usuario
  en `app.ocr.*`.
- **Degradación.** Si el proveedor no responde, la entrega queda *pendiente de verificación*, no
  bloqueada para siempre: timeout corto, sin reintentos infinitos, estado visible para el cuidador.
  Un servicio caído no puede ser la razón por la que alguien no accede a su propio historial.
- **Token del proveedor en variable de entorno**, como `RESEND_API_KEY`.
- **Cobertura.** Quien no está en RENIEC (carné de extranjería) necesita una vía alterna; si no, el
  canje le queda cerrado por diseño.

```
ClaimInvitation
  + nameCheckedAt   : Instant?
  + nameCheckResult : MATCH | MISMATCH | NOT_FOUND | UNAVAILABLE
  + nameCheckRef    : String?   -- referencia de la respuesta, nunca el DNI
```

No se agrega `birthDateVerifiedAt` ni fuente de la fecha: mientras la edad sea declarada, esas
columnas no tendrían ningún valor distinto de «declarada» y darían la impresión contraria. Entran
el día que exista un proveedor con fecha de nacimiento o el convenio.

**Y entonces, 16 o 18.** El reglamento de la Ley 29733 (DS 003-2013-JUS) permite tratar datos de un
menor **de 14 a 18 años con su propio consentimiento**, si la información se le presenta en lenguaje
que pueda entender, salvo cuando la ley exija la asistencia de quien ejerce la patria potestad o
tutela. Por debajo de 14 se requiere el consentimiento de los padres o tutores. Y los datos de
salud son **datos sensibles**: para esos la ley exige consentimiento **por escrito**, y el
reglamento admite firma manuscrita, firma digital «o cualquier otro mecanismo de autenticación».

De ahí se siguen tres cosas:

1. **16 es defendible y más conservador que el piso legal de 14.** 18 es el más cauto de los dos,
   pero deja a quienes tienen 16 y 17 sin acceso a su propio historial de salud, lo que juega
   *contra* los derechos del titular en vez de protegerlo. Recomendación: 16, configurable.
2. **La declaración versionada que ya existe es el instrumento de consentimiento.** Texto exacto
   guardado, versión, sesión autenticada y fecha del servidor son razonablemente «otro mecanismo de
   autenticación» para el requisito de consentimiento escrito. El canje no necesita un mecanismo
   nuevo: necesita usar ese.
3. **El texto que ve un adolescente de 16 debe estar redactado para que lo entienda.** Es requisito
   del reglamento, no una mejora de experiencia.

Advertencia sobre la norma citada: el piso de 14 años viene del reglamento de 2013
(DS 003-2013-JUS), que **fue derogado** por el DS 016-2024-JUS. No está releído qué dice el
reglamento vigente sobre menores y sobre datos de salud, así que el piso puede haber cambiado y
todo el párrafo anterior debe tratarse como indicio, no como la norma. Confirmar el texto vigente
con la Autoridad Nacional de Protección de Datos Personales antes del piloto. Esto es insumo para
esa revisión, no la revisión.


### 3.6 Una cuenta por DNI

**Regla pedida el 26-nov-2026:** una persona no puede tener dos cuentas con el mismo DNI, salvo que
elimine la que tiene.

Cumplirla obliga a recordar qué DNI ya tienen cuenta, y eso **invierte una decisión de §3.5**: hoy
el DNI no se guarda en ningún lado —ni en la base ni en el log— y la consulta solo contrasta el
nombre. La regla exige persistir algo por DNI.

**Cómo persistirlo sin guardar el DNI legible.** Un `sha256` del DNI no protege nada: son ocho
dígitos, cien millones de combinaciones, y la tabla completa se calcula en segundos. Hace falta un
HMAC con una clave que viva fuera de la base:

```
User
  dniHmac : String?        -- HMAC-SHA256(DNI, DNI_PEPPER), UNIQUE, 64 hex
  dniCheckedAt : Instant?  -- cuándo se contrastó, no qué decía
```

Se borra junto con la cuenta, que es exactamente la excepción pedida: al eliminarla desaparece la
fila y el DNI queda libre. Eso además juega a favor del derecho de supresión de la Ley 29733, no
en contra.

**Dos cosas la bloquean hoy:**

1. **No existe eliminar la cuenta.** No hay endpoint ni servicio de borrado en todo el proyecto, así
   que la excepción de la regla no se puede ejercer. Aplicar la unicidad ahora es bloqueo
   permanente, sin salida para nadie.
2. **El DNI no está verificado, solo contrastado.** Cualquiera puede escribir un DNI ajeno con el
   nombre que le corresponde —dato que muchas veces es público— y quedárselo. La unicidad sobre un
   identificador no verificado deja de proteger y se vuelve un arma: el primero que llega deja al
   dueño real sin poder registrarse nunca, y hoy no habría forma de devolvérselo.

**Recomendación:** dejar la regla registrada y no aplicarla todavía. Primero el borrado de cuenta y
una vía de desbloqueo (administrador con prueba de identidad); mientras no existan, es más seguro
**detectar el duplicado y avisar** que impedirlo.

### 3.7 Qué significa «eliminar la cuenta»

Precisión del 26-nov-2026: al eliminar la cuenta el DNI queda libre y se puede abrir otra con él, y
la eliminación **no se puede deshacer**.

Eso descarta el borrado blando: si la cuenta se pudiera restaurar, el DNI estaría en dos cuentas a
la vez y la regla de §3.6 se rompe.

Pero tampoco puede ser un `DELETE` de la fila. Catorce entidades apuntan a `users` con clave ajena,
y dos de ellas son la cadena de auditoría que sostiene el producto:
`CareAuthorizationDeclaration.declaredBy` (quién declaró el consentimiento sobre una persona) y
`HealthEntryVersion.declaredBy` (quién declaró cada versión clínica). Borrar la fila falla por las
claves ajenas o, si se pone en cascada, **destruye el historial de salud de otras personas y la
prueba de su consentimiento**. Quien cuidó a su madre no puede llevarse el historial de la madre al
cerrar su cuenta.

Forma correcta: **borrar el dato identificante y conservar la fila como lápida.**

```
User
  email, name, passwordHash, googleSubject, gender, dniHmac  ->  borrados
  deletedAt : Instant                                        ->  marcado
```

El historial queda legible como «declarado por una cuenta eliminada», que es la verdad. Es
irreversible, porque el dato se fue de verdad y no quedó una bandera que alguien pueda desmarcar. Y
como `dniHmac` se borra, el DNI vuelve a estar libre: la regla de §3.6 se cumple sin romper nada.

**Perfiles donde esa cuenta era el único `PRINCIPAL`.** Decidido el 26-nov-2026: no se elige por
ella. Se le muestra qué pierde y ella confirma.

Esto no es un cuadro de diálogo del frontend. Un cliente puede no mostrarlo, así que la
confirmación tiene que ser un dato que el servidor exija y verifique:

```
GET    /api/v1/users/me/deletion-preview   -- qué se pierde, sin borrar nada
DELETE /api/v1/users/me                    -- exige confirmar ese detalle
```

La vista previa separa los perfiles según la consecuencia, porque son distintas:

| Grupo | Qué pasa |
|---|---|
| Su propio perfil (es `accountUser`) | Se elimina con sus registros. Es el derecho de supresión. |
| Perfiles donde es el único `PRINCIPAL` | **Nadie podría abrirlos.** Puede entregarlos antes (§3.4) o aceptar que se eliminen, con el nombre y cuántos registros tiene cada uno. |
| Perfiles con otro `PRINCIPAL` activo | No se pierde nada: siguen accesibles y ella solo deja de figurar. |

El `DELETE` exige la palabra `ELIMINAR` y repetir los identificadores que aceptó perder. Si la
lista cambió desde la vista previa —agregó un perfil mientras decidía— el servidor rechaza y obliga
a verla otra vez, igual que `declare` ya rechaza una declaración cuando el historial cambió debajo
(`previousDeclarationId`).

Lo que la pantalla debe decir con claridad, porque es lo que más sorprende: su nombre desaparece de
la cadena de auditoría, pero **las declaraciones que hizo sobre otras personas no se borran**.
Quedan como «declarado por una cuenta eliminada». No puede retirar la prueba del consentimiento con
que registró a otro, y eso es a propósito.

**Entregado el 26-nov-2026:** las dos rutas, la confirmación verificada en el servidor, la lápida,
el acceso cerrado —el filtro resuelve el usuario en cada petición, así que descartar la cuenta
eliminada ahí corta también los tokens ya emitidos— y el correo liberado para volver a registrarse.
Nueve pruebas de integración.

**Límite aceptado el 26-nov-2026:** un perfil con historial de salud no se puede eliminar. La
regla ya existía en `CareSubjectService.delete` y **se decidió no cambiarla**, así que el borrado se
rechaza y la vista previa lo avisa antes, nombrando el perfil.

Hay que decir lo que eso implica, porque no es solo un detalle técnico: **quien ya registró datos de
salud no puede eliminar su cuenta todavía.** El derecho de supresión queda a medias y el DNI de
§3.6 no se liberaría en ese caso. Es una limitación conocida y asumida (R9 en §6), no un descuido:
el borrado en cascada del historial propio arrastra archivos en almacenamiento, no solo filas, y
conservar el historial se consideró preferible a construir ese camino ahora.

---

## 4. Recordatorios de medicación

### 4.0 Qué se reusa

El diseño de abajo no parte de cero: `AppointmentReminder` ya resuelve la parte difícil —reservar
antes de enviar para no reenviar si el servidor cae, contar intentos, y bloquear el duplicado con
una restricción única— y `ReminderScheduler` ya recupera los avisos atrasados al arrancar. La
medicación agrega lo que una cita no necesita: **recurrencia**. Una cita tiene una fecha; un
medicamento tiene una regla que genera fechas. De ahí la separación plan / ocurrencia de §4.2:
el plan guarda la regla, y cada ocurrencia materializada se comporta como el recordatorio de cita
que ya funciona. Conviene extraer el canal, el estado y el disparo a algo compartido antes de
tener dos planificadores que se parecen.

### 4.1 Por qué no van en `HealthEntryVersion`

`dose` y `frequency` son texto libre dentro del registro clínico versionado. Un recordatorio
necesita (a) datos estructurados —horas, días, vigencia, zona horaria— y (b) estado operativo
—próximo disparo, enviado, confirmado, omitido—. Si eso vive en `HealthEntryVersion`, cada vez
que alguien pospone una toma se crea una versión nueva del historial clínico y la cadena de
auditoría deja de significar lo que significa.

Separación propuesta: **el qué se toma es clínico; el cuándo recordarlo es operativo.**

### 4.2 Entidades

```
MedicationPlan                       -- 1 por HealthEntry de categoría MEDICATION
  healthEntry (unique), careSubject  -- careSubject explícito: el WHERE de autorización lo usa
  scheduleType : FIXED_TIMES | INTERVAL_HOURS
  times[] : LocalTime                -- o intervalHours + hora de anclaje
  daysOfWeek : Set?                  -- null = todos los días
  startDate, endDate?                -- null = indefinido
  zoneId : String                    -- p. ej. America/Lima
  active, createdBy, createdAt, deactivatedAt?

ReminderOccurrence                   -- materializadas con horizonte corto (p. ej. 48 h)
  plan, careSubject, scheduledAt : Instant (UTC)
  status : PENDING | SENT | TAKEN | SKIPPED | MISSED
  sentAt?, respondedAt?, respondedBy?
  UNIQUE (plan_id, scheduled_at)     -- idempotencia: ningún disparo duplicado

MedicationPlanRecipient
  plan, user, channel : IN_APP | EMAIL
  UNIQUE (plan_id, user_id)
```

### 4.3 Reglas

- **Quién crea y edita el plan:** `PRINCIPAL` del perfil. Un titular siempre puede desactivar
  recordatorios sobre su propio perfil — es la contrapartida de §3.4.
- **Quién recibe:** destinatarios explícitos. Por defecto, el titular si existe; si no, el
  `PRINCIPAL` que lo creó. Notificar a todos los cuidadores por omisión genera ruido y hace que
  nadie se sienta responsable de la toma.
- **Quién confirma:** cualquier relación activa; se guarda `respondedBy`.
- **`MISSED` no es una afirmación clínica.** Significa «nadie confirmó en la app», no «no se tomó
  el medicamento». La misma doctrina de «Sin declaración registrada» frente a «Verificado» aplica
  aquí, y el copy del frontend debe sostenerla.
- **Adherencia** = confirmadas / vencidas en una ventana. Sale gratis de este modelo y es el
  tercer punto de las prioridades actuales del proyecto.

### 4.4 Entrega

Fase 1: notificación en app + correo, con `@EnableScheduling` y un poller que toma ocurrencias
vencidas. Push móvil queda fuera de alcance (no hay infraestructura y añade credenciales y
dependencia de tienda).

Riesgos técnicos que el diseño debe resolver desde el principio, no después:

- **Duplicados con más de una instancia.** El poller necesita `SELECT ... FOR UPDATE SKIP LOCKED`
  o un lock distribuido. La unicidad `(plan, scheduledAt)` protege la materialización, no el envío.
- **Zona horaria.** La base guarda UTC (`hibernate.jdbc.time_zone=UTC`). «8:00» sin `zoneId` no
  significa nada, y el horario de una persona no cambia porque el servidor se mueva de región.
- **Nuevos tipos de notificación** (`MEDICATION_REMINDER`, `CLAIM_INVITATION`, `PROFILE_CLAIMED`,
  `ROLE_CHANGED`): `ddl-auto=update` no modifica el CHECK del enum en PostgreSQL. Ya hay un parche
  manual para esto; cada tipo nuevo pasa por ahí.
- **Correo masivo.** Un recordatorio diario por persona multiplica el volumen de correo por
  órdenes de magnitud frente a las invitaciones actuales. Revisar límites del proveedor y qué
  ocurre cuando falla el envío (reintento, no silencio).

---

## 5. Historias de usuario y criterios de aceptación

**HU-1 — Registro propio.** Como persona que se registra, quiero que mi perfil exista desde el
primer momento para registrar mi salud sin declarar nada sobre terceros.
- La cuenta nueva tiene exactamente un perfil propio, con relación `PRINCIPAL` activa y declaración `SELF`.
- `GET /api/v1/care-subjects` lo devuelve con `isSelf: true`.
- Dos registros concurrentes de la misma cuenta no crean dos perfiles propios.

**HU-2 — Agregar a quien no tiene cuenta.** Sin cambios respecto de hoy, salvo que el perfil
creado no es propio y la lista los separa.

**HU-3 — Agregar a quien sí tiene cuenta.** Al invitar por correo a una cuenta que ya es titular
de su perfil, la app ofrece **pedirle acceso a su historial**, no crear un perfil nuevo sobre ella.
- Si el correo corresponde a un titular, la respuesta lo indica y la creación de perfil duplicado
  se ofrece solo como alternativa explícita.
- La petición queda `PENDING` hasta que el titular la acepta; nunca da acceso antes.

**HU-4 — Menor que cumple 16.** Como cuidadora, quiero entregarle a mi hijo el acceso a su
historial cuando cumpla la edad; como hijo, quiero ser titular de mi dato.
- Antes de la edad mínima, la invitación de entrega no se puede emitir (409 con mensaje claro).
- Sin `birthDate` registrada, tampoco (400).
- Tras el canje: el perfil tiene titular, el titular es `PRINCIPAL`, la cuidadora queda `CAREGIVER`
  activa, hay declaración `SELF` del titular, y el historial mantiene su `declaredBy` original.
- Un token ya canjeado, vencido o revocado no vuelve a funcionar.
- Nadie, salvo el titular, puede revocar la relación del titular (403).

**HU-5 — Recordatorio de medicación.** Como `PRINCIPAL`, quiero activar recordatorios sobre un
medicamento ya registrado.
- Solo para entradas de categoría `MEDICATION` y no eliminadas.
- Activar o editar el plan **no** crea una versión nueva del registro clínico.
- Las ocurrencias se calculan en la zona horaria del plan; un cambio de zona no reescribe las
  ocurrencias ya enviadas.
- Confirmar o omitir registra quién y cuándo.
- Dos ejecuciones del planificador no envían dos veces la misma ocurrencia.
- El titular puede desactivar recordatorios sobre su propio perfil siempre.

**HU-6 — Adherencia.** Como cuidadora, quiero ver cuántas tomas se confirmaron en un período, con
el texto aclarando que lo no confirmado no equivale a no tomado.

---

## 6. Riesgos y limitaciones aceptadas

| # | Riesgo | Mitigación propuesta |
|---|---|---|
| R1 | **Perfiles duplicados de la misma persona** (su perfil propio + el que un cuidador creó sobre ella). Fusionar historiales es caro y arriesgado. | No construir fusión en el MVP. Prevenir en el alta: al escribir un correo conocido, ofrecer la petición de acceso. Documentar la limitación. |
| R2 | Identidad no verificada: el canje lo recibe quien controle el correo indicado. **La consulta de DNI no resuelve esto** (§3.5): contrasta el nombre, no quién usa el teléfono, y la edad ni siquiera se verifica. | Vigencia corta del token, aviso a todos los cuidadores al canjear, registro en `AccessLog`, y copy que diga «nombre contrastado», nunca «edad verificada» ni «identidad verificada». El único cierre real es el biométrico facial de RENIEC, fuera de alcance del MVP. |
| R7 | Dependencia de un tercero en el camino del canje: caída, cambio de campos, fin del plan gratuito, o costo por consulta creciente. | Adaptador aislado (`IdentityDirectory`, ya escrito), timeout corto, caché, y estado «pendiente de contraste» en vez de bloqueo permanente. |
| R8 | El DNI entra al sistema: dato identificador nuevo en el inventario, en el aviso de privacidad y en el impacto de una filtración. | No persistirlo: guardar solo el resultado de la verificación (§3.5) y jamás escribirlo en logs. |
| R3 | La inmutabilidad del historial choca con el derecho de supresión que el nuevo titular adquiere. | Es el mismo problema ya abierto en #73; el canje lo vuelve exigible antes. Definir conservación y supresión antes del piloto. |
| R4 | El bucle diario eleva la expectativa de fiabilidad (§1.3). | Métricas de envío, reintentos, y copy que no prometa más de lo que el canal garantiza. |
| R5 | Cambio de esquema con *backfill* (perfiles propios de cuentas existentes) bajo `ddl-auto=update`. | `update` agrega la columna, pero no hace backfill ni garantiza índices parciales. Es el caso que justifica Flyway antes de empezar. |
| R9 | **El borrado de cuenta no alcanza a quien tiene historial de salud** (§3.7). La regla de no eliminar un perfil con registros se mantuvo a propósito, así que esas cuentas no se pueden eliminar y su derecho de supresión queda pendiente. | Decirlo en la pantalla con el perfil que bloquea, y ofrecer la entrega del perfil (§3.4) como salida cuando el titular es otro. Resolver el borrado en cascada del historial propio —incluidos los archivos en almacenamiento— antes del piloto con datos reales. |
| R6 | Edad mínima y consentimiento de menores sobre dato sensible; el reglamento pudo cambiar desde 2013. **La edad es declarada, no verificada** (§3.5): un año falso o mal tecleado abre o cierra el canje. | Política configurable + revisión legal con el texto vigente antes del piloto. Aviso a los cuidadores activos al emitir y al canjear, para que un error sea visible. Texto del canje redactado para que un adolescente lo entienda. No afirmar en producto que una declaración «verifica» algo. |

---

## 7. Plan por fases

| Fase | Contenido | Depende de |
|---|---|---|
| **F0** | Flyway y baseline del esquema actual | — |
| **F1** | Perfil propio: `accountUser` y unicidad **entregados**; falta el alta en el registro, `isSelf`, backfill guiado de cuentas existentes y la separación en la lista | F0 |
| **F2** | Entrega del perfil: `ClaimInvitation`, canje, invariante del titular y notificación `PROFILE_CLAIMED` **entregados**; falta el correo con el enlace y la petición inversa de acceso | ninguna (corre sin F1) |
| **F2b** | Contraste del nombre: **entregado** el endpoint `identity-check` con `IdentityDirectory`, adaptador de APIsPERU y 23 pruebas; falta caché, límite por usuario, `AccessLog` y engancharlo a la emisión de la invitación | ninguna (el endpoint ya corre sin F1 ni F2) |
| **F3** | Recordatorios de medicación: `MedicationPlan`, ocurrencias y adherencia, extendiendo el planificador y los canales de `AppointmentReminder` | F0 |

F3 no depende de F2: se puede entregar sobre perfiles propios en cuanto F1 esté lista. Si hay que
elegir una sola, F1 es la que cambia el producto; F3 es la que cambia el uso diario.

---

## 8. Decisiones que requieren respuesta antes de implementar

1. ¿El texto de declaración `SELF` entra en la pantalla de registro? (bloquea F1)
2. ~~¿El cuidador conserva acceso degradado tras el canje, o se revoca?~~ **Resuelta: conserva**,
   degradado a `CAREGIVER` activo, y el titular puede revocarlo. Implementado y probado.
3. ¿Edad mínima 16 confirmada por revisión legal? Ya es configurable
   (`app.care.self-claim-min-age`, por omisión 16) y el código está entregado, así que esto dejó de
   bloquear la implementación y pasó a bloquear el **piloto con datos reales**.
4. ¿Destinatarios del recordatorio por omisión: solo titular, o todos los cuidadores activos?
   (recomendación: titular, o el PRINCIPAL creador si no hay titular)
5. ¿Se acepta la limitación R1 —sin fusión de perfiles duplicados— en el MVP?
6. ~~¿Cómo se comprueba la edad, ahora que se confirmó que APIsPERU no devuelve fecha de
   nacimiento?~~ **Resuelta el 26-nov-2026: opción (a)**, la compuerta queda sobre la fecha
   declarada y la consulta solo contrasta el nombre (§3.5). Quedan dos consecuencias para la
   revisión legal del punto 3: que el producto no puede afirmar que verifica la edad, y que la
   fecha de nacimiento sigue siendo un dato que escribe un tercero sobre un menor.
7. ¿`MISMATCH` bloquea la emisión o solo advierte al cuidador? (recomendación: bloquea, porque un
   tipeo se corrige y un nombre que no es el del DNI es justo la señal que se buscaba)
8. ¿Se aplica «una cuenta por DNI» (§3.6)? Requiere guardar un HMAC del DNI, que hoy no se guarda, y
   depende de dos piezas que no existen: el borrado de cuenta y una vía de desbloqueo. Sin ellas la
   regla bloquea para siempre a quien le hayan tomado el DNI. (recomendación: registrarla, detectar
   el duplicado y avisar, y aplicarla recién cuando esas dos piezas estén)
