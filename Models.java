import java.util.*;

/** Plain data classes (the "model" layer). One class = one kind of record. */
class Friend { final String name, role; Friend(String name, String role) { this.name = name; this.role = role; } }

class GroupExpense {
    long id; String date, name, mode, route, paidBy, notes = ""; double amount;
    List<String> participants = new ArrayList<String>();
}

class PersonalExpense {
    long id; String date, name, mode, from, to, time = "", notes = ""; double amount;
}

class Settlement { long id; String date, from, to; double amount; }

class RouteComparison { long id; String date, from, to, cheapest, fastest; double fare; }

/** One suggested payment: "from" should pay "to" this amount. */
class Debt { final String from, to; final double amount; Debt(String f, String t, double a) { from = f; to = t; amount = a; } }

/** Helper used by the debt-settling algorithm. */
class Holder { final String name; double value; Holder(String n, double v) { name = n; value = v; } }
