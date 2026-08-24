# Vellum Railway infrastructure

This directory is the project-level Railway Infrastructure-as-Code source for
Vellum's minimal Supabase-compatible backend. Railway's older `railway.toml`
format is deliberately not used because it is deprecated for new services.

The file describes resources but does not contain credentials. Required
credentials are Railway shared variables documented in
`../infrastructure/railway/supabase/README.md`.

Safe local checks:

```powershell
Set-Location .railway
npm ci
npm run check
```

`railway config plan` reads Railway state and previews changes. Do not run
`railway config apply` until the deployment phase has been separately approved.

The current `primaryRegion` value is `europe-west4`, aligning the database with
the existing European R2 placement. Confirm that region before the first apply.
