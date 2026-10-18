import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * RouteAudit web server. Uses only the JDK (no Tomcat, no Maven, no database).
 *   GET  /              -> web/index.html (the website)
 *   GET  /api/state     -> all data + balances + suggested payments (JSON)
 *   GET  /api/export.csv-> download the ledger as CSV
 *   POST /api/group, /api/personal, /api/settle, /api/route, /api/reset
 */
public class Main {
    static final Ledger ledger = new Ledger();
    static final Path WEB = Paths.get("web").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        int port = 8080;                                              // default for your own computer
        if (System.getenv("PORT") != null) port = Integer.parseInt(System.getenv("PORT"));  // hosting sites (Render) give the port here
        if (args.length > 0) port = Integer.parseInt(args[0]);
        if (!Storage.load(ledger)) { ledger.seedDemo(); Storage.save(ledger); }   // first run: start with demo data
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.start();
        System.out.println("RouteAudit is running. Open http://localhost:" + port + " in your browser. (Ctrl+C to stop)");
    }

    // ---------- API ----------
    static void handleApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        try {
            if (method.equals("GET") && path.equals("/api/state")) {
                synchronized (ledger) { send(ex, 200, "application/json; charset=UTF-8", ledger.toJson()); }
                return;
            }
            if (method.equals("GET") && path.equals("/api/export.csv")) {
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"routeaudit-ledger.csv\"");
                synchronized (ledger) { send(ex, 200, "text/csv; charset=UTF-8", ledger.toCsv()); }
                return;
            }
            if (!method.equals("POST")) { send(ex, 404, "application/json", "{\"error\":\"Not found\"}"); return; }
            Map<String, String> form = readForm(ex);
            synchronized (ledger) {                                  // one request changes data at a time
                switch (path) {
                    case "/api/group":    ledger.addGroup(form); break;
                    case "/api/personal": ledger.addPersonal(form); break;
                    case "/api/settle":   ledger.addSettlement(form); break;
                    case "/api/route":    ledger.addRoute(form); break;
                    case "/api/reset":
                        if (!"true".equals(System.getenv("ALLOW_RESET"))) throw new IllegalArgumentException("Reset is disabled.");
                        ledger.seedDemo(); break;
                    default: send(ex, 404, "application/json", "{\"error\":\"Not found\"}"); return;
                }
                Storage.save(ledger);                                // write to disk after every change
                send(ex, 200, "application/json; charset=UTF-8", ledger.toJson());
            }
        } catch (IllegalArgumentException e) {                       // validation problem -> friendly message
            send(ex, 400, "application/json; charset=UTF-8", "{\"error\":" + Json.str(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", "{\"error\":\"Server error\"}");
        }
    }

    static Map<String, String> readForm(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096]; int n;
        InputStream in = ex.getRequestBody();
        while ((n = in.read(chunk)) > 0 && buf.size() < 100000) buf.write(chunk, 0, n);
        String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<String, String>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return map;
    }

    // ---------- static files (the website) ----------
    static void handleStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path file = WEB.resolve(p.substring(1)).normalize();
        if (!file.startsWith(WEB) || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "404 Not found"); return; }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=UTF-8" : name.endsWith(".css") ? "text/css"
                : name.endsWith(".js") ? "application/javascript" : name.endsWith(".png") ? "image/png"
                : name.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    static void send(HttpExchange ex, int code, String type, String text) throws IOException { send(ex, code, type, text.getBytes(StandardCharsets.UTF_8)); }
    static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody(); os.write(data); os.close();
    }
}
