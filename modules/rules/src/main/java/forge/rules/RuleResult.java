package forge.rules;

import java.util.List;

/**
 * The outcome of one rule. status is PASS, FAIL or ERROR. checked is how many rows the rule looked at;
 * sample rows start with the key. note is a warning such as "checked 0 rows".
 */
public record RuleResult(
    String name, String kind, String subject, String status, long checked, long violations, List<String> columns,
    List<List<String>> sample, long millis, String error, String note, String sql) {}
