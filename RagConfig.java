package com.example.swaggerag;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.SearchMode;

import javax.sql.DataSource;

/**
 * Shared factory for the embedding model and the PgVector store, so the
 * ingest job and the eval/query job build them identically (same table,
 * same dimension, same hybrid config).
 *
 * Configuration via environment variables (with sensible local defaults):
 *   OPENAI_API_KEY   (required)
 *   PG_HOST          default localhost
 *   PG_PORT          default 5432
 *   PG_DATABASE      default postgres
 *   PG_USER          default my_user
 *   PG_PASSWORD      default my_password
 *   PG_TABLE         default ses_api_chunks
 *   EMBED_MODEL      default text-embedding-3-small  (1536 dims)
 */
public final class RagConfig {

    public static final String TABLE = env("PG_TABLE", "ses_api_chunks");
    public static final String EMBED_MODEL = env("EMBED_MODEL", "text-embedding-3-small");

    private RagConfig() {}

    public static EmbeddingModel embeddingModel() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        return OpenAiEmbeddingModel.builder()
                .apiKey(key)
                .modelName(EMBED_MODEL)
                .build();
    }

    public static DataSource dataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + env("PG_HOST", "localhost")
                + ":" + env("PG_PORT", "5432")
                + "/" + env("PG_DATABASE", "postgres"));
        cfg.setUsername(env("PG_USER", "my_user"));
        cfg.setPassword(env("PG_PASSWORD", "my_password"));
        cfg.setMaximumPoolSize(8);
        return new HikariDataSource(cfg);
    }

    /**
     * Build the PgVector store in HYBRID mode (vector + Postgres full-text,
     * fused with Reciprocal Rank Fusion). dimension must match the embedding
     * model: 1536 for text-embedding-3-small / -3-large is 3072.
     *
     * @param createFresh if true, drops & recreates the table (ingest only)
     */
    public static EmbeddingStore<TextSegment> store(DataSource ds, int dimension,
                                                    boolean createFresh) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(ds)
                .table(TABLE)
                .dimension(dimension)
                .createTable(true)
                .dropTableFirst(createFresh)
                // Hybrid: combines cosine vector search with tsvector keyword
                // search via RRF. Requires query TEXT (not just embedding) at
                // search time. 'english' stemming suits the API prose; switch
                // to 'simple' if you prefer exact token matching only.
                .searchMode(SearchMode.HYBRID)
                .textSearchConfig("english")
                .rrfK(60)
                .metadataStorageConfig(MetadataStorageConfig.combinedJsonb())
                .build();
    }

    static String env(String k, String dflt) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
