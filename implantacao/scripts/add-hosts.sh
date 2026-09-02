#!/bin/bash
# Adiciona domínios lab.dev ao /etc/hosts (requer sudo)
set -e
DOMAINS="jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev"
HOSTS_ENTRY="127.0.0.1 $DOMAINS"

if grep -q "grafana.lab.dev" /etc/hosts; then
  echo "✓ Domínios já existem em /etc/hosts:"
  grep lab.dev /etc/hosts
else
  echo "Adicionando domínios ao /etc/hosts (senha sudo será solicitada)..."
  echo "$HOSTS_ENTRY" | sudo tee -a /etc/hosts > /dev/null
  echo "✓ Adicionado:"
  grep lab.dev /etc/hosts
fi

echo ""
echo "Teste:"
echo "  ping -c1 grafana.lab.dev"
echo "  getent hosts grafana.lab.dev"
