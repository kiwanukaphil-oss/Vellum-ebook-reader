# Minimal Supabase-compatible Railway backend

This scaffold runs only the Supabase components Vellum currently consumes:

| Railway service | Pinned image | Purpose | Public |
|---|---|---|---|
| `db` | `supabase/postgres:17.6.1.136` | Auth records, catalogue data, RPCs, and RLS | No |
| `auth` | `supabase/gotrue:v2.189.0` | Passwordless email sessions | No |
| `rest` | `postgrest/postgrest:v14.12` | REST and RPC Data API | No |
| `gateway` | `envoyproxy/envoy:v1.39.0` | API-key enforcement and `/auth/v1`, `/rest/v1` routing | Yes |

The versions come from Supabase self-hosted `v0.8.0`. Cloudflare Worker and R2
remain the private file gateway and object store.

Railway initially builds from `agent/elevenlabs-narration-cache`, the branch
containing this scaffold. Candidate follow-up: change the IaC source branch to
`main` after this branch is merged; do not remove the deployment branch first.

## Deliberately excluded candidates

Studio, Realtime, Storage, Edge Functions, Analytics, Vector, imgproxy,
postgres-meta, and Supavisor are not required by the current Android app or
Worker. They are documented here rather than silently removed from any running
Supabase environment. Reintroduce a service only when a concrete Vellum feature
depends on it.

## Required Railway shared variables

Create these in the new Railway environment before planning a deployment:

| Variable | Requirement |
|---|---|
| `POSTGRES_PASSWORD` | Random database password; never commit it |
| `JWT_SECRET` | Random value of at least 32 characters |
| `ANON_KEY` | HS256 JWT with `role=anon`, generated from `JWT_SECRET` |
| `SERVICE_ROLE_KEY` | HS256 JWT with `role=service_role`; never expose it |
| `SUPABASE_PUBLIC_URL` | HTTPS gateway base URL without `/auth/v1` |
| `SMTP_ADMIN_EMAIL` | Sender address verified in Brevo |
| `SMTP_USER` | Brevo SMTP login shown under SMTP & API settings |
| `SMTP_PASS` | Brevo SMTP key, not an API key; seal this variable in Railway |
| `SMTP_SENDER_NAME` | Human-readable sender, for example `Vellum` |

Only `ANON_KEY` will eventually be copied into the Android build and the
Cloudflare Worker. `SERVICE_ROLE_KEY`, `JWT_SECRET`, database credentials, and
SMTP credentials must remain server-side.

The Auth service is fixed to Brevo's `smtp-relay.brevo.com` endpoint on port
`587`. Brevo negotiates TLS with STARTTLS on this port. The SMTP login and key
remain environment-owned secrets so they never enter source control.

## Network and volume contract

- Generate a public HTTPS domain only for `gateway`.
- Keep `db`, `auth`, and `rest` on Railway private networking.
- Mount `vellum-db-data` at `/var/lib/postgresql/data`.
- Mount `vellum-db-config` at `/etc/postgresql-custom`.
- Keep serverless sleeping disabled for all four services.
- Enable daily and weekly backups on both database volumes before importing data.

The IaC uses the confirmed `europe-west4` region, matching the existing European
R2 placement. Changing a persisted volume's region later requires a data
migration.

## Local validation

Docker Desktop must be running. From the repository root:

```powershell
.\infrastructure\railway\supabase\validate.ps1
```

The validator type-checks Railway IaC, builds all pinned containers, and asks
Envoy to validate its rendered configuration. It does not create or modify any
Railway, Supabase, or Cloudflare resources.

## Deployment boundary

`railway config plan` is read-only. `railway config apply` creates or changes
external resources and belongs to the next approved phase. Before applying:

1. Confirm the Railway region and SMTP provider.
2. Review all shared variables without printing secrets to logs.
3. Review the plan for exactly four services and two volumes.
4. Reject any unexpected deletion or change to unrelated Railway projects.
