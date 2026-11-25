package tau;

import java.io.PrintStream;
import java.util.Arrays;

/** Dispatches before loading libraries which may write to the console. */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
		if (Arrays.asList(args).contains("--mcp-stdio")) {
			PrintStream protocol = System.out;
			// Reserve the original stdout exclusively for MCP, including during
			// library initialisation. All incidental console output goes to stderr.
			System.setOut(System.err);
			System.exit(McpStdio.run(args, System.in, protocol, System.err));
		} else if (Arrays.asList(args).contains(RelaunchCommand.OPTION)) {
			System.exit(RelaunchCommand.run(args, System.out, System.err));
		} else {
			Tau.main(args);
		}
	}
}
