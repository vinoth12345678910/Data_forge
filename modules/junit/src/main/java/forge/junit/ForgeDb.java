package forge.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives a test class its own seeded database (docs/design/JUNIT_AND_SCALE.md). The class gets a ForgeHandle parameter.
 * schema and config are file paths; ${name} is replaced by the system property of that name.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ForgeExtension.class)
public @interface ForgeDb {

    /** A DDL file for the schema. */
    String schema();

    /** A forge.yml: row counts, rules, states, scenarios. */
    String config();

    /** The seed. It replaces the one in forge.yml. */
    long seed() default 42;

    /** A scenario from forge.yml; "default" means none. */
    String scenario() default "default";

    /** Edge-case mode. */
    boolean edge() default false;
}
