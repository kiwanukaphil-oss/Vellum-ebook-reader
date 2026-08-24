FROM supabase/postgres:17.6.1.136

COPY init/roles.sql /docker-entrypoint-initdb.d/init-scripts/99-roles.sql
COPY init/jwt.sql /docker-entrypoint-initdb.d/init-scripts/99-jwt.sql
COPY start-postgres.sh /usr/local/bin/start-vellum-postgres

RUN chmod +x /usr/local/bin/start-vellum-postgres

EXPOSE 5432

HEALTHCHECK --interval=5s --timeout=5s --retries=10 \
  CMD pg_isready -U postgres -h 127.0.0.1 -p 5432

CMD ["/usr/local/bin/start-vellum-postgres"]
