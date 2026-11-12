package forge.testkit;

import forge.junit.ForgeHandle;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the sample classes saw: the database name and a hash of its data, for the gate to compare. */
final class SampleRecord {

    static final Map<String, String> NAME = new ConcurrentHashMap<>();
    static final Map<String, String> HASH = new ConcurrentHashMap<>();

    private SampleRecord() {}

    static void note(String who, ForgeHandle db) throws Exception {
        NAME.put(who, db.name());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            for (String t : new String[] {"users", "events", "orders", "payments"}) {
                try (var r = s.executeQuery("select * from " + t + " order by 1")) {
                    int n = r.getMetaData().getColumnCount();
                    while (r.next()) {
                        for (int i = 1; i <= n; i++) {
                            md.update(String.valueOf(r.getObject(i)).getBytes());
                            md.update((byte) 0);
                        }
                    }
                }
            }
        }
        HASH.put(who, HexFormat.of().formatHex(md.digest()));
    }
}
