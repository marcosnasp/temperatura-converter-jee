# 01 — Imagem WildFly não encontrada (`quay.io/wildfly/wildfly:31.0.0.Final-jdk21: not found`)

> `Dockerfile:8` → `failed to resolve source metadata ... not found`

---

## Sintoma

```bash
docker compose build jee
# ou
docker build -t temperatura-converter-jee .

=> ERROR [jee 2/5] FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk21
=> failed to resolve source metadata for quay.io/wildfly/wildfly:31.0.0.Final-jdk21:
   quay.io/wildfly/wildfly:31.0.0.Final-jdk21: not found
```

`docker compose up --build` falha antes de criar container. Nenhum outro serviço afeta.

---

## Causa

`quay.io/wildfly/wildfly` purga tags antigas. Tag `31.0.0.Final-jdk21` lançada em 2024-01 foi removida do registry.

Prova:

```bash
# 404
curl -s -o /dev/null -w "%{http_code}" \
  https://quay.io/v2/wildfly/wildfly/manifests/31.0.0.Final-jdk21 \
  -H "Accept: application/vnd.docker.distribution.manifest.v2+json"
# 31.0.0.Final-jdk21 -> 404

# 200 — tags recentes ainda existem
for tag in 32.0.1.Final-jdk21 33.0.2.Final-jdk21 34.0.1.Final-jdk21 35.0.1.Final-jdk21 36.0.1.Final-jdk21; do
  curl -s -o /dev/null -w "%{http_code}" \
    https://quay.io/v2/wildfly/wildfly/manifests/$tag \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" | xargs echo "$tag ->"
done
# 32.0.1.Final-jdk21 -> 200
```

Lista atual (2026-09): `quay.io/api/v1/repository/wildfly/wildfly/tag/?limit=50` só retorna de `33.0.1.Final-jdk21` em diante.

---

## Diagnóstico

```bash
# confirmar tag removida
curl -s "https://quay.io/api/v1/repository/wildfly/wildfly/tag/?limit=50" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('\n'.join(t['name'] for t in d['tags'][:20]))"

# ou checar manifesto direto (404 = purgada)
curl -I https://quay.io/v2/wildfly/wildfly/manifests/31.0.0.Final-jdk21
```

Se usar `podman`/`skopeo`:
```bash
skopeo inspect docker://quay.io/wildfly/wildfly:31.0.0.Final-jdk21  # -> 404
```

---

## Correção aplicada

`Dockerfile:8` — bump mínimo que preserva Jakarta EE 10 + JDK 21:

```dockerfile
# antes (404)
FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk21

# depois
FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21
```

Por que `32.0.1.Final-jdk21`:
- Menor diff que resolve (closest to 31, ainda Jakarta EE 10).
- `34.0.1.Final-jdk21` e `35.0.1.Final-jdk21` também são EE 10 e válidos se quiser mais recente.
- `36.0.1.Final-jdk21` já mira EE 11 (evitar sem testar `jakarta.jakartaee-api:10.0.0`).

Alternativa resiliente a purga (floating tag):
```dockerfile
FROM quay.io/wildfly/wildfly:latest-jdk21
# ou pin + renovação periódica via Renovate/Dependabot
```

> Docs citam `31.0.0.Final` em `implantacao/README.md:170`, `docs/guia-desenvolvedor.md:75`, etc. Não quebram build, mas atualizar quando bump for definitivo.

---

## Validação

```bash
# 1. manifesto existe
curl -s -o /dev/null -w "%{http_code}" \
  https://quay.io/v2/wildfly/wildfly/manifests/32.0.1.Final-jdk21 \
  -H "Accept: application/vnd.docker.distribution.manifest.v2+json"
# 200

# 2. build
docker compose -f implantacao/docker-compose.yml build --no-cache jee
# ou
docker build -t temperatura-converter-jee .

# 3. sobe e testa
docker compose -f implantacao/docker-compose.yml up -d jee
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
```

---

## Prevenção

- Prefira tags `latest-jdk21` ou pin com renovação automática (Renovate) se Quay purgar novamente.
- Valide tag antes de commitar: `curl -s -I https://quay.io/v2/wildfly/wildfly/manifests/<tag>` deve ser `200`.
- Mantenha `Dockerfile` e docs (`README.md:170`, `guia-desenvolvedor.md:75`) sincronizados.

---

*Arquivo: `Dockerfile:8` | Stack: `jee` | Data: 2026-09-02 | Relacionado: `02-tls-unrecognized-name-https-jee.md`*
