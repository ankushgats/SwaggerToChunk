package com.example.swaggerag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Retrieval + eval bridge.
 *
 * Reads eval_set.json, runs each question through HYBRID retrieval, and
 * writes results.json in the exact shape run_eval.py expects:
 *   { "<questionId>": ["<chunk id> ...ranked..."], ... }
 *
 * Then score with:
 *   python3 run_eval.py eval_set.json results.json 5
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass=com.example.swaggerag.EvalApp \
 *       -Dexec.args="eval_set.json results.json 8"
 */
public class EvalApp {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path evalPath = Path.of(args.length > 0 ? args[0] : "eval_set.json");
        Path outPath = Path.of(args.length > 1 ? args[1] : "results.json");
        int k = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        EmbeddingModel embeddingModel = RagConfig.embeddingModel();
        int dim = embeddingModel.dimension();
        DataSource ds = RagConfig.dataSource();
        EmbeddingStore<TextSegment> store = RagConfig.store(ds, dim, false);

        JsonNode evalSet = JSON.readTree(Files.newBufferedReader(evalPath));
        ObjectNode results = JSON.createObjectNode();

        for (JsonNode q : evalSet.path("questions")) {
            String qid = q.path("id").asText();
            String question = q.path("question").asText();

            Embedding qEmb = embeddingModel.embed(question).content();
            // HYBRID requires BOTH the embedding and the raw query text.
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(qEmb)
                    .query(question)
                    .maxResults(k)
                    .build();

            EmbeddingSearchResult<TextSegment> res = store.search(req);
            ArrayNode ranked = results.putArray(qid);
            for (EmbeddingMatch<TextSegment> match : res.matches()) {
                // prefer the stored chunk id; fall back to METHOD+path
                String id = match.embedded().metadata().getString("id");
                if (id == null || id.isEmpty()) {
                    id = match.embedded().metadata().getString("method")
                            + " " + match.embedded().metadata().getString("path");
                }
                ranked.add(id);
            }
        }

        Files.writeString(outPath, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(results));
        System.out.println("Wrote retrieval results to " + outPath
                + "\nNow run: python3 run_eval.py " + evalPath + " " + outPath + " 5");
        System.exit(0);
    }
}
