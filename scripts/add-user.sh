#!/bin/bash
set -e
# ponytail: cria usuario ApplicationRealm para Basic Auth a partir de env vars
USER_NAME=${APP_USERNAME:-admin}
USER_PASS=${APP_PASSWORD:-admin123}
# ignora erro se usuario ja existe
/opt/jboss/wildfly/bin/add-user.sh -a -u "$USER_NAME" -p "$USER_PASS" -g guest --silent 2>/dev/null || true
# OTel env ja vem do compose
exec /opt/jboss/wildfly/bin/standalone.sh -b 0.0.0.0 -bmanagement 0.0.0.0
