# Declaraciones por persona — backend #69 / frontend #52

Este contrato registra lo que declara una cuenta sobre un perfil. No acredita la identidad del titular, su voluntad ni las facultades de un representante. `PRINCIPAL` sigue siendo un rol de permisos interno. El registro independiente de la aceptación de términos de cuenta se implementará en #67.

## Obtener el texto antes de mostrarlo

`GET /api/v1/care-subjects/authorization-policy` requiere una sesión de usuario y devuelve `textVersion`, `thirdPartyQuestion` y `options`. Cada opción contiene `type`, `label`, `description`, `scope` y `confirmationText`.

Frontend debe mostrar el texto del catálogo y conservar su versión al enviar la elección. El servidor guarda una copia exacta del texto de la opción y confirmación asociado a la versión enviada, su alcance y la fecha del servidor; el backend por sí solo no demuestra qué pantalla vio el usuario. Cuando cambie un texto o alcance, también debe cambiar su versión. La versión de esta declaración es independiente de la del aviso de privacidad.

| Tipo | Significado | Alcance registrado |
|---|---|---|
| `SELF` | La cuenta declara que el perfil es propio | `OWN_DATA` |
| `FICTITIOUS` | Se declara que sólo se usarán datos inventados | `FICTITIOUS_DATA_ONLY` |
| `HOLDER_PERMISSION` | El cuidador declara permiso del titular | `WITHIN_DECLARED_AUTHORIZATION` |
| `LEGAL_REPRESENTATION` | El cuidador declara facultades de representación | `WITHIN_DECLARED_AUTHORIZATION` |
| `PENDING` | No puede confirmar autorización | No se persiste un perfil ni declaración |

Una elección de `PENDING` no bloquea la cuenta. Frontend conserva el formulario, permite volver o corregir la selección, y no envía datos de salud. Si el cliente intenta crear el perfil con esa opción, el servidor responde 409 sin guardar nada. No se incorpora carga de documentos para acreditar representación.

## Crear una persona

`POST /api/v1/care-subjects`

```json
{
  "requestId": "e9a743e3-6ca7-4dc7-a06c-df514519dd41",
  "name": "Persona de ejemplo",
  "declaration": {
    "type": "FICTITIOUS",
    "textVersion": "1",
    "confirmed": true
  }
}
```

`birthDate`, `gender` y `notes` conservan su contrato anterior. La respuesta 201 mantiene la forma de `CareSubjectResponse`. El alta del perfil, relación de cuidado y declaración ocurre en una transacción.

`requestId` es una clave opaca por cuenta, de 1 a 64 caracteres ASCII alfanuméricos, guion o guion bajo. Se recomienda un UUID generado una vez al confirmar el formulario. Mantenerlo al reintentar la misma petición después de un fallo de conexión. Si cambia el contenido, resolver primero el resultado del intento anterior y después iniciar una solicitud nueva con otra clave si corresponde; no generar otra clave sólo porque se perdió la respuesta.

La misma cuenta, clave y contenido recuperan el mismo perfil con 201, mientras exista el comprobante y el usuario conserve permisos. Una clave reutilizada con contenido diferente devuelve 409. La respuesta describe el perfil en su estado actual, no una instantánea del primer response. Los comprobantes se eliminan con el perfil; no reutilizar claves de perfiles eliminados. El hash sirve para comparar peticiones y no se expone en la API.

## Consultar y completar declaraciones de perfiles existentes

Sólo un `PRINCIPAL` activo del perfil puede utilizar estas rutas. Un cuidador, invitado pendiente, usuario ajeno o administrador global sin esa relación no puede leer o escribir la declaración.

`GET /api/v1/care-subjects/{id}/authorization-declarations`

Devuelve las declaraciones de la más reciente a la más antigua. Un perfil anterior sin declaración devuelve `[]`; frontend debe mostrar «Sin declaración registrada», nunca «Autorizado» o «Verificado». Los endpoints existentes de listado y detalle conservan su respuesta y no añaden consultas de declaraciones por cada perfil.

`POST /api/v1/care-subjects/{id}/authorization-declarations`

```json
{
  "type": "HOLDER_PERMISSION",
  "textVersion": "1",
  "confirmed": true,
  "previousDeclarationId": 123
}
```

Para un perfil sin declaración, omitir `previousDeclarationId` o enviar null. Para cambiar una declaración, enviar el ID de la primera fila del historial que se revisó. Una escritura nueva exige que esa fila siga siendo la actual; si cambió, responde 409 y el cliente debe consultar de nuevo.

Si el actor, tipo y versión ya coinciden con la declaración actual, devuelve esa fila con 200 sin escribir otra, incluso si se reenvió la referencia anterior. Es un no-op por coincidencia del estado actual. Un reintento de una declaración antigua después de otra diferente no revierte el historial.

La respuesta contiene `id`, `careSubjectId`, `declaredByUserId`, `type`, `scope`, `textVersion`, `text` y `declaredAt`. La identidad se toma de la sesión y la fecha del servidor. No se aceptan como evidencia un autor, texto o fecha suministrados por el cliente.

## Errores y experiencia de frontend

| Caso | HTTP | Tratamiento del cliente |
|---|---|---|
| Falta declaración en el alta | 400 | Mostrar `message`: «Actualiza Yuyay para registrar la declaración de esta persona» |
| Falta confirmación, tipo o clave válida | 400 | Mostrar error y conservar el formulario |
| Tipo `PENDING` | 409 | Conservar el formulario sin crear perfil; permitir corregir la elección |
| Versión del texto antigua | 409 | Volver a obtener el catálogo y pedir nueva revisión; no confirmar automáticamente |
| Declaración actual cambió | 409 | Recargar el historial antes de volver a confirmar |
| Clave de alta reutilizada con otros datos | 409 | Resolver el estado del intento anterior antes de iniciar otra solicitud |
| Usuario sin relación `PRINCIPAL` activa | 403 | No ofrecer escritura ni mostrar historial |

## Transición, esquema y entrega

- Los perfiles anteriores y los generados por el seeder no reciben declaraciones fabricadas. Sus permisos existentes se conservan. Esto es una transición explícita del MVP, no prueba de autorización para un piloto real.
- Antes de un piloto real, definir la evidencia aplicable por tipo de declaración, la comprobación de identidad, voluntad o facultades según el caso, el alcance de acceso y los procedimientos de revocación, conservación y supresión de #73.
- Un APK anterior puede consultar y usar perfiles existentes según sus permisos, pero no crear perfiles con el contrato antiguo. El error principal es legible por su cliente HTTP actual.
- Preparar frontend #52, el APK actualizado y su distribución antes de fusionar este PR: el workflow de backend puede desplegar al recibir un push a `main`.
- La nueva tabla `care_authorization_declarations` es aditiva. Guarda actor/perfil, tipo/alcance, versión, texto, fecha, clave de creación y hash. Tiene índice de perfil/ID, unicidad de actor/clave y FK al perfil con borrado en cascada. No modifica ni rellena filas antiguas de `care_subjects`.
- Esta entrega conserva el mecanismo actual de esquema del proyecto; no introduce Flyway ni modifica `ddl-auto`. Antes del despliegue, comprobar respaldo, permisos de creación de tabla/índices y DDL efectivo en una copia PostgreSQL. Las pruebas H2 no sustituyen esa comprobación.
- El historial se mantiene al cambiar declaraciones mientras exista el perfil. La eliminación permitida de un perfil vacío conserva su comportamiento y elimina sus declaraciones. Una política completa de conservación, revocación y supresión se sigue en [backend #73](https://github.com/jocelynuceda/dbp-yuyay/issues/73).
- La colección Postman y el smoke OCR usan personas ficticias. Usar exclusivamente documentos sintéticos en ese smoke. La variable dinámica de Postman genera una clave nueva por ejecución; para probar un replay, fijar manualmente la misma clave y el mismo cuerpo en ambos envíos.

## Comprobaciones

La integración prueba opciones y snapshot exacto, perfiles antiguos, errores sin escrituras parciales, permisos de lectura/escritura, historial, reintentos del alta y declaraciones, concurrencia, claves por cuenta y eliminación del perfil vacío. La suite existente sigue comprobando adjuntos, consultas, invitaciones, delegaciones y número constante de consultas SQL al listar perfiles.
