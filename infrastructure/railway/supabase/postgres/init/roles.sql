-- Derived from Supabase self-hosted v0.8.0. The base image creates these roles;
-- this script assigns the deployment-specific database password on first boot.
\set pgpass `echo "$POSTGRES_PASSWORD"`

select format('alter role %I with password %L;', candidate_role, :'pgpass')
from unnest(array[
  'postgres',
  'authenticator',
  'pgbouncer',
  'supabase_auth_admin',
  'supabase_functions_admin',
  'supabase_storage_admin'
]) as candidate_roles(candidate_role)
where exists (
  select 1 from pg_roles where rolname = candidate_role
)
\gexec
