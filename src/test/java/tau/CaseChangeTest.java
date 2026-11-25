package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import tau.vault.Mnemonic;

/**
 * Tau's case changes are the same whatever language the computer is set to:
 * under Turkish, Java's bare {@code toLowerCase()} lower-cases I to a dotless ı.
 * Isolated: it sets the JVM's default locale, which classes running alongside
 * must not see.
 */
@Isolated
class CaseChangeTest {

	/** A BIP39 test vector with an i in it. */
	private static final String PHRASE = "legal winner thank year wave sausage worth useful legal winner thank yellow";

	@Test
	void caseChangesIgnoreTheComputersLanguage() {
		Locale was = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		try {
			assertNotEquals("winner", "WINNER".toLowerCase(), "Java's bare form follows the language");
			assertTrue(Mnemonic.isValid(PHRASE.toUpperCase(Locale.ROOT)), "a recovery phrase typed in capitals");
			assertEquals("Running", AgentInfo.state("RUNNING"));
		} finally {
			Locale.setDefault(was);
		}
	}
}
