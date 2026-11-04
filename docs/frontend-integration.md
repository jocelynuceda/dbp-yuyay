# Backend: bloque inmediato de la entrega final

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
