import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

describe("Vellum shared-books Worker", () => {
  it("reports health without exposing configuration", async () => {
    const response = await SELF.fetch("https://vellum.invalid/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
  });

  it("rejects unauthenticated object requests", async () => {
    const response = await SELF.fetch(
      "https://vellum.invalid/v1/libraries/11111111-1111-4111-8111-111111111111/publications/22222222-2222-4222-8222-222222222222/file",
    );
    expect(response.status).toBe(401);
  });

  it("does not accept guessed object paths", async () => {
    const response = await SELF.fetch("https://vellum.invalid/libraries/a/publications/b/original.epub");
    expect(response.status).toBe(404);
  });
});
