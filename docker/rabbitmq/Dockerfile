# RabbitMQ on the workbench loopback. The entrypoint sets the intelanalysis
# user from CISPACES_RABBITMQ_PASSWORD without placing it on argv.
FROM rabbitmq:3.12

USER root
# AMQP stays on the loopback. The image's Prometheus listener is not part of
# this stack; leave it disabled so it does not bind the workbench network.
RUN printf '%s\n' \
        'listeners.tcp.1 = 127.0.0.1:5672' \
        'listeners.tcp.2 = ::1:5672' \
        > /etc/rabbitmq/rabbitmq.conf \
    && printf '%s\n' '[].' > /etc/rabbitmq/enabled_plugins \
    && chown rabbitmq:rabbitmq /etc/rabbitmq/rabbitmq.conf /etc/rabbitmq/enabled_plugins

COPY entrypoint.sh /usr/local/bin/cispaces-rabbitmq.sh
RUN chmod 755 /usr/local/bin/cispaces-rabbitmq.sh

USER rabbitmq
ENV RABBITMQ_LOGS=- \
    RABBITMQ_SASL_LOGS=- \
    HOME=/var/lib/rabbitmq
ENTRYPOINT ["/usr/local/bin/cispaces-rabbitmq.sh"]
