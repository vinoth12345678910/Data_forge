package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void moduleLoads() {
        assertEquals("rules", ModuleInfo.NAME);
    }
}
