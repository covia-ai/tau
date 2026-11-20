package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.util.JSON;

/**
 * The token file for local tools: what a tool's header helper prints, a user
 * token whole, replaced in place and gone when cleared.
 */
class ToolTokenTest {

	@TempDir
	Path home;

	@Test
	void theFileIsTheHeadersAToolSendsAsTheUser() throws Exception {
		AKeyPair key = AKeyPair.generate();
		String venueDID = "did:key:z6MkVenue", userDID = venueDID + ":u:mike";
		long now = System.currentTimeMillis() / 1000;
		ToolToken.USER.write(home, Tau.signAccessToken(key.getSeed().toHexString(), venueDID, userDID, now,
			ToolToken.LIFETIME_SECONDS));

		AMap<AString, ACell> headers = JSON.parse(Files.readString(ToolToken.USER.file(home)));
		String auth = headers.get(Strings.create("Authorization")).toString();
		assertTrue(auth.startsWith("Bearer "), auth.substring(0, 7));
		AMap<AString, ACell> claims = JWT.parse(Strings.create(auth.substring(7))).getClaims();
		assertEquals(userDID, claims.get(JWT.SUB).toString(), "as the user, never the operator");
		assertTrue(ToolToken.REFRESH_SECONDS * 4 < ToolToken.LIFETIME_SECONDS, "renewed long before it lapses");

		ToolToken.USER.write(home, "second");
		assertEquals(ToolToken.headers("second"), Files.readString(ToolToken.USER.file(home)), "replaced, not appended");
		try (var left = Files.list(home)) {
			assertEquals(1, left.count(), "no temporary file left beside it");
		}
		ToolToken.USER.clear(home);
		assertFalse(Files.exists(ToolToken.USER.file(home)));
		ToolToken.USER.clear(home); // and clearing twice is fine
	}

	@Test
	void theOperatorLoginIsKeptApartAsTheVenue() throws Exception {
		AKeyPair key = AKeyPair.generate();
		String venueDID = "did:key:z6MkVenue";
		long now = System.currentTimeMillis() / 1000;
		ToolToken.USER.write(home, "the user's");
		ToolToken.OPERATOR.write(home, Tau.signAccessToken(key.getSeed().toHexString(), venueDID, venueDID, now,
			ToolToken.LIFETIME_SECONDS));

		assertNotEquals(ToolToken.USER.file(home), ToolToken.OPERATOR.file(home));
		AMap<AString, ACell> headers = JSON.parse(Files.readString(ToolToken.OPERATOR.file(home)));
		String auth = headers.get(Strings.create("Authorization")).toString();
		assertEquals(venueDID, JWT.parse(Strings.create(auth.substring(7))).getClaims().get(JWT.SUB).toString(),
			"as the venue itself");
		ToolToken.OPERATOR.clear(home);
		assertFalse(Files.exists(ToolToken.OPERATOR.file(home)));
		assertEquals(ToolToken.headers("the user's"), Files.readString(ToolToken.USER.file(home)),
			"clearing one leaves the other");
	}
}
