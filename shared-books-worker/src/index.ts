type AuthUser = {
  id: string;
  email?: string;
};

type PublicationRow = {
  id: string;
  library_id: string;
  object_key: string;
  cover_object_key: string | null;
  sha256: string;
  size_bytes: number;
  status: "uploading" | "ready" | "failed";
  format: "epub" | "pdf" | "cbz" | "cbr";
  original_file_name: string;
};

type ObjectRoute = {
  libraryId: string;
  publicationId: string;
  kind: "file" | "cover";
};

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const OBJECT_ROUTE = new RegExp(
  `^/v1/libraries/(${UUID})/publications/(${UUID})/(file|cover)$`,
  "i",
);
const SHA256 = /^[0-9a-f]{64}$/;
const MAX_BOOK_BYTES = 100 * 1024 * 1024;
const MAX_COVER_BYTES = 2 * 1024 * 1024;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({ status: "ok" });
    }

    try {
      const route = parseObjectRoute(url.pathname);
      if (route === null) throw new HttpError(404, "Not found.");
      const authorization = request.headers.get("authorization");
      if (!authorization?.startsWith("Bearer ")) {
        throw new HttpError(401, "Sign in to open this shared book.");
      }
      assertConfigured(env);
      const user = await authenticate(authorization, env);
      const publication = await publicationFor(route, authorization, env);

      if (request.method === "GET") {
        return await download(route, publication, env);
      }
      if (request.method === "PUT") {
        await requireCurator(route.libraryId, user.id, authorization, env);
        return await upload(request, route, publication, authorization, env);
      }
      throw new HttpError(405, "Method not allowed.");
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      const publicMessage = error instanceof HttpError ? error.message : "Internal server error.";
      if (status >= 500) {
        console.error(
          JSON.stringify({
            message: "shared books request failed",
            path: url.pathname,
            method: request.method,
            error: error instanceof Error ? error.message : String(error),
          }),
        );
      }
      return Response.json(
        { error: publicMessage },
        {
          status,
          headers: securityHeaders(),
        },
      );
    }
  },
} satisfies ExportedHandler<Env>;

function assertConfigured(env: Env): void {
  if (!env.SUPABASE_URL.startsWith("https://") || env.SUPABASE_PUBLISHABLE_KEY.length < 10) {
    throw new HttpError(503, "Shared Libraries are not configured.");
  }
}

function parseObjectRoute(pathname: string): ObjectRoute | null {
  const match = OBJECT_ROUTE.exec(pathname);
  if (match === null) return null;
  const libraryId = match[1];
  const publicationId = match[2];
  const kind = match[3];
  if (libraryId === undefined || publicationId === undefined || (kind !== "file" && kind !== "cover")) {
    return null;
  }
  return { libraryId, publicationId, kind };
}

async function authenticate(authorization: string, env: Env): Promise<AuthUser> {
  const response = await fetch(`${env.SUPABASE_URL}/auth/v1/user`, {
    headers: supabaseHeaders(env, authorization),
  });
  if (!response.ok) throw new HttpError(401, "Your Shared Libraries session has expired.");
  const body: unknown = await response.json();
  if (!isAuthUser(body)) throw new HttpError(401, "The signed-in account could not be verified.");
  return body;
}

async function publicationFor(
  route: ObjectRoute,
  authorization: string,
  env: Env,
): Promise<PublicationRow> {
  const query = new URL(`${env.SUPABASE_URL}/rest/v1/publications`);
  query.searchParams.set("id", `eq.${route.publicationId}`);
  query.searchParams.set("library_id", `eq.${route.libraryId}`);
  query.searchParams.set("deleted_at", "is.null");
  query.searchParams.set(
    "select",
    "id,library_id,object_key,cover_object_key,sha256,size_bytes,status,format,original_file_name",
  );
  query.searchParams.set("limit", "1");
  const response = await fetch(query, { headers: supabaseHeaders(env, authorization) });
  if (!response.ok) throw await supabaseError(response);
  const body: unknown = await response.json();
  if (!Array.isArray(body) || body.length !== 1 || !isPublicationRow(body[0])) {
    throw new HttpError(404, "That shared book is no longer available.");
  }
  return body[0];
}

async function requireCurator(
  libraryId: string,
  userId: string,
  authorization: string,
  env: Env,
): Promise<void> {
  const query = new URL(`${env.SUPABASE_URL}/rest/v1/memberships`);
  query.searchParams.set("library_id", `eq.${libraryId}`);
  query.searchParams.set("user_id", `eq.${userId}`);
  query.searchParams.set("role", "in.(owner,librarian)");
  query.searchParams.set("select", "role");
  query.searchParams.set("limit", "1");
  const response = await fetch(query, { headers: supabaseHeaders(env, authorization) });
  if (!response.ok) throw await supabaseError(response);
  const body: unknown = await response.json();
  if (!Array.isArray(body) || body.length !== 1) {
    throw new HttpError(403, "Only the owner or a librarian can publish books.");
  }
}

async function upload(
  request: Request,
  route: ObjectRoute,
  publication: PublicationRow,
  authorization: string,
  env: Env,
): Promise<Response> {
  if (request.body === null) throw new HttpError(400, "The upload is empty.");
  const length = Number(request.headers.get("content-length"));
  const limit = route.kind === "file" ? MAX_BOOK_BYTES : MAX_COVER_BYTES;
  if (!Number.isSafeInteger(length) || length <= 0) {
    throw new HttpError(411, "The upload must include its size.");
  }
  if (length > limit) {
    throw new HttpError(413, route.kind === "file" ? "Books must be 100 MB or smaller." : "Covers must be 2 MB or smaller.");
  }

  const suppliedSha256 = request.headers.get("x-vellum-sha256")?.toLowerCase() ?? "";
  if (!SHA256.test(suppliedSha256)) throw new HttpError(400, "The upload fingerprint is invalid.");
  if (route.kind === "file" && suppliedSha256 !== publication.sha256) {
    throw new HttpError(409, "The selected file does not match this publication.");
  }
  if (route.kind === "file" && length !== publication.size_bytes) {
    throw new HttpError(409, "The selected file size does not match this publication.");
  }

  const contentType = request.headers.get("content-type") ?? "application/octet-stream";
  if (route.kind === "cover" && contentType !== "image/webp") {
    throw new HttpError(415, "Shared covers must be WebP images.");
  }
  if (route.kind === "file" && !validBookContentType(publication.format, contentType)) {
    throw new HttpError(415, "The uploaded file type does not match this publication.");
  }

  const key = route.kind === "file" ? publication.object_key : publication.cover_object_key;
  if (key === null || !safeObjectKey(key, route)) throw new HttpError(400, "The object destination is invalid.");
  const stored = await env.BOOKS.put(key, request.body, {
    sha256: suppliedSha256,
    httpMetadata: {
      contentType,
      contentDisposition:
        route.kind === "file"
          ? `attachment; filename="${safeFileName(publication.original_file_name)}"`
          : "inline",
      cacheControl: "private, no-store",
    },
    customMetadata: {
      libraryId: route.libraryId,
      publicationId: route.publicationId,
      sha256: suppliedSha256,
    },
  });
  if (stored === null) throw new HttpError(500, "The object store did not accept the upload.");

  if (route.kind === "file") {
    const response = await fetch(
      `${env.SUPABASE_URL}/rest/v1/publications?id=eq.${route.publicationId}&library_id=eq.${route.libraryId}`,
      {
        method: "PATCH",
        headers: {
          ...supabaseHeaders(env, authorization),
          "content-type": "application/json",
          prefer: "return=minimal",
        },
        body: JSON.stringify({ status: "ready", size_bytes: stored.size }),
      },
    );
    if (!response.ok) {
      await env.BOOKS.delete(key);
      throw await supabaseError(response);
    }
  }

  return Response.json(
    { status: "ready", size: stored.size, etag: stored.httpEtag },
    { headers: securityHeaders() },
  );
}

async function download(
  route: ObjectRoute,
  publication: PublicationRow,
  env: Env,
): Promise<Response> {
  if (publication.status !== "ready") throw new HttpError(409, "That book is still being prepared.");
  const key = route.kind === "file" ? publication.object_key : publication.cover_object_key;
  if (key === null || !safeObjectKey(key, route)) throw new HttpError(404, "That shared cover is not available.");
  const object = await env.BOOKS.get(key);
  if (object === null) throw new HttpError(404, "That shared file is not available.");

  const headers = securityHeaders();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("content-length", String(object.size));
  headers.set("cache-control", "private, no-store");
  headers.set("x-vellum-sha256", publication.sha256);
  return new Response(object.body, { headers });
}

function supabaseHeaders(env: Env, authorization: string): Record<string, string> {
  return {
    apikey: env.SUPABASE_PUBLISHABLE_KEY,
    authorization,
    accept: "application/json",
  };
}

async function supabaseError(response: Response): Promise<HttpError> {
  const body: unknown = await response.json().catch(() => null);
  const message =
    isObject(body) && typeof body.message === "string"
      ? body.message
      : response.status === 401
        ? "Your Shared Libraries session has expired."
        : "The shared catalogue rejected this request.";
  return new HttpError(response.status === 401 ? 401 : 403, message);
}

function securityHeaders(): Headers {
  return new Headers({
    "content-type": "application/json; charset=utf-8",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
  });
}

function safeObjectKey(key: string, route: ObjectRoute): boolean {
  const prefix = `libraries/${route.libraryId}/publications/${route.publicationId}/`;
  return key.startsWith(prefix) && !key.includes("..") && !key.startsWith("/");
}

function safeFileName(value: string): string {
  const clean = value.replace(/[\r\n"\\/]/g, "_").trim();
  return clean.length > 0 ? clean.slice(0, 180) : "book";
}

function validBookContentType(format: PublicationRow["format"], contentType: string): boolean {
  const normalized = contentType.split(";")[0]?.trim().toLowerCase();
  const accepted: Record<PublicationRow["format"], readonly string[]> = {
    epub: ["application/epub+zip", "application/octet-stream"],
    pdf: ["application/pdf", "application/octet-stream"],
    cbz: ["application/vnd.comicbook+zip", "application/zip", "application/octet-stream"],
    cbr: ["application/vnd.comicbook-rar", "application/x-rar-compressed", "application/octet-stream"],
  };
  return normalized !== undefined && accepted[format].includes(normalized);
}

function isAuthUser(value: unknown): value is AuthUser {
  return isObject(value) && typeof value.id === "string";
}

function isPublicationRow(value: unknown): value is PublicationRow {
  return (
    isObject(value) &&
    typeof value.id === "string" &&
    typeof value.library_id === "string" &&
    typeof value.object_key === "string" &&
    (typeof value.cover_object_key === "string" || value.cover_object_key === null) &&
    typeof value.sha256 === "string" &&
    typeof value.size_bytes === "number" &&
    (value.status === "uploading" || value.status === "ready" || value.status === "failed") &&
    (value.format === "epub" || value.format === "pdf" || value.format === "cbz" || value.format === "cbr") &&
    typeof value.original_file_name === "string"
  );
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
