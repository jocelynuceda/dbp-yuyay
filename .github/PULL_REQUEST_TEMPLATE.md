## Qué hace este PR

## Checklist
- [ ] Controller → Service → Repository; ningún controller importa `entity`
- [ ] Request y Response con DTOs; mapper MapStruct
- [ ] Validaciones `@Valid` en el DTO de entrada
- [ ] `AuthorizationService.requireAccess/requireRole` en cada método que toca un CareSubject
- [ ] Excepciones personalizadas (nada de `RuntimeException` genérica)
- [ ] Test de éxito + test de 403 (si aplica)
- [ ] Sin secretos ni `.env` en el commit
- [ ] `./mvnw verify` verde en local
