package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void moduleLoads() {
        assertEquals("cli", ModuleInfo.NAME);
    }
}
