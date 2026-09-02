# Troubleshooting — temperatura-converter-jee

> Guia para diagnosticar e corrigir falhas na stack `jee + otel-collector + prometheus + grafana + nginx` (HTTPS `*.lab.dev`). Foco em iniciantes: sintomas, causa, comando e fix.

---

## Índice

1. [Como coletar logs](#1-como-coletar-logs)
2. [Casos reais corrigidos nesta implantação](#2-casos-reais-corrigidos-nesta-implantação)
3. [Catálogo de falhas por serviço](#3-catálogo-de-falhas-por-serviço)
4. [Checklist rápido (5 comandos)](#4-checklist-rápido-5-comandos)
5. [Referência de arquivos e portas](#5-referência-de-arquivos-e-portas)

---

## 1. Como coletar logs

```bash
cd implantacao

# todos os serviços
docker compose logs --tail=100
docker compose logs --tail=100 otel-collector
docker compose logs --tail=100 grafana
docker compose logs --tail=100 nginx
docker compose logs --tail=100 prometheus
docker compose logs --tail=100 jee

# seguir ao vivo
docker compose logs -f jee

# filtrar erros
docker compose logs --tail=200 2>&1 | grep -i -E "error|failed|TLS handshake|has invalid keys"

# status e health
docker compose ps
curl -s http://localhost:13133   # otel health
curl -s http://localhost:8080/health | jq
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job, health:.health}'
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1
curl -k https://jee.lab.dev/nginx-health -H "Host: jee.lab.dev" --resolve jee.lab.dev:443:127.0.0.1
```

---

## 2. Casos reais corrigidos nesta implantação

> Novos ajustes de 02/09/2026 (Grafana vazio, WildFly stats, Nginx 404) em [`04-ajustes-set-2026.md`](./04-ajustes-set-2026.md) — casos 4,5,6.

### Caso 1 — OTel Collector em Restart loop

**Sintoma nos logs:**
```
otel-collector | Error: failed to get config: cannot unmarshal the configuration: decoding failed due to the following error(s):
otel-collector | '' has invalid keys: telemetry
otel-collector | collector server run finished with error
otel-collector | Restarting (1) 30 seconds ago
docker compose ps → otel-collector Restarting
curl http://localhost:13133 → Connection refused
```

**Causa:**
`implantacao/otel-collector/otel-collector-config.yaml:56-65` tinha bloco `telemetry:` no root com `metrics.readers.pull.exporter.prometheus` (host 0.0.0.0 port 8888). Na imagem `otel/opentelemetry-collector-contrib:0.128.0` esse schema é inválido — root não aceita `telemetry` e o reader pull não existe nessa versão. Parser rejeita e Collector não sobe.

**Diagnóstico:**
```bash
docker compose logs otel-collector --tail=20 | grep telemetry
cat implantacao/otel-collector/otel-collector-config.yaml | tail -15
docker run --rm -v $(pwd)/implantacao/otel-collector/otel-collector-config.yaml:/etc/otel-collector-config.yaml:ro otel/opentelemetry-collector-contrib:0.128.0 --config=/etc/otel-collector-config.yaml 2>&1 | head
```

**Correção aplicada:**
```yaml
# antes (quebrava)
telemetry:
  logs: {level: info}
  metrics:
    readers:
      - pull: {exporter: {prometheus: {host: 0.0.0.0, port: 8888}}}

# depois (em implantacao/otel-collector/otel-collector-config.yaml:55)
service:
  telemetry:   # dentro de service, não no root
    logs: {level: info}
  # ponytail: self-metrics pull removido — usa padrão do collector
```
E em `implantacao/prometheus/prometheus.yml:17-21` removido alvo `otel-collector:8888` (só `8889` do exporter `prometheus` permanece).

**Validação:**
```bash
docker compose up -d --force-recreate otel-collector; sleep 8; docker compose ps
# otel-collector Up, não Restarting
docker compose logs otel-collector --tail=10 | grep "Everything is ready"
curl -s http://localhost:13133 | jq  # {"status":"Server available"}
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="otel-collector") | .health' # "up"
```

**Prevenção:** validar config antes de subir:
```bash
sed 's/server jee:8080;/server 127.0.0.1:8080;/' implantacao/nginx/nginx.conf > /tmp/n.conf && docker run --rm -v /tmp/n.conf:/etc/nginx/nginx.conf:ro nginx:1.27-alpine nginx -t
# para otel, use --dry-run se disponível ou cheque com docker compose config
```

---

### Caso 2 — Grafana via Nginx retorna 400 + TLS handshake error

**Sintoma nos logs:**
```
grafana | t=... level=info msg="HTTP Server Listen" address=[::]:3000 protocol=https
grafana | t=... level=info msg="http: TLS handshake error from 172.19.0.6:43964: client sent an HTTP request to an HTTPS server"
nginx   | [error] 30#30: *36 readv() failed (104: Connection reset by peer) while reading upstream, client: 172.19.0.1, server: grafana.lab.dev, request: "GET / HTTP/1.1", upstream: "http://172.19.0.2:3000/"
nginx   | 172.19.0.1 - - [27/Aug/2026:12:28:46] "GET / HTTP/1.1" 400 0 "-" host=grafana.lab.dev
# curl -k https://grafana.lab.dev/ → 400
# curl http://localhost:3000/api/health → 200 (direto funciona)
```

**Causa:**
`implantacao/docker-compose.yml:66-68` tinha:
```yaml
GF_SERVER_DOMAIN: grafana.lab.dev
GF_SERVER_PROTOCOL: https
GF_SERVER_ROOT_URL: https://grafana.lab.dev/
```
`GF_SERVER_PROTOCOL=https` faz Grafana **escutar TLS** em :3000 (espera cert próprio). Mas `implantacao/nginx/nginx.conf:54` faz `proxy_pass http://grafana:3000` (HTTP plain). Grafana recebe HTTP em socket HTTPS → fecha conexão → Nginx loga `Connection reset by peer`.

**Diagnóstico:**
```bash
docker compose logs grafana --tail=20 | grep "HTTP Server Listen"
# protocol=https → errado, deveria ser http quando TLS termina no Nginx
curl -s http://localhost:3000/api/health | jq
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 -v 2>&1 | grep "400\|TLS"
```

**Correção aplicada:**
```yaml
# implantacao/docker-compose.yml:66
GF_SERVER_DOMAIN: grafana.lab.dev
GF_SERVER_ROOT_URL: https://grafana.lab.dev/
GF_SERVER_PROTOCOL: http   # ponytail: keep Grafana on http internally, TLS terminates at nginx
```

**Validação:**
```bash
docker compose up -d --force-recreate grafana; sleep 10; docker compose logs grafana --tail=5 | grep "HTTP Server Listen"
# agora protocol=http
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 | jq  # 200
curl -k -H "Host: grafana.lab.dev" https://127.0.0.1/api/health | jq  # 200
# sem novos "TLS handshake error" após 12:31
docker compose logs --tail=20 2>&1 | grep -c "TLS handshake error"  # deve parar de crescer
```

**Prevenção:** sempre que `nginx` termina TLS, backends ficam em `http`. Só use `GF_SERVER_PROTOCOL=https` se montar certs dentro do Grafana (`GF_SERVER_CERT_FILE`).

---

### Caso 3 — Prometheus com alvo down (efeito do Caso 1)

**Sintoma:**
```
curl http://localhost:9090/api/v1/targets → otel-collector:8888 health=down lastError="connection refused"
docker compose ps → prometheus Up mas 1 target down
```

**Causa:** `prometheus.yml:21` listava `otel-collector:8888` (self-telemetry) que foi removido no Caso 1.

**Correção:** `implantacao/prometheus/prometheus.yml:17` agora só `targets: ["otel-collector:8889"]`.

**Validação:** `curl -s http://localhost:9090/api/v1/targets | python3 -c "print(...)"` → 3 up, 0 down.

---

## 3. Catálogo de falhas por serviço

### JEE (temperatura-converter-jee :8080 WildFly)

| Sintoma | Comando | Causa comum | Fix |
|---------|---------|-------------|-----|
| `401 Unauthorized` em `/converter/**` | `curl -i http://localhost:8080/temperatura/converter/ctof/10` | Sem `Authorization: Basic` | `curl -u admin:admin123 ...` ou `APP_USERNAME/PASSWORD` |
| `curl /metrics → 401` | `curl -s http://localhost:8080/metrics \| head` | `BasicAuthFilter.java:21` sem liberar `metrics` | Manter `path.equals("metrics")` com `return` |
| `Port 8080 already in use` | `lsof -i :8080` | Outra app | `docker compose down` ou mudar `ports` |
| `java: release version 21 not supported` | `java -version` | JDK 17 ativo | `export JAVA_HOME=/usr/lib/jvm/temurin-21` |
| JEE Up mas `http_server_requests` não cresce | `curl http://localhost:8080/metrics \| grep http_server` | Sem tráfego | Gere: `for i in {1..20}; do curl -s -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/$i > /dev/null; done` |

### OTel Collector (:4317/:4318/:8889/:13133)

| Sintoma | Fix |
|---------|-----|
| `has invalid keys: telemetry` (restart loop) | Ver Caso 1 — remova ou corrija `service.telemetry` |
| `address already in use 0.0.0.0:4318` | Porta ocupada → `lsof -i :4318` |
| `health_check` `Connection refused` | Collector não subiu — `docker logs otel-collector` |

### Prometheus (:9090)

| Sintoma | Fix |
|---------|-----|
| `targets down` `jee:8080` | JEE não está na rede `monitoring` ou `/metrics` 401 |
| `targets down` `otel:8888` | Remover 8888 se telemetry removido |
| Config não recarrega | `docker compose restart prometheus` |

### Grafana (:3000 / https://grafana.lab.dev)

| Sintoma | Fix |
|---------|-----|
| `TLS handshake error` + 400 via Nginx | Ver Caso 2 — `GF_SERVER_PROTOCOL=http` |
| `400 Unknown host` via Nginx | `nginx.conf` sem `server_name grafana.lab.dev` ou `/etc/hosts` sem `grafana.lab.dev` |
| Datasource vermelho | `datasource.yml` deve ser `http://prometheus:9090` (interno, não `localhost`) |
| Dashboard `No data` | Sem tráfego ou `application` label errado → gere tráfego e cheque `application="temperatura-converter-jee"` |

### Nginx (:80/:443)

| Sintoma | Fix |
|---------|-----|
| `ERR_NAME_NOT_RESOLVED` para `*.lab.dev` | `cat /etc/hosts \| grep lab.dev` → rode `./scripts/add-hosts.sh` ou `echo "127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev" \| sudo tee -a /etc/hosts` |
| `ERR_CERT_AUTHORITY_INVALID` | Cert autoassinado — `curl -k` ou `curl --cacert nginx/certs/lab.dev.crt` ou importar cert |
| `502 Bad Gateway` | Backend down → `docker ps`, `docker logs <backend>` |
| `host not found in upstream "jee:8080"` no log | Nginx subiu antes do jee → `docker compose restart nginx` |
| `404 Unknown host` | Host header errado → use `-H "Host: grafana.lab.dev"` e `--resolve` |

---

## 4. Checklist rápido (5 comandos)

```bash
# 1. Tudo Up?
docker compose --project-directory implantacao ps

# 2. Algum restart loop?
docker compose --project-directory implantacao logs --tail=30 2>&1 | grep -E "Restarting|has invalid keys|TLS handshake error"

# 3. Health de cada serviço?
curl -s http://localhost:8080/health | jq .status
curl -s http://localhost:13133 | jq .status
curl -s http://localhost:9090/-/healthy
curl -s http://localhost:3000/api/health | jq .database
curl -k https://jee.lab.dev/health --resolve jee.lab.dev:443:127.0.0.1 | jq .status
curl -k https://jee.lab.dev/metrics --resolve jee.lab.dev:443:127.0.0.1 | head -5

# 4. Prometheus scrapando?
curl -s http://localhost:9090/api/v1/targets | python3 -c "import json,sys; d=json.load(sys.stdin); print('\n'.join(f\"{t['labels']['job']} {t['health']}\" for t in d['data']['activeTargets']))"

# 5. Grafana via Nginx?
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 | jq
curl -k https://prometheus.lab.dev/-/healthy --resolve prometheus.lab.dev:443:127.0.0.1
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1
```

Se todos retornam `UP`/`ok`/`200`, stack saudável. Se não, vá ao Caso correspondente na seção 2/3.

---

## 5. Referência de arquivos e portas

```
implantacao/
├── docker-compose.yml                      # 5 serviços, GF_SERVER_PROTOCOL=http
├── otel-collector/otel-collector-config.yaml  # sem pull reader (fix Caso 1)
├── prometheus/prometheus.yml               # só 8889 (fix Caso 3)
├── nginx/nginx.conf                        # 4 vhosts TLS, proxy_pass http://*
├── nginx/certs/lab.dev.crt/.key            # SAN: grafana, prometheus, jee, otel
├── scripts/add-hosts.sh                    # registra 127.0.0.1 *.lab.dev
├── troubleshoot/README.md                  # este arquivo
└── docs/{README,nginx-https,guia-desenvolvedor}.md

Portas:
  jee:        8080 → http://localhost:8080 / https://jee.lab.dev
  otel:       4317/4318 (OTLP), 8889 (metrics), 13133 (health)
  prometheus: 9090 → http://localhost:9090 / https://prometheus.lab.dev
  grafana:    3000 → http://localhost:3000 / https://grafana.lab.dev
  nginx:      80→301, 443 TLS
```

**Validar config sem subir:**
```bash
docker compose --project-directory implantacao config | grep -A2 nginx
# nginx syntax (troca upstreams por 127.0.0.1 para teste isolado)
sed 's/server jee:8080;/server 127.0.0.1:8080;/' implantacao/nginx/nginx.conf > /tmp/n.conf && docker run --rm -v /tmp/n.conf:/etc/nginx/nginx.conf:ro -v $(pwd)/implantacao/nginx/certs/lab.dev.crt:/etc/nginx/certs/lab.dev.crt:ro -v $(pwd)/implantacao/nginx/certs/lab.dev.key:/etc/nginx/certs/lab.dev.key:ro nginx:1.27-alpine nginx -t
openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -dates -ext subjectAltName
```

---

*Última atualização: 02/09/2026 — casos 4,5,6 (Grafana metrics, WildFly statistics-enabled, Nginx 9990) adicionados em [`04-ajustes-set-2026.md`](./04-ajustes-set-2026.md). Casos 01 (WildFly 31) e 02 (TLS unrecognized_name) em `docs/troubleshoot/` ([01](../docs/troubleshoot/01-wildfly-image-not-found.md), [02](../docs/troubleshoot/02-tls-unrecognized-name-https-jee.md)). Ao adicionar novo serviço, documente lá e linke aqui.*
