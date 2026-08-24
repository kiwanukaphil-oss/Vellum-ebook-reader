-- Derived from Supabase self-hosted v0.8.0. The base image creates these roles;
-- this script assigns the deployment-specific database password on first boot.
\set pgpass `echo "$POSTGRES_PASSWORD"`

alter user authenticator with password :'pgpass';
alter user pgbouncer with password :'pgpass';
alter user supabase_auth_admin with password :'pgpass';
alter user supabase_functions_admin with password :'pgpass';
alter user supabase_storage_admin with password :'pgpass';
