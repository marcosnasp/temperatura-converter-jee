# Nginx — Balanceador + TLS (HTTPS)

Este diretório contém o reverse-proxy que expõe todas as ferramentas via HTTPS com domínios `*.lab.dev`.

## Arquivos

```
nginx/
├── nginx.conf          # configuração principal (upstreams + 4 vhosts TLS + redirect 80→443)
├── certs/
│   ├── lab.dev.crt     # certificado autoassinado (365 dias, SAN para 4 domínios)
│   └── lab.dev.key     # chave privada
└── README.md           # este arquivo
```

## Domínios (JEE — WildFly 31)

| Domínio | Destino | Porta interna |
|---------|---------|---------------|
| https://jee.lab.dev | `jee:8080` | WildFly 31 — JAX-RS `/temperatura` (sem Spring Boot) |
| https://grafana.lab.dev | `grafana:3000` | Grafana |
| https://prometheus.lab.dev | `prometheus:9090` | Prometheus |
| https://otel.lab.dev | `otel-collector:13133` | OTel Collector health/zpages |

Todos resolvem para `127.0.0.1` via `/etc/hosts` (ver `../docs/nginx-https.md`). Cert `*.lab.dev` já cobre `jee.lab.dev`.

## Como funciona

```
Browser https://grafana.lab.dev:443
  → nginx:443 (TLS termination, cert *.lab.dev)
    → proxy_pass http://grafana:3000 (rede monitoring)
```

- `listen 80` → `301 https://$host$request_uri` (força HTTPS)
- `listen 443 ssl` com `ssl_certificate` e `ssl_protocols TLSv1.2 TLSv1.3`
- `proxy_set_header Host/X-Real-IP/X-Forwarded-*` preserva IP e protocolo original
- Upstreams permitem adicionar réplicas depois (balanceamento): `upstream jee_backend { server jee:8080; server jee2:8080; }`

## Gerar novo certificado

```bash
./scripts/generate-certs.sh
# ou manualmente:
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/certs/lab.dev.key \
  -out nginx/certs/lab.dev.crt \
  -subj "/CN=*.lab.dev/O=Lab Dev/C=BR" \
  -addext "subjectAltName=DNS:grafana.lab.dev,DNS:prometheus.lab.dev,DNS:jee.lab.dev,DNS:otel.lab.dev,DNS:*.lab.dev,IP:127.0.0.1"
```

## Testar config

```bash
docker compose config | grep nginx -A5
# teste de sintaxe (troca upstreams por 127.0.0.1 para validar fora do compose)
sed 's/server jee:8080;/server 127.0.0.1:8080;/' nginx/nginx.conf > /tmp/n.conf && \
docker run --rm -v /tmp/n.conf:/etc/nginx/nginx.conf:ro \
  -v $(pwd)/nginx/certs/lab.dev.crt:/etc/nginx/certs/lab.dev.crt:ro \
  -v $(pwd)/nginx/certs/lab.dev.key:/etc/nginx/certs/lab.dev.key:ro \
  nginx:1.27-alpine nginx -t
```

## Adicionar novo domínio

1. Adicione SAN ao gerar cert (inclua `DNS:novo.lab.dev`)
2. Adicione server block em `nginx.conf`:

```nginx
server {
    listen 443 ssl;
    server_name novo.lab.dev;
    ssl_certificate /etc/nginx/certs/lab.dev.crt;
    ssl_certificate_key /etc/nginx/certs/lab.dev.key;
    location / { proxy_pass http://novo_servico:porta; }
}
```

3. Adicione `127.0.0.1 novo.lab.dev` ao `/etc/hosts`
4. `docker compose up -d --force-recreate nginx`
