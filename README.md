# Yuyay — Registro de salud compartido entre cuidadores

**Curso:** CS2031 · Desarrollo Basado en Plataformas · Universidad de Ingeniería y Tecnología (UTEC)
**Sección:** Laboratorio 11 · Grupo 3

**Integrantes**

| Integrante | Código |
| --- | --- |
| Caceres Sanchez, Matias Andres | 202510370 |
| Huapaya Núñez, Luis Felipe Augusto | 202220394 |
| Paredes Rodriguez, Nayra Kamila | 202510441 |
| Puicon Arapa, Valentina Yamile | 202510090 |
| Uceda Tang, Jocelyn Avril | 202510545 |

---

## Índice

1. [Introducción](#1-introducción)
2. [Identificación del problema o necesidad](#2-identificación-del-problema-o-necesidad)
3. [Descripción de la solución](#3-descripción-de-la-solución)
4. [Modelo de entidades](#4-modelo-de-entidades)
5. [Manejo de errores](#5-manejo-de-errores)
6. [Medidas de seguridad implementadas](#6-medidas-de-seguridad-implementadas)
7. [Eventos y asincronía](#7-eventos-y-asincronía)
8. [GitHub y gestión del proyecto](#8-github-y-gestión-del-proyecto)
9. [Ejecución local y despliegue](#9-ejecución-local-y-despliegue)
10. [Conclusión](#10-conclusión)
11. [Apéndices](#11-apéndices)

---

## 1. Introducción

### Contexto

Cuando varios familiares se turnan para cuidar a una persona con una condición persistente, la información queda repartida entre recetas de papel, el registro de cada clínica y la memoria del cuidador principal. Quien acompaña al paciente suele ser el que menos sabe: no puede responder qué toma ni qué cambió, y la consulta se resuelve con información incompleta.

### Objetivos del proyecto

1. Compartir la información de salud entre los cuidadores, con trazabilidad de quién declaró cada dato y cuándo.
2. Generar un resumen de traspaso que el acompañante muestre al médico en segundos, sin que este instale nada.
3. Delegar acceso temporal y limitado a terceros sin cuenta, como una enfermera contratada por una semana.
4. Evitar que un endpoint exponga información fuera del alcance del rol o de la delegación vigente.

---

## 2. Identificación del problema o necesidad

### Descripción del problema

La información clínica de una persona dependiente vive fragmentada y sin dueño: las historias clínicas no se comparten entre establecimientos y están pensadas para el personal de salud, no para la familia. El cuidador que acompaña responde de memoria e introduce errores en datos sensibles como dosis y alergias.

### Justificación

El costo es directo: prescripciones duplicadas, interacciones no detectadas y exámenes repetidos. Yuyay no pretende ser una historia clínica ni reemplazar el criterio médico: es un **registro declarativo** que no garantiza que un dato sea verdadero, sino **quién lo declaró, cuándo y si alguien lo modificó**. Por eso cada dato lleva un nivel de confianza explícito (`CONFIRMED` o `UNCERTAIN`).

---

## 3. Descripción de la solución

### Funcionalidades implementadas

| Funcionalidad | Cómo resuelve el problema |
| --- | --- |
| **Cuentas y autenticación** | Registro y login con contraseñas cifradas; sesiones sin estado mediante JWT y refresh token rotatorio. |
| **Personas a cargo y vínculos de cuidado** | Un usuario registra a la persona que cuida e invita por correo a otros cuidadores. La invitación se acepta explícitamente; el vínculo tiene rol (`PRINCIPAL` o `CAREGIVER`), etiqueta de parentesco y vigencia, y puede revocarse. |
| **Registros de salud versionados** | Alergias, condiciones, medicación, vacunas y episodios. Editar nunca sobrescribe: crea una versión nueva e inmutable con autor y fecha, de modo que el historial de lo declarado se conserva completo. |
| **Consultas médicas** | Cada visita queda registrada con fecha y motivo, y sirve como punto de corte para revisar qué se registró después. |
| **Resumen de traspaso** | El cuidador que sabe arma una pantalla única con el motivo de la cita, las preguntas que quiere hacer y las versiones exactas de los datos que eligió mostrar. |
| **Enlace temporal del resumen** | Se comparte por un enlace que funciona sin cuenta, con fecha tope y ventana de minutos desde la primera apertura. Quien lo abre declara su nombre y rol, y queda registrado. |
| **Delegaciones** | Acceso temporal para alguien sin cuenta, limitado a categorías concretas y con vencimiento. El enlace se canjea una sola vez por un token de sesión con permisos restringidos. |
| **Notificaciones y correo** | Todos los cuidadores se enteran de los cambios; la bienvenida al registrarse, las invitaciones y las delegaciones llegan por correo con plantillas HTML. |
| **Bitácora de accesos** | Registro inmutable de quién vio o modificó qué y cuándo, incluidos los accesos anónimos por enlace. |
| **Adjuntos con OCR** | El cuidador sube la foto de una receta o un resultado de laboratorio; el archivo se guarda en almacenamiento externo y el texto se extrae de forma asíncrona. La extracción nunca crea un registro por sí sola: propone el texto y el cuidador decide. |
| **Administración** | Rol global `ADMIN` con endpoints propios para gestionar usuarios y consultar estadísticas del sistema. |

### Tecnologías utilizadas

Java 17 y **Spring Boot 4.1.1** (Web MVC, Data JPA con Hibernate 7, Security 7, Bean Validation), **PostgreSQL** (16 en desarrollo, 18 en RDS), **MapStruct**, **Lombok**, **JJWT**, **Thymeleaf** para plantillas de correo y **Resend** como servicio de envío. Los adjuntos se almacenan en **AWS S3** y su texto se extrae con **AWS Textract**. El entorno local usa **Docker Compose**, la integración continua **GitHub Actions** y el despliegue **AWS (EC2 + RDS)**. Las pruebas usan JUnit 5, MockMvc y H2 en memoria.

### Arquitectura

El proyecto se organiza **por funcionalidad** (`auth`, `user`, `care`, `health`, `consultation`, `attachment`, `notification`, `audit`, `admin`), y cada una respeta las capas **Controller → Service → Repository**. Las dependencias se inyectan por constructor y ninguna entidad se expone en una respuesta HTTP.

---

## 4. Modelo de entidades

```mermaid
erDiagram
    USER ||--o{ REFRESH_TOKEN : "tiene"
    USER ||--o{ CARE_RELATIONSHIP : "cuida como"
    CARE_SUBJECT ||--o{ CARE_RELATIONSHIP : "es cuidado por"
    CARE_SUBJECT ||--o{ DELEGATION : "delega acceso"
    USER ||--o{ DELEGATION : "otorga"
    DELEGATION }o--o{ HEALTH_CATEGORY : "alcance"
    CARE_SUBJECT ||--o{ HEALTH_ENTRY : "registra"
    HEALTH_CATEGORY ||--o{ HEALTH_ENTRY : "clasifica"
    USER ||--o{ HEALTH_ENTRY : "crea"
    HEALTH_ENTRY ||--|{ HEALTH_ENTRY_VERSION : "versiona"
    USER ||--o{ HEALTH_ENTRY_VERSION : "declara"
    CARE_SUBJECT ||--o{ CONSULTATION : "asiste a"
    CARE_SUBJECT ||--o{ HANDOFF : "resume"
    USER ||--o{ HANDOFF : "prepara"
    HANDOFF ||--o{ HANDOFF_ITEM : "contiene"
    HEALTH_ENTRY_VERSION ||--o{ HANDOFF_ITEM : "incluida en"
    HANDOFF ||--o{ HANDOFF_SESSION : "se comparte por"
    USER ||--o{ NOTIFICATION : "recibe"
    CARE_SUBJECT ||--o{ ATTACHMENT : "documenta"
    CONSULTATION |o--o{ ATTACHMENT : "respalda"
    USER ||--o{ ATTACHMENT : "sube"
    ATTACHMENT |o--o{ HEALTH_ENTRY_VERSION : "respalda"
    CARE_SUBJECT ||--o{ ACCESS_LOG : "auditado en"
    USER |o--o{ ACCESS_LOG : "actor"
    DELEGATION |o--o{ ACCESS_LOG : "actor"

    USER {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar name
        enum role "USER | ADMIN"
        timestamp created_at
    }
    REFRESH_TOKEN {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        timestamp expires_at
        timestamp revoked_at
    }
    CARE_SUBJECT {
        bigint id PK
        varchar name
        date birth_date
        text notes
        timestamp created_at
    }
    CARE_RELATIONSHIP {
        bigint id PK
        bigint user_id FK
        bigint care_subject_id FK
        enum role "PRINCIPAL | CAREGIVER"
        varchar relationship_label
        enum status "PENDING | ACTIVE | REVOKED"
        timestamp invited_at
        timestamp accepted_at
        timestamp revoked_at
    }
    DELEGATION {
        bigint id PK
        bigint care_subject_id FK
        bigint granted_by FK
        varchar grantee_name
        varchar grantee_email
        varchar token_hash UK
        timestamp valid_until
        timestamp exchanged_at
        timestamp revoked_at
    }
    HEALTH_CATEGORY {
        bigint id PK
        varchar code UK
        varchar name
        varchar description
    }
    HEALTH_ENTRY {
        bigint id PK
        bigint care_subject_id FK
        bigint health_category_id FK
        bigint created_by FK
        timestamp created_at
        timestamp deleted_at
    }
    HEALTH_ENTRY_VERSION {
        bigint id PK
        bigint health_entry_id FK
        int version_number
        enum change_type "CREATED | UPDATED | DELETED"
        varchar title
        text details
        varchar dose
        varchar frequency
        date occurred_on
        enum confidence_level "CONFIRMED | UNCERTAIN"
        bigint source_attachment_id FK
        bigint declared_by FK
        timestamp declared_at
    }
    CONSULTATION {
        bigint id PK
        bigint care_subject_id FK
        bigint created_by FK
        date date
        varchar reason
        text notes
    }
    HANDOFF {
        bigint id PK
        bigint care_subject_id FK
        bigint created_by FK
        text reason
        text questions
        timestamp created_at
    }
    HANDOFF_ITEM {
        bigint id PK
        bigint handoff_id FK
        bigint health_entry_version_id FK
    }
    HANDOFF_SESSION {
        bigint id PK
        bigint handoff_id FK
        varchar token_hash UK
        timestamp expires_at
        int session_window_minutes
        timestamp first_opened_at
        timestamp revoked_at
    }
    ATTACHMENT {
        bigint id PK
        bigint care_subject_id FK
        bigint consultation_id FK
        bigint uploaded_by FK
        varchar original_filename
        varchar content_type
        bigint size_bytes
        varchar storage_key
        enum ocr_status "PENDING | DONE | FAILED"
        text ocr_text
        text ocr_error
        timestamp uploaded_at
        timestamp ocr_completed_at
    }
    NOTIFICATION {
        bigint id PK
        bigint user_id FK
        enum type
        varchar title
        text body
        timestamp read_at
        timestamp created_at
    }
    ACCESS_LOG {
        bigint id PK
        bigint care_subject_id FK
        bigint user_id FK
        bigint delegation_id FK
        enum action
        varchar target_type
        bigint target_id
        varchar visitor_name
        varchar visitor_role
        timestamp occurred_at
    }
```

### Descripción de las entidades principales

**User** es la cuenta con la que alguien inicia sesión y **RefreshToken** guarda el hash de cada refresh para rotarlo y revocarlo. **CareSubject** es la persona cuidada, que puede no tener cuenta, y **CareRelationship** es la entidad puente entre ambas: una relación muchos a muchos con datos propios (rol, parentesco, estado `PENDING`/`ACTIVE`/`REVOKED` y marcas de tiempo), razón por la cual es entidad y no un `@ManyToMany` simple. Solo `ACTIVE` otorga acceso.

**HealthCategory** es el catálogo de tipos de dato (`ALLERGY`, `CONDITION`, `MEDICATION`, `IMMUNIZATION`, `EPISODE`). **HealthEntry** es solo la cabecera y no contiene el dato clínico: ese contenido vive en **HealthEntryVersion**, inmutable, donde cada edición inserta una versión nueva con número correlativo, tipo de cambio, nivel de confianza, autor y, si procede, el adjunto del que salió. Esa separación cumple la promesa del producto: reconstruir qué se declaró en cada momento y quién lo hizo.

**Consultation** registra una visita médica. **Handoff** es el resumen de una cita y **HandoffItem** lo vincula con las **versiones** elegidas, de modo que queda congelado aunque el dato cambie; **HandoffSession** es el enlace temporal para compartirlo. **Delegation** otorga acceso a alguien sin cuenta y se relaciona con `HealthCategory` por un `@ManyToMany` real. **Attachment** guarda el archivo subido —clave de almacenamiento, tipo, tamaño y estado de la extracción de texto—. **Notification** y **AccessLog** cierran el modelo: avisos por usuario y bitácora inmutable con tres actores posibles.

Las relaciones son `FetchType.LAZY` para no cargar historiales completos, y el `cascade` se decidió caso por caso: `Handoff` es dueño de sus items y sesiones y `CareSubject` de sus relaciones y consultas, pero **no** hay cascade hacia `HealthEntry` ni `AccessLog`, porque el historial y la auditoría no deben desaparecer como efecto colateral. Los borrados de salud son lógicos (`deleted_at`).

## 5. Manejo de errores

Toda excepción de negocio hereda de `YuyayException`, que lleva asociado su `HttpStatus`. Existen 20 personalizadas: 13 transversales (`ResourceNotFoundException`, `DuplicateEmailException`, `InvalidTokenException`, `ForbiddenCareSubjectAccessException`, `HandoffSessionExpiredException`, `DelegationExpiredException`, `InvalidOperationException`…) y 7 propias de cada módulo.

Un único `@RestControllerAdvice` las traduce, junto con las de Spring (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `DataIntegrityViolationException`, `AccessDeniedException`), al formato `ErrorResponseDTO` con `timestamp`, `status`, `error`, `message`, `path` y los campos inválidos. Los errores del filtro de seguridad se escriben igual desde `JwtAuthenticationEntryPoint` y `JwtAccessDeniedHandler`.

Centralizarlo evita repetir `try/catch` en cada controlador, garantiza que el mismo fallo responda siempre igual e impide filtrar detalles internos: los errores no controlados responden 500 genérico y el detalle queda en el log. Los códigos usados son 200, 201, 202, 204, 400, 401, 403, 404, 409, 410 y 500.

---

## 6. Medidas de seguridad implementadas

### Seguridad de los datos

Las contraseñas se almacenan con **BCrypt** (factor 12) y nunca salen en una respuesta. La autenticación es **sin estado**: el login entrega un *access token* JWT de 60 minutos y un *refresh token* opaco de 7 días del que solo se guarda su **SHA-256**; cada uso lo rota y revoca el anterior, así un token filtrado sirve una vez. Los tokens de enlaces y delegaciones se generan con `SecureRandom`, se entregan una única vez y en la base queda su hash. Los secretos vienen de variables de entorno.

### Autorización en dos niveles

La decisión central es que **el rol global no determina el acceso a un recurso**. El JWT solo indica si quien llama es `USER`, `ADMIN` o `DELEGATE`; que pueda ver a una persona lo decide su `CareRelationship` activa, y que un delegado vea cierta categoría, su `Delegation` vigente. Esa verificación vive en `AuthorizationService`, invocado en cada método de servicio que toca datos de una persona; administración usa además `@PreAuthorize("hasRole('ADMIN')")`.

El filtrado ocurre **a nivel de consulta**, no del DTO: los listados no usan `findAll()`, sino JPQL con join a las relaciones activas del usuario, y el del delegado filtra por categorías permitidas en el propio `WHERE`. Un tercero sin vínculo recibe lista vacía; por id recibe 403. Revocar corta el acceso de inmediato, aunque el token siga vigente.

### Prevención de vulnerabilidades comunes

**Inyección SQL:** no se concatena SQL; todo el acceso pasa por Spring Data JPA con *query methods* o JPQL con parámetros nombrados, que se traducen a sentencias preparadas.

**XSS:** la API devuelve solo JSON serializado por Jackson y las plantillas de correo usan Thymeleaf, que escapa variables por defecto; la validación de entrada limita además el contenido aceptado.

**CSRF:** la protección está deshabilitada de forma deliberada y segura: al ser una API sin estado sin cookies de sesión, la credencial viaja en la cabecera `Authorization`, que el navegador no adjunta solo en peticiones de terceros. **CORS** se restringe a los orígenes configurados.

**Archivos maliciosos:** los adjuntos se validan por firma real del archivo, no por su extensión, antes de subirse al almacenamiento.

**Enumeración de usuarios:** el login responde igual ante correo inexistente y contraseña incorrecta, y los enlaces públicos responden 404 sin revelar si el token existió alguna vez.

## 7. Eventos y asincronía

El sistema publica seis eventos de dominio, todos definidos como `record` inmutables que transportan únicamente identificadores:

| Evento | Se publica en | Listener | Efecto |
| --- | --- | --- | --- |
| `HealthEntryChangedEvent` | `HealthEntryService` al crear, editar o eliminar | `NotificationListener` | Crea una notificación para cada cuidador activo distinto del autor y le envía un correo |
| `UserRegisteredEvent` | `AuthService.register` | `EmailListener` | Envía el correo de bienvenida |
| `CaregiverInvitedEvent` | `CareRelationshipService.invite` | `EmailListener` | Envía el correo de invitación |
| `DelegationCreatedEvent` | `DelegationService.create` | `EmailListener` | Envía el enlace de acceso temporal al delegado |
| `AttachmentUploadedEvent` | `AttachmentService.upload` | `AttachmentListener` | Extrae el texto del archivo y actualiza su estado |
| `AccessRecordedEvent` | `HealthEntryService` (crear, ver, editar, borrar), apertura de un enlace, canje de delegación y lecturas del delegado | `AccessLogListener` | Inserta la entrada en la bitácora |

Los listeners usan `@TransactionalEventListener(phase = AFTER_COMMIT)`, `@Async` y `@Transactional(propagation = REQUIRES_NEW)` sobre un `ThreadPoolTaskExecutor` propio.

La asincronía no es decorativa: enviar un correo o extraer texto de una imagen puede tardar o fallar, y **no debe bloquear ni revertir** la operación del cuidador; quien registra un cambio recibe su 200 de inmediato. `AFTER_COMMIT` garantiza que el listener solo corra si la transacción terminó bien, evitando notificar cambios deshechos, y `REQUIRES_NEW` le da su propia transacción para escribir desde otro hilo. Cada listener captura sus excepciones sin relanzarlas, así una caída del proveedor externo nunca afecta a la API.

---

## 8. GitHub y gestión del proyecto

El trabajo se organizó con *issues* etiquetados (`feature`, `fix`, `test`, `docs`, `infra`), agrupados en *milestones* por fecha de entrega y asignados según el módulo responsable de cada integrante.

El flujo es GitFlow simplificado: `main` protegida —requiere pull request, revisión aprobada y build verde— y una rama por funcionalidad. Cada pull request usa una plantilla con lista de verificación (DTOs, autorización, excepciones, pruebas, ausencia de secretos) y lo revisa otro integrante.

**GitHub Actions** corre la integración continua en cada push y pull request: compila y ejecuta la suite completa con `./mvnw verify` sobre H2. Un segundo workflow construye la imagen Docker y la despliega en AWS al integrar a `main`.

---

## 9. Ejecución local y despliegue

```bash
cp .env.example .env
docker compose up -d
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`; las pruebas (`./mvnw test`) usan H2 y no requieren Docker.

**Variables de entorno:** base de datos, JWT, CORS, correo, almacenamiento de archivos y administrador inicial; la lista completa está en `.env.example`.

**Documentación de la API:** `postman_collection.json` (raíz) contiene 106 peticiones en 15 carpetas, con ejemplos de respuesta, variables automáticas y casos de error por cada código HTTP; los entornos están en `postman/`.

**Despliegue:** la API está en **http://34.193.16.240:8080** — documentación en **/swagger-ui.html** y estado en `/actuator/health`. Corre sobre un laboratorio de AWS Academy, por lo que responde solo mientras el laboratorio está activo. Backend en **EC2** con Docker y base de datos en **RDS PostgreSQL**, accesible solo desde el grupo de seguridad de la instancia. Cada push a `main` ejecuta las pruebas, publica la imagen en GHCR, la despliega por SSH y verifica `/actuator/health`.

---

## 10. Conclusión

### Logros

Se implementó un backend completo que cubre el ciclo de uso del producto: registrar personas a cargo, coordinar cuidadores, mantener un historial versionado y auditable, preparar el resumen para la consulta, compartirlo con quien no tiene cuenta, delegar accesos acotados y adjuntar documentos con extracción de texto. Son 15 entidades, 43 DTOs, 56 endpoints, 20 excepciones y 6 eventos asíncronos, con pruebas de integración de éxito y de autorización denegada.

### Aprendizajes clave

El aprendizaje central fue distinguir **autenticación** de **autorización contextual**: la intuición era modelar "cuidador de Pedro" como un rol del token, y el diseño correcto resultó el opuesto —el token dice quién eres y la base de datos a qué tienes acceso—. El segundo fue el manejo de transacciones con eventos: un listener asíncrono debe correr tras el commit y abrir su propia transacción. El tercero: el versionado inmutable resultó más fiel al problema que actualizar registros.

### Trabajo futuro

La extracción tiene una limitación conocida: Textract reconoce manuscrito solo en inglés, así que las recetas **impresas** en español se leen bien pero las notas a mano no. Es la razón de fondo por la que la confirmación del cuidador es obligatoria; resolverlo requeriría un modelo de visión-lenguaje ajustado a caligrafía en español.

Quedan además dos extensiones de producto: notas de voz con transcripción y notificaciones push. En lo técnico, migrar el esquema a Flyway y paginar todos los listados.

---

## 11. Apéndices

### Licencia

Este proyecto se distribuye bajo la **Licencia MIT**. El texto completo está en el archivo [`LICENSE`](LICENSE).

### Referencias

- Spring Boot Reference Documentation — https://docs.spring.io/spring-boot/index.html
- Spring Security Reference — https://docs.spring.io/spring-security/reference/index.html
- Spring Data JPA Reference — https://docs.spring.io/spring-data/jpa/reference/index.html
- Jakarta Bean Validation Specification — https://beanvalidation.org/
- JSON Web Token (RFC 7519) — https://datatracker.ietf.org/doc/html/rfc7519
- OWASP Top Ten — https://owasp.org/www-project-top-ten/
- MapStruct Reference Guide — https://mapstruct.org/documentation/stable/reference/html/
- Resend API Documentation — https://resend.com/docs
- Amazon EC2 y Amazon RDS User Guides — https://docs.aws.amazon.com/
