import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * WebCrawler - BFS-based crawler that collects all URLs within a domain.
 *
 * Usage: java -cp ".:jsoup-*.jar" WebCrawler <startUrl> [maxPages] [outputFile]
 *
 * Example:
 *   java -cp ".:jsoup-1.17.2.jar" WebCrawler https://example.com 500 urls.txt
 *
 * Dependencies:
 *   - jsoup (https://jsoup.org) — add jsoup-*.jar to classpath
 *     Maven: <dependency>
 *              <groupId>org.jsoup</groupId>
 *              <artifactId>jsoup</artifactId>
 *              <version>1.17.2</version>
 *            </dependency>
 */
public class WebCrawler {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------
    private static final int    DEFAULT_MAX_PAGES      = 1000;
    private static final int    CONNECTION_TIMEOUT_MS  = 10_000;
    private static final int    READ_TIMEOUT_MS        = 10_000;
    private static final long   CRAWL_DELAY_MS         = 500;   // polite delay between requests
    private static final String USER_AGENT             =
        "Mozilla/5.0 (compatible; CompanyCrawler/1.0; +https://yourcompany.com/bot)";

    // File extensions to capture but NOT crawl for further links
    private static final Set<String> FILE_EXTENSIONS = new HashSet<>(Arrays.asList(
        // Documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "csv", "txt", "rtf",
        // Images
        "jpg", "jpeg", "png", "gif", "svg", "webp", "ico", "bmp", "tiff",
        // Audio / Video
        "mp3", "mp4", "wav", "ogg", "avi", "mov", "wmv", "flv", "webm",
        // Archives
        "zip", "tar", "gz", "rar", "7z",
        // Code / Data
        "json", "xml", "yaml", "yml", "js", "css",
        // Other
        "exe", "dmg", "pkg", "deb", "rpm"
    ));

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final String          baseDomain;
    private final String          baseScheme;
    private final int             maxPages;
    private final String          outputFile;

    /** All discovered URLs (HTML pages + files), with their metadata. */
    private final Map<String, UrlRecord> discovered = new ConcurrentHashMap<>();

    /** BFS queue — HTML pages left to crawl. */
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    /** Stats */
    private int crawledCount  = 0;
    private int skippedCount  = 0;
    private int errorCount    = 0;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WebCrawler <startUrl> [maxPages] [outputFile]");
            System.err.println("Example: WebCrawler https://example.com 500 urls.txt");
            System.exit(1);
        }

        String startUrl    = args[0].trim();
        int    maxPages    = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_MAX_PAGES;
        String outputFile  = args.length >= 3 ? args[2] : "crawled_urls.txt";

        try {
            WebCrawler crawler = new WebCrawler(startUrl, maxPages, outputFile);
            crawler.crawl();
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public WebCrawler(String startUrl, int maxPages, String outputFile) throws URISyntaxException {
        URI uri = new URI(startUrl);
        this.baseDomain  = uri.getHost();
        this.baseScheme  = uri.getScheme();
        this.maxPages    = maxPages;
        this.outputFile  = outputFile;

        // Seed the queue
        String normalised = normalizeUrl(startUrl);
        queue.add(normalised);
        discovered.put(normalised, new UrlRecord(normalised, "text/html", "SEED", 0));

        System.out.println("==============================================");
        System.out.println("  Web Crawler");
        System.out.println("==============================================");
        System.out.printf("  Start URL  : %s%n", startUrl);
        System.out.printf("  Domain     : %s%n", baseDomain);
        System.out.printf("  Max pages  : %d%n", maxPages);
        System.out.printf("  Output     : %s%n", outputFile);
        System.out.println("==============================================");
    }

    // -----------------------------------------------------------------------
    // Main crawl loop (BFS)
    // -----------------------------------------------------------------------
    public void crawl() {
        long startTime = System.currentTimeMillis();

        while (!queue.isEmpty() && crawledCount < maxPages) {
            String url = queue.poll();

            System.out.printf("[%4d/%4d] Crawling: %s%n", crawledCount + 1, maxPages, url);

            try {
                processPage(url);
                crawledCount++;
                Thread.sleep(CRAWL_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                errorCount++;
                discovered.get(url).status = "ERROR: " + e.getMessage();
                System.err.printf("  [ERROR] %s — %s%n", url, e.getMessage());
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        printSummary(elapsed);
        saveResults();
    }

    // -----------------------------------------------------------------------
    // Fetch a page, extract links, classify each one
    // -----------------------------------------------------------------------
    private void processPage(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
            .userAgent(USER_AGENT)
            .timeout(CONNECTION_TIMEOUT_MS)
            .followRedirects(true)
            .get();

        // Update content type for this page
        UrlRecord thisRecord = discovered.get(pageUrl);
        if (thisRecord != null) {
            thisRecord.contentType = "text/html";
            thisRecord.status      = "OK";
            thisRecord.pageTitle   = doc.title();
        }

        // Collect all href and src attributes
        Set<String> rawLinks = new LinkedHashSet<>();
        for (Element el : doc.select("a[href]"))   rawLinks.add(el.attr("abs:href"));
        for (Element el : doc.select("link[href]")) rawLinks.add(el.attr("abs:href"));
        for (Element el : doc.select("img[src]"))   rawLinks.add(el.attr("abs:src"));
        for (Element el : doc.select("script[src]"))rawLinks.add(el.attr("abs:src"));
        for (Element el : doc.select("source[src]"))rawLinks.add(el.attr("abs:src"));

        for (String raw : rawLinks) {
            classifyAndEnqueue(raw, pageUrl);
        }
    }

    // -----------------------------------------------------------------------
    // Decide what to do with each discovered URL
    // -----------------------------------------------------------------------
    private void classifyAndEnqueue(String rawUrl, String foundOnPage) {
        if (rawUrl == null || rawUrl.isBlank()) return;

        // Strip fragment (#section)
        rawUrl = rawUrl.split("#")[0].trim();
        if (rawUrl.isBlank()) return;

        // Only http / https
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return;

        String url;
        try {
            url = normalizeUrl(rawUrl);
        } catch (Exception e) {
            return; // malformed — skip
        }

        // Already seen?
        if (discovered.containsKey(url)) return;

        // Must be within our domain
        if (!isSameDomain(url)) {
            skippedCount++;
            return;
        }

        String ext = getExtension(url);
        boolean isFile = FILE_EXTENSIONS.contains(ext);

        // Detect content type for file URLs via HEAD request
        String contentType = isFile ? detectContentType(url) : "text/html";

        UrlRecord record = new UrlRecord(url, contentType, foundOnPage, discovered.size());
        record.isFile    = isFile;
        record.extension = ext.isEmpty() ? "html" : ext;

        discovered.put(url, record);

        if (!isFile) {
            // HTML page — add to BFS queue
            queue.add(url);
        } else {
            // File — record only, don't crawl
            record.status = "FILE (not crawled)";
            System.out.printf("         [FILE] %s (%s)%n", url, ext.toUpperCase());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isSameDomain(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            // Accept exact match and all subdomains
            return host.equals(baseDomain) || host.endsWith("." + baseDomain);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String normalizeUrl(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String path = uri.getPath();
        // Remove trailing slash (except root)
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Drop query string and fragment; lowercase host
        return new URI(
            uri.getScheme().toLowerCase(),
            uri.getHost().toLowerCase(),
            path,
            null, // query — stripped intentionally; adjust if query params matter on your site
            null
        ).toString();
    }

    private String getExtension(String url) {
        try {
            String path = new URI(url).getPath();
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash && dot < path.length() - 1) {
                return path.substring(dot + 1).toLowerCase();
            }
        } catch (URISyntaxException ignored) {}
        return "";
    }

    private String detectContentType(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.connect();
            String ct = conn.getContentType();
            conn.disconnect();
            return ct != null ? ct : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------
    private void printSummary(long elapsedSeconds) {
        System.out.println("\n==============================================");
        System.out.println("  Crawl Complete");
        System.out.println("==============================================");
        System.out.printf("  Pages crawled   : %d%n", crawledCount);
        System.out.printf("  Total URLs found: %d%n", discovered.size());
        System.out.printf("  Errors          : %d%n", errorCount);
        System.out.printf("  Skipped (ext.)  : %d%n", skippedCount);
        System.out.printf("  Time elapsed    : %ds%n", elapsedSeconds);

        // Breakdown by type
        Map<String, Long> byExt = new TreeMap<>();
        for (UrlRecord r : discovered.values()) {
            byExt.merge(r.extension.toLowerCase(), 1L, Long::sum);
        }
        System.out.println("\n  URL breakdown by type:");
        byExt.forEach((ext, count) -> System.out.printf("    %-10s : %d%n", ext, count));
        System.out.println("==============================================");
    }

    private void saveResults() {
        try (PrintWriter out = new PrintWriter(new FileWriter(outputFile))) {
            out.println("# Web Crawler Results");
            out.println("# Generated : " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            out.println("# Domain    : " + baseDomain);
            out.println("# Total URLs: " + discovered.size());
            out.println("#");
            out.printf("%-6s | %-8s | %-12s | %-50s | %-40s | %s%n",
                "SEQ", "TYPE", "CONTENT-TYPE", "URL", "FOUND-ON", "PAGE-TITLE");
            out.println("-".repeat(180));

            List<UrlRecord> sorted = new ArrayList<>(discovered.values());
            sorted.sort(Comparator.comparingInt(r -> r.sequence));

            for (UrlRecord r : sorted) {
                out.printf("%-6d | %-8s | %-12s | %-50s | %-40s | %s%n",
                    r.sequence,
                    r.isFile ? "FILE" : "PAGE",
                    truncate(r.contentType, 12),
                    r.url,
                    truncate(r.foundOnPage, 40),
                    r.pageTitle != null ? r.pageTitle : ""
                );
            }

            System.out.println("\nResults saved to: " + outputFile);
        } catch (IOException e) {
            System.err.println("Failed to save results: " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // -----------------------------------------------------------------------
    // Data class
    // -----------------------------------------------------------------------
    static class UrlRecord {
        final String url;
        final String foundOnPage;
        final int    sequence;
        String contentType;
        String status;
        String pageTitle;
        String extension = "html";
        boolean isFile   = false;

        UrlRecord(String url, String contentType, String foundOnPage, int sequence) {
            this.url         = url;
            this.contentType = contentType;
            this.foundOnPage = foundOnPage;
            this.sequence    = sequence;
        }
    }
}
