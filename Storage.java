import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Saves the Ledger to a plain text file (data/ledger.txt) and reads it back.
 * One line = one record. Fields are separated by TAB. First letter tells the record type:
 * F friend, G group expense, P personal expense, S settlement, R route comparison.
 */
public class Storage {
    static final Path FILE = Paths.get("data", "ledger.txt");

    static void save(Ledger l) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (Friend f : l.friends) lines.add(join("F", f.name, f.role));
        for (GroupExpense e : l.groups)
            lines.add(join("G", "" + e.id, e.date, e.name, e.mode, e.route, "" + e.amount, e.paidBy, String.join("|", e.participants), e.notes));
        for (PersonalExpense e : l.personals)
            lines.add(join("P", "" + e.id, e.date, e.name, e.mode, e.from, e.to, "" + e.amount, e.time, e.notes));
        for (Settlement s : l.settlements) lines.add(join("S", "" + s.id, s.date, s.from, s.to, "" + s.amount));
        for (RouteComparison r : l.routes) lines.add(join("R", "" + r.id, r.date, r.from, r.to, r.cheapest, "" + r.fare, r.fastest));
        Files.createDirectories(FILE.getParent());
        Path tmp = Paths.get("data", "ledger.tmp");                  // write to a temp file first, so a crash can't corrupt data
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Returns false when no saved file exists yet (first run). */
    static boolean load(Ledger l) throws IOException {
        if (!Files.exists(FILE)) return false;
        long maxId = 0;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", -1);
            if (p.length < 2) continue;
            switch (p[0]) {
                case "F": l.friends.add(new Friend(p[1], p[2])); break;
                case "G": { GroupExpense e = new GroupExpense(); e.id = Long.parseLong(p[1]); e.date = p[2]; e.name = p[3]; e.mode = p[4]; e.route = p[5];
                    e.amount = Double.parseDouble(p[6]); e.paidBy = p[7]; e.participants.addAll(Arrays.asList(p[8].split("\\|"))); e.notes = p[9];
                    l.groups.add(e); maxId = Math.max(maxId, e.id); break; }
                case "P": { PersonalExpense e = new PersonalExpense(); e.id = Long.parseLong(p[1]); e.date = p[2]; e.name = p[3]; e.mode = p[4];
                    e.from = p[5]; e.to = p[6]; e.amount = Double.parseDouble(p[7]); e.time = p[8]; e.notes = p[9];
                    l.personals.add(e); maxId = Math.max(maxId, e.id); break; }
                case "S": { Settlement s = new Settlement(); s.id = Long.parseLong(p[1]); s.date = p[2]; s.from = p[3]; s.to = p[4]; s.amount = Double.parseDouble(p[5]);
                    l.settlements.add(s); maxId = Math.max(maxId, s.id); break; }
                case "R": { RouteComparison r = new RouteComparison(); r.id = Long.parseLong(p[1]); r.date = p[2]; r.from = p[3]; r.to = p[4];
                    r.cheapest = p[5]; r.fare = Double.parseDouble(p[6]); r.fastest = p[7];
                    l.routes.add(r); maxId = Math.max(maxId, r.id); break; }
                default: break;
            }
        }
        l.nextId = maxId + 1;
        return true;
    }

    private static String join(String... parts) { return String.join("\t", parts); }
}
