package forge.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the local API: forge-server [port]. Prints the address and the session token; Ctrl+C stops it. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        Path scratch = Files.createTempDirectory("forge-server");
        Server s = Server.start(scratch, port);
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        System.out.println("forge server http://" + s.host() + ":" + s.port());
        System.out.println("session token (send it as the X-Forge-Token header): " + s.token());
        Thread.currentThread().join();
    }
}
