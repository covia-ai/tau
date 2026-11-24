package tau.rooms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;

/**
 * Which new posts are worth a notification, and when: only in rooms you own or
 * have posted in yourself, never your own posts, and for each room at most once
 * a minute, grouped ("12 new posts in standup").
 */
public final class Notices {

	/** At most one notification per room in this long. */
	public static final long EVERY_MILLIS = 60_000;

	/** A grouped notification: how many new posts in a room, and who made them, by name. */
	public record Notice(String room, int posts, List<String> from) {

		/** The notification's text, plain: "3 new posts in release · from relay (via release-bot) and scout". */
		public String text() {
			String count = posts == 1 ? "1 new post" : posts + " new posts";
			return count + " in " + room + (from.isEmpty() ? "" : " · from " + names(from));
		}

		private static String names(List<String> from) {
			if (from.size() == 1) return from.get(0);
			if (from.size() == 2) return from.get(0) + " and " + from.get(1);
			return String.join(", ", from.subList(0, from.size() - 1)) + ", and " + from.get(from.size() - 1);
		}
	}

	private final String you;
	private final String venue;
	/** The next number seen in each room: posts from it on are new. */
	private final Map<String, Long> seen = new HashMap<>();
	/** New posts waiting to be told, by room. */
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	/** When each room was last told. */
	private final Map<String, Long> told = new HashMap<>();
	private boolean primed;

	private static final class Pending {
		int posts;
		final Set<String> from = new LinkedHashSet<>();
	}

	public Notices(String you, String venue) {
		this.you = you;
		this.venue = venue;
	}

	/**
	 * Takes the rooms view as it now stands and returns the notifications due
	 * at {@code now}. The first view only marks where each room is, so posts
	 * already there are never news.
	 */
	public List<Notice> offer(ACell view, long now) {
		for (Rooms.Room r : Rooms.rooms(view)) {
			long from = seen.getOrDefault(r.name(), primed ? r.first() : r.end());
			if (r.end() > from) {
				boolean mine = r.ownedBy(you);
				List<Rooms.Post> fresh = Rooms.postsFrom(view, r.name(), from);
				boolean postedIn = mine || Rooms.postsFrom(view, r.name(), 0).stream()
					.anyMatch(p -> you.equals(p.from()) && p.via() == null);
				if (postedIn) {
					for (Rooms.Post p : fresh) {
						Who w = Who.of(p, you, venue);
						if (w.kind() == Who.Kind.YOU) continue;
						Pending q = pending.computeIfAbsent(r.name(), k -> new Pending());
						q.posts++;
						q.from.add(w.kind() == Who.Kind.OUTSIDE ? w.name() + " (via " + p.via() + ")" : w.name());
					}
				}
			}
			seen.put(r.name(), r.end());
		}
		primed = true;
		return due(now);
	}

	/** The notifications due at {@code now}: each room with news, if it was not told in the last minute. */
	public List<Notice> due(long now) {
		List<Notice> out = new ArrayList<>();
		for (var it = pending.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Long last = told.get(e.getKey());
			if (last != null && now - last < EVERY_MILLIS) continue;
			out.add(new Notice(e.getKey(), e.getValue().posts, List.copyOf(e.getValue().from)));
			told.put(e.getKey(), now);
			it.remove();
		}
		return out;
	}
}
