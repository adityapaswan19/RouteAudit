import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Accounts and login sessions.
 * - Passwords are never stored. We store a random salt + a PBKDF2 hash of the password.
 * - After login the server gives the browser a random session token (cookie) and remembers it in memory.
 */
public class Auth {
    static class User { long id; String email, name, salt, hash; }

    static final Path FILE = Paths.get("data", "users.txt");
    final List<User> users = new ArrayList<User>();
    final Map<String, long[]> sessions = new HashMap<String, long[]>();   // sha256(token) -> {user id, created ms}
    static final Path SESSIONS = Paths.get("data", "sessions.txt");
    static final long SESSION_MS = 365L * 24 * 3600 * 1000;               // stay logged in for 1 year
    final Map<String, long[]> failures = new HashMap<String, long[]>();   // email -> {failed count, locked until (ms)}
    final SecureRandom random = new SecureRandom();
    long nextId = 1;

    // ---------- accounts ----------
    User register(String name, String email, String password) throws IOException {
        name = Ledger.clean(name, 40);
        email = Ledger.clean(email, 100).toLowerCase();
        if (name.isEmpty()) throw new IllegalArgumentException("Please enter your name.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("Please enter a valid email.");
        if (password == null || password.length() < 6 || password.length() > 100) throw new IllegalArgumentException("Password must be 6 to 100 characters.");
        if (find(email) != null) throw new IllegalArgumentException("An account with this email already exists.");
        User u = new User();
        u.id = nextId++; u.email = email; u.name = name;
        byte[] salt = new byte[16]; random.nextBytes(salt);
        u.salt = hex(salt); u.hash = hash(password, salt);
        users.add(u);
        save();
        return u;
    }

    User login(String email, String password) {
        email = Ledger.clean(email, 100).toLowerCase();
        long[] f = failures.get(email);
        if (f != null && f[1] > System.currentTimeMillis()) throw new IllegalArgumentException("Too many wrong attempts. Try again in a minute.");
        User u = find(email);
        boolean ok = u != null && password != null && MessageDigest.isEqual(hash(password, unhex(u.salt)).getBytes(StandardCharsets.UTF_8), u.hash.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            if (f == null) { f = new long[2]; failures.put(email, f); }
            if (++f[0] >= 5) { f[0] = 0; f[1] = System.currentTimeMillis() + 60000; }   // lock for 1 minute after 5 mistakes
            throw new IllegalArgumentException("Wrong email or password.");
        }
        failures.remove(email);
        return u;
    }

    User find(String email) { for (User u : users) if (u.email.equals(email)) return u; return null; }

    // ---------- sessions ----------
    /** Creates a random token for the browser. We only keep its hash, so a leaked file cannot be used to log in. */
    String newSession(long userId) throws IOException {
        byte[] b = new byte[32]; random.nextBytes(b);
        String token = hex(b);
        sessions.put(sha(token), new long[] { userId, System.currentTimeMillis() });
        saveSessions();
        return token;
    }
    void endSession(String token) throws IOException { if (token != null && sessions.remove(sha(token)) != null) saveSessions(); }
    User byToken(String token) throws IOException {
        if (token == null) return null;
        long[] s = sessions.get(sha(token));
        if (s == null) return null;
        if (System.currentTimeMillis() - s[1] > SESSION_MS) { sessions.remove(sha(token)); saveSessions(); return null; }
        for (User u : users) if (u.id == s[0]) return u;
        return null;
    }
    void saveSessions() throws IOException {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, long[]> e : sessions.entrySet()) lines.add(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1]);
        Files.createDirectories(Paths.get("data"));
        Path tmp = Paths.get("data", "sessions.tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, SESSIONS, StandardCopyOption.REPLACE_EXISTING);
    }
    static String sha(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- file ----------
    void save() throws IOException {
        List<String> lines = new ArrayList<String>();
        for (User u : users) lines.add(u.id + "\t" + u.email + "\t" + u.name + "\t" + u.salt + "\t" + u.hash);
        Files.createDirectories(Paths.get("data"));
        Path tmp = Paths.get("data", "users.tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
    }
    void load() throws IOException {
        if (Files.exists(SESSIONS)) for (String line : Files.readAllLines(SESSIONS, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", -1);
            if (p.length == 3) sessions.put(p[0], new long[] { Long.parseLong(p[1]), Long.parseLong(p[2]) });
        }
        if (!Files.exists(FILE)) return;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", -1);
            if (p.length < 5) continue;
            User u = new User(); u.id = Long.parseLong(p[0]); u.email = p[1]; u.name = p[2]; u.salt = p[3]; u.hash = p[4];
            users.add(u); nextId = Math.max(nextId, u.id + 1);
        }
    }

    // ---------- helpers ----------
    static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
    static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
}
