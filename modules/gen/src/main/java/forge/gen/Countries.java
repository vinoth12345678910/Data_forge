package forge.gen;

import java.util.List;

/**
 * Country, currency and phone code, bundled so a correlated column cannot contradict its leader.
 * Append-only: a pick is an index into this list, so reordering or deleting changes output.
 */
final class Countries {

    private record C(String code, String currency, String dial) {}

    private static final List<C> LIST = List.of(
        new C("IN", "INR", "+91"), new C("US", "USD", "+1"), new C("GB", "GBP", "+44"), new C("DE", "EUR", "+49"),
        new C("FR", "EUR", "+33"), new C("JP", "JPY", "+81"), new C("AU", "AUD", "+61"), new C("CA", "CAD", "+1"),
        new C("SG", "SGD", "+65"), new C("AE", "AED", "+971"), new C("BR", "BRL", "+55"), new C("ZA", "ZAR", "+27"));

    private Countries() {}

    static int size() {
        return LIST.size();
    }

    static String code(int i) {
        return LIST.get(i).code;
    }

    static String currency(String code) {
        return find(code).currency;
    }

    static String dial(String code) {
        return find(code).dial;
    }

    private static C find(String code) {
        for (C c : LIST) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown country " + code);
    }
}
