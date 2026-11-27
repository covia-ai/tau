package tau.rooms;

import java.util.List;

/**
 * Spots two participants answering each other: when two posters other than
 * you take turns for {@link #TURNS} posts or more inside {@link #WINDOW_MILLIS},
 * the room shows a banner naming both, so the owner can stop them.
 */
public final class LoopWatch {

	/** How many alternating posts raise the banner. */
	public static final int TURNS = 6;
	/** Inside how long: three minutes. */
	public static final long WINDOW_MILLIS = 180_000;

	/** Two posters taking turns: who they are (as {@link Who#key}), and how many posts, in how long. */
	public record Loop(Who first, Who second, int posts, long millis) {
	}

	private LoopWatch() {
	}

	/**
	 * The loop at the end of {@code posts} (oldest first), or null: the longest
	 * run of newest posts that alternate between exactly two posters, neither of
	 * them {@code you} in person, all inside the window up to {@code now}.
	 */
	public static Loop check(List<Rooms.Post> posts, String you, String venue, long now) {
		if (posts.size() < TURNS) return null;
		Who a = null, b = null;
		int run = 0;
		long oldest = now;
		for (int i = posts.size() - 1; i >= 0; i--) {
			Rooms.Post p = posts.get(i);
			if (now - p.ts() > WINDOW_MILLIS) break;
			Who w = Who.of(p, you, venue);
			if (w.kind() == Who.Kind.YOU) break;
			if (a == null) {
				a = w;
			} else if (b == null) {
				if (w.key().equals(a.key())) break;
				b = w;
			} else {
				// Turns alternate: this post is by whoever did not make the one after it.
				Who expected = (run % 2 == 0) ? a : b;
				if (!w.key().equals(expected.key())) break;
			}
			run++;
			oldest = p.ts();
		}
		return (b != null && run >= TURNS) ? new Loop(b, a, run, now - oldest) : null;
	}
}
