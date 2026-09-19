# Yuyay — Backend

Registro de salud declarativo compartido entre cuidadores, con resumen de traspaso para la consulta médica y delegaciones temporales.
Curso CS2031 · Desarrollo Basado en Plataformas · UTEC · Grupo 3 · Sección 11.

**Integrantes:** Caceres Sanchez, Matias Andres (202510370) · Huapaya Núñez, Luis Felipe Augusto (202220394) · Paredes Rodriguez, Nayra Kamila (202510441) · Puicon Arapa, Valentina Yamile (202510090) · Uceda Tang, Jocelyn Avril (202510545)

## Stack
Spring Boot 4.1.1 · Java 17 · Spring Data JPA / Hibernate · PostgreSQL · Spring Security + JWT (jjwt) · MapStruct · Lombok · Thymeleaf + Resend (correo) · Docker · AWS EC2 + RDS.

## Cómo correr en local
```bash
cp .env.example .env            # editar si hace falta
docker compose up -d            # PostgreSQL 16 en localhost:5432 (DB_PORT=5433 si el 5432 está ocupado)
./mvnw spring-boot:run          # perfil dev por defecto
```
- Health: `GET http://localhost:8080/actuator/health`
- Tests (H2 en memoria, no necesita Docker): `./mvnw test`

## Estructura (package by feature, Controller → Service → Repository)
```
com.yuyay
├── config/        SecurityConfig, CorsConfig, AsyncConfig, MapStructConfig, AppProperties, DataSeeder
├── security/      JwtService, JwtAuthenticationFilter, CustomUserDetailsService, UserPrincipal, DelegatePrincipal,
│                  CurrentUserService, AuthorizationService, entry point / access denied (JSON), TokenHasher
├── exception/     ErrorResponseDTO, YuyayException + 13 excepciones, GlobalExceptionHandler
├── auth/          registro, login, refresh (rotación), logout
├── user/          User, Role, RefreshToken
├── care/          CareSubject, CareRelationship, Delegation
├── health/        HealthCategory, HealthEntry, HealthEntryVersion
├── consultation/  Consultation, Handoff, HandoffItem, HandoffSession
├── notification/  Notification (+ email/)
├── audit/         AccessLog
└── event/         eventos (records) + listener/
```
Cada feature: `entity/ repository/ service/ controller/ dto/ mapper/`.

## Glosario (presentación ↔ código)
| Presentación | Código | Nota |
|---|---|---|
| CareSubject | `CareSubject` | persona cuidada, puede no tener cuenta |
| HealthEntry / HealthyEntryVersion / HealthyCategory | `HealthEntry` / `HealthEntryVersion` / `HealthCategory` | la cabecera no tiene contenido; el contenido vive en versiones inmutables |
| "Episodios" | categoría `EPISODE` | junto a ALLERGY, CONDITION, MEDICATION, IMMUNIZATION |
| Consultation | `Consultation` | fecha, motivo, notas |
| Handoff | `Handoff` + `HandoffItem` → versión | resumen fijo de lo que se eligió mostrar |
| Delegation | `Delegation` | acceso largo sin cuenta, por categorías, token canjeado una vez por JWT `DELEGATE` |
| (enlace de resumen) | `HandoffSession` | enlace corto para la cita, con fecha tope y ventana de minutos |
| AccessLog | `AccessLog` | bitácora inmutable escrita por listener async |

## Reglas del proyecto
1. Ningún controller importa `entity`: entra un DTO, sale un DTO (MapStruct).
2. El usuario actual se obtiene con `CurrentUserService`; nunca llega por parámetro.
3. Todo método de service que toca un CareSubject llama antes a `AuthorizationService.requireAccess` / `requireRole`.
4. Nada de `findAll()`: los listados se filtran en la consulta por relación ACTIVE.
5. Errores: siempre una excepción de `exception/` (nunca `RuntimeException`).
6. Eventos son `record` con ids; listeners con `@TransactionalEventListener(AFTER_COMMIT) @Async @Transactional(REQUIRES_NEW)`.
7. Secretos solo en variables de entorno (`.env.example`).

## Cómo añadir una funcionalidad (plantilla: `auth/` y `user/`)
1. DTOs (`record` con validaciones) en `dto/`.
2. Mapper MapStruct con `@Mapper(config = MapStructConfig.class)`.
3. Service `@Service @RequiredArgsConstructor`, `@Transactional`, que autoriza, opera y devuelve DTOs.
4. Controller `@RestController @RequestMapping("/api/v1/<recurso-plural>")` con `@Valid`.
5. Test de integración (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`): éxito + 400 + 403.

### Autenticación

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/auth/register` | 201, devuelve access + refresh |
| POST | `/api/v1/auth/login` | 200 |
| POST | `/api/v1/auth/refresh` | rota el refresh token |
| POST | `/api/v1/auth/logout` | 204, revoca el refresh |
| GET / PATCH | `/api/v1/users/me` | perfil propio |

### Cuidado (CareSubject y relaciones)

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/care-subjects` | 201, crea la persona cuidada; el creador queda como PRINCIPAL ACTIVE |
| GET | `/api/v1/care-subjects` | lista los CareSubject a los que el usuario tiene acceso ACTIVE |
| GET | `/api/v1/care-subjects/{id}` | detalle con el rol del usuario actual |
| PATCH | `/api/v1/care-subjects/{id}` | actualiza nombre, fecha de nacimiento y notas |
| DELETE | `/api/v1/care-subjects/{id}` | 204, solo PRINCIPAL |
| POST | `/api/v1/care-subjects/{subjectId}/relationships` | 201, invita a un cuidador por email (solo PRINCIPAL) |
| GET | `/api/v1/care-subjects/{subjectId}/relationships` | lista las relaciones del sujeto (cualquier miembro con acceso) |
| GET | `/api/v1/me/relationships/pending` | invitaciones pendientes del usuario actual |
| POST | `/api/v1/me/relationships/{relationshipId}/accept` | 200, acepta una invitación |
| DELETE | `/api/v1/care-subjects/{subjectId}/relationships/{relationshipId}` | 204, revoca una relación (solo PRINCIPAL; no puede revocar al PRINCIPAL) |

### Salud (HealthEntry)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/v1/health-categories` | catálogo fijo: ALLERGY, CONDITION, MEDICATION, IMMUNIZATION, EPISODE |
| POST | `/api/v1/care-subjects/{subjectId}/health-entries` | 201, crea entrada + versión 1 (`CREATED`) |
| GET | `/api/v1/care-subjects/{subjectId}/health-entries` | lista entradas activas; filtro opcional `?category=ALLERGY` |
| GET | `/api/v1/care-subjects/{subjectId}/health-entries/{entryId}` | detalle + última versión |
| GET | `/api/v1/care-subjects/{subjectId}/health-entries/{entryId}/versions` | historial completo de versiones (inmutable) |
| PATCH | `/api/v1/care-subjects/{subjectId}/health-entries/{entryId}` | 200, crea nueva versión (`UPDATED`) |
| DELETE | `/api/v1/care-subjects/{subjectId}/health-entries/{entryId}` | 204, soft delete + versión (`DELETED`) |

(El resto se documenta a medida que se implementa.)


## Modelos clave

### Roles y estados de cuidado

| Concepto | Valores |
|---|---|
| CareRole | `PRINCIPAL` (dueño del sujeto, único) · `CAREGIVER` |
| CareStatus | `PENDING` · `ACTIVE` · `REVOKED` |
| ChangeType | `CREATED` · `UPDATED` · `DELETED` |
| Confidence | `CONFIRMED` · `UNCERTAIN` |
| AccessAction | `VIEW` · `CREATE` · `UPDATE` · `DELETE` · `OPEN_HANDOFF_LINK` · `EXCHANGE_DELEGATION` |

- Al crear un `CareSubject`, el usuario autenticado queda automáticamente como
  `PRINCIPAL` con `status = ACTIVE`.
- Las invitaciones se crean como `PENDING`; el invitado las acepta vía
  `/me/relationships/{id}/accept`.

### Versionado de salud

`HealthEntry` es la cabecera (careSubject, categoría, autor, timestamps). El
contenido real vive en `HealthEntryVersion`, que **nunca se modifica**:

- Cada edición añade una nueva versión con `changeType = UPDATED`.
- El borrado añade una versión `DELETED` y marca `deletedAt` en la cabecera
  (soft delete).
- El historial completo queda disponible en `GET .../versions`.


## Códigos de estado

| Código | Significado |
|---|---|
| 200 OK | Consulta o actualización exitosa |
| 201 Created | Recurso creado (POST) |
| 204 No Content | Eliminación exitosa (DELETE) |
| 400 Bad Request | Validación fallida del body |
| 401 Unauthorized | Token inválido o expirado |
| 403 Forbidden | Sin acceso al CareSubject o sin rol requerido |
| 404 Not Found | Recurso no existe |
| 409 Conflict | Recurso duplicado |
| 500 Server Error | Error inesperado |


## Pendientes por implementar

| Feature | Rutas por definir |
|---|---|
| Delegation | `/api/v1/care-subjects/{id}/delegations`, `/api/v1/delegations/exchange` |
| Consultation | `/api/v1/care-subjects/{id}/consultations` |
| Handoff | `/api/v1/care-subjects/{id}/handoffs`, `/api/v1/handoffs/{id}/sessions` |
| Notification | `/api/v1/me/notifications` |
| Audit | `/api/v1/care-subjects/{id}/access-logs` |