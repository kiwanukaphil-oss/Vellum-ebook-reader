FROM envoyproxy/envoy:v1.39.0

COPY envoy.template.yaml /etc/vellum-envoy/envoy.template.yaml
COPY entrypoint.sh /etc/vellum-envoy/entrypoint.sh

RUN chmod 0555 /etc/vellum-envoy/entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/bin/sh", "/etc/vellum-envoy/entrypoint.sh"]
