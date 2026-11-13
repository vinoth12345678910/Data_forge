package forge.rules;

import java.util.List;

/** The results of one verify run, in rule order. */
public record Report(List<RuleResult> results) {

    /** 0 all PASS, 1 at least one FAIL, 2 at least one ERROR (an error wins over a failure). */
    public int exitCode() {
        int code = 0;
        for (RuleResult r : results) {
            if (r.status().equals("ERROR")) {
                return 2;
            }
            if (r.status().equals("FAIL")) {
                code = 1;
            }
        }
        return code;
    }

    public RuleResult get(String name) {
        for (RuleResult r : results) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    public String text(String where) {
        StringBuilder b = new StringBuilder("forge verify: " + results.size() + " rules on " + where + "\n");
        int fail = 0;
        int err = 0;
        for (RuleResult r : results) {
            b.append(String.format(java.util.Locale.ROOT, "  %-5s  %s  %s  %s", r.status(), r.name(), r.kind(), r.subject()));
            switch (r.status()) {
                case "PASS" -> b.append("  checked ").append(r.checked()).append(" rows");
                case "FAIL" -> {
                    fail++;
                    b.append("  ").append(r.violations()).append(" of ").append(r.checked()).append(" rows");
                }
                case "NOT_APPLICABLE" -> b.append("  not applicable: ").append(r.note());
                default -> {
                    err++;
                    b.append("  ").append(r.error());
                }
            }
            b.append('\n');
            if (r.note() != null && !r.status().equals("NOT_APPLICABLE")) {
                b.append("         WARN ").append(r.note()).append('\n');
            }
            if (r.status().equals("FAIL")) {
                for (List<String> row : r.sample()) {
                    StringBuilder line = new StringBuilder("         ");
                    for (int i = 0; i < row.size(); i++) {
                        line.append(i == 0 ? "" : "  ").append(r.columns().get(i)).append('=').append(row.get(i));
                    }
                    b.append(line).append('\n');
                }
            }
        }
        return b.append("result: ").append(fail).append(" FAIL, ").append(err).append(" ERROR   (exit code ")
            .append(exitCode()).append(")\n").toString();
    }

    public String json() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(java.util.Map.of("exit", exitCode(), "rules", results));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
