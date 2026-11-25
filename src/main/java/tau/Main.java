package tau;

import java.io.PrintStream;
import java.util.Arrays;

/** Dispatches before loading libraries which may write to the console. */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
		if (Arrays.asList(args).contains("--self-test")) {
			// Headless, before anything loads AWT: an end-to-end check of an
			// installed copy in a throwaway folder (SelfTest).
			System.setProperty("java.awt.headless", "true");
			System.exit(SelfTest.run(args, System.out, System.err));
		} else if (Arrays.asList(args).contains("--mcp-stdio")) {
			PrintStream protocol = System.out;
			// Reserve the original stdout exclusively for MCP, including during
			// library initialisation. All incidental console output goes to stderr.
			System.setOut(System.err);
			System.exit(McpStdio.run(args, System.in, protocol, System.err));
		} else {
			Tau.main(args);
		}
	}
}
