package forge.gen;

import forge.core.Column;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Turns the text of a generated cell back into a value the evaluator can compare. */
final class Values {

    private Values() {}

    static Object typed(Column c, String text) {
        if (text == null || c.type().dims() > 0) {
            return text;
        }
        return switch (c.type().name()) {
            case "int2", "int4", "int8", "numeric", "float4", "float8" -> new BigDecimal(text);
            case "bool" -> Boolean.valueOf(text);
            case "date" -> when(true, text);
            case "timestamp", "timestamptz" -> when(false, text);
            default -> text;
        };
    }

    /** A date as days since 1970, or a timestamp as seconds since 1970. */
    static BigDecimal when(boolean date, String text) {
        String s = text.strip().replace(' ', 'T');
        if (date) {
            return BigDecimal.valueOf(LocalDate.parse(s.substring(0, 10)).toEpochDay());
        }
        if (s.matches(".*[+-]\\d\\d$")) {
            s += ":00";
        }
        if (s.matches(".*[+-]\\d\\d:\\d\\d$")) {
            return BigDecimal.valueOf(OffsetDateTime.parse(s).toEpochSecond());
        }
        if (s.endsWith("Z")) {
            return BigDecimal.valueOf(OffsetDateTime.parse(s).toEpochSecond());
        }
        return BigDecimal.valueOf(LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC));
    }
}
