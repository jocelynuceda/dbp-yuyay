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

## Endpoints implementados
| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/auth/register` | 201, devuelve access + refresh |
| POST | `/api/v1/auth/login` | 200 |
| POST | `/api/v1/auth/refresh` | rota el refresh token |
| POST | `/api/v1/auth/logout` | 204, revoca el refresh |
| GET / PATCH | `/api/v1/users/me` | perfil propio |

(El resto se documenta a medida que se implementa.)

## Variables de entorno
Ver `.env.example`: `DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, JWT_ACCESS_EXPIRATION_MINUTES, JWT_REFRESH_EXPIRATION_DAYS, APP_BASE_URL, CORS_ALLOWED_ORIGINS, RESEND_API_KEY, MAIL_FROM`.
