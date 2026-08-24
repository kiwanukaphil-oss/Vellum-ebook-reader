$ErrorActionPreference = "Stop"

$supabaseInfrastructureDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryDirectory = Resolve-Path (Join-Path $supabaseInfrastructureDirectory "..\..\..")
$railwayInfrastructureDirectory = Join-Path $repositoryDirectory ".railway"

$requiredFiles = @(
    ".railway\railway.ts",
    ".railway\package.json",
    "infrastructure\railway\supabase\postgres\Dockerfile",
    "infrastructure\railway\supabase\auth\Dockerfile",
    "infrastructure\railway\supabase\rest\Dockerfile",
    "infrastructure\railway\supabase\gateway\Dockerfile",
    "infrastructure\railway\supabase\gateway\envoy.template.yaml"
)

foreach ($requiredFile in $requiredFiles) {
    $requiredPath = Join-Path $repositoryDirectory $requiredFile
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required infrastructure file is missing: $requiredFile"
    }
}

Push-Location $railwayInfrastructureDirectory
try {
    npm ci
    npm run check
}
finally {
    Pop-Location
}

$serviceDirectories = @("postgres", "auth", "rest", "gateway")
foreach ($serviceDirectory in $serviceDirectories) {
    $serviceBuildContext = Join-Path $supabaseInfrastructureDirectory $serviceDirectory
    docker build --tag "vellum-supabase-$serviceDirectory`:scaffold" $serviceBuildContext
}

docker run --rm `
    --env PORT=8000 `
    --env AUTH_HOST=auth.railway.internal `
    --env AUTH_PORT=9999 `
    --env REST_HOST=rest.railway.internal `
    --env REST_PORT=3000 `
    --env ANON_KEY=scaffold-anon-key `
    --env SERVICE_ROLE_KEY=scaffold-service-role-key `
    "vellum-supabase-gateway:scaffold" `
    --mode validate

Write-Host "Vellum Railway Supabase scaffold validation passed."
