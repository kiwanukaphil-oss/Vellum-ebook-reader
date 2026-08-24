#!/bin/sh
set -eu

: "${PORT:?PORT is required}"
: "${AUTH_HOST:?AUTH_HOST is required}"
: "${AUTH_PORT:?AUTH_PORT is required}"
: "${REST_HOST:?REST_HOST is required}"
: "${REST_PORT:?REST_PORT is required}"
: "${ANON_KEY:?ANON_KEY is required}"
: "${SERVICE_ROLE_KEY:?SERVICE_ROLE_KEY is required}"

sed \
  -e "s|\${PORT}|${PORT}|g" \
  -e "s|\${AUTH_HOST}|${AUTH_HOST}|g" \
  -e "s|\${AUTH_PORT}|${AUTH_PORT}|g" \
  -e "s|\${REST_HOST}|${REST_HOST}|g" \
  -e "s|\${REST_PORT}|${REST_PORT}|g" \
  -e "s|\${ANON_KEY}|${ANON_KEY}|g" \
  -e "s|\${SERVICE_ROLE_KEY}|${SERVICE_ROLE_KEY}|g" \
  /etc/vellum-envoy/envoy.template.yaml \
  > /tmp/envoy.yaml

exec envoy -c /tmp/envoy.yaml "$@"
