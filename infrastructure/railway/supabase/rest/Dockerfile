FROM postgrest/postgrest:v14.12

EXPOSE 3000

HEALTHCHECK --interval=5s --timeout=5s --retries=10 \
  CMD ["postgrest", "--ready"]
