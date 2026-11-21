# Backend: bloque inmediato de la entrega final

Para altas por correo, ingreso con Google y términos vigentes, consultar el
[contrato de autenticación](google-auth-and-terms.md).

## Contrato de paginación

Estos tres GET autenticados ahora devuelven un objeto, en lugar de un array:

| Ruta (prefijo `/api/v1`) | Tamaño por defecto | Orden por defecto | Filtro |
| --- | --- | --- | --- |
| `/care-subjects/{id}/health-entries` | 10 | `createdAt,desc` | `category=ALLERGY` |
| `/notifications` | 10 | `createdAt,desc` | `unread=true` o `unread=false` |
| `/care-subjects/{id}/access-logs` | 20 | `occurredAt,desc` | Ninguno |

Ejemplo: `GET /api/v1/care-subjects/1/health-entries?category=ALLERGY&page=0&size=2&sort=createdAt,desc`.

Antes:

```json
[{ "id": 12, "categoryCode": "ALLERGY" }]
```

Ahora (elementos abreviados; cada elemento conserva sus campos anteriores):

```json
{
  "content": [{ "id": 12, "categoryCode": "ALLERGY" }],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 2
}
```

- `page` comienza en 0; `size` se limita a 100. Una página posterior al final devuelve
  `content: []` y conserva los totales del filtro. Una colección vacía tiene `totalPages: 0`.
- Se permite ordenar por `id` o por el campo de fecha indicado en la tabla, con `asc` o `desc`.
  Un campo no admitido devuelve 400. Se añade `id,desc` como desempate si no se pidió un orden por id.
- `category` ausente, vacío o en blanco incluye todas las categorías activas. Los registros
  eliminados no participan en `content` ni en los totales.
- `unread` ausente o vacío incluye todos los avisos; `true` incluye solo los sin leer;
  `false` incluye solo los leídos. Para la pestaña **Todas**, omitir el parámetro.
- Los avisos siguen limitados al usuario autenticado. La bitácora sigue reservada al PRINCIPAL
  de la persona. Los registros siguen exigiendo una relación de cuidado activa.

El móvil actualizado en el PR #1 de `yuyay-frontend` (commit `1f125e3`) acepta
`Page` y el array legado. Recorre todas las páginas de registros y avisos para mantener los
contadores de las pantallas actuales. Las ramas móviles anteriores a ese cambio deben integrar
main antes de usar esta API. La bitácora requiere leer `content` si un cliente la incorpora.

Conservan su contrato de lista: `/delegate/health-entries`, `.../health-entries/{entryId}/versions`,
consultas (incluido `/changes`), traspasos, delegaciones y adjuntos. No se modificaron los métodos
de repositorio usados por otros servicios ni el intercambio de tokens de delegación.

## Consultas, CORS y enlaces

Las consultas admiten fechas pasadas, presentes y futuras al crear y actualizar. La fecha sigue
siendo obligatoria en creación; en creación, motivo vacío y fechas mal formadas siguen devolviendo 400.

```dotenv
APP_BASE_URL=http://localhost:8080
FRONTEND_URL=http://localhost:5173
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8081,http://localhost:3000
```

`APP_BASE_URL` continúa identificando la API. `FRONTEND_URL` configura las rutas web de invitaciones
(`/invitaciones/{id}`), delegaciones (`/acceso/{token}`) y el enlace de bienvenida a la raíz de la web.
Se quitan barras finales de `FRONTEND_URL` para evitar `//` al construir rutas. La API pública para
canjear la delegación continúa siendo `POST /api/v1/public/delegations/{token}/exchange`.

Los orígenes CORS por defecto incluyen localhost 5173, 8081 y 3000. Ya se permitían los headers
`Authorization`/`Content-Type` y los métodos PATCH/DELETE, y se preservan. Cuando exista el dominio
web real, agregar su origen exacto a `CORS_ALLOWED_ORIGINS` y usarlo en `FRONTEND_URL`; no hay un
dominio de Vercel supuesto. Si se define `CORS_ALLOWED_ORIGINS`, su valor reemplaza los defaults.

El despliegue existente pasa `~/.env` de EC2 mediante `docker run --env-file`; basta añadir esas
variables a ese archivo al coordinar el despliegue. No es necesario introducir secrets de Actions
para configurar los enlaces. El workflow de la rama de despliegue prepara la comprobación de salud por HTTPS.

## Validación y límites

Ejecutar `./mvnw verify` (Windows: `.\mvnw.cmd verify`) con Java 26. Las pruebas de integración
activan el perfil `test`: H2 en memoria, correo simulado y almacenamiento/OCR mockeados en los
tests de adjuntos. No requieren RDS, Resend, S3 ni Textract reales.

Validación del bloque: `mvnw.cmd -B verify` con Java 26, 91 tests, sin fallos, errores ni omitidos.

La cobertura incluye fechas futuras, validación restante, páginas 0/1, tamaño y límite de 100,
totales, filtros, empate de fechas, orden explícito, páginas fuera de rango, permisos y aislamiento,
preflight CORS y enlaces web con host distinto de la API y barras finales.

HTTPS está activo en producción. Quedan datos demo, medición de latencia y despliegue de la web
funcional. El listado
de salud conserva la consulta existente de última versión por registro (N+1), ahora limitada al
tamaño de página; optimizarla y medir latencia corresponde a la revisión de rendimiento del plan.

## Género (opcional)

`gender` acepta `FEMALE`, `MALE`, `OTHER` o `PREFER_NOT_TO_SAY` en el registro, en `PATCH /users/me`
y al crear o editar una persona (`POST`/`PATCH /care-subjects`).

- Para no declararlo, **omitir el campo o mandar `null`**. Un texto vacío (`"gender": ""`) o un valor
  desconocido responde **400**: los formularios que usan `''` como "sin elegir" deben convertirlo a
  `null` antes de enviar.
- En los dos `PATCH`, omitirlo o mandar `null` **conserva** el valor guardado; no lo borra. Para dejar de
  declararlo está `PREFER_NOT_TO_SAY`.
- Las respuestas devuelven `gender: null` en las cuentas y personas que no lo tienen.

## Acceso y borrado de adjuntos

Ver [contrato de adjuntos privados](attachment-access.md) para abrir imágenes/PDF con
un enlace temporal de cinco minutos, eliminar adjuntos sin referencias y manejar
403/404/409/502. La edición clínica conserva su evidencia original.

## Declaraciones al añadir una persona

El alta requiere `declaration` y `requestId`. Consultar el [contrato de declaraciones](care-authorization.md)
para obtener el catálogo de textos, conservar la clave de reintento y tratar los perfiles anteriores.
Coordinar la publicación con frontend #52: los clientes antiguos reciben un 400 legible al intentar
crear una persona. El registro de una declaración no equivale a verificar una autorización legal.

## Orígenes permitidos: quién llega con `Origin` y quién no

CORS lo aplica el navegador, no el servidor: solo un navegador decide bloquear una respuesta por el
origen. Lo que la API sí hace es rechazar con 403 cualquier petición que traiga una cabecera `Origin`
no permitida, sin importar qué cliente la envió. Por eso, al depurar, la pregunta útil no es «¿es web
o móvil?» sino «¿esta petición lleva `Origin` y, si lo lleva, está en la lista?».

| Cliente | Camino a la API | `Origin` y efecto |
| --- | --- | --- |
| Web en el navegador | `VITE_API_URL` vacío deja la ruta relativa `/api/v1`, que Vercel reenvía a la API (`web/vercel.json`) | Para el navegador es mismo origen: no hay preflight. Pero en POST/PUT/PATCH/DELETE el navegador sí envía `Origin` con el dominio de la web y el reenvío lo propaga; si ese origen no está permitido, la API responde 403 sin llegar al controlador |
| Móvil, binarios `preview` y `production` | `EXPO_PUBLIC_API_URL=https://yuyay-frontend.vercel.app` (`mobile/eas.json`): el móvil también pasa por Vercel, no va directo a la API | React Native nativo no aplica las restricciones CORS del navegador y no envía `Origin`. La petición llega sin esa cabecera y la API no evalúa nada: un origen mal configurado no rompe este camino |
| Móvil en desarrollo (Expo Go, emulador) | Sin `EXPO_PUBLIC_API_URL` usa el host de Metro o `http://10.0.2.2:8080` (`mobile/src/services/http.ts`) | Igual que el anterior: sin `Origin`, sin CORS |
| Expo Web | Es un navegador; sin `EXPO_PUBLIC_API_URL` la URL base es `window.location.origin` | Sí aplica CORS, con las mismas reglas que la web |

Es decir: hoy un fallo de CORS solo puede aparecer en los clientes que corren dentro de un navegador
—la web y Expo Web—, nunca en el binario móvil. Lo que sí puede romper al móvil es el otro extremo:
depende del despliegue de Vercel de la web, no de la API.

Comprobado contra la API desplegada (`https://api.34-193-16-240.sslip.io`) el 20-nov-2026: un
preflight con un origen no permitido devuelve 403 y con `https://yuyay-web.vercel.app` devuelve 200;
un POST con `Origin` no permitido devuelve 403, y el mismo POST sin `Origin` llega al controlador
(400 por el cuerpo vacío). A través de `https://yuyay-frontend.vercel.app` el resultado es idéntico,
lo que confirma que el reenvío de Vercel propaga la cabecera `Origin`.

Un detalle del despliegue que conviene revisar: `https://yuyay-web.vercel.app/api/v1/notifications`
devuelve el `index.html` de la SPA (200, `text/html`) en lugar de la respuesta de la API, mientras que
`https://yuyay-frontend.vercel.app/api/v1/notifications` sí responde 401 del backend. En ese dominio
los reenvíos de `web/vercel.json` no están activos, así que la web publicada ahí solo puede hablar con
la API con una URL absoluta y, entonces, con CORS real. Añadir su origen a la lista permitida, como
hace este cambio, es necesario en cualquiera de los dos escenarios.

La API permite un origen cuando coincide con `CORS_ALLOWED_ORIGINS` **o** con
`CORS_ALLOWED_ORIGIN_PATTERNS`: ambas listas se concatenan y se evalúan con el mismo mecanismo de
patrones de Spring, que acepta tanto orígenes exactos como comodines. Los valores en blanco y los
repetidos se descartan. Esto reemplaza la configuración anterior, que usaba `setAllowedOrigins` y
`setAllowedOriginPatterns` por separado.

En la práctica: los orígenes nuevos van en `CORS_ALLOWED_ORIGINS`, aunque lleven comodín.
`CORS_ALLOWED_ORIGIN_PATTERNS` se mantiene porque el `~/.env` de la EC2 ya la define, y su contenido
se suma al de la otra. Definir cualquiera de las dos reemplaza su propio valor por defecto, no el de
la otra.

Los valores por defecto incluyen el dominio de producción de la web, `https://yuyay-web.vercel.app`,
y el patrón de las vistas previas de cada rama. Si Vercel cambia el nombre del proyecto, el patrón de
vistas previas deja de coincidir y hay que actualizarlo.

Queda una decisión de arquitectura en `yuyay-frontend`, fuera de este PR: el móvil apunta a un dominio
de la web (`yuyay-frontend.vercel.app`) y no a la API, de modo que cada binario depende del despliegue
web y añade un salto por Vercel. Darle al móvil la URL de la API —propia o compartida con la web, pero
decidida a propósito— es probablemente lo correcto; mientras no se decida, un cambio de orígenes se
prueba en ambos clientes.
