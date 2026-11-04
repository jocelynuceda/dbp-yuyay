# Diagnóstico de autenticación

Con cuentas sintéticas, el recorrido directo y por Metro dio registro 201, login 200,
password diferente 401, email duplicado 409, refresh 200, token usado/revocado 401,
logout 204 y nuevo login 200. Se preservaron Unicode y espacios de la contraseña.
La misma cuenta y refresh persistieron al reiniciar API y PostgreSQL conservando el volumen.
Estas pruebas no establecen la causa de la cuenta histórica reportada.

Se corrigieron dos defectos reproducidos antes del cambio.

- Una clave de 38 caracteres y 74 bytes UTF-8 pasaba @Size(72) y BCrypt producía 500.
  Ahora el registro devuelve 400 con fieldErrors.password al superar 72 bytes.
  Login devuelve 401 para claves que BCrypt no puede verificar. Se prueban límites ASCII,
  Unicode y contraseñas con espacios.
- Varias renovaciones concurrentes podían canjear el mismo refresh. Un bloqueo de escritura
  dentro de la transacción asegura un solo canje. En PostgreSQL, seis peticiones dieron
  un 200 y cinco 401 después del cambio. Las dos regresiones fallaron antes y pasan después.

El correo simulado deja de imprimir HTML, destinatarios y enlaces con tokens. Las nuevas
pruebas fijan MockMvcPrint.NONE y comparan estados sin imprimir cuerpos.

## Arranque y pruebas

Desde este repositorio, con JAVA_HOME apuntando a un JDK 26 instalado y su bin en Path.
El perfil dev usa PostgreSQL. Conservar el volumen mantiene las cuentas entre reinicios.

    docker compose up -d db
    .\mvnw.cmd spring-boot:run
    .\mvnw.cmd -B verify
    node scripts/auth-smoke.mjs http://localhost:8080
    node scripts/auth-smoke.mjs http://localhost:8081

El último comando requiere Metro activo con su proxy. El script acepta hosts locales,
crea una cuenta sintética única y muestra sólo estados HTTP. La suite activa test con H2
en memoria y mocks de adjuntos. No se añadió un entorno de desarrollo H2.

Para comparar un caso real, registrar endpoint, estado HTTP y entorno. Registro 409 confirma
que el email existe en esa instancia. Login 401 indica que la combinación presentada no
verificó. Evitar compartir contraseñas, JWT, refresh, hashes o archivos de entorno.

## Tokens

BCrypt usa coste 12. El JWT se firma con HMAC y JJWT selecciona HS256/384/512 según longitud
de clave. Se exige al menos 32 bytes, cuya longitud no demuestra entropía. La clave debe ser
aleatoria. El servidor valida firma y expiración. El payload legible incluye email/rol o
nombre del delegado, y excluye contraseñas y claves de firma.

El refresh usa 32 bytes de SecureRandom y la base almacena SHA-256 con expiración/revocación.
HTTP local se usó para desarrollo y pruebas. El transporte público requiere HTTPS.
El CI de este repositorio ejecuta Maven. Las regresiones móviles pertenecen al CI frontend.

La verificación final con Java 26 pasó 91 tests, sin fallos, errores ni tests omitidos.
