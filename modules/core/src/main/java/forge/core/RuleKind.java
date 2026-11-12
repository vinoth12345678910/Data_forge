package forge.core;

/** The kinds of rule. VERIFY is a check that only the verify pass runs (the generator makes it true another way, for example a state machine). */
public enum RuleKind { DERIVE, CHECK, LIMIT, VERIFY }
