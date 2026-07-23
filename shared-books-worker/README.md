# Vellum Shared Books Worker

This Cloudflare Worker is the private file gateway for Vellum Shared Libraries.
It streams original books and covers to and from the `vellum-shared-books` R2
bucket. The bucket has no public endpoint.

Every object request must include a Supabase access token. The Worker verifies
the token and the current library membership before reading R2. Uploads also
require an Owner or Librarian role. The Supabase publishable key is intentionally
public; no service-role key or R2 credential is stored in the Android app.

## Production

- Worker: `vellum-shared-books`
- URL: `https://vellum-shared-books.kiwanukaphil.workers.dev`
- R2 bucket: `vellum-shared-books` (EEUR, Standard)
- Supabase project ref: `cdsmygejvbusxnzscghk`

## Local verification

```powershell
npm ci
npm run check
npm test
npx wrangler deploy --dry-run
```

Deploy from this directory with `npx wrangler deploy`. The committed
`wrangler.jsonc` contains only public configuration and the R2 binding.
