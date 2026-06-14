package com.example.swaggerag;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Step 1 of the swagger RAG pipeline (Java port).
 *
 * Parses an OpenAPI/Swagger JSON and emits one self-contained,
 * retrieval-friendly document per endpoint+method, with all $refs
 * resolved inline by swagger-parser (ResolveFully).
 *
 * Output: JSONL — one record per chunk:
 *   { "id", "text", "metadata": { path, method, tags, summary,
 *     operationId, sourceFile, chunk?, chunksTotal? } }
 *
 * Usage: java ... SwaggerToDocs <spec.json> <out.jsonl>
 *
 * Dependencies (Maven):
 *   io.swagger.parser.v3:swagger-parser:2.1.22
 *   com.fasterxml.jackson.core:jackson-databind:2.17.x
 */
public class SwaggerToDocs {

    /** Max chars per chunk when splitting oversized endpoint docs. */
    private static final int CHUNK_TARGET = 4000;
    /** Max schema nesting depth rendered into text (cycles are cut by ResolveFully). */
    private static final int MAX_RENDER_DEPTH = 12;

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: SwaggerToDocs <spec.json> <out.jsonl> [portalUrl]");
            System.err.println("  portalUrl example: https://apidocs.securitycloud.symantec.com/#/doc?id=ses_policies");
            System.exit(1);
        }
        String portalUrl = args.length == 3 ? args[2] : "";
        Path specPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);

        // ResolveFully inlines $refs — the equivalent of the Python resolve().
        // swagger-parser handles circular refs by leaving a $ref in place,
        // which the renderer below names instead of recursing forever.
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser()
                .readLocation(specPath.toAbsolutePath().toString(), null, opts);
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            System.err.println("Failed to parse spec: " + result.getMessages());
            System.exit(2);
        }
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            // Real-world specs are often slightly malformed; warn but continue.
            result.getMessages().forEach(m -> System.err.println("[parser warning] " + m));
        }

        String apiTitle = api.getInfo() != null ? nz(api.getInfo().getTitle()) : "";
        String serverUrl = (api.getServers() != null && !api.getServers().isEmpty())
                ? nz(api.getServers().get(0).getUrl()) : "";
        String sourceFile = specPath.getFileName().toString();

        List<Map<String, Object>> records = new ArrayList<>();

        for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
            String path = e.getKey();
            PathItem item = e.getValue();
            List<Parameter> pathLevelParams = item.getParameters() != null
                    ? item.getParameters() : List.of();

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry
                    : item.readOperationsMap().entrySet()) {
                String method = opEntry.getKey().name();          // GET, POST, ...
                Operation op = opEntry.getValue();

                // Merge path-level parameters ahead of operation-level ones
                List<Parameter> params = new ArrayList<>(pathLevelParams);
                if (op.getParameters() != null) params.addAll(op.getParameters());

                String text = opToText(path, method, op, params, api);
                String docUrl = makeDocUrl(portalUrl, method, path);
                if (!docUrl.isEmpty()) text += "\n\nDocumentation: " + docUrl;
                String apiHeader = apiTitle.isEmpty() ? ""
                        : "API: " + apiTitle + "\nBase URL: " + serverUrl + "\n\n";
                String full = apiHeader + text;

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("path", path);
                meta.put("method", method);
                meta.put("tags", op.getTags() != null ? op.getTags() : List.of());
                meta.put("summary", oneLine(op.getSummary()));
                meta.put("operationId", nz(op.getOperationId()));
                meta.put("sourceFile", sourceFile);
                meta.put("docUrl", docUrl);

                String opId = method + " " + path;

                if (full.length() <= CHUNK_TARGET * 1.5) {
                    records.add(record(opId, full, meta));
                } else {
                    String epHeader = apiHeader
                            + "API Endpoint: " + method + " " + path
                            + "\nSummary: " + oneLine(op.getSummary())
                            + "\n(continued)\n\n";
                    List<String> pieces = splitOversized(full);
                    for (int i = 0; i < pieces.size(); i++) {
                        String ctext = (i == 0) ? pieces.get(i) : epHeader + pieces.get(i);
                        Map<String, Object> cmeta = new LinkedHashMap<>(meta);
                        cmeta.put("chunk", i + 1);
                        cmeta.put("chunksTotal", pieces.size());
                        records.add(record(
                                opId + " [part " + (i + 1) + "/" + pieces.size() + "]",
                                ctext, cmeta));
                    }
                }
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outPath))) {
            for (Map<String, Object> r : records) {
                w.println(JSON.writeValueAsString(r));
            }
        }
        System.out.println("Wrote " + records.size() + " endpoint documents to " + outPath);
    }

    // ---------- operation -> retrieval-friendly text ----------

    private static String opToText(String path, String method, Operation op,
                                   List<Parameter> params, OpenAPI api) {
        List<String> parts = new ArrayList<>();
        String summary = oneLine(op.getSummary());
        String desc = oneLine(op.getDescription());

        parts.add("API Endpoint: " + method + " " + path);
        if (!summary.isEmpty()) parts.add("Summary: " + summary);
        if (op.getTags() != null && !op.getTags().isEmpty()) {
            parts.add("Tags: " + String.join(", ", op.getTags()));
        }
        if (!desc.isEmpty() && !desc.equals(summary)) {
            parts.add("Description: " + desc);
        }

        // security: operation-level overrides global
        List<SecurityRequirement> sec = op.getSecurity() != null
                ? op.getSecurity() : api.getSecurity();
        if (sec != null && !sec.isEmpty()) {
            Set<String> schemes = new TreeSet<>();
            sec.forEach(s -> schemes.addAll(s.keySet()));
            parts.add("Authentication: " + String.join(", ", schemes));
        }

        // parameters
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

        // request body (first content type is enough for retrieval text)
        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null && !rb.getContent().isEmpty()) {
            Map.Entry<String, MediaType> first = rb.getContent().entrySet().iterator().next();
            String bodyTxt = schemaToText(first.getValue().getSchema(), 0, 0,
                    new IdentityHashMap<>());
            String req = Boolean.TRUE.equals(rb.getRequired()) ? " (required)" : "";
            parts.add("Request Body (" + first.getKey() + ")" + req + ":\n"
                    + (bodyTxt.isEmpty() ? "- (no schema)" : bodyTxt));
        }

        // responses
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

        return String.join("\n\n", parts);
    }

    // ---------- schema -> compact human-readable text ----------

    @SuppressWarnings("rawtypes")
    private static String schemaToText(Schema schema, int indent, int depth,
                                       IdentityHashMap<Schema, Boolean> visiting) {
        if (schema == null || depth > MAX_RENDER_DEPTH) return "";
        // ResolveFully leaves unresolvable circular refs as $ref pointers,
        // and identity-based visit tracking guards against object cycles
        // ResolveFully can create by reusing the same Schema instance.
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

            // allOf / oneOf / anyOf combiners
            Map<String, List<Schema>> combiners = new HashMap<>();
            if (schema.getAllOf() != null) combiners.put("allOf", schema.getAllOf());
            if (schema.getOneOf() != null) combiners.put("oneOf", schema.getOneOf());
            if (schema.getAnyOf() != null) combiners.put("anyOf", schema.getAnyOf());
            if (!combiners.isEmpty()) {
                for (Map.Entry<String, List<Schema>> c : combiners.entrySet()) {
                    lines.add(pad + "(" + c.getKey() + ":)");
                    for (Schema sub : c.getValue()) {
                        String t = schemaToText(sub, indent + 1, depth + 1, visiting);
                        if (!t.isEmpty()) lines.add(t);
                    }
                }
                return String.join("\n", lines);
            }

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
                        List<?> ev = prop.getEnum();
                        List<String> evs = new ArrayList<>();
                        for (int i = 0; i < Math.min(12, ev.size()); i++) {
                            evs.add(String.valueOf(ev.get(i)));
                        }
                        bits.add("one of: " + String.join(", ", evs));
                    }

                    StringBuilder line = new StringBuilder(pad)
                            .append("- ").append(name)
                            .append(" (").append(String.join(", ", bits)).append(")");
                    String d = oneLine(prop.getDescription());
                    if (!d.isEmpty()) line.append(": ").append(d);
                    lines.add(line.toString());

                    // recurse into nested objects / array items
                    Schema nested = prop.getProperties() != null ? prop : itemSchema;
                    if (nested != null && nested.getProperties() != null) {
                        String t = schemaToText(nested, indent + 1, depth + 1, visiting);
                        if (!t.isEmpty()) lines.add(t);
                    }
                }
            } else if ("array".equals(schema.getType()) && schema.getItems() != null) {
                lines.add(pad + "(array)");
                String t = schemaToText(schema.getItems(), indent + 1, depth + 1, visiting);
                if (!t.isEmpty()) lines.add(t);
            }
            return String.join("\n", lines);
        } finally {
            visiting.remove(schema);
        }
    }

    // ---------- chunking ----------

    /** Split a large doc on line boundaries into ~CHUNK_TARGET-char pieces. */
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

    // ---------- helpers ----------

    private static Map<String, Object> record(String id, String text,
                                              Map<String, Object> meta) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("text", text);
        r.put("metadata", meta);
        return r;
    }

    /**
     * Deep link to the API portal anchored at this endpoint.
     * Pattern: {portalUrl}&endpoint={METHOD}-{url-encoded path}
     * e.g. ...&endpoint=PATCH-%2Fv1%2Fpolicies%2Fallow-list%2F%7Bpolicy_uid%7D...
     */
    private static String makeDocUrl(String portalUrl, String method, String path) {
        if (portalUrl == null || portalUrl.isEmpty()) return "";
        return portalUrl + "&endpoint=" + method
                + "-" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    /** Coerce possibly-null/malformed values to a clean one-line string. */
    private static String oneLine(String s) {
        return s == null ? "" : s.strip().replace("\n", " ");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String nz(String s, String dflt) { return s == null ? dflt : s; }
}
