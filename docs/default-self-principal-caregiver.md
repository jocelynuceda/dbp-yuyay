# Perfil propio como cuidador principal

## Alta transaccional
AuthService.registerUser, común al alta por correo y Google, llama a CareSubjectService.ensureSelfProfile antes de emitir la respuesta. Usuario, perfil y relación se guardan en la misma transacción: un fallo revierte los tres.
El perfil se vincula mediante account_user_id, usa el alias validado (máximo 120 caracteres), y solo copia fecha de nacimiento y género cuando existen. No inventa edad, datos médicos ni una declaración SELF confirmada. Los términos y la verificación de identidad siguen siendo obligatorios.
El titular puede registrar expresamente su declaración con el flujo de autorización existente.

## Unicidad y permisos
Se bloquea la fila del usuario antes de consultar/crear el perfil. Se reutilizan las restricciones únicas de account_user_id y (user_id, care_subject_id). Repetir la operación no modifica un perfil o relación existentes. Un inicio de sesión no aprovisiona perfiles ni eleva permisos.
Una creación manual SELF se vincula a la cuenta solo si no tiene ya titularidad; en caso contrario devuelve 409 y orienta a abrir el perfil existente. Los reintentos de creación ya confirmados se resuelven antes de esta validación.

## Compatibilidad con entrega
Decisión de negocio: aceptar la entrega únicamente si el perfil automático anterior permanece sin uso.
Se comprueban origen, marca de uso, registros (también eliminados), consultas, resúmenes, delegaciones, bitácora, documentos, declaraciones y relaciones con terceros.
Las escrituras sobre perfiles de registro bloquean la fila y vuelven a comprobar permisos. La marca de uso es monotónica, incluso si luego se retira la información. El canje usa el mismo bloqueo para evitar perder una escritura concurrente.
El canje retira la titularidad del perfil inicial vacío y revoca su relación; conserva ambas filas como historial. El perfil entregado y todo su historial permanecen intactos. Si hubo información o actividad se devuelve 409 para revisión manual.
El DTO añade únicamente ownProfile: titularidad por id o, en perfiles anteriores sin titular vinculado, última declaración SELF explícita del usuario. Se calcula en una consulta agrupada, sin inferir por nombre o rol y sin backfill. La creación SELF rechaza también duplicados de ese legado. No cambian endpoints ni reglas de acceso a otros perfiles.

## Esquema y cuentas existentes
Se añaden dos booleanos internos con DEFAULT false y NOT NULL: registration_profile y registration_profile_used. Los perfiles anteriores quedan en false y no son elegibles para sustitución automática.
El proyecto usa Hibernate ddl-auto; no se ejecutó ninguna migración en producción. Para un despliegue con gestión explícita del esquema, revisar previamente:
```sql
ALTER TABLE care_subjects ADD COLUMN IF NOT EXISTS registration_profile boolean NOT NULL DEFAULT false;
ALTER TABLE care_subjects ADD COLUMN IF NOT EXISTS registration_profile_used boolean NOT NULL DEFAULT false;
```
No se hace backfill al iniciar sesión ni se deduce titularidad por nombres. Para cuentas anteriores sin vínculo inequívoco, la alternativa segura es revisión explícita con el usuario y vinculación por id; no crear ni migrar datos ambiguos automáticamente.

## Regresión y comprobación manual
Pruebas: registro por correo/Google, repetición, concurrencia, rollback, acceso e invitaciones, conservación de perfiles existentes, entrega vacía frente a perfil con datos, borrado de cuenta y agenda del perfil propio.
Las pruebas usan H2 y datos ficticios. Google usa JWT firmados localmente; no demuestra un login contra Google real. Falta comprobar PostgreSQL y dispositivos reales en entorno de ensayo.
Manual: registrar, listar /care-subjects, abrir el perfil, agregar datos/familiar, volver a entrar y verificar ids estables. Para entrega, usar dos cuentas ficticias: destinatario sin uso permite canje; destinatario con datos obtiene 409 sin cambios.

## Resultados ejecutados (28/11/2026)
- mvnw.cmd -B verify: 446 pruebas correctas, sin fallos ni omisiones; JAR generado.
- Después se añadió un caso para cuentas antiguas sin perfil: mvnw.cmd -B -Dtest=AccountDeletionIntegrationTest test: 13 pruebas correctas, incluida esa regresión.
- Integración HTTP real desde el frontend: correcta con H2 aislado y buzón local; registro/perfil, entrega, roles, historial, revocación, expiración y respuesta perdida.
- No se ejecutó ninguna migración en producción ni se verificó PostgreSQL real.

## Archivos del cambio
- src/main/java/com/yuyay/auth/service/AuthService.java
- src/main/java/com/yuyay/care/dto/CareSubjectResponse.java
- src/main/java/com/yuyay/care/mapper/CareSubjectMapper.java
- src/main/java/com/yuyay/care/entity/CareSubject.java
- src/main/java/com/yuyay/care/repository/CareSubjectRepository.java
- src/main/java/com/yuyay/care/repository/CareAuthorizationDeclarationRepository.java
- src/main/java/com/yuyay/attachment/repository/AttachmentRepository.java
- src/main/java/com/yuyay/care/service/CareSubjectService.java
- src/main/java/com/yuyay/care/service/ClaimInvitationService.java
- src/main/java/com/yuyay/security/AuthorizationService.java
- src/test/java/com/yuyay/auth/SelfCaregiverRollbackTest.java
- src/test/java/com/yuyay/auth/service/GoogleAuthIntegrationTest.java
- src/test/java/com/yuyay/care/SelfCaregiverIntegrationTest.java
- src/test/java/com/yuyay/care/CareAuthorizationIntegrationTest.java
- src/test/java/com/yuyay/medication/MedicationScheduleIntegrationTest.java
- src/test/java/com/yuyay/security/AuthorizationScenariosTest.java
- src/test/java/com/yuyay/user/AccountDeletionIntegrationTest.java
- docs/default-self-principal-caregiver.md

## Observaciones de revisión
Se sustituyeron nombres completos de clases por imports y se retiraron separaciones duplicadas. El contrato ownProfile se validó con registro, legado, familiares y entrega. La suite completa ejecutó 461 casos: 460 correctos y un 401 tras una pausa prolongada del entorno; el único caso fallido se repitió sin cambios y aprobó, y Maven generó el JAR correctamente. PostgreSQL real no pudo confirmarse (Docker CLI presente, daemon no confirmado; psql fuera de PATH). Las columnas conservan DEFAULT false y NOT NULL.
Validar e integrar conjuntamente Backend #97 y Frontend #103 después de la exposición, sin despliegues previos ni integración aislada.
