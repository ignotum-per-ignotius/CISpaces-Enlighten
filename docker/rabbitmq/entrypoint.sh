#!/bin/bash
# Start RabbitMQ and create user intelanalysis.
# The broker reads the password from a mode 600 file. rabbitmqctl argv does not.

set +x
set -euo pipefail

reject_secret() {
    set +x
    local name="$1"
    local value="${!name-}"
    if [ -z "$value" ]; then
        echo "# Set ${name} in the environment before starting. See config/secrets.env.example. The value is not printed."
        exit 1
    fi
    case "$value" in
        *\\*|*$'\n'*|*$'\r'*|*'$'*)
            echo "# ${name} contains a backslash, dollar sign, or newline. Choose another value. The value is not printed."
            exit 1
            ;;
    esac
}

user_exists() {
    rabbitmqctl list_users | awk '$1 == "intelanalysis" { found = 1 } END { exit !found }'
}

guest_exists() {
    rabbitmqctl list_users | awk '$1 == "guest" { found = 1 } END { exit !found }'
}

set_broker_password() {
    set +x
    local action="$1"
    local password_file broker_file expr status
    password_file=$(mktemp)
    chmod 600 "$password_file"
    printf '%s' "$CISPACES_RABBITMQ_PASSWORD" > "$password_file"
    broker_file=$(mktemp -p /var/lib/rabbitmq cispaces-pw.XXXXXXXX)
    chmod 600 "$broker_file"
    cp "$password_file" "$broker_file"
    chmod 600 "$broker_file"
    rm -f "$password_file"
    case "$broker_file" in
        *[!/A-Za-z0-9._-]*)
            rm -f "$broker_file"
            echo "# Could not store the RabbitMQ password for rabbitmqctl. The value is not printed."
            return 1
            ;;
    esac
    # file:read_file keeps the password out of the eval text. The form ends in ok.
    expr="{ok, PasswordBin} = file:read_file(\"${broker_file}\"), code:ensure_loaded(rabbit_auth_backend_internal), case erlang:function_exported(rabbit_auth_backend_internal, ${action}, 3) of true -> rabbit_auth_backend_internal:${action}(<<\"intelanalysis\">>, PasswordBin, <<\"cispaces-compose\">>); false -> rabbit_auth_backend_internal:${action}(<<\"intelanalysis\">>, PasswordBin) end, ok."
    if ! rabbitmqctl eval "$expr" >/dev/null; then
        rm -f "$broker_file"
        echo "# Failed to set the RabbitMQ password. The value is not printed."
        return 1
    fi
    rm -f "$broker_file"
}

stop_broker() {
    if [ "${stopping:-0}" -eq 1 ]; then
        return 0
    fi
    stopping=1
    rabbitmqctl stop >/dev/null 2>&1 || true
    if [ -n "${server_pid:-}" ]; then
        wait "$server_pid" 2>/dev/null || true
    fi
}

reject_secret CISPACES_RABBITMQ_PASSWORD

stopping=0
server_pid=""
trap stop_broker EXIT
trap 'stop_broker; exit 0' TERM INT

rabbitmq-server &
server_pid=$!

ready=0
# ping answers as soon as the Erlang node is up, before the rabbit application
# has booted. list_users and add_user need the application, so wait for that.
for _ in $(seq 1 60); do
    if rabbitmq-diagnostics -q check_running >/dev/null 2>&1; then
        ready=1
        break
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        echo "# RabbitMQ exited before it accepted connections"
        exit 1
    fi
    sleep 2
done
if [ "$ready" -ne 1 ]; then
    echo "# RabbitMQ did not accept connections"
    stop_broker
    exit 1
fi

if user_exists; then
    set_broker_password change_password
else
    set_broker_password add_user
fi
rabbitmqctl set_user_tags intelanalysis administrator >/dev/null
rabbitmqctl set_permissions intelanalysis ".*" ".*" ".*" >/dev/null
if guest_exists; then
    rabbitmqctl delete_user guest >/dev/null
fi

printf '%s\n' ready > /var/lib/rabbitmq/cispaces.ready
chmod 644 /var/lib/rabbitmq/cispaces.ready
echo "# RabbitMQ user intelanalysis is ready"

wait "$server_pid"
