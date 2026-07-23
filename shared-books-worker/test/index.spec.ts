import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { parseEnrichmentInput, parseOpenAiResult } from "../src";

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

  it("keeps the AI librarian behind authentication", async () => {
    const response = await SELF.fetch("https://vellum.invalid/v1/librarian/enrich", {
      method: "POST",
      body: JSON.stringify({}),
    });
    expect(response.status).toBe(401);
  });

  it("bounds and normalizes enrichment input before it reaches a model", () => {
    expect(
      parseEnrichmentInput({
        title: "  Pride and Prejudice  ",
        author: " Jane Austen ",
        fileName: "book.epub",
        format: "epub",
        currentCategory: null,
        currentGenres: ["Classics"],
        excerpt: "It is a truth universally acknowledged.",
      }),
    ).toEqual({
      title: "Pride and Prejudice",
      author: "Jane Austen",
      fileName: "book.epub",
      format: "epub",
      currentCategory: null,
      currentGenres: ["Classics"],
      excerpt: "It is a truth universally acknowledged.",
    });
  });

  it("turns malformed model output into a controlled upstream failure", () => {
    expect(() =>
      parseOpenAiResult({
        output: [{ content: [{ type: "output_text", text: "{not-json" }] }],
      }),
    ).toThrow("The librarian returned invalid metadata.");
  });
});
