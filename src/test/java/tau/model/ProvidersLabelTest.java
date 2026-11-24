package tau.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The composer's model chip says the model in plain words, never an operation id where a name is known. */
class ProvidersLabelTest {

	@Test
	void aListedModelShowsItsLabelAndWhoPays() {
		Providers.Provider p = Providers.defaultProvider();
		Providers.Model m = p.models().get(0);
		String op = Providers.modelOp(p.id(), m.id());
		assertEquals(m.label(), Providers.label(op));
		assertEquals(p.needsApiKey() ? "Your key" : "On this computer", Providers.marker(op));
	}

	@Test
	void theEchoBotAndUnknownOnes() {
		Providers.Model echo = Providers.byId(Providers.ECHO).model(Providers.ECHO);
		assertEquals(echo.label(), Providers.label(Providers.ECHO_OPERATION));
		assertNull(Providers.marker(Providers.ECHO_OPERATION));
		assertEquals("v/ops/custom", Providers.label("v/ops/custom"));
		assertEquals("No model", Providers.label(null));
	}
}
