# HTTPS del backend

Registro de la migración aplicada el 3 de noviembre de 2026, hora de Colombia (UTC-5).
La imagen, el certificado, los respaldos y las comprobaciones descritos corresponden a esa
ejecución; no indican por sí solos la versión desplegada actualmente.

- [API](https://api.34-193-16-240.sslip.io)
- [Swagger](https://api.34-193-16-240.sslip.io/swagger-ui.html)
- [Salud](https://api.34-193-16-240.sslip.io/actuator/health)
- Imagen — ghcr.io/jocelynuceda/yuyay-backend:243602df05db361d0c9ac66bdbf5ec55f304456c
- La IP 34.193.16.240 tiene una Elastic IP asociada a esta instancia.

La instancia sirve la API. Su raíz devuelve 401 sin autenticación y la documentación está en
Swagger. La aplicación web funcional se despliega por separado. El puerto 80 redirige al dominio
HTTPS con 308, conservando la ruta, el método y el cuerpo de la petición.

## Red y proxy

El security group sg-0701f61fc04259918 permite TCP 22, 80 y 443. Se revocó únicamente la regla
pública TCP 8080 sgr-0414045eb6c3f7408. Docker publica la API en 127.0.0.1:8080:8080 y Caddy
conecta a ese loopback. Las reglas de salida se conservaron.

Caddy 2.11.8 está activo y habilitado como servicio systemd, instalado desde el repositorio
oficial estable. La configuración reproducible está en ops/Caddyfile y se instala en
/etc/caddy/Caddyfile con propietario root, grupo caddy y modo 640.

Los certificados y su renovación automática persisten en
/var/lib/caddy/.local/share/caddy. El certificado observado es de Let's Encrypt, válido para
api.34-193-16-240.sslip.io hasta el 2 de febrero de 2027. Preservar ese directorio al actualizar
el servicio. La instalación sigue la [documentación oficial de Caddy](https://caddyserver.com/docs/install).

Después de editar la configuración, validar antes de recargar.

    sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
    sudo systemctl reload caddy
    systemctl is-active caddy

## Bootstrap manual de una instancia nueva

**La instalación y configuración inicial de Caddy se hicieron manualmente.** El workflow
no instala el proxy ni copia `ops/Caddyfile`, no prepara una EC2 nueva y no recrea la
infraestructura. Antes de ejecutar Deploy sobre una instancia vacía, operaciones debe:

1. Preparar Ubuntu y Docker, asociar la Elastic IP/dominio previsto y configurar las reglas
   de red para 80/443 y la administración necesaria. Mantener 8080 sin acceso público.
2. Instalar Caddy y su unidad systemd siguiendo la documentación oficial enlazada arriba.
   Conservar la configuración del sitio y adaptar el dominio/IP si cambió la asignación.
3. Desde un checkout del repositorio en esa instancia, instalar y validar la configuración:

       sudo install -o root -g caddy -m 640 ops/Caddyfile /etc/caddy/Caddyfile
       sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
       sudo systemctl enable --now caddy
       sudo systemctl reload caddy

4. Restaurar de forma privada `/home/ubuntu/.env` con modo 600 y las credenciales/configuración
   de la API; preparar también acceso a RDS/S3/Textract y los secretos de SSH de GitHub.
   Ajustar `APP_BASE_URL`, `CORS_ALLOWED_ORIGINS` y `FRONTEND_URL` al entorno. El workflow
   solo comprueba que el archivo exista y lo entrega a Docker; no lo crea ni lo actualiza.
5. Conservar o restaurar el almacenamiento de Caddy en `/var/lib/caddy/.local/share/caddy`
   cuando se reemplace la instancia, con permisos para su servicio. Si no se restaura,
   Caddy tendrá que emitir certificados nuevos y el dominio/80/443 deben permitirlo.
6. Ejecutar Deploy y después comprobar salud HTTPS, redirección HTTP y cierre público de
   8080 según esta guía. Antes de arrancar la API, el proxy puede responder 502 porque
   todavía no hay backend en loopback.

Guardar `ops/Caddyfile` en Git hace reproducible la configuración del sitio; aplicarla,
instalar/actualizar Caddy y recuperar el estado de certificados siguen siendo tareas
operativas manuales. La renovación de certificados la realiza Caddy mientras el servicio
y su almacenamiento estén disponibles.

## Configuración de la API

El contenedor usa /home/ubuntu/.env, modo 600, mediante Docker --env-file.
También existe /home/ubuntu/yuyay.env, pero el workflow utiliza el primero.
Se actualizaron exclusivamente APP_BASE_URL y CORS_ALLOWED_ORIGINS.

    APP_BASE_URL=https://api.34-193-16-240.sslip.io

La lista CORS conserva sus orígenes previos y añade http://localhost:8081 y
http://localhost:5173. Se verificaron Authorization, Content-Type, POST, PATCH y DELETE.
Swagger anuncia el mismo servidor HTTPS para evitar peticiones de contenido mixto.
FRONTEND_URL requiere la dirección de la futura web funcional cuando esté disponible.

El archivo ignorado mobile/.env del repositorio frontend tiene EXPO_PUBLIC_API_URL apuntando
a esta API HTTPS. Reiniciar Metro permite que Expo relea esa variable.
Los clientes con la dirección antigua deben migrar al dominio nuevo.
Las ramas móviles previas al PR #1 también requieren integrar la compatibilidad con Page.

## Automatización

Como antecedente de esta migración, la imagen de main 243602d (PR #50) pasó el run
41746235101 de Deploy, que entonces comprobaba HTTP 8080. La corrección HTTPS ya está
integrada en `.github/workflows/deploy.yml` de main.

El workflow actual verifica la URL de salud HTTPS, ejecuta `mvnw -B verify` con Java 26,
construye y publica en GHCR las etiquetas `latest` y el SHA del commit. Cuando está
configurado `EC2_HOST`, entra por SSH, descarga la imagen del SHA y recrea el contenedor
`yuyay` con `--env-file ~/.env` y `-p 127.0.0.1:8080:8080`; después comprueba salud por
HTTPS. Sin ese secreto ejecuta las pruebas y construye/publica la imagen, pero omite SSH y salud remota.
No instala Docker/Caddy, no copia el Caddyfile, no configura red/DNS/Elastic IP ni
genera o restaura archivos de entorno. Depende del bootstrap manual anterior.

Observación del 4 de noviembre de 2026: la salud pública responde UP y Caddy sigue
activo, pero el runtime observado todavía utiliza la imagen 243602d. Después del merge
del PR #51 (main aba0620), CI 41923769429 terminó correctamente y Deploy 41923769384
pasó resolución HTTPS, pruebas y publicación de imagen; falló en `Desplegar en EC2 por
SSH` y omitió la comprobación de salud posterior. La causa del fallo SSH sigue sin
confirmarse. Una imagen publicada o un merge no demuestran que ese SHA esté ejecutándose;
comprobar el contenedor antes de declarar desplegada una funcionalidad. No se reintentó
ni modificó AWS como parte de esta aclaración documental.

Si API_HEALTH_URL existe como variable de GitHub, debe contener una URL HTTPS sin credenciales.
Si está vacía y EC2_HOST es una IPv4, se deriva
https://api.<IP-con-guiones>.sslip.io/actuator/health. Un host diferente requiere un override
HTTPS explícito. La validación ocurre antes de construir y desplegar.

La Elastic IP confirmada mantiene el dominio asociado mientras se conserve esa asignación.
Si se cambia su asociación o dirección, actualizar Caddyfile, APP_BASE_URL y los clientes.

## Validación realizada

Se verificaron desde la PC el certificado público sin omitir TLS, salud 200 UP, Swagger 200,
OpenAPI con servidor HTTPS y CORS 200 para localhost:8081 y localhost:5173. Un origen ajeno devuelve 403.
El redirect desde la IP devuelve 308 y conserva la ruta.

Con una cuenta sintética identificada como Deployment check 243602d se probaron registro 201,
login 200, contraseña incorrecta 401, usuario autenticado 200, avisos paginados 200, acceso ajeno 403,
refresh 200, reutilización 401 y logout 204. Las credenciales permanecieron en memoria.
Tras el cambio a loopback se repitieron salud, CORS y los rechazos de autenticación.

Comprobaciones públicas que no requieren credenciales.

    curl -fsS https://api.34-193-16-240.sslip.io/actuator/health
    curl -IL https://api.34-193-16-240.sslip.io/swagger-ui.html
    curl -I http://34.193.16.240/actuator/health

La conexión desde la PC a 34.193.16.240:8080 debe fallar. En EC2, el mismo endpoint por
http://127.0.0.1:8080/actuator/health debe devolver UP.

## Rollback

El respaldo privado está en
/home/ubuntu/deployment-backups/https-243602d-20261104, modo 700. Contiene production.env
con modo 600, metadatos del contenedor y reglas originales, y copias del Caddyfile.
Estos archivos contienen configuración privada y se conservan fuera de Git.

Se retuvieron dos contenedores detenidos.

- yuyay-rollback-243602d-20261104, anterior al cambio de APP_BASE_URL/CORS.
- yuyay-rollback-before-loopback-20261104, anterior al binding loopback, con CORS/HTTPS actualizados.

Para recuperar disponibilidad desde el último contenedor, comprobar sus nombres e imagen
antes de ejecutar en EC2.

    docker stop yuyay
    docker rename yuyay yuyay-failed-after-https
    docker rename yuyay-rollback-before-loopback-20261104 yuyay
    docker start yuyay
    curl -fsS http://127.0.0.1:8080/actuator/health

Ese rollback mantiene la regla pública 8080 cerrada y Caddy continúa accediendo al backend.
El contenedor retenido usa binding 0.0.0.0, por lo que después de recuperar disponibilidad
corresponde volver a crear la instancia sana con binding loopback.
Reabrir HTTP público exige una decisión explícita de emergencia y restaurar una regla
TCP 8080 en el security group. El rollback automático de esta migración no la restablece.

Quedan pendientes los datos demo y la revisión de latencia/N+1 del plan de entrega.
