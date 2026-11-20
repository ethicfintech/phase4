#!/bin/bash
# Server startup script (launch template ethicfin-lt). Runs once, as root, on first boot.
# A failure in one section must not stop the following sections.
exec > /var/log/user-data.log 2>&1
echo "$(date) - User Data script starting..."

REGION=ap-south-1
ECR=843780049323.dkr.ecr.ap-south-1.amazonaws.com
GATEWAY_IMAGE=$ECR/ethicfin/phase4-peppol:latest
# The gateway image baked into the server image; used only if the new image cannot start
GATEWAY_FALLBACK_IMAGE=phase4-peppol:latest
# Gateway settings that differ from the defaults inside the image (Java system properties).
# - phase4.webhook.url: inbound invoices go to the app on this same server (nginx, port 8888)
# - peppol.ae.tdd.receiver: the tax authority participant that receives the Tax Data Document
#   (Peppol testbed value; replace with the FTA's production ID when MOF provides it)
GATEWAY_OPTS="-Dphase4.webhook.url=http://172.17.0.1:8888/peppol/webhook -Dpeppol.ae.tdd.receiver=0242:000006-MLS.3001"

# Traffic from the load balancer to this server is HTTPS, on three ports served by nginx:
#   8442 = portal and API, 8443 = AS4 gateway, 8444 = SMP
# PLAIN_HTTP_PORTS controls the old unencrypted ports (80, 8080, 8090):
#   keep  = they stay open next to the HTTPS ports (only while the load balancer is being switched over)
#   close = nginx stops serving 80/443, and the gateway and SMP containers listen on 127.0.0.1 only
PLAIN_HTTP_PORTS=close

# ── 1. PHP application ──────────────────────────────────────────────────────
(
  set -e
  cd /var/www/einvoicing
  sudo -u ubuntu git fetch origin master
  sudo -u ubuntu git reset --hard origin/master
  composer install --no-dev --optimize-autoloader --no-interaction 2>/dev/null || true
  chown -R www-data:www-data writable/
  chmod -R 775 writable/
  sudo -u www-data php8.2 spark migrate --all 2>/dev/null || true
  systemctl reload php8.2-fpm
) && echo "$(date) - app updated" || echo "$(date) - ERROR: app update failed"

# ── 1b. Invoice job queue worker - every minute as www-data ─────────────────
cat > /etc/cron.d/einvoicing-queue <<'CRON'
# Invoice job queue worker (async Peppol submission) - runs every minute as www-data
* * * * * www-data cd /var/www/einvoicing && /usr/bin/php8.2 spark queue:process --timeout=25 >> /var/www/einvoicing/writable/logs/queue.log 2>&1
CRON
chown root:root /etc/cron.d/einvoicing-queue
chmod 644 /etc/cron.d/einvoicing-queue

# ── 1c. HTTPS between the load balancer and this server ─────────────────────
# A new private key and certificate are created on every new server; nothing is shared between
# servers or stored in the server image. The load balancer encrypts to the server with it. It does
# not check who issued it, so a self-signed certificate is the normal choice here; the firewall
# allows only the load balancer to reach these ports.
TLS_DIR=/etc/nginx/internal-tls
TLS_SITE=/etc/nginx/sites-available/internal-tls
LEGACY_SITES_DIR=/etc/nginx/legacy-sites
(
  set -e
  mkdir -p $TLS_DIR
  chmod 700 $TLS_DIR
  openssl req -x509 -newkey rsa:2048 -nodes -days 397 \
    -keyout $TLS_DIR/server.key -out $TLS_DIR/server.crt \
    -subj "/O=Ethicfin/CN=$(hostname)" \
    -addext "subjectAltName=DNS:einvoicing.ethicfin.com,DNS:as4.ethicfin.com,DNS:smp.ethicfin.com"
  chmod 600 $TLS_DIR/server.key

  cat > $TLS_SITE <<'NGINX'
# Written by the server startup script (deploy/ec2-user-data.sh in the phase4 repository).
# HTTPS entry points for the load balancer. Do not edit on the server; change the script.

# Portal and API
server {
    listen 8442 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/internal-tls/server.crt;
    ssl_certificate_key /etc/nginx/internal-tls/server.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:internal_tls:10m;

    root /var/www/einvoicing;
    index index.php index.html;

    location ~* ^/(app|system|tests|vendor|writable)/ {
        deny all;
    }

    location ~* ^/(composer\.(json|lock)|spark|preload\.php|.*\.(md|yaml))$ {
        deny all;
    }

    location ~ /\. {
        deny all;
    }

    location / {
        try_files $uri $uri/ /index.php?$uri&$args;
    }

    location ~ \.php$ {
        include snippets/fastcgi-php.conf;
        fastcgi_pass unix:/var/run/php/php8.2-fpm.sock;
        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
        include fastcgi_params;
        fastcgi_param HTTP_X_FORWARDED_PROTO $http_x_forwarded_proto;
    }
}

# AS4 gateway. Request headers from the load balancer (Host, X-Forwarded-*) are passed on unchanged,
# and there is no size limit and no buffering of the request, as when the load balancer reached the
# gateway directly.
server {
    listen 8443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/internal-tls/server.crt;
    ssl_certificate_key /etc/nginx/internal-tls/server.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:internal_tls:10m;

    client_max_body_size 0;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $http_host;
        proxy_request_buffering off;
        proxy_connect_timeout 10s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }
}

# SMP
server {
    listen 8444 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/internal-tls/server.crt;
    ssl_certificate_key /etc/nginx/internal-tls/server.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:internal_tls:10m;

    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $http_host;
        proxy_connect_timeout 10s;
        proxy_read_timeout 60s;
    }
}
NGINX
  ln -sf $TLS_SITE /etc/nginx/sites-enabled/internal-tls

  # The webhook listener (8888) is for the gateway container on this server only
  WEBHOOK_SITE=/etc/nginx/sites-enabled/webhook-internal
  if [ -f $WEBHOOK_SITE ] && ! grep -q 'deny all' $WEBHOOK_SITE; then
    sed -i 's|^\(\s*\)server_name _;|&\n\1allow 127.0.0.1;\n\1allow 172.17.0.0/16;\n\1deny all;|' $WEBHOOK_SITE
  fi

  # Old entry points on 80 and 443 (certificates from before the load balancer existed)
  if [ "$PLAIN_HTTP_PORTS" = "close" ]; then
    mkdir -p $LEGACY_SITES_DIR
    for site in as4 smp.ethicfin.com einvoicing.ethicfin.com; do
      [ -e /etc/nginx/sites-enabled/$site ] && mv /etc/nginx/sites-enabled/$site $LEGACY_SITES_DIR/
    done
  fi

  if nginx -t; then
    systemctl reload nginx
  else
    # Never leave nginx with a configuration it cannot load: put the old entry points back
    for site in as4 smp.ethicfin.com einvoicing.ethicfin.com; do
      [ -e $LEGACY_SITES_DIR/$site ] && mv $LEGACY_SITES_DIR/$site /etc/nginx/sites-enabled/
    done
    rm -f /etc/nginx/sites-enabled/internal-tls
    nginx -t && systemctl reload nginx
    exit 1
  fi
) && echo "$(date) - HTTPS entry points ready (8442 portal, 8443 AS4, 8444 SMP); plain HTTP ports: $PLAIN_HTTP_PORTS" \
  || echo "$(date) - ERROR: HTTPS entry points could not be set up"

# With PLAIN_HTTP_PORTS=close the containers are reachable only through nginx on this server
if [ "$PLAIN_HTTP_PORTS" = "close" ]; then CONTAINER_BIND="127.0.0.1:"; else CONTAINER_BIND=""; fi

# ── 2. AS4 gateway (phase4-peppol) ──────────────────────────────────────────
run_gateway() {   # $1 = image
  docker stop phase4-peppol 2>/dev/null || true
  docker rm phase4-peppol 2>/dev/null || true
  docker run -d --name phase4-peppol --restart unless-stopped \
    --log-opt max-size=50m --log-opt max-file=5 \
    -p ${CONTAINER_BIND}8080:8080 \
    -v /home/ubuntu/data:/data/as4 \
    -v /home/ubuntu/certs:/certs:ro \
    -e CATALINA_OPTS="$GATEWAY_OPTS" \
    "$1"
}
gateway_healthy() {   # waits up to 4 minutes for HTTP 200 on the load balancer's health check path
  for i in $(seq 1 48); do
    [ "$(curl -s -o /dev/null -m 4 -w '%{http_code}' http://localhost:8080/)" = "200" ] && return 0
    sleep 5
  done
  return 1
}

# The AWS CLI is needed to log in to the image registry
if ! command -v aws >/dev/null 2>&1; then
  echo "$(date) - installing AWS CLI"
  snap install aws-cli --classic || echo "$(date) - ERROR: AWS CLI install failed"
  export PATH=$PATH:/snap/bin
fi

if aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR \
   && docker pull $GATEWAY_IMAGE; then
  echo "$(date) - starting gateway image $(docker image inspect $GATEWAY_IMAGE --format '{{.Id}} built {{.Created}}')"
  run_gateway $GATEWAY_IMAGE
  if gateway_healthy; then
    echo "$(date) - gateway is healthy on the new image"
  else
    echo "$(date) - ERROR: new gateway image did not become healthy; last log lines:"
    docker logs --tail 40 phase4-peppol 2>&1
    echo "$(date) - falling back to $GATEWAY_FALLBACK_IMAGE"
    run_gateway $GATEWAY_FALLBACK_IMAGE
  fi
else
  echo "$(date) - ERROR: could not pull $GATEWAY_IMAGE; the gateway keeps running the image already on this server"
  docker start phase4-peppol 2>/dev/null || true
fi

# ── 3. SMP (phoss-smp) - image and data are on the server; just make sure it runs ──
if [ "$PLAIN_HTTP_PORTS" = "close" ]; then
  sed -i 's|- "8090:8080"|- "127.0.0.1:8090:8080"|' /home/ubuntu/phoss-smp/docker-compose.yml
fi
(cd /home/ubuntu/phoss-smp && docker compose up -d) || echo "$(date) - ERROR: SMP start failed"

echo "$(date) - User Data script complete!"
