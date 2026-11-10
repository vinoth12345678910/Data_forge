package forge.gen;

import forge.core.Column;
import forge.core.Type;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guesses a generator from a column's name and type. It knows no domain: only everyday column
 * names (email, phone, quantity ...). Constraints (check IN lists, enums, ranges, lengths,
 * uniqueness) are applied by RowGen on top of the guess.
 */
final class Infer {

    /** gen is a name from Config.GENS. lo and hi are a range where the generator has one; values for pick. */
    record Guess(String gen, Long lo, Long hi, List<String> values) {}

    private static final List<String> TEXT = List.of("text", "varchar", "bpchar");
    private static final List<String> INT = List.of("int2", "int4", "int8");
    private static final List<String> NUM = List.of("numeric", "float4", "float8");

    private static final Pattern EMAIL = Pattern.compile("(.*_)?e?mail(_address)?");
    private static final Pattern PHONE = Pattern.compile("(.*_)?(phone|mobile|msisdn|telephone)(_(number|no|num))?");
    private static final Pattern FIRST = Pattern.compile("(.*_)?(first|given)_?name");
    private static final Pattern LAST = Pattern.compile("(.*_)?(last|family|sur)_?name");
    private static final Pattern FULL = Pattern.compile("(.*_)?(full|display)_?name");
    private static final Pattern CITY = Pattern.compile("(.*_)?(city|town)");
    private static final Pattern PIN = Pattern.compile("(.*_)?(pincode|pin_code|postal_code|postcode|zip|zip_?code)");
    private static final Pattern GSTIN = Pattern.compile("(.*_)?(gstin|gst_(number|no|num|id))");
    private static final Pattern IBAN = Pattern.compile("(.*_)?iban");
    private static final Pattern URL = Pattern.compile("(.*_)?(url|uri|website|homepage)");
    private static final Pattern LONG_TEXT =
        Pattern.compile("(.*_)?(description|desc|notes?|comments?|bio|summary|message|body|remarks?)");
    private static final Pattern SHORT_TEXT = Pattern.compile("(.*_)?(title|name|label|subject|heading)");
    private static final Pattern ADDRESS = Pattern.compile("(.*_)?(address|street|address_line\\d?)");
    private static final Pattern STATUS = Pattern.compile("(.*_)?status");
    private static final Pattern CURRENCY = Pattern.compile("(.*_)?currency(_code)?");
    private static final Pattern MINOR = Pattern.compile(".*(_paise|_minor|_cents|_pence)");
    private static final Pattern QTY = Pattern.compile("(.*_)?(quantity|qty)");
    private static final Pattern AGE = Pattern.compile("(.*_)?age");
    private static final Pattern PERCENT = Pattern.compile("(.*_)?(percent|pct|percentage)");
    private static final Pattern MONEY = Pattern.compile("(.*_)?(price|amount|cost|fee|total|balance|salary)");
    private static final Pattern RATE = Pattern.compile("(.*_)?(price|amount|cost|fee|total|balance|salary|rate)");
    private static final Pattern BIRTH = Pattern.compile("(.*_)?(dob|birth_?date|birthday|date_of_birth)");

    private Infer() {}

    static Guess guess(String name, Column col) {
        Type t = col.type();
        if (t.dims() > 0 || !t.enumValues().isEmpty()) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (TEXT.contains(t.name())) {
            return text(n);
        }
        if (INT.contains(t.name())) {
            return whole(n);
        }
        if (NUM.contains(t.name())) {
            return RATE.matcher(n).matches() ? new Guess("decimal", 1L, 10_000L, null) : null;
        }
        if (t.name().equals("date") && BIRTH.matcher(n).matches()) {
            return new Guess("date", 18L * 365, 70L * 365, null);
        }
        return null;
    }

    private static Guess text(String n) {
        if (EMAIL.matcher(n).matches()) {
            return plain("email");
        } else if (PHONE.matcher(n).matches()) {
            return plain("phone");
        } else if (FIRST.matcher(n).matches()) {
            return plain("first_name");
        } else if (LAST.matcher(n).matches()) {
            return plain("last_name");
        } else if (FULL.matcher(n).matches()) {
            return plain("full_name");
        } else if (CITY.matcher(n).matches()) {
            return plain("city");
        } else if (PIN.matcher(n).matches()) {
            return plain("pincode");
        } else if (GSTIN.matcher(n).matches()) {
            return plain("gstin");
        } else if (IBAN.matcher(n).matches()) {
            return plain("iban");
        } else if (URL.matcher(n).matches()) {
            return plain("url");
        } else if (LONG_TEXT.matcher(n).matches()) {
            return new Guess("text", 4L, 10L, null);
        } else if (SHORT_TEXT.matcher(n).matches()) {
            return new Guess("text", 1L, 3L, null);
        } else if (ADDRESS.matcher(n).matches()) {
            return new Guess("text", 3L, 5L, null);
        } else if (STATUS.matcher(n).matches()) {
            return new Guess("pick", null, null, List.of("active", "inactive", "pending"));
        } else if (CURRENCY.matcher(n).matches()) {
            return new Guess("pick", null, null, List.of("INR", "USD", "EUR", "GBP"));
        }
        return null;
    }

    private static Guess whole(String n) {
        if (MINOR.matcher(n).matches()) {
            return new Guess("money_minor", 100L, 1_000_000L, null);
        } else if (PIN.matcher(n).matches()) {
            return plain("pincode");
        } else if (QTY.matcher(n).matches()) {
            return new Guess("int", 1L, 10L, null);
        } else if (AGE.matcher(n).matches()) {
            return new Guess("int", 18L, 80L, null);
        } else if (PERCENT.matcher(n).matches()) {
            return new Guess("int", 0L, 100L, null);
        } else if (MONEY.matcher(n).matches()) {
            return new Guess("int", 1L, 10_000L, null);
        }
        return null;
    }

    private static Guess plain(String gen) {
        return new Guess(gen, null, null, null);
    }
}
