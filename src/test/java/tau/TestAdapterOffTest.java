package tau;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * Covia's test adapter is off in a real install: a venue on Tau's default
 * config has no test operations, and it is switched on only while the
 * owner's model is the offline echo bot that onboarding offers.
 */
class TestAdapterOffTest {

	@Test
	void aRealInstallHasNoTestOperationsUnlessItRunsTheEchoBot() throws Exception {
		Path home = Files.createTempDirectory("tau-test-adapter");
		try (EmbeddedVenue venue = EmbeddedVenue.launch(AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp")).assoc(Fields.PORT, CVMLong.create(0)))) {
			RequestContext owner = RequestContext.of(Strings.create(Identity.of("echo-owner").userDID(venue.did())));
			assertFalse(venue.engine().hasAdapter("test"), "off by default");
			assertThrows(Exception.class, () -> venue.engine().jobs()
				.invokeInternal("v/test/ops/echo", Maps.of("message", "hi"), owner).get(10, TimeUnit.SECONDS));
			assertFalse(Tau.allowEchoModel(venue.engine(), AppConfig.DEFAULT_LLM_OPERATION), "a real model leaves it off");
			assertFalse(venue.engine().hasAdapter("test"));
			assertTrue(Tau.allowEchoModel(venue.engine(), AppConfig.ECHO_LLM_OPERATION), "the echo bot switches it on");
			assertNotNull(venue.engine().jobs()
				.invokeInternal("v/test/ops/echo", Maps.of("message", "hi"), owner).get(10, TimeUnit.SECONDS));
		} finally {
			try (var paths = Files.walk(home)) {
				for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
			}
		}
	}
}
