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
 *   GET  /                 -> web/index.html (the website)
 *   POST /api/register, /api/login, /api/logout, GET /api/me      -> accounts
 *   GET  /api/state, /api/export.csv                               -> your data (login required)
 *   POST /api/group, /api/personal, /api/settle, /api/route, /api/reset
 * Every account has its own private ledger.
 */
public class Main {
    static final Object LOCK = new Object();                       // one request at a time touches the data
    static final Auth auth = new Auth();
    static final Map<Long, Ledger> ledgers = new HashMap<Long, Ledger>();
    static final Path WEB = Paths.get("web").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        int port = 8080;                                              // default for your own computer
        if (System.getenv("PORT") != null) port = Integer.parseInt(System.getenv("PORT"));  // hosting sites (Render) give the port here
        if (args.length > 0) port = Integer.parseInt(args[0]);
        auth.load();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.start();
        System.out.println("RouteAudit is running. Open http://localhost:" + port + " in your browser. (Ctrl+C to stop)");
    }

    /** Gets (or loads, or creates with demo data) the ledger that belongs to one user. */
    static Ledger ledgerOf(long uid) throws IOException {
        Ledger l = ledgers.get(uid);
        if (l == null) {
            l = new Ledger();
            if (!Storage.load(l, uid)) { l.seedDemo(); Storage.save(l, uid); }   // new account starts with sample data
            ledgers.put(uid, l);
        }
        return l;
    }

    // ---------- API ----------
    static void handleApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        try {
            Map<String, String> form = method.equals("POST") ? readForm(ex) : new HashMap<String, String>();
            synchronized (LOCK) {
                // ----- accounts (no login needed) -----
                if (method.equals("POST") && (path.equals("/api/register") || path.equals("/api/login"))) {
                    Auth.User u = path.equals("/api/register")
                            ? auth.register(form.get("name"), form.get("email"), form.get("password"))
                            : auth.login(form.get("email"), form.get("password"));
                    String token = auth.newSession(u.id);
                    ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800" + secure(ex));
                    send(ex, 200, "application/json; charset=UTF-8", userJson(u));
                    return;
                }
                if (method.equals("POST") && path.equals("/api/logout")) {
                    auth.endSession(cookie(ex));
                    ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; Max-Age=0");
                    send(ex, 200, "application/json", "{\"ok\":true}");
                    return;
                }
                // ----- everything below needs a logged-in user -----
                Auth.User user = auth.byToken(cookie(ex));
                if (user == null) { send(ex, 401, "application/json; charset=UTF-8", "{\"error\":\"Please log in.\"}"); return; }
                Ledger ledger = ledgerOf(user.id);

                if (method.equals("GET") && path.equals("/api/me")) { send(ex, 200, "application/json; charset=UTF-8", userJson(user)); return; }
                if (method.equals("GET") && path.equals("/api/state")) { send(ex, 200, "application/json; charset=UTF-8", ledger.toJson()); return; }
                if (method.equals("GET") && path.equals("/api/export.csv")) {
                    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"routeaudit-ledger.csv\"");
                    send(ex, 200, "text/csv; charset=UTF-8", ledger.toCsv());
                    return;
                }
                if (!method.equals("POST")) { send(ex, 404, "application/json", "{\"error\":\"Not found\"}"); return; }
                switch (path) {
                    case "/api/group":    ledger.addGroup(form); break;
                    case "/api/personal": ledger.addPersonal(form); break;
                    case "/api/settle":   ledger.addSettlement(form); break;
                    case "/api/route":    ledger.addRoute(form); break;
                    case "/api/reset":    ledger.seedDemo(); break;
                    default: send(ex, 404, "application/json", "{\"error\":\"Not found\"}"); return;
                }
                Storage.save(ledger, user.id);                       // write to disk after every change
                send(ex, 200, "application/json; charset=UTF-8", ledger.toJson());
            }
        } catch (IllegalArgumentException e) {                       // validation problem -> friendly message
            send(ex, 400, "application/json; charset=UTF-8", "{\"error\":" + Json.str(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", "{\"error\":\"Server error\"}");
        }
    }

    static String userJson(Auth.User u) { return "{\"name\":" + Json.str(u.name) + ",\"email\":" + Json.str(u.email) + "}"; }
    static String secure(HttpExchange ex) { return "https".equals(ex.getRequestHeaders().getFirst("X-Forwarded-Proto")) ? "; Secure" : ""; }

    /** Reads the "session" cookie sent by the browser. */
    static String cookie(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers != null) for (String h : headers) for (String part : h.split(";")) {
            part = part.trim();
            if (part.startsWith("session=")) return part.substring(8);
        }
        return null;
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
