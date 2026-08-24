import {
  createRailwayContext,
  defineRailway,
  github,
  project,
  service,
  volume,
} from "railway/iac";

const repositorySource = "kiwanukaphil-oss/Vellum-ebook-reader";
const deploymentBranch = "agent/elevenlabs-narration-cache";
const primaryRegion = "europe-west4";

// Declares Vellum's complete minimal Supabase-compatible Railway project. Secrets
// remain environment-owned shared variables, while service-to-service traffic stays
// on Railway private networking and only the API gateway is intended to be public.
export default defineRailway((railwayContext) => {
  // Normalize the CLI-provided evaluation input because the Windows CLI runner
  // currently omits the SDK's shared-variable proxy from its raw context object.
  const normalizedRailwayContext = createRailwayContext(railwayContext);
  const databaseDataVolume = volume("vellum-db-data", {
    region: primaryRegion,
    sizeMB: 1024,
  });
  const databaseService = service("db", {
    source: github(repositorySource, {
      branch: deploymentBranch,
      rootDirectory: "infrastructure/railway/supabase/postgres",
    }),
    replicas: { [primaryRegion]: 1 },
    volumeMounts: {
      "/var/lib/postgresql/data": databaseDataVolume,
    },
    env: {
      POSTGRES_DB: "postgres",
      POSTGRES_PASSWORD: normalizedRailwayContext.shared.POSTGRES_PASSWORD,
      POSTGRES_PORT: "5432",
      PGPORT: "5432",
      JWT_SECRET: normalizedRailwayContext.shared.JWT_SECRET,
      JWT_EXP: "3600",
    },
  });

  const authenticationService = service("auth", {
    source: github(repositorySource, {
      branch: deploymentBranch,
      rootDirectory: "infrastructure/railway/supabase/auth",
    }),
    replicas: { [primaryRegion]: 1 },
    healthcheck: "/health",
    healthcheckTimeout: 120,
    env: {
      PORT: "9999",
      GOTRUE_API_HOST: "0.0.0.0",
      GOTRUE_API_PORT: "9999",
      API_EXTERNAL_URL: "${{shared.SUPABASE_PUBLIC_URL}}/auth/v1",
      GOTRUE_DB_DRIVER: "postgres",
      GOTRUE_DB_DATABASE_URL:
        "postgres://supabase_auth_admin:${{shared.POSTGRES_PASSWORD}}@${{db.RAILWAY_PRIVATE_DOMAIN}}:5432/postgres",
      GOTRUE_SITE_URL: "vellum://auth/callback",
      GOTRUE_URI_ALLOW_LIST: "vellum://auth/callback",
      GOTRUE_DISABLE_SIGNUP: "false",
      GOTRUE_JWT_ADMIN_ROLES: "service_role",
      GOTRUE_JWT_AUD: "authenticated",
      GOTRUE_JWT_DEFAULT_GROUP_NAME: "authenticated",
      GOTRUE_JWT_EXP: "3600",
      GOTRUE_JWT_SECRET: normalizedRailwayContext.shared.JWT_SECRET,
      GOTRUE_JWT_ISSUER: "${{shared.SUPABASE_PUBLIC_URL}}/auth/v1",
      GOTRUE_EXTERNAL_EMAIL_ENABLED: "true",
      GOTRUE_EXTERNAL_ANONYMOUS_USERS_ENABLED: "false",
      GOTRUE_EXTERNAL_PHONE_ENABLED: "false",
      GOTRUE_MAILER_AUTOCONFIRM: "false",
      GOTRUE_MAILER_OTP_EXP: "3600",
      GOTRUE_MAILER_OTP_LENGTH: "8",
      GOTRUE_SECURITY_REFRESH_TOKEN_ROTATION_ENABLED: "true",
      GOTRUE_SECURITY_REFRESH_TOKEN_REUSE_INTERVAL: "10",
      GOTRUE_SMTP_ADMIN_EMAIL:
        normalizedRailwayContext.shared.SMTP_ADMIN_EMAIL,
      GOTRUE_SMTP_HOST: "smtp-relay.brevo.com",
      GOTRUE_SMTP_PORT: "587",
      GOTRUE_SMTP_USER: normalizedRailwayContext.shared.SMTP_USER,
      GOTRUE_SMTP_PASS: normalizedRailwayContext.shared.SMTP_PASS,
      GOTRUE_SMTP_SENDER_NAME:
        normalizedRailwayContext.shared.SMTP_SENDER_NAME,
      GOTRUE_MAILER_URLPATHS_CONFIRMATION: "/auth/v1/verify",
      GOTRUE_MAILER_URLPATHS_INVITE: "/auth/v1/verify",
      GOTRUE_MAILER_URLPATHS_RECOVERY: "/auth/v1/verify",
      GOTRUE_MAILER_URLPATHS_EMAIL_CHANGE: "/auth/v1/verify",
    },
  });

  const dataApiService = service("rest", {
    source: github(repositorySource, {
      branch: deploymentBranch,
      rootDirectory: "infrastructure/railway/supabase/rest",
    }),
    replicas: { [primaryRegion]: 1 },
    healthcheck: "/",
    healthcheckTimeout: 120,
    env: {
      PORT: "3000",
      PGRST_DB_URI:
        "postgres://authenticator:${{shared.POSTGRES_PASSWORD}}@${{db.RAILWAY_PRIVATE_DOMAIN}}:5432/postgres",
      PGRST_DB_SCHEMAS: "public",
      PGRST_DB_MAX_ROWS: "1000",
      PGRST_DB_EXTRA_SEARCH_PATH: "public,extensions",
      PGRST_DB_ANON_ROLE: "anon",
      PGRST_JWT_SECRET: normalizedRailwayContext.shared.JWT_SECRET,
      PGRST_DB_USE_LEGACY_GUCS: "false",
      PGRST_APP_SETTINGS_JWT_SECRET:
        normalizedRailwayContext.shared.JWT_SECRET,
      PGRST_APP_SETTINGS_JWT_EXP: "3600",
    },
  });

  const apiGatewayService = service("gateway", {
    source: github(repositorySource, {
      branch: deploymentBranch,
      rootDirectory: "infrastructure/railway/supabase/gateway",
    }),
    replicas: { [primaryRegion]: 1 },
    healthcheck: "/health",
    healthcheckTimeout: 120,
    env: {
      PORT: "8000",
      AUTH_HOST: authenticationService.env.RAILWAY_PRIVATE_DOMAIN,
      AUTH_PORT: "9999",
      REST_HOST: dataApiService.env.RAILWAY_PRIVATE_DOMAIN,
      REST_PORT: "3000",
      ANON_KEY: normalizedRailwayContext.shared.ANON_KEY,
      SERVICE_ROLE_KEY: normalizedRailwayContext.shared.SERVICE_ROLE_KEY,
    },
  });

  // Excluded candidates: Studio, Realtime, Storage, Functions, Analytics,
  // Vector, imgproxy, postgres-meta, and Supavisor are not used by Vellum.
  return project("vellum-shared-library", {
    resources: [
      databaseDataVolume,
      databaseService,
      authenticationService,
      dataApiService,
      apiGatewayService,
    ],
  });
});
