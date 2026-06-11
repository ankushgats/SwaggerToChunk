# Swagger → RAG Documents (Step 1) — Java

Java port of the tested Python `swagger_to_docs.py`. Parses an OpenAPI/Swagger
JSON and emits one self-contained, retrieval-friendly document per
endpoint+method as JSONL, with all `$ref`s resolved inline.

## Project layout

```
pom.xml
src/main/java/com/example/swaggerag/SwaggerToDocs.java
```

(Move `SwaggerToDocs.java` into `src/main/java/com/example/swaggerag/`.)

## Build & run

```bash
mvn clean package
java -jar target/swagger-rag-step1.jar SES_PolicyCommands_public_API.json endpoint_docs.jsonl
```

## Output format (same as the Python version)

One JSON object per line:

```json
{
  "id": "PUT /v1/policies/allow-list/{policy_uid}/versions/{version}",
  "text": "API: ...\nBase URL: ...\n\nAPI Endpoint: PUT /v1/...\nSummary: ...\nParameters:\n- policy_uid (path, string, required): ...",
  "metadata": {
    "path": "/v1/policies/allow-list/{policy_uid}/versions/{version}",
    "method": "PUT",
    "tags": ["Update Policy"],
    "summary": "...",
    "operationId": "...",
    "sourceFile": "SES_PolicyCommands_public_API.json"
  }
}
```

Oversized endpoints (like `GET /v1/policies/{policy_uid}/versions/{version}`,
which expands to ~144K chars in your spec) are split into ~4,000-char chunks.
Each continuation chunk repeats the endpoint header so every chunk remains
self-contained for retrieval. Chunked records get `"chunk"` / `"chunksTotal"`
metadata and ids like `"... [part 2/36]"`.

## Design notes / differences from the Python version

- **$ref resolution** is delegated to swagger-parser's `ParseOptions
  .setResolveFully(true)` instead of hand-rolled resolution. It inlines refs
  and leaves genuinely circular refs as `$ref` pointers, which the text
  renderer names as `(see schema: X)`. An identity-based visited set guards
  against object cycles ResolveFully can create by reusing Schema instances.
- **Malformed specs**: parser warnings are printed to stderr but do not abort,
  matching how the Python version tolerated the non-string `type`/`description`
  values found in the SES spec.
- The Python version also kept the fully resolved operation JSON in a `raw`
  field. That's omitted here for output size; if you want it, serialize the
  `Operation` with `io.swagger.v3.core.util.Json.mapper()` and add it to the
  record map.

## Expected result on the SES Policy Commands spec

~70 chunks, sizes roughly 500–6,000 chars (the Python reference produced
70 chunks, min 541 / max 5,889 / avg 3,617 chars). Exact text differs slightly
(property ordering, circular-ref labels) but structure and content match.

## Caveat

This code was written against swagger-parser 2.1.x APIs but could not be
compile-tested in the sandbox (no network access for Maven dependencies).
The chunking and rendering logic mirrors the Python version that WAS tested
on your actual spec. If anything fails to compile, it will most likely be a
minor API signature difference — share the error and it's a quick fix.
