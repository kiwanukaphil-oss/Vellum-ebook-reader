import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  cacheControlForObject,
  parseCurationInput,
  parseEnrichmentInput,
  parseOpenAiCurationResult,
  parseOpenAiResult,
} from "../src";

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

  it("allows private immutable cover caching while keeping book files uncached", () => {
    expect(cacheControlForObject("cover")).toBe("private, max-age=31536000, immutable");
    expect(cacheControlForObject("file")).toBe("private, no-store");
  });

  it("keeps the AI librarian behind authentication", async () => {
    const response = await SELF.fetch("https://vellum.invalid/v1/librarian/enrich", {
      method: "POST",
      body: JSON.stringify({}),
    });
    expect(response.status).toBe(401);
  });

  it("keeps library curation behind authentication", async () => {
    const response = await SELF.fetch("https://vellum.invalid/v1/librarian/curate", {
      method: "POST",
      body: JSON.stringify({ books: [] }),
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

  it("bounds library-level curation input", () => {
    expect(
      parseCurationInput({
        books: [
          {
            id: "book-1",
            title: "  A Wizard of Earthsea ",
            author: " Ursula K. Le Guin ",
            category: "Fiction",
            genres: ["Fantasy", "Not a controlled genre"],
            seriesName: " Earthsea ",
            seriesIndex: 1,
          },
        ],
      }),
    ).toEqual([
      {
        id: "book-1",
        title: "A Wizard of Earthsea",
        author: "Ursula K. Le Guin",
        category: "Fiction",
        genres: ["Fantasy"],
        seriesName: "Earthsea",
        seriesIndex: 1,
      },
    ]);
  });

  it("accepts useful structured collection proposals", () => {
    expect(
      parseOpenAiCurationResult(
        {
          output: [
            {
              content: [
                {
                  type: "output_text",
                  text: JSON.stringify({
                    collections: [
                      {
                        name: "Earthsea",
                        kind: "series",
                        bookIds: ["book-1", "book-2"],
                        confidence: 0.97,
                        explanation: "Both books carry the Earthsea series metadata.",
                      },
                      {
                        name: "Imagined Worlds",
                        kind: "theme",
                        bookIds: ["book-1", "book-2", "book-3"],
                        confidence: 0.93,
                        explanation: "Three speculative novels centred on invented worlds.",
                      },
                    ],
                  }),
                },
              ],
            },
          ],
        },
        new Set(["book-1", "book-2", "book-3"]),
      ).collections,
    ).toHaveLength(2);
  });

  it("rejects one-book and invented collection membership", () => {
    const result = {
      output: [
        {
          content: [
            {
              type: "output_text",
              text: JSON.stringify({
                collections: [
                  {
                    name: "Invented shelf",
                    kind: "series",
                    bookIds: ["book-1", "not-in-library"],
                    confidence: 0.99,
                    explanation: "Invalid membership.",
                  },
                ],
              }),
            },
          ],
        },
      ],
    };
    expect(() => parseOpenAiCurationResult(result, new Set(["book-1"]))).toThrow(
      "The librarian returned invalid collection members.",
    );
  });
});
