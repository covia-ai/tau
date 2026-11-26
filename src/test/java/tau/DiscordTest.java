package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * Tau's side of the Discord integration: Covia's Discord adapter is part of
 * the embedded venue with its own skill, and Settings reads the owner's bot
 * without leaving a job record. Creating a bot starts Covia's connection to
 * Discord itself, which is Covia's to test, not Tau's, so no test here makes
 * one.
 */
class DiscordTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
		userDID = Identity.of("discord-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
	}

	@Test
	void theAdapterIsOnTheVenue() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : new String[] { "v/ops/discord/create", "v/ops/discord/delete", "v/ops/discord/bots",
			"v/ops/discord/send", "v/ops/secret/set" }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(op), ctx), "op present: " + op);
		}
		ACell skill = venue.engine().resolvePath(Strings.create("v/skills/adapters/discord"), ctx);
		assertNotNull(skill, "the module's skill is installed for the assistant to discover");
		// The venue jar also ships a generic Discord skill; the module's must be
		// the one installed, or the assistant is taught raw REST instead of the
		// adapter's operations.
		assertTrue(String.valueOf(RT.getIn(skill, "skill", "tools")).contains("v/ops/discord/send"),
			"the module's own skill, not the venue's generic copy: " + RT.getIn(skill, "skill", "tools"));
	}

	@Test
	void theOwnersBotIsReadWithoutAJobRecordAndIsAbsentBeforeSetup() throws Exception {
		long jobsBefore = RecordedJobs.of(venue, userDID);
		assertNull(Discord.status(user), "nothing before setup");
		assertEquals(jobsBefore, RecordedJobs.of(venue, userDID), "status is a read: visiting Settings leaves no job record");
	}
}
