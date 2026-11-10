package forge.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void moduleLoads() {
        assertEquals("cleanup", ModuleInfo.NAME);
    }
}
