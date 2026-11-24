package tau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import covia.venue.Audit;
import covia.venue.server.RequestLog;

/**
 * Tau's shipped logging configuration, loaded into a context of its own:
 * the audit trail and the access log keep the details Covia logs as
 * key-values, not only the event's name.
 */
class LogFilesTest {

	@TempDir
	Path home;

	@Test
	void auditAndAccessLinesKeepTheirDetails() throws Exception {
		LoggerContext context = new LoggerContext();
		context.setMDCAdapter(new LogbackMDCAdapter());
		context.putProperty("tau.home", home.toString());
		JoranConfigurator config = new JoranConfigurator();
		config.setContext(context);
		config.doConfigure(LogFilesTest.class.getResource("/tau/logback.xml"));
		context.getLogger(Audit.LOGGER).atInfo().addKeyValue("ip", "192.0.2.7").log("auth.failure");
		context.getLogger(RequestLog.LOGGER).atInfo().addKeyValue("path", "/api/v1/status").log("request");
		context.stop();

		String audit = Files.readString(home.resolve("logs/audit.log"));
		String access = Files.readString(home.resolve("logs/access.log"));
		assertTrue(audit.contains("auth.failure") && audit.contains("192.0.2.7"), audit);
		assertTrue(access.contains("/api/v1/status"), access);
	}
}
