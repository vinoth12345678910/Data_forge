package forge.junit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/** API skeleton only: no behaviour yet (the P4.5 gate is red against it). */
public final class ForgeExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    @Override
    public void beforeAll(ExtensionContext context) {
        throw new UnsupportedOperationException("forge junit extension: not built yet");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        throw new UnsupportedOperationException("forge junit extension: not built yet");
    }

    @Override
    public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
        return false;
    }

    @Override
    public Object resolveParameter(ParameterContext p, ExtensionContext e) {
        throw new UnsupportedOperationException("forge junit extension: not built yet");
    }
}
