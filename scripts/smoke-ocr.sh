#!/usr/bin/env bash
# Prueba de humo E2E del OCR contra un despliegue REAL (EC2 + RDS + S3 + Textract), sin mocks.
# Uso:   ./scripts/smoke-ocr.sh http://34.193.16.240:8080 ruta/a/receta.jpg
# Requiere: curl y jq. Crea un usuario y una persona de prueba nuevos en cada corrida.
set -euo pipefail

BASE="${1:?Uso: $0 BASE_URL IMAGEN}"
IMG="${2:?Uso: $0 BASE_URL IMAGEN}"
API="$BASE/api/v1"

case "${IMG,,}" in
  *.jpg|*.jpeg) TYPE=image/jpeg ;;
  *.png)        TYPE=image/png ;;
  *.pdf)        TYPE=application/pdf ;;
  *) echo "Formato no soportado: usa JPG, PNG o PDF"; exit 1 ;;
esac

ok()   { echo "  [OK]   $*"; }
fail() { echo "  [FAIL] $*"; exit 1; }

echo "1. Health check"
[ "$(curl -s "$BASE/actuator/health" | jq -r .status)" = "UP" ] && ok "backend UP" || fail "backend no responde"

echo "2. Registro de cuidadora de prueba"
EMAIL="smoke.$(date +%s).$RANDOM@yuyay.app"
TOKEN=$(curl -s -X POST "$API/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Secreta123\",\"name\":\"Smoke OCR\"}" | jq -r .accessToken)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] && ok "token obtenido ($EMAIL)" || fail "registro fallido"
AUTH="Authorization: Bearer $TOKEN"

echo "3. Persona a cargo"
SID=$(curl -s -X POST "$API/care-subjects" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Paciente Smoke","birthDate":"1950-01-01"}' | jq -r .id)
[[ "$SID" =~ ^[0-9]+$ ]] && ok "careSubjectId=$SID" || fail "no se creo la persona"

echo "4. Subida del documento (S3)"
UP=$(curl -s -w '\n%{http_code}' -X POST "$API/care-subjects/$SID/attachments" -H "$AUTH" -F "file=@$IMG;type=$TYPE")
CODE=$(tail -n1 <<<"$UP"); BODY=$(sed '$d' <<<"$UP")
[ "$CODE" = "201" ] || fail "esperaba 201, llego $CODE: $BODY"
AID=$(jq -r .id <<<"$BODY")
[ "$(jq -r .ocrStatus <<<"$BODY")" = "PENDING" ] && ok "attachmentId=$AID, ocrStatus=PENDING" || fail "no quedo PENDING"

echo "5. OCR asincrono (Textract)"
for _ in $(seq 1 30); do
  R=$(curl -s "$API/care-subjects/$SID/attachments/$AID" -H "$AUTH")
  STATUS=$(jq -r .ocrStatus <<<"$R")
  [ "$STATUS" != "PENDING" ] && break
  sleep 1
done
[ "$STATUS" = "DONE" ] || fail "OCR termino en $STATUS: $(jq -r .ocrError <<<"$R")"
ok "ocrStatus=DONE, $(jq -r '.ocrText | split("\n") | length' <<<"$R") lineas detectadas"
jq -r '.ocrText' <<<"$R" | head -5 | sed 's/^/         | /'

echo "6. El OCR no creo registros por si solo"
[ "$(curl -s "$API/care-subjects/$SID/health-entries" -H "$AUTH" | jq length)" = "0" ] \
  && ok "0 registros de salud antes de confirmar" || fail "el OCR creo registros sin confirmacion"

echo "7. Confirmacion humana enlazada a la evidencia"
SRC=$(curl -s -X POST "$API/care-subjects/$SID/health-entries" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"categoryCode\":\"MEDICATION\",\"title\":\"Dato confirmado por la cuidadora\",\"dose\":\"segun receta\",\"confidenceLevel\":\"CONFIRMED\",\"sourceAttachmentId\":$AID}" \
  | jq -r .latestVersion.sourceAttachmentId)
[ "$SRC" = "$AID" ] && ok "latestVersion.sourceAttachmentId=$AID" || fail "no quedo enlazado (llego $SRC)"

echo
echo "SMOKE OCR: TODO OK contra $BASE"
