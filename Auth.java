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
    final Map<String, Long> sessions = new HashMap<String, Long>();       // token -> user id
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
    String newSession(long userId) { byte[] b = new byte[32]; random.nextBytes(b); String t = hex(b); sessions.put(t, userId); return t; }
    void endSession(String token) { if (token != null) sessions.remove(token); }
    User byToken(String token) {
        Long id = token == null ? null : sessions.get(token);
        if (id == null) return null;
        for (User u : users) if (u.id == id) return u;
        return null;
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
