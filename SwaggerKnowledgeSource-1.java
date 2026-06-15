package com.example.swaggerag;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Accepts a list of API documentation HTML page URLs (Angular SPA pages).
 * For each page it:
 *   1. Opens the page in a headless Chromium browser (via Playwright)
 *   2. Waits for Angular to finish rendering
 *   3. Finds the  <a class="doc-spec-link" download="...">  anchor
 *   4. Reads the blob: URL content via in-browser JavaScript (fetch)
 *   5. Parses the swagger JSON + resolves all $refs
 *   6. Converts each endpoint+method into a TextSegment
 *   7. Calls storeEmbeddings(segments) — fill that method from your existing repo
 *
 * Usage:
 *   List<String> pageUrls = List.of(
 *       "https://apidocs.securitycloud.symantec.com/#/doc?id=ses_policies",
 *       "https://apidocs.securitycloud.symantec.com/#/doc?id=ses_incidents"
 *   );
 *   new SwaggerKnowledgeSource(pageUrls, "https://apidocs.securitycloud.symantec.com/#/doc?id=ses_policies")
 *       .ingest();
 *
 * Maven dependency to add:
 *   <dependency>
 *       <groupId>com.microsoft.playwright</groupId>
 *       <artifactId>playwright</artifactId>
 *       <version>1.44.0</version>
 *   </dependency>
 *
 * First run: Playwright downloads Chromium automatically (~300 MB).
 * To pre-download: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
 */
public class SwaggerKnowledgeSource {

    private static final Logger LOG = Logger.getLogger(SwaggerKnowledgeSource.class.getName());

    private static final int CHUNK_TARGET = 4000; // chars per chunk
    private static final int MAX_DEPTH    = 12;   // schema text rendering depth

    // CSS selector for the download anchor on the Symantec API portal.
    // Change this if you use a different portal that has a different selector.
    private static final String DOWNLOAD_ANCHOR_SELECTOR = "a.doc-spec-link";

    // How long to wait (ms) for Angular to render the download anchor.
    private static final double SELECTOR_TIMEOUT_MS = 15_000;

    private final List<String> pageUrls;    // HTML documentation page URLs
    private final String portalBaseUrl;     // used to generate per-endpoint deep links

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param pageUrls      list of API portal HTML page URLs, one per API category
     * @param portalBaseUrl base URL for generating per-endpoint deep links, e.g.
     *                      "https://apidocs.securitycloud.symantec.com/#/doc?id=ses_policies"
     *                      Pass "" if you don't need deep links.
     */
    public SwaggerKnowledgeSource(List<String> pageUrls, String portalBaseUrl) {
        this.pageUrls     = List.copyOf(pageUrls);
        this.portalBaseUrl = portalBaseUrl == null ? "" : portalBaseUrl;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Opens a single headless browser session, iterates all page URLs,
     * and processes each swagger JSON in turn.
     * Failures on individual pages are logged and skipped so one bad page
     * doesn't abort the rest.
     */
    public void ingest() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            for (String pageUrl : pageUrls) {
                LOG.info("Processing page: " + pageUrl);
                try {
                    String   json     = fetchJsonFromPage(page, pageUrl);
                    OpenAPI  api      = parse(json, pageUrl);
                    List<TextSegment> segments = toSegments(api, pageUrl);
                    LOG.info("  -> " + segments.size() + " chunks");
                    storeEmbeddings(segments);
                } catch (Exception e) {
                    LOG.warning("Failed to process " + pageUrl + ": " + e.getMessage());
                }
            }
            context.close();
            browser.close();
        }
    }

    // -------------------------------------------------------------------------
    // Step 1: fetch JSON from the HTML page via Playwright
    // -------------------------------------------------------------------------

    /**
     * Navigates to the documentation page, waits for Angular to render,
     * then executes JavaScript inside the browser to read the blob: URL
     * content — the only way to access blob: URLs since they exist only
     * inside the browser session that created them.
     */
    private String fetchJsonFromPage(Page page, String pageUrl) {
        // Navigate; NETWORKIDLE waits until no network activity for 500ms,
        // which is usually enough for Angular's initial data fetch.
        page.navigate(pageUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE));

        // Wait for Angular to render the download anchor.
        page.waitForSelector(DOWNLOAD_ANCHOR_SELECTOR,
                new Page.WaitForSelectorOptions().setTimeout(SELECTOR_TIMEOUT_MS));

        // Read the filename for logging (from the download="..." attribute).
        String filename = (String) page.evaluate(
                "document.querySelector('" + DOWNLOAD_ANCHOR_SELECTOR + "')"
                + ".getAttribute('download')");
        LOG.info("  found download link: " + filename);

        // Fetch the blob: URL content inside the browser context.
        // This is the only way to read a blob: URL — it only exists in the
        // browser session that created it, so we use in-browser fetch().
        String json = (String) page.evaluate(
                "async () => {" +
                "  const anchor   = document.querySelector('" + DOWNLOAD_ANCHOR_SELECTOR + "');" +
                "  const blobUrl  = anchor.href;" +
                "  const response = await fetch(blobUrl);" +
                "  return await response.text();" +
                "}");

        if (json == null || json.isBlank()) {
            throw new RuntimeException("Fetched empty JSON from page: " + pageUrl);
        }
        return json;
    }

    // -------------------------------------------------------------------------
    // Fill in from your existing repo
    // -------------------------------------------------------------------------

    /**
     * Called once per swagger spec with all its TextSegments ready to embed.
     *
     * Each TextSegment contains:
     *   segment.text()                            — embed this field only
     *   segment.metadata().getString("id")        — e.g. "GET /v1/policies"
     *   segment.metadata().getString("path")
     *   segment.metadata().getString("method")
     *   segment.metadata().getString("summary")
     *   segment.metadata().getString("tags")      — comma-joined
     *   segment.metadata().getString("docUrl")    — portal deep link (may be empty)
     *   segment.metadata().getString("sourceUrl") — the HTML page URL it came from
     */
    protected void storeEmbeddings(List<TextSegment> segments) {
        // TODO: add your existing embedding and vector-store ingestion code here
    }

    // -------------------------------------------------------------------------
    // Step 2: parse swagger JSON string
    // -------------------------------------------------------------------------

    private OpenAPI parse(String json, String sourceUrl) {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true); // inlines all $refs; handles circular refs

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
    // Step 3: convert spec -> TextSegments
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

                String docUrl = makeDocUrl(portalBaseUrl, method, path);
                String text   = opToText(path, method, op, params, api, docUrl);
                String apiHdr = apiTitle.isEmpty() ? ""
                        : "API: " + apiTitle + "\nBase URL: " + serverUrl + "\n\n";
                String full   = apiHdr + text;
                String opId   = method + " " + path;

                if (full.length() <= CHUNK_TARGET * 1.5) {
                    result.add(buildSegment(opId, full, path, method, op,
                            docUrl, sourceUrl, 0, 1));
                } else {
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
        md.put("id",          id);
        md.put("path",        path);
        md.put("method",      method);
        md.put("summary",     oneLine(op.getSummary()));
        md.put("operationId", nz(op.getOperationId()));
        md.put("sourceUrl",   sourceUrl);
        md.put("docUrl",      docUrl);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
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

            if (schema.getAllOf() != null) {
                lines.add(pad + "(allOf:)");
                schema.getAllOf().forEach(s -> { String t = schemaToText((Schema) s, indent + 1, depth + 1, visiting); if (!t.isEmpty()) lines.add(t); });
                return String.join("\n", lines);
            }
            if (schema.getOneOf() != null) {
                lines.add(pad + "(oneOf:)");
                schema.getOneOf().forEach(s -> { String t = schemaToText((Schema) s, indent + 1, depth + 1, visiting); if (!t.isEmpty()) lines.add(t); });
                return String.join("\n", lines);
            }
            if (schema.getAnyOf() != null) {
                lines.add(pad + "(anyOf:)");
                schema.getAnyOf().forEach(s -> { String t = schemaToText((Schema) s, indent + 1, depth + 1, visiting); if (!t.isEmpty()) lines.add(t); });
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

    private static String nz(String s)           { return s == null ? "" : s; }
    private static String nz(String s, String d) { return s == null ? d  : s; }
}
