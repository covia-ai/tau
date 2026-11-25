package tau.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a test class that installs a look and feel. It holds the JVM's look
 * exclusively: no {@link ReadsLook} class runs alongside it, so none sees the
 * look change under it, while classes that never touch Swing carry on as
 * usual. It leaves its last look installed; readers pass under any.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(ChangesLook.RESOURCE)
public @interface ChangesLook {

	/** The JVM's look and feel, as a JUnit resource lock names it. */
	String RESOURCE = "tau.ui.look-and-feel";
}
