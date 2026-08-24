FROM supabase/postgres:17.6.1.136

COPY init/roles.sql /docker-entrypoint-initdb.d/init-scripts/99-roles.sql
COPY init/jwt.sql /docker-entrypoint-initdb.d/init-scripts/99-jwt.sql

EXPOSE 5432

HEALTHCHECK --interval=5s --timeout=5s --retries=10 \
  CMD pg_isready -U postgres -h 127.0.0.1 -p 5432

CMD ["postgres", "-c", "config_file=/etc/postgresql/postgresql.conf", "-c", "log_min_messages=fatal"]
