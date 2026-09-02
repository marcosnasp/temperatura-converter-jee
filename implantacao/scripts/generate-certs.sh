#!/bin/bash
# Gera certificado autoassinado para *.lab.dev (365 dias)
# Saída: implantacao/nginx/certs/lab.dev.crt + lab.dev.key
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="$SCRIPT_DIR/../nginx/certs"

mkdir -p "$CERT_DIR"

echo "Gerando certificado autoassinado para *.lab.dev..."

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout "$CERT_DIR/lab.dev.key" \
  -out "$CERT_DIR/lab.dev.crt" \
  -subj "/CN=*.lab.dev/O=Lab Dev/C=BR" \
  -addext "subjectAltName=DNS:grafana.lab.dev,DNS:prometheus.lab.dev,DNS:jee.lab.dev,DNS:otel.lab.dev,DNS:*.lab.dev,DNS:lab.dev,IP:127.0.0.1"

echo "✓ Certificado gerado:"
openssl x509 -in "$CERT_DIR/lab.dev.crt" -noout -subject -dates -ext subjectAltName
ls -lh "$CERT_DIR/"

echo ""
echo "Para confiar no navegador (opcional):"
echo "  # Firefox/Chrome: importar $CERT_DIR/lab.dev.crt em Autoridades"
echo "  # ou via linha de comando (Ubuntu):"
echo "  sudo cp $CERT_DIR/lab.dev.crt /usr/local/share/ca-certificates/lab.dev.crt && sudo update-ca-certificates"
