import java.time.LocalDate;
import java.util.*;

/**
 * The brain of RouteAudit: holds all data, validates input,
 * and calculates balances and "who should pay whom".
 */
public class Ledger {
    final List<Friend> friends = new ArrayList<Friend>();
    final List<GroupExpense> groups = new ArrayList<GroupExpense>();
    final List<PersonalExpense> personals = new ArrayList<PersonalExpense>();
    final List<Settlement> settlements = new ArrayList<Settlement>();
    final List<RouteComparison> routes = new ArrayList<RouteComparison>();
    long nextId = 1;

    // ---------------- validation helpers ----------------
    static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\t\\r\\n|]", " ").trim();          // these characters are used by the storage format
        return s.length() > max ? s.substring(0, max) : s;
    }
    static double round2(double d) { return Math.round(d * 100.0) / 100.0; }
    boolean isFriend(String n) { for (Friend f : friends) if (f.name.equals(n)) return true; return false; }
    static String validDate(String s) {
        try { s = s.trim(); LocalDate.parse(s); return s; }
        catch (Exception e) { throw new IllegalArgumentException("Please enter a valid date."); }
    }
    static double validAmount(String s, boolean allowZero) {
        double a;
        try { a = Double.parseDouble(s.trim()); } catch (Exception e) { throw new IllegalArgumentException("Amount must be a number."); }
        boolean ok = allowZero ? (a >= 0) : (a > 0);
        if (!ok || a > 1000000) throw new IllegalArgumentException("Amount must be between 0.01 and 1,000,000.");
        return round2(a);
    }

    // ---------------- adding records ----------------
    void addGroup(Map<String, String> f) {
        GroupExpense e = new GroupExpense();
        e.date = validDate(f.get("date"));
        e.name = clean(f.get("name"), 80);
        if (e.name.isEmpty()) throw new IllegalArgumentException("Expense name is required.");
        e.mode = clean(f.get("mode"), 30); if (e.mode.isEmpty()) e.mode = "Other";
        e.route = clean(f.get("route"), 80);
        e.amount = validAmount(f.get("amount"), false);
        e.paidBy = clean(f.get("paidBy"), 30);
        if (!isFriend(e.paidBy)) throw new IllegalArgumentException("Choose who paid.");
        Set<String> people = new LinkedHashSet<String>();
        String p = f.get("participants");
        if (p != null) for (String n : p.split("\\|")) {
            n = n.trim(); if (n.isEmpty()) continue;
            if (!isFriend(n)) throw new IllegalArgumentException("Unknown friend: " + n);
            people.add(n);
        }
        if (people.isEmpty()) throw new IllegalArgumentException("Choose at least one person.");
        e.participants.addAll(people);
        e.id = nextId++;
        groups.add(e);
    }

    void addPersonal(Map<String, String> f) {
        PersonalExpense e = new PersonalExpense();
        e.date = validDate(f.get("date"));
        e.name = clean(f.get("name"), 80);
        if (e.name.isEmpty()) throw new IllegalArgumentException("Trip name is required.");
        e.mode = clean(f.get("mode"), 30); if (e.mode.isEmpty()) e.mode = "Other";
        e.from = clean(f.get("from"), 60); e.to = clean(f.get("to"), 60);
        e.amount = validAmount(f.get("amount"), false);
        e.time = clean(f.get("time"), 10); e.notes = clean(f.get("notes"), 120);
        e.id = nextId++;
        personals.add(e);
    }

    void addSettlement(Map<String, String> f) {
        Settlement s = new Settlement();
        s.from = clean(f.get("from"), 30); s.to = clean(f.get("to"), 30);
        if (!isFriend(s.from) || !isFriend(s.to) || s.from.equals(s.to)) throw new IllegalArgumentException("Invalid payment.");
        s.amount = validAmount(f.get("amount"), false);
        s.date = LocalDate.now().toString();
        s.id = nextId++;
        settlements.add(s);
    }

    void addRoute(Map<String, String> f) {
        RouteComparison r = new RouteComparison();
        r.from = clean(f.get("from"), 60); r.to = clean(f.get("to"), 60);
        if (r.from.isEmpty() || r.to.isEmpty()) throw new IllegalArgumentException("Enter start and destination.");
        r.cheapest = clean(f.get("cheapest"), 60); r.fastest = clean(f.get("fastest"), 60);
        r.fare = validAmount(f.get("fare"), true);
        r.date = LocalDate.now().toString();
        r.id = nextId++;
        routes.add(r);
    }

    // ---------------- the money logic ----------------
    /** Positive balance = the group owes this person. Negative = this person owes the group. */
    Map<String, Double> balances() {
        Map<String, Double> b = new LinkedHashMap<String, Double>();
        for (Friend f : friends) b.put(f.name, 0.0);
        for (GroupExpense e : groups) {
            double share = e.amount / e.participants.size();      // equal split
            b.put(e.paidBy, b.get(e.paidBy) + e.amount);          // payer is credited the full amount
            for (String p : e.participants) b.put(p, b.get(p) - share); // everyone who joined owes their share
        }
        for (Settlement s : settlements) {                        // a payment cancels part of the debt
            b.put(s.from, b.get(s.from) + s.amount);
            b.put(s.to, b.get(s.to) - s.amount);
        }
        return b;
    }

    /** Greedy algorithm: the biggest debtor pays the biggest creditor, repeat. Gives few payments. */
    List<Debt> debts() {
        List<Holder> creditors = new ArrayList<Holder>(), debtors = new ArrayList<Holder>();
        for (Map.Entry<String, Double> e : balances().entrySet()) {
            if (e.getValue() > 0.01) creditors.add(new Holder(e.getKey(), e.getValue()));
            else if (e.getValue() < -0.01) debtors.add(new Holder(e.getKey(), -e.getValue()));
        }
        Comparator<Holder> biggestFirst = new Comparator<Holder>() {
            public int compare(Holder x, Holder y) { return Double.compare(y.value, x.value); }
        };
        Collections.sort(creditors, biggestFirst);
        Collections.sort(debtors, biggestFirst);
        List<Debt> out = new ArrayList<Debt>();
        int i = 0, j = 0;
        while (i < debtors.size() && j < creditors.size()) {
            Holder d = debtors.get(i), c = creditors.get(j);
            double amount = Math.min(d.value, c.value);
            if (amount > 0.01) out.add(new Debt(d.name, c.name, round2(amount)));
            d.value -= amount; c.value -= amount;
            if (d.value < 0.01) i++;
            if (c.value < 0.01) j++;
        }
        return out;
    }

    // ---------------- output ----------------
    String toJson() {
        StringBuilder sb = new StringBuilder("{\"friends\":[");
        for (int i = 0; i < friends.size(); i++)
            sb.append(i > 0 ? "," : "").append("{\"name\":").append(Json.str(friends.get(i).name)).append(",\"role\":").append(Json.str(friends.get(i).role)).append("}");
        sb.append("],\"groupExpenses\":[");
        for (int i = 0; i < groups.size(); i++) {
            GroupExpense e = groups.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":").append(e.id).append(",\"type\":\"group\",\"date\":").append(Json.str(e.date))
              .append(",\"name\":").append(Json.str(e.name)).append(",\"mode\":").append(Json.str(e.mode))
              .append(",\"route\":").append(Json.str(e.route)).append(",\"amount\":").append(Json.num(e.amount))
              .append(",\"paidBy\":").append(Json.str(e.paidBy)).append(",\"notes\":").append(Json.str(e.notes)).append(",\"participants\":[");
            for (int k = 0; k < e.participants.size(); k++) sb.append(k > 0 ? "," : "").append(Json.str(e.participants.get(k)));
            sb.append("]}");
        }
        sb.append("],\"personalExpenses\":[");
        for (int i = 0; i < personals.size(); i++) {
            PersonalExpense e = personals.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":").append(e.id).append(",\"type\":\"personal\",\"date\":").append(Json.str(e.date))
              .append(",\"name\":").append(Json.str(e.name)).append(",\"mode\":").append(Json.str(e.mode))
              .append(",\"from\":").append(Json.str(e.from)).append(",\"to\":").append(Json.str(e.to))
              .append(",\"amount\":").append(Json.num(e.amount)).append(",\"time\":").append(Json.str(e.time))
              .append(",\"notes\":").append(Json.str(e.notes)).append("}");
        }
        sb.append("],\"settlements\":[");
        for (int i = 0; i < settlements.size(); i++) {
            Settlement s = settlements.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":").append(s.id).append(",\"date\":").append(Json.str(s.date))
              .append(",\"from\":").append(Json.str(s.from)).append(",\"to\":").append(Json.str(s.to)).append(",\"amount\":").append(Json.num(s.amount)).append("}");
        }
        sb.append("],\"routeComparisons\":[");
        for (int i = 0; i < routes.size(); i++) {
            RouteComparison r = routes.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":").append(r.id).append(",\"date\":").append(Json.str(r.date))
              .append(",\"from\":").append(Json.str(r.from)).append(",\"to\":").append(Json.str(r.to))
              .append(",\"cheapest\":").append(Json.str(r.cheapest)).append(",\"fare\":").append(Json.num(r.fare))
              .append(",\"fastest\":").append(Json.str(r.fastest)).append("}");
        }
        sb.append("],\"balances\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : balances().entrySet()) {
            sb.append(first ? "" : ",").append(Json.str(e.getKey())).append(":").append(Json.num(e.getValue())); first = false;
        }
        sb.append("},\"debts\":[");
        List<Debt> ds = debts();
        for (int i = 0; i < ds.size(); i++)
            sb.append(i > 0 ? "," : "").append("{\"from\":").append(Json.str(ds.get(i).from)).append(",\"to\":").append(Json.str(ds.get(i).to)).append(",\"amount\":").append(Json.num(ds.get(i).amount)).append("}");
        return sb.append("]}").toString();
    }

    String toCsv() {
        StringBuilder sb = new StringBuilder("\uFEFFdate,category,name,mode,route,paidBy,amount,participants\r\n");
        for (GroupExpense e : groups)
            sb.append(row(e.date, "Group", e.name, e.mode, e.route, e.paidBy, String.format(Locale.ROOT, "%.2f", e.amount), String.join("; ", e.participants)));
        for (PersonalExpense e : personals)
            sb.append(row(e.date, "Personal", e.name, e.mode, e.from + " -> " + e.to, "You", String.format(Locale.ROOT, "%.2f", e.amount), "You"));
        return sb.toString();
    }
    private static String row(String... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) sb.append(i > 0 ? "," : "").append('"').append(cols[i].replace("\"", "\"\"")).append('"');
        return sb.append("\r\n").toString();
    }

    // ---------------- starter data ----------------
    /** New accounts start with the friends list but no expenses. */
    void seedFriends() {
        friends.clear(); groups.clear(); personals.clear(); settlements.clear(); routes.clear(); nextId = 1;
        friends.add(new Friend("You", "you")); friends.add(new Friend("Madhaban", "boy")); friends.add(new Friend("Priya", "girl"));
        friends.add(new Friend("Viraj", "boy")); friends.add(new Friend("Riya", "girl"));
    }

    void seedDemo() {
        friends.clear(); groups.clear(); personals.clear(); settlements.clear(); routes.clear(); nextId = 1;
        friends.add(new Friend("You", "you")); friends.add(new Friend("Madhaban", "boy")); friends.add(new Friend("Priya", "girl"));
        friends.add(new Friend("Viraj", "boy")); friends.add(new Friend("Riya", "girl"));
        g("2026-10-14", "College -> Dadar", "Metro", 240, "You", "Metro tickets after college", "You", "Madhaban", "Priya", "Viraj");
        g("2026-10-14", "Dadar -> Marine Drive", "Bus", 120, "Madhaban", "Bus ride to Marine Drive", "You", "Madhaban", "Priya", "Viraj");
        g("2026-10-14", "Marine Drive -> Station", "Rickshaw / Auto", 180, "Priya", "Shared rickshaw", "You", "Madhaban", "Priya");
        g("2026-10-07", "College -> Churchgate", "Local train", 80, "Viraj", "Local train tickets", "You", "Viraj", "Riya", "Priya");
        g("2026-10-07", "Station -> Cafe", "Rickshaw / Auto", 140, "You", "Rickshaw to cafe", "You", "Viraj", "Riya");
        g("2026-09-30", "College -> Dadar", "Bus", 100, "Madhaban", "Return bus tickets", "You", "Madhaban", "Priya", "Viraj");
        g("2026-10-15", "College -> Home", "Bus", 60, "Riya", "Bus tickets", "You", "Riya");
        g("2026-10-10", "Weekend plan", "Taxi / Cab", 360, "You", "Cab share", "You", "Priya", "Viraj");
        g("2026-09-23", "College -> Dadar", "Metro", 200, "Priya", "Metro fare", "You", "Priya", "Madhaban", "Viraj");
        g("2026-08-19", "College -> Museum", "Bus", 100, "Viraj", "Bus fare", "You", "Viraj", "Madhaban", "Priya");
        p("2026-10-14", "Going to college", "Local train", "Home", "College", 20, "08:10");
        p("2026-10-14", "Metro after class", "Metro", "College", "Dadar", 30, "16:30");
        p("2026-10-07", "Going to college", "Bus", "Home", "College", 25, "08:05");
        p("2026-10-08", "Library visit", "Rickshaw / Auto", "Home", "Library", 55, "14:00");
        p("2026-09-30", "Going to college", "Local train", "Home", "College", 20, "08:15");
    }
    private void g(String date, String route, String mode, double amt, String payer, String name, String... people) {
        GroupExpense e = new GroupExpense(); e.id = nextId++; e.date = date; e.route = route; e.mode = mode; e.amount = amt;
        e.paidBy = payer; e.name = name; e.participants.addAll(Arrays.asList(people)); groups.add(e);
    }
    private void p(String date, String name, String mode, String from, String to, double amt, String time) {
        PersonalExpense e = new PersonalExpense(); e.id = nextId++; e.date = date; e.name = name; e.mode = mode;
        e.from = from; e.to = to; e.amount = amt; e.time = time; personals.add(e);
    }
}
