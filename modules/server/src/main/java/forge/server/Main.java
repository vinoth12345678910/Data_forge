package forge.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the local API: forge-server [port] [--ui ui/dist]. Prints the address and the session token; Ctrl+C stops it. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 0;
        Path ui = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--ui") && i + 1 < args.length) {
                ui = Path.of(args[++i]);
            } else {
                port = Integer.parseInt(args[i]);
            }
        }
        Path scratch = Files.createTempDirectory("forge-server");
        Server s = Server.start(scratch, port, ui);
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        String base = "http://" + s.host() + ":" + s.port();
        System.out.println("forge server " + base);
        System.out.println("session token (send it as the X-Forge-Token header): " + s.token());
        if (ui != null) {
            System.out.println("open the UI: " + base + "/#token=" + s.token());
        }
        Thread.currentThread().join();
    }
}
