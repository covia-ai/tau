package tau.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a test class that builds Swing components, loads icons or reads the
 * look ({@code Theme}, {@code UIManager}, {@link LAF}). Readers run alongside
 * one another, never while a {@link ChangesLook} class swaps the look, and pass
 * under whichever look is installed when they start: the JVM's default or the
 * last one a changer left.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(value = ChangesLook.RESOURCE, mode = ResourceAccessMode.READ)
public @interface ReadsLook {
}
