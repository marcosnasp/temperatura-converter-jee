# Troubleshooting — `implantacao/docs/troubleshoot`

> Índice dos casos documentados para a stack `jee + otel-collector + prometheus + grafana + nginx` (HTTPS `*.lab.dev`). Cada arquivo: sintoma → causa → diagnóstico → correção → validação.

---

## Índice

| # | Arquivo | Sintoma | Causa |
|---|---------|---------|-------|
| 01 | [`01-wildfly-image-not-found.md`](./01-wildfly-image-not-found.md) | `quay.io/wildfly/wildfly:31.0.0.Final-jdk21: not found` ao `docker build` | Tag purgada no Quay (só 32+ existe) |
| 02 | [`02-tls-unrecognized-name-https-jee.md`](./02-tls-unrecognized-name-https-jee.md) | `curl: (35) tlsv1 unrecognized name` em `https://jee.lab.dev` | `/etc/hosts` sem `jee.lab.dev` → DNS externo + stack parada |
| 03 | [`03-contexto-404-temperatura.md`](./03-contexto-404-temperatura.md) | `404` em `https://jee.lab.dev/temperatura/converter/...` (vazio) | `ROOT.war` mapeia `/` e ignora `jboss-web.xml:/temperatura` → URL real é `/converter` |

> Casos históricos (OTel `telemetry` inválido, Grafana `TLS handshake error`, Prometheus `8888 down`) estão em [`implantacao/troubleshoot/README.md`](../../troubleshoot/README.md) — Catálogo completo por serviço.

---

## Como usar

```bash
# identificar caso pelo log
docker compose -f implantacao/docker-compose.yml logs --tail=100 2>&1 | grep -E "not found|unrecognized name|TLS handshake|has invalid keys"

# validar infra antes de HTTPS
cat /etc/hosts | grep lab.dev
getent hosts jee.lab.dev          # deve ser 127.0.0.1
docker compose -f implantacao/docker-compose.yml ps
ss -tlnp | grep 443

# testes rápidos
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # bypass nginx
curl -k --resolve jee.lab.dev:443:127.0.0.1 -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
```

---

## Checklist ao adicionar novo caso

1. Crie `NN-nome-curto.md` neste diretório.
2. Siga template: **Sintoma** (log exato) → **Causa** (arquivo:linha) → **Diagnóstico** (comandos) → **Correção** (diff) → **Validação** (curl/docker) → **Prevenção**.
3. Atualize tabela acima e link em `../../troubleshoot/README.md` se for caso recorrente.
4. Referencie `arquivo:linha` exato (ex.: `Dockerfile:8`, `nginx/nginx.conf:53`).

---

*Última atualização: 2026-09-02 — casos 01 e 02 adicionados. Ao corrigir novo bug, documente aqui antes de fechar PR.*
