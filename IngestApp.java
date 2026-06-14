package com.example.swaggerag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingestion (indexing) stage.
 *
 * Reads the pre-chunked endpoint_docs.jsonl produced by SwaggerToDocs
 * (each line already a self-contained chunk — so NO further splitting),
 * embeds the `text` field only, and stores each chunk with its metadata
 * (path, method, tags, summary, operationId, docUrl, ...) in PgVector.
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass=com.example.swaggerag.IngestApp \
 *       -Dexec.args="endpoint_docs.jsonl"
 */
public class IngestApp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 50;  // embed/store in batches

    public static void main(String[] args) throws Exception {
        Path jsonl = Path.of(args.length > 0 ? args[0] : "endpoint_docs.jsonl");

        EmbeddingModel embeddingModel = RagConfig.embeddingModel();
        int dim = embeddingModel.dimension();
        DataSource ds = RagConfig.dataSource();
        // createFresh=true: wipe & recreate so re-ingest is idempotent.
        EmbeddingStore<TextSegment> store = RagConfig.store(ds, dim, true);

        List<TextSegment> segments = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(jsonl)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                segments.add(toSegment(JSON.readTree(line)));
            }
        }
        System.out.println("Loaded " + segments.size() + " chunks from " + jsonl);

        int stored = 0;
        for (int i = 0; i < segments.size(); i += BATCH) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + BATCH, segments.size()));
            // embedAll embeds the SEGMENT TEXT only (metadata is not embedded).
            Response<List<Embedding>> resp = embeddingModel.embedAll(batch);
            store.addAll(resp.content(), batch);
            stored += batch.size();
            System.out.println("  stored " + stored + "/" + segments.size());
        }
        System.out.println("Ingestion complete. Table: " + RagConfig.TABLE
                + " | model: " + RagConfig.EMBED_MODEL + " | dim: " + dim);
        System.exit(0);  // close Hikari pool threads
    }

    /** Map a JSONL record to a TextSegment, flattening metadata to scalar values. */
    private static TextSegment toSegment(JsonNode rec) {
        String text = rec.path("text").asText("");
        JsonNode m = rec.path("metadata");

        Metadata md = new Metadata();
        // keep the chunk id as a retrievable key for the eval harness
        md.put("id", rec.path("id").asText(""));
        putIfPresent(md, m, "path");
        putIfPresent(md, m, "method");
        putIfPresent(md, m, "summary");
        putIfPresent(md, m, "operation_id");   // python key
        putIfPresent(md, m, "operationId");    // java-port key
        putIfPresent(md, m, "source_file");
        putIfPresent(md, m, "sourceFile");
        putIfPresent(md, m, "doc_url");        // python key
        putIfPresent(md, m, "docUrl");         // java-port key

        // tags: array -> comma-joined string (Metadata holds scalars)
        JsonNode tags = m.path("tags");
        if (tags.isArray() && tags.size() > 0) {
            List<String> t = new ArrayList<>();
            tags.forEach(n -> t.add(n.asText()));
            md.put("tags", String.join(",", t));
        }
        // chunk info if present
        if (m.has("chunk")) md.put("chunk", m.path("chunk").asInt());
        if (m.has("chunks_total")) md.put("chunks_total", m.path("chunks_total").asInt());

        return TextSegment.from(text, md);
    }

    private static void putIfPresent(Metadata md, JsonNode m, String key) {
        JsonNode v = m.get(key);
        if (v != null && !v.isNull() && !v.asText().isEmpty()) {
            md.put(key, v.asText());
        }
    }
}
