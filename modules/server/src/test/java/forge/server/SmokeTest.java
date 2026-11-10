package forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void moduleLoads() {
        assertEquals("server", ModuleInfo.NAME);
    }
}
