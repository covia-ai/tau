package tau;

/**
 * Shortens the change gate's wait while any test class that answers its
 * cards runs, and restores it when the last one finishes: classes run in
 * parallel in one JVM, so each holds the short wait rather than setting and
 * restoring a shared value of its own.
 */
final class ShortGateWaits {

	private static int holders;
	private static long saved;

	private ShortGateWaits() {
	}

	static synchronized void hold() {
		if (holders++ == 0) {
			saved = ChangeGate.waitMillis;
			ChangeGate.waitMillis = 1500;
		}
	}

	static synchronized void release() {
		if (--holders == 0) ChangeGate.waitMillis = saved;
	}
}
