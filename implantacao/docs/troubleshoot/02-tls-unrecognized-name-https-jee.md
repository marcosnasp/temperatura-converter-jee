# 02 — `curl: (35) tlsv1 unrecognized name` em `https://jee.lab.dev`

> `curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100` → `OpenSSL/3.0.13: error:0A000458:SSL routines::tlsv1 unrecognized name`

---

## Sintoma

```bash
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100

* Host jee.lab.dev:443 was resolved.
* IPv4: 52.223.13.41        # <-- IP externo, não 127.0.0.1
* Connected to jee.lab.dev (52.223.13.41) port 443
* TLSv1.3 (OUT), TLS handshake, Client hello
* TLSv1.3 (IN), TLS alert, unrecognized name (624):
curl: (35) OpenSSL/3.0.13: error:0A000458:SSL routines::tlsv1 unrecognized name
```

Variante com `--resolve` (quando stack está parada):

```bash
curl -vk --resolve jee.lab.dev:443:127.0.0.1 https://jee.lab.dev/temperatura/converter/ctof/100

* Added jee.lab.dev:443:127.0.0.1 to DNS cache
* Trying 127.0.0.1:443...
* connect to 127.0.0.1 port 443 failed: Conexão recusada
curl: (7) Failed to connect to jee.lab.dev port 443
```

Dois bugs independentes se manifestam com a mesma requisição.

---

## Causa raiz — 2 falhas

### A. `/etc/hosts` sem `jee.lab.dev`

```bash
cat /etc/hosts
# 127.0.0.1 app.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
#            ^^^^^^^^^^ antigo, falta jee.lab.dev

getent hosts jee.lab.dev       # 52.223.13.41 (DNS público)
getent hosts grafana.lab.dev   # 127.0.0.1 (hosts local)
```

`implantacao/scripts/add-hosts.sh` espera:

```
127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
```

Sem a entrada, `jee.lab.dev` resolve via DNS para IP externo (`52.223.13.41:443`). Esse servidor não conhece SNI `jee.lab.dev` → alerta TLS `unrecognized_name` (112).

`implantacao/nginx/nginx.conf:53` (`server_name jee.lab.dev`) nunca é alcançado — requisição nem chega ao Nginx local.

### B. Stack parada (`nginx-lb` não escuta 443)

```bash
docker ps -a --format "table {{.Names}}\t{{.Status}}"
# nginx-lb   Exited (137) 5 days ago
# jee        Exited (143) 5 days ago
ss -tlnp | grep 443   # vazio
```

Mesmo corrigindo hosts, `curl --resolve jee.lab.dev:443:127.0.0.1` dá `Conexão recusada` porque `nginx:443` não está Up.

Motivo comum: após falha de build (Caso 01) ou `docker compose down`, stack não foi recriada.

---

## Diagnóstico

```bash
# 1. hosts
cat /etc/hosts | grep lab.dev
getent hosts jee.lab.dev
getent hosts grafana.lab.dev
# jee deve ser 127.0.0.1; se for 52.x.x.x → bug A

# 2. resolução real usada pelo curl
curl -vk https://jee.lab.dev/temperatura/converter/ctof/100 2>&1 | grep -E "Host.*resolved|IPv4|Connected"
# IPv4: 127.0.0.1 → ok (hosts); 52.x.x.x → bug A

# 3. stack
docker compose -f implantacao/docker-compose.yml ps
docker ps -a | grep nginx-lb
ss -tlnp | grep -E "80|443"
docker compose -f implantacao/docker-compose.yml logs nginx --tail=30

# 4. teste bypass (isola hosts vs nginx)
curl -k --resolve jee.lab.dev:443:127.0.0.1 -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100 -v
# Conexão recusada → bug B; unrecognized_name → ainda bug A; 200 → ok

# 5. direto sem nginx (isola WildFly)
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # deve dar 212.0 se jee Up
```

---

## Correção

### Fix A — `/etc/hosts`

```bash
# opção 1: script oficial (pede sudo)
bash implantacao/scripts/add-hosts.sh

# opção 2: manual (troca app.lab.dev → jee.lab.dev)
sudo sed -i 's/127.0.0.1 app.lab.dev/127.0.0.1 jee.lab.dev/' /etc/hosts

# opção 3: recria linha completa (idempotente)
grep -q "jee.lab.dev" /etc/hosts || echo "127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev" | sudo tee -a /etc/hosts

# valida
grep lab.dev /etc/hosts
# esperado: 127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
getent hosts jee.lab.dev  # 127.0.0.1
```

Cert `implantacao/nginx/certs/lab.dev.crt:11` já tem SAN `jee.lab.dev`, `*.lab.dev`, `IP:127.0.0.1` — não precisa regenerar.

### Fix B — subir stack

```bash
# rebuild já com fix 32.0.1 (Caso 01)
docker compose -f implantacao/docker-compose.yml up -d --build
docker compose -f implantacao/docker-compose.yml ps
# esperado: nginx-lb Up (0.0.0.0:443->443), jee Up (healthy), grafana/prometheus/otel Up

# se apenas nginx caiu
docker compose -f implantacao/docker-compose.yml up -d nginx
```

---

## Validação

```bash
# 1. hosts ok
getent hosts jee.lab.dev  # 127.0.0.1

# 2. nginx ouvindo
ss -tlnp | grep 443
curl -k https://jee.lab.dev/nginx-health --resolve jee.lab.dev:443:127.0.0.1
# ok jee

# 3. via nginx + TLS (com hosts correto, sem --resolve)
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
# 212.0

# 4. via nginx explícito
curl -k --resolve jee.lab.dev:443:127.0.0.1 -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
# 212.0

# 5. healthchecks
curl -k https://jee.lab.dev/health --resolve jee.lab.dev:443:127.0.0.1 | jq .status  # UP
curl http://localhost:8080/health | jq .status                                      # UP
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 | jq
docker compose -f implantacao/docker-compose.yml logs --tail=20 2>&1 | grep -i "unrecognized name"  # 0
```

Sem `jee.lab.dev` em hosts, use sempre `--resolve` ou `-H "Host: jee.lab.dev" https://127.0.0.1/...` como workaround.

---

## Prevenção

- Sempre rode `implantacao/scripts/add-hosts.sh` após clonar ou se `getent hosts jee.lab.dev` não for `127.0.0.1`.
- Não use `app.lab.dev` (legado) — padrão é `jee.lab.dev` (`nginx/nginx.conf:53`, `docker-compose.yml:107`).
- `docker compose ps` antes de testar HTTPS — se `nginx-lb` não estiver Up, teste direto `http://localhost:8080`.
- Para CI/ambientes efêmeros sem `/etc/hosts`, prefira `curl --resolve jee.lab.dev:443:127.0.0.1` ou `http://localhost:8080`.
- Valide cert: `openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -ext subjectAltName | grep jee.lab.dev`.

---

*Arquivos: `implantacao/nginx/nginx.conf:51-85`, `implantacao/nginx/certs/lab.dev.crt`, `implantacao/scripts/add-hosts.sh`, `implantacao/docker-compose.yml:107` | Data: 2026-09-02 | Relacionado: `01-wildfly-image-not-found.md`*
