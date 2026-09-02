# 03 — `404 Not Found` em `https://jee.lab.dev/temperatura/converter/...`

> `curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100` → `404` com `Content-Length: 0` (via Nginx e direto em `http://localhost:8080`)

---

## Sintoma

```bash
curl -k -i -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1
HTTP/1.1 404 Not Found
Server: nginx/1.27.5
Content-Length: 0

curl -s -i -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
HTTP/1.1 404 Not Found

# mesmo via Nginx log:
# nginx-lb | 172.19.0.1 - admin [02/Sep/2026:11:44:50] "GET /temperatura/converter/ctof/100 HTTP/1.1" 404 0 host=jee.lab.dev upstream=172.19.0.3:8080

# stack está Up (não é rede):
docker compose -f implantacao/docker-compose.yml ps
# jee Up (healthy), nginx-lb Up (healthy)
```

Sem body, sem erro TLS — requisição chega ao WildFly, mas WildFly responde 404.

---

## Causa

`Dockerfile:14` deploya como `ROOT.war`:

```dockerfile
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/ROOT.war
```

`ROOT.war` em WildFly **sempre** mapeia para contexto `/`, ignorando `src/main/webapp/WEB-INF/jboss-web.xml:3`:

```xml
<jboss-web><context-root>/temperatura</context-root></jboss-web>
```

Log confirma:

```
WFLYUT0021: Registered web context: '/' for server 'default-server'
Deployed "ROOT.war" (runtime-name : "ROOT.war")
```

Portanto endpoints reais são **sem** prefixo `/temperatura`:

| URL documentada (errada) | URL real (ROOT.war) | Status |
|--------------------------|---------------------|--------|
| `/temperatura/converter/ctof/100` | `/converter/ctof/100` | 200 vs 404 |
| `/temperatura/health` | `/health` | 200 vs 404 |
| `/temperatura/metrics` | `9990/metrics` (management) | 404 vs 200 |

`@ApplicationPath("/")` + `@Path("/converter")` → `http://jee:8080/converter/...` (não `/temperatura/converter`).

`BasicAuthFilter.java:13` libera `path.equals("health")` — com `/temperatura/health` o `path` é `temperatura/health` e não bate no `equals`, mas ainda passa por `!path.startsWith("converter")` → sem auth, porém JAX-RS não encontra recurso → 404.

---

## Diagnóstico

```bash
# 1. contexto registrado
docker compose -f implantacao/docker-compose.yml logs jee | grep "Registered web context"
# WFLYUT0021: Registered web context: '/' → confirma ROOT

# 2. teste ambos os prefixos
for p in "/converter/ctof/100" "/temperatura/converter/ctof/100" "/health" "/temperatura/health"; do
  echo -n "$p -> "; curl -s -o /dev/null -w "%{http_code}" -u admin:admin123 http://localhost:8080$p; echo
done
# /converter/ctof/100 -> 200
# /temperatura/converter/ctof/100 -> 404

# 3. direto vs nginx
curl -s -i -u admin:admin123 http://localhost:8080/converter/ctof/100 | head
curl -k -s -i -u admin:admin123 https://jee.lab.dev/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1 | head
# ambos 200 se usar /converter

# 4. deployments
docker exec temperatura-converter-jee ls -lh /opt/jboss/wildfly/standalone/deployments/
# ROOT.war  ROOT.war.deployed

# 5. métricas (efeito colateral)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/metrics   # 404
curl -s -o /dev/null -w "%{http_code}" http://localhost:9990/metrics   # 200
```

---

## Correção

### Opção A — imediata (sem rebuild): use URL correta

```bash
# sem /temperatura
curl -k -u admin:admin123 https://jee.lab.dev/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1
# 212.0

curl -k -u admin:admin123 https://jee.lab.dev/health --resolve jee.lab.dev:443:127.0.0.1
# {"status":"UP"}

# todos os endpoints: ctok, ftoc, ftok, ktoc, ktof
curl -k -u admin:admin123 https://jee.lab.dev/converter/ctok/0 --resolve jee.lab.dev:443:127.0.0.1  # 273.15
```

Via Nginx `nginx/nginx.conf:66` (`proxy_pass http://jee_backend`) o prefixo é repassado 1:1 — use `/converter`, não `/temperatura/converter`.

### Opção B — permanente: alinhar deploy com docs (`/temperatura`)

Se quiser manter URLs documentadas com `/temperatura`, renomeie deployment:

```dockerfile
# Dockerfile:14 — antes
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/ROOT.war

# depois (preserva context-root /temperatura do jboss-web.xml)
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/temperatura.war
```

Rebuild:

```bash
docker compose -f implantacao/docker-compose.yml up -d --build jee
docker compose -f implantacao/docker-compose.yml logs jee | grep "Registered web context"
# WFLYUT0021: Registered web context: '/temperatura'
curl -s -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
```

> Escolha **uma** opção. Misturar (`ROOT.war` + `/temperatura`) mantém 404. Recomendado: **Opção A** (menos diff, já funciona) e atualizar docs para remover `/temperatura` do path da API.

### Fix adicional — Prometheus `jee:8080/metrics` 404

`implantacao/prometheus/prometheus.yml:13` scraepa `jee:8080/metrics` que não existe em `ROOT.war`. Métricas só em `jee:9990/metrics`:

```yaml
# antes
targets: ["jee:8080"]

# depois
targets: ["jee:9990"]
```

Ou mantenha ambos e marque `jee:8080` como opcional. Sem isso, Grafana mostra `up=0` para `temperatura-converter-jee`.

---

## Validação

```bash
# A — sem /temperatura (ROOT.war atual)
curl -s -u admin:admin123 http://localhost:8080/converter/ctof/100 | grep 212
curl -k -s -u admin:admin123 https://jee.lab.dev/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1 | grep 212
curl -s http://localhost:8080/health | jq .status  # UP
curl -s http://localhost:9990/metrics | head -5     # HELP ...

# B — com /temperatura (após renomear para temperatura.war)
curl -s -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100 --resolve jee.lab.dev:443:127.0.0.1
docker compose -f implantacao/docker-compose.yml logs nginx --tail=5 | grep " 200 "
```

---

## Prevenção

- Decida contexto único: `ROOT.war` → `/` (`jboss-web.xml` com `/` ou removido) **ou** `temperatura.war` → `/temperatura` (`jboss-web.xml` com `/temperatura`). Não misture.
- Valide com `grep "Registered web context"` após cada build.
- Mantenha `docker-compose.yml:25` healthcheck e `prometheus.yml:13` coerentes com contexto escolhido.
- Teste matriz de URLs antes de documentar: `/converter/*`, `/health`, `/metrics` (8080 vs 9990).

---

*Arquivos: `Dockerfile:14`, `src/main/webapp/WEB-INF/jboss-web.xml:3`, `src/main/java/.../RestApplication.java:5`, `implantacao/prometheus/prometheus.yml:13`, `implantacao/docker-compose.yml:25`, `implantacao/nginx/nginx.conf:66` | Data: 2026-09-02 | Relacionado: `01-wildfly-image-not-found.md`, `02-tls-unrecognized-name-https-jee.md`*
