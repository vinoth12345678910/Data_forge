package forge.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** forge mcp: an MCP server on stdin and stdout for other AI tools. Local only; no network; no tool deletes. */
@Command(name = "mcp", description = "Serve forge to MCP clients over stdio (read-mostly tools; nothing is deleted).")
final class McpCmd implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Mcp mcp = new Mcp();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Forge.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String reply = mcp.handle(line);
                if (reply != null) {
                    out.println(reply);
                    out.flush();
                }
            }
            return 0;
        } catch (IOException e) {
            spec.commandLine().getErr().println("forge mcp: " + e.getMessage());
            return 2;
        }
    }
}
