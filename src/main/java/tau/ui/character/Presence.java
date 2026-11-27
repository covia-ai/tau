package tau.ui.character;

import java.util.List;

/**
 * What the character shows for each beat of Tau's work (docs/CHARACTER.md):
 * which pose, where the eyes look, and the one moment of motion a beat may
 * play. No Swing here, so the mapping is testable on its own.
 *
 * <p>Poses are the pack's signed poses only, crossfaded, never rotated. Status
 * beats (paused, cancelled, failed, refused, offline, locked) have no pose:
 * the τ glyph and a calm badge carry them, and the character leaves the view.
 */
public final class Presence {

	/** The poses a pack may hold. */
	public static final List<String> POSES = List.of("rest", "thinking", "waiting", "asleep", "excited", "welcoming");

	/** Where the eyes look, as a fraction of the frame's width and height from where the art puts them. */
	public enum Gaze {
		AHEAD(0, 0), UP_ASIDE(-0.014, -0.02), AT_YOU(0, 0.006), AT_WORK(0.016, 0.008), AT_AGENT(0.02, -0.004), DOWN(
			0.004, 0.014);

		public final double dx;
		public final double dy;

		Gaze(double dx, double dy) {
			this.dx = dx;
			this.dy = dy;
		}
	}

	/** The one moment of motion a beat plays on entering it; never a loop. */
	public enum Moment {
		NONE, SHIVER, CATCH, HOP, JUMP
	}

	public enum Beat {
		IDLE("Resting", "Tau is ready"),
		ASLEEP("Resting", "Tau is resting"),
		NEW_REQUEST("Preparing", "Tau has your request"),
		THINKING("Thinking", "Tau is thinking"),
		WORKING("At work", "Tau is at work"),
		DELEGATING("Asking an agent", "Tau is asking another agent"),
		WAITING("Waiting on you", "Tau is waiting for you"),
		HANDING_BACK("Replied", "Tau has replied"),
		DONE("Done", "Tau finished"),
		MILESTONE("Done", "Tau finished"),
		TIMED_OUT("Timed out", "Tau's task ran out of time"),
		WELCOMING("Welcome", "Tau welcomes you"),
		PAUSED("Paused", "Tau's task is paused"),
		CANCELLED("Cancelled", "Tau's task was cancelled"),
		FAILED("Failed", "Tau's task failed"),
		REFUSED("Refused", "Tau's request was refused"),
		OFFLINE("Offline", "Tau is offline"),
		LOCKED("Locked", "Tau is locked");

		/** The plain words beside the slot. */
		public final String label;
		/** The alt text. */
		public final String description;

		Beat(String label, String description) {
			this.label = label;
			this.description = description;
		}
	}

	/**
	 * What a beat shows. {@code pose} is null for the status beats (the glyph
	 * stands in); {@code squash} narrows the eyes (done's happy squash);
	 * {@code warm} warms the floor glow to amber (waiting).
	 */
	public record Look(String pose, Gaze gaze, Moment moment, boolean squash, boolean warm, boolean breathes) {
		public boolean character() {
			return pose != null;
		}
	}

	private Presence() {
	}

	public static Look look(Beat beat) {
		return switch (beat) {
			case IDLE -> new Look("rest", Gaze.AHEAD, Moment.NONE, false, false, true);
			case ASLEEP -> new Look("asleep", Gaze.AHEAD, Moment.NONE, false, false, true);
			// One spark in, caught by the gloves: the crossfade from rest to the clasp, then back to rest.
			case NEW_REQUEST -> new Look("rest", Gaze.AT_YOU, Moment.CATCH, false, false, false);
			case THINKING -> new Look("thinking", Gaze.UP_ASIDE, Moment.NONE, false, false, true);
			case WORKING -> new Look("rest", Gaze.AT_WORK, Moment.NONE, false, false, false);
			case DELEGATING -> new Look("rest", Gaze.AT_AGENT, Moment.NONE, false, false, false);
			case WAITING -> new Look("waiting", Gaze.AT_YOU, Moment.NONE, false, true, true);
			case HANDING_BACK -> new Look("rest", Gaze.AT_YOU, Moment.NONE, false, false, false);
			case DONE -> new Look("rest", Gaze.AHEAD, Moment.HOP, true, false, false);
			case MILESTONE -> new Look("excited", Gaze.AHEAD, Moment.JUMP, false, false, false);
			case TIMED_OUT -> new Look("waiting", Gaze.AT_YOU, Moment.NONE, false, false, false);
			case WELCOMING -> new Look("welcoming", Gaze.AT_YOU, Moment.NONE, false, false, true);
			case PAUSED, CANCELLED, FAILED, REFUSED, OFFLINE, LOCKED -> new Look(null, Gaze.AHEAD, Moment.NONE, false,
				false, false);
		};
	}

	/** The pose the catch crossfades to: the signed clasp. */
	public static final String CATCH_POSE = "waiting";
}
