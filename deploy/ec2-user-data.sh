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

# ── 2. AS4 gateway (phase4-peppol) ──────────────────────────────────────────
run_gateway() {   # $1 = image
  docker stop phase4-peppol 2>/dev/null || true
  docker rm phase4-peppol 2>/dev/null || true
  docker run -d --name phase4-peppol --restart unless-stopped \
    --log-opt max-size=50m --log-opt max-file=5 \
    -p 8080:8080 \
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
(cd /home/ubuntu/phoss-smp && docker compose up -d) || echo "$(date) - ERROR: SMP start failed"

echo "$(date) - User Data script complete!"
