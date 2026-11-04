# HTTPS del backend

Estado aplicado el 3 de noviembre de 2026, hora de Colombia (UTC-5).

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

La aplicación actual proviene del merge del PR #50, main 243602d. El run 41746235101 de Deploy
terminó correctamente y comprobó la salud por HTTP antes de esta migración.

El workflow preparado en fix/https-deployment debe integrarse antes del próximo despliegue.
El workflow que aún está en main comprueba HTTP 8080, que ya está cerrado públicamente.
La corrección de esta rama publica Docker en loopback y comprueba la salud por HTTPS.

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
