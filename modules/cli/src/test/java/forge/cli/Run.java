package forge.cli;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Runs the CLI in this JVM and captures exit code and output. */
record Run(int code, String out, String err) {

    static Run of(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new Run(code, out.toString(), err.toString());
    }
}
