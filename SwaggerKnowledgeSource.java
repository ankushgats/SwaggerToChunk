package com.example.swaggerag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Swagger JSON knowledge source for an existing LangChain4j ingestion pipeline.
 *
 * Usage:
 *   List<String> urls = List.of(
 *       "https://internal-docs.example.com/api/ses-policies/swagger.json",
 *       "https://internal-docs.example.com/api/ses-incidents/swagger.json"
 *   );
 *   new SwaggerKnowledgeSource(urls, "https://apidocs.securitycloud.symantec.com/#/doc?id=ses_policies")
 *       .ingest();
 *
 * How it works:
 *   For each URL:
 *     1. Downloads the raw swagger JSON via HTTP
 *     2. Parses + resolves all $refs with swagger-parser
 *     3. Converts each endpoint+method into a self-contained TextSegment
 *     4. Splits oversized segments (~4000 chars) so every chunk fits embedding limits
 *     5. Calls storeEmbeddings(segments) — fill that method from your existing repo
 *
 * To add auth headers (Bearer token, API key, etc.), add them in buildRequest().
 */
public class SwaggerKnowledgeSource {

    private static final Logger LOG = Logger.getLogger(SwaggerKnowledgeSource.class.getName());

    private static final int CHUNK_TARGET   = 4000;  // chars per chunk
    private static final int MAX_DEPTH      = 12;    // schema text rendering depth

    private final List<String> urls;
    private final String portalBaseUrl;   // e.g. "https://.../#/doc?id=ses_policies"
                                          // set to "" if you have no portal deep links

    private final HttpClient httpClient;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SwaggerKnowledgeSource(List<String> urls, String portalBaseUrl) {
        this.urls          = List.copyOf(urls);
        this.portalBaseUrl = portalBaseUrl == null ? "" : portalBaseUrl;
        this.httpClient    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Downloads each swagger JSON, converts to TextSegments, and calls
     * storeEmbeddings() for each spec's chunks.
     */
    public void ingest() {
        for (String url : urls) {
            LOG.info("Processing swagger URL: " + url);
            try {
                String json      = download(url);
                OpenAPI api      = parse(json, url);
                List<TextSegment> segments = toSegments(api, url);
                LOG.info("  -> " + segments.size() + " chunks from " + url);
                storeEmbeddings(segments);
            } catch (Exception e) {
                LOG.warning("Failed to process " + url + ": " + e.getMessage());
                // continue with the next URL rather than aborting everything
            }
        }
    }

    // -------------------------------------------------------------------------
    // Method to fill in from your existing repo
    // -------------------------------------------------------------------------

    /**
     * Called once per swagger spec with all of its TextSegments ready to embed.
     *
     * Each segment contains:
     *   - segment.text()                       — natural-language endpoint description
     *                                            (embed this field only)
     *   - segment.metadata().getString("id")   — e.g. "GET /v1/policies"
     *   - segment.metadata().getString("path")
     *   - segment.metadata().getString("method")
     *   - segment.metadata().getString("summary")
     *   - segment.metadata().getString("tags")  — comma-joined
     *   - segment.metadata().getString("docUrl") — portal deep link (may be empty)
     *   - segment.metadata().getString("sourceUrl") — the URL this was downloaded from
     *
     * Replace this body with your existing embedding + store logic.
     */
    protected void storeEmbeddings(List<TextSegment> segments) {
        // TODO: add your existing embedding and vector-store ingestion code here
    }

    // -------------------------------------------------------------------------
    // Step 1: Download
    // -------------------------------------------------------------------------

    private String download(String url) throws Exception {
        HttpRequest request = buildRequest(url);
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    /**
     * Override or extend this method to add authentication headers
     * (Bearer token, API key, basic auth, etc.) required by your internal portals.
     *
     * Example:
     *   return HttpRequest.newBuilder(URI.create(url))
     *       .GET()
     *       .timeout(Duration.ofSeconds(30))
     *       .header("Authorization", "Bearer " + System.getenv("SWAGGER_TOKEN"))
     *       .header("Accept", "application/json")
     *       .build();
     */
    protected HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .build();
    }

    // -------------------------------------------------------------------------
    // Step 2: Parse
    // -------------------------------------------------------------------------

    private OpenAPI parse(String json, String sourceUrl) {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true);  // inlines all $refs; handles circular refs

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json, null, opts);
        if (result.getOpenAPI() == null) {
            throw new RuntimeException("swagger-parser returned null for " + sourceUrl
                    + ". Messages: " + result.getMessages());
        }
        if (result.getMessages() != null) {
            result.getMessages().forEach(m ->
                LOG.warning("  [parser warning] " + sourceUrl + ": " + m));
        }
        return result.getOpenAPI();
    }

    // -------------------------------------------------------------------------
    // Step 3: Convert spec -> TextSegments
    // -------------------------------------------------------------------------

    private List<TextSegment> toSegments(OpenAPI api, String sourceUrl) {
        String apiTitle  = api.getInfo() != null ? nz(api.getInfo().getTitle()) : "";
        String serverUrl = (api.getServers() != null && !api.getServers().isEmpty())
                ? nz(api.getServers().get(0).getUrl()) : "";

        List<TextSegment> result = new ArrayList<>();

        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            String   path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();

            List<Parameter> pathLevelParams = item.getParameters() != null
                    ? item.getParameters() : List.of();

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry
                    : item.readOperationsMap().entrySet()) {

                String    method = opEntry.getKey().name();
                Operation op     = opEntry.getValue();

                List<Parameter> params = new ArrayList<>(pathLevelParams);
                if (op.getParameters() != null) params.addAll(op.getParameters());

                String docUrl  = makeDocUrl(portalBaseUrl, method, path);
                String text    = opToText(path, method, op, params, api, docUrl);
                String apiHdr  = apiTitle.isEmpty() ? ""
                        : "API: " + apiTitle + "\nBase URL: " + serverUrl + "\n\n";
                String full    = apiHdr + text;
                String opId    = method + " " + path;

                if (full.length() <= CHUNK_TARGET * 1.5) {
                    result.add(buildSegment(opId, full, path, method, op, docUrl, sourceUrl, 0, 1));
                } else {
                    // Repeat endpoint header on each continuation chunk so every
                    // chunk is self-contained for retrieval.
                    String contHdr = apiHdr
                            + "API Endpoint: " + method + " " + path
                            + "\nSummary: " + oneLine(op.getSummary())
                            + "\n(continued)\n\n";
                    List<String> pieces = splitOversized(full);
                    for (int i = 0; i < pieces.size(); i++) {
                        String ctext = (i == 0) ? pieces.get(i) : contHdr + pieces.get(i);
                        result.add(buildSegment(
                                opId + " [part " + (i + 1) + "/" + pieces.size() + "]",
                                ctext, path, method, op, docUrl, sourceUrl,
                                i + 1, pieces.size()));
                    }
                }
            }
        }
        return result;
    }

    private TextSegment buildSegment(String id, String text,
                                     String path, String method, Operation op,
                                     String docUrl, String sourceUrl,
                                     int chunk, int chunksTotal) {
        Metadata md = new Metadata();
        md.put("id",         id);
        md.put("path",       path);
        md.put("method",     method);
        md.put("summary",    oneLine(op.getSummary()));
        md.put("operationId", nz(op.getOperationId()));
        md.put("sourceUrl",  sourceUrl);
        md.put("docUrl",     docUrl);
        if (op.getTags() != null && !op.getTags().isEmpty()) {
            md.put("tags", String.join(",", op.getTags()));
        }
        if (chunksTotal > 1) {
            md.put("chunk",       chunk);
            md.put("chunksTotal", chunksTotal);
        }
        return TextSegment.from(text, md);
    }

    // -------------------------------------------------------------------------
    // Operation -> text
    // -------------------------------------------------------------------------

    private String opToText(String path, String method, Operation op,
                            List<Parameter> params, OpenAPI api, String docUrl) {
        List<String> parts = new ArrayList<>();
        String summary = oneLine(op.getSummary());
        String desc    = oneLine(op.getDescription());

        parts.add("API Endpoint: " + method + " " + path);
        if (!summary.isEmpty()) parts.add("Summary: " + summary);
        if (op.getTags() != null && !op.getTags().isEmpty()) {
            parts.add("Tags: " + String.join(", ", op.getTags()));
        }
        if (!desc.isEmpty() && !desc.equals(summary)) {
            parts.add("Description: " + desc);
        }

        List<SecurityRequirement> sec = op.getSecurity() != null
                ? op.getSecurity() : api.getSecurity();
        if (sec != null && !sec.isEmpty()) {
            Set<String> schemes = new TreeSet<>();
            sec.forEach(s -> schemes.addAll(s.keySet()));
            parts.add("Authentication: " + String.join(", ", schemes));
        }

        if (!params.isEmpty()) {
            StringBuilder sb = new StringBuilder("Parameters:");
            for (Parameter p : params) {
                List<String> bits = new ArrayList<>();
                bits.add(nz(p.getIn(), "?"));
                bits.add(p.getSchema() != null ? nz(p.getSchema().getType(), "string") : "string");
                if (Boolean.TRUE.equals(p.getRequired())) bits.add("required");
                sb.append("\n- ").append(p.getName())
                  .append(" (").append(String.join(", ", bits)).append(")");
                String d = oneLine(p.getDescription());
                if (!d.isEmpty()) sb.append(": ").append(d);
            }
            parts.add(sb.toString());
        }

        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null && !rb.getContent().isEmpty()) {
            Map.Entry<String, MediaType> first = rb.getContent().entrySet().iterator().next();
            String bodyTxt = schemaToText(first.getValue().getSchema(), 0, 0,
                    new IdentityHashMap<>());
            String req = Boolean.TRUE.equals(rb.getRequired()) ? " (required)" : "";
            parts.add("Request Body (" + first.getKey() + ")" + req + ":\n"
                    + (bodyTxt.isEmpty() ? "- (no schema)" : bodyTxt));
        }

        if (op.getResponses() != null) {
            StringBuilder sb = new StringBuilder("Responses:");
            for (Map.Entry<String, ApiResponse> re : op.getResponses().entrySet()) {
                String code = re.getKey();
                sb.append("\n- ").append(code).append(": ")
                  .append(oneLine(re.getValue().getDescription()));
                Content content = re.getValue().getContent();
                if (content != null && !content.isEmpty() && code.startsWith("2")) {
                    Map.Entry<String, MediaType> first = content.entrySet().iterator().next();
                    String bodyTxt = schemaToText(first.getValue().getSchema(), 1, 0,
                            new IdentityHashMap<>());
                    if (!bodyTxt.isEmpty()) {
                        sb.append("\n  Response schema (").append(first.getKey()).append("):\n")
                          .append(bodyTxt);
                    }
                }
            }
            parts.add(sb.toString());
        }

        if (!docUrl.isEmpty()) {
            parts.add("Documentation: " + docUrl);
        }

        return String.join("\n\n", parts);
    }

    // -------------------------------------------------------------------------
    // Schema -> compact text
    // -------------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private String schemaToText(Schema schema, int indent, int depth,
                                IdentityHashMap<Schema, Boolean> visiting) {
        if (schema == null || depth > MAX_DEPTH || indent > MAX_DEPTH) return "";
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            return "  ".repeat(indent) + "- (see schema: " + name + ")";
        }
        if (visiting.containsKey(schema)) {
            return "  ".repeat(indent) + "- (circular schema)";
        }
        visiting.put(schema, Boolean.TRUE);
        try {
            String pad = "  ".repeat(indent);
            List<String> lines = new ArrayList<>();

            if (schema.getAllOf() != null) { lines.add(pad + "(allOf:)"); schema.getAllOf().forEach(s -> { String t = schemaToText((Schema)s, indent+1, depth+1, visiting); if(!t.isEmpty()) lines.add(t); }); return String.join("\n", lines); }
            if (schema.getOneOf() != null) { lines.add(pad + "(oneOf:)"); schema.getOneOf().forEach(s -> { String t = schemaToText((Schema)s, indent+1, depth+1, visiting); if(!t.isEmpty()) lines.add(t); }); return String.join("\n", lines); }
            if (schema.getAnyOf() != null) { lines.add(pad + "(anyOf:)"); schema.getAnyOf().forEach(s -> { String t = schemaToText((Schema)s, indent+1, depth+1, visiting); if(!t.isEmpty()) lines.add(t); }); return String.join("\n", lines); }

            Map<String, Schema> props = schema.getProperties();
            if (props != null) {
                Set<String> required = schema.getRequired() != null
                        ? Set.copyOf(schema.getRequired()) : Set.of();
                for (Map.Entry<String, Schema> pe : props.entrySet()) {
                    String name = pe.getKey();
                    Schema prop = pe.getValue();
                    if (prop == null) continue;

                    String ptype = nz(prop.getType(), "object");
                    Schema itemSchema = null;
                    if (prop instanceof ArraySchema as && as.getItems() != null) {
                        itemSchema = as.getItems();
                        ptype = "array of " + nz(itemSchema.getType(), "object");
                    } else if ("array".equals(ptype) && prop.getItems() != null) {
                        itemSchema = prop.getItems();
                        ptype = "array of " + nz(itemSchema.getType(), "object");
                    }

                    List<String> bits = new ArrayList<>();
                    bits.add(ptype);
                    if (required.contains(name)) bits.add("required");
                    if (prop.getEnum() != null && !prop.getEnum().isEmpty()) {
                        List<String> evs = new ArrayList<>();
                        for (int i = 0; i < Math.min(12, prop.getEnum().size()); i++)
                            evs.add(String.valueOf(prop.getEnum().get(i)));
                        bits.add("one of: " + String.join(", ", evs));
                    }

                    StringBuilder line = new StringBuilder(pad)
                            .append("- ").append(name)
                            .append(" (").append(String.join(", ", bits)).append(")");
                    String d = oneLine(prop.getDescription());
                    if (!d.isEmpty()) line.append(": ").append(d);
                    lines.add(line.toString());

                    Schema nested = prop.getProperties() != null ? prop : itemSchema;
                    if (nested != null && nested.getProperties() != null)
                        lines.add(schemaToText(nested, indent + 1, depth + 1, visiting));
                }
            } else if ("array".equals(schema.getType()) && schema.getItems() != null) {
                lines.add(pad + "(array)");
                lines.add(schemaToText(schema.getItems(), indent + 1, depth + 1, visiting));
            }
            return String.join("\n", lines);
        } finally {
            visiting.remove(schema);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String makeDocUrl(String portalBaseUrl, String method, String path) {
        if (portalBaseUrl == null || portalBaseUrl.isEmpty()) return "";
        return portalBaseUrl + "&endpoint=" + method + "-"
                + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private static List<String> splitOversized(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (cur.length() + line.length() > CHUNK_TARGET && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.strip().replace("\n", " ");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String nz(String s, String dflt) { return s == null ? dflt : s; }
}
