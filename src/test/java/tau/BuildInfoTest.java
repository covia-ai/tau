package tau;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

	@Test
	void theBuildNamesItsVersions() {
		assertNotEquals("unknown", BuildInfo.version(), "Maven filled in Tau's version");
		assertNotEquals("unknown", BuildInfo.coviaVersion(), "Maven filled in the Covia version");
		assertFalse(BuildInfo.coviaVersion().contains("${"));
	}
}
