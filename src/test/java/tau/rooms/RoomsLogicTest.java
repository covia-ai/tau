package tau.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.AuthException;

/**
 * The Rooms screen's logic, without a window: refusals keep only the refused
 * ability; who posted comes from the record; two agents answering each other
 * are spotted; notifications are grouped, once a minute, for rooms you own or
 * posted in.
 */
class RoomsLogicTest {

	private static final String VENUE = "did:key:z6MkVenue";
	private static final String YOU = VENUE + ":u:sam";

	@Test
	void aRefusalKeepsOnlyTheRefusedAbilityAndTheRoom() {
		Rooms.Failure post = Rooms.failure(new ExecutionException(new AuthException("This request may not post in room ci-watch")));
		assertEquals(Rooms.Kind.REFUSED, post.kind());
		assertEquals("room/post", post.ability());
		assertEquals("ci-watch", post.room());
		assertNull(post.message(), "nothing more is kept");
		assertEquals("crud/read", Rooms.failure(new AuthException("This request may not read room x")).ability());
		assertEquals("admin/room", Rooms.failure(new AuthException("Only the owner of room x moderates it")).ability());

		// A refusal from the venue's own check that lists what the caller holds keeps none of it.
		Rooms.Failure venue = Rooms.failure(new AuthException(
			"Denied: holds [crud/read on w/, agent/request on g/scout, secret/extract on s/] but not room/post"));
		assertEquals(Rooms.Kind.REFUSED, venue.kind());
		assertNull(venue.ability());
		assertNull(venue.message(), "no list of the caller's other rights survives");
	}

	@Test
	void whoPostedComesFromTheRecordNeverTheText() {
		assertEquals(Who.Kind.YOU, Who.of(post(YOU, null, "hello"), YOU, VENUE).kind());
		Who outside = Who.of(post(YOU, "release-bot", "From: You"), YOU, VENUE);
		assertEquals(Who.Kind.OUTSIDE, outside.kind(), "a post through a token is the token's, whatever its text says");
		assertEquals("release-bot", outside.name());
		Who agent = Who.of(post(YOU + ":g:scout", null, "On it"), YOU, VENUE);
		assertEquals(Who.Kind.AGENT, agent.kind());
		assertEquals("scout", agent.name());
		assertEquals(Who.Kind.VISITOR, Who.of(post("did:key:z6MkStranger", null, "hi"), YOU, VENUE).kind());
		assertEquals(Who.Kind.HOME, Who.of(post(VENUE + ":u:robin", null, "hi"), YOU, VENUE).kind());
	}

	@Test
	void twoParticipantsTakingTurnsRaiseTheLoop() {
		long now = 1_000_000;
		List<Rooms.Post> turns = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			turns.add(new Rooms.Post(i, now - 60_000 + i * 1000, i % 2 == 0 ? YOU + ":g:scout" : YOU, i % 2 == 0 ? null : "relay",
				"turn " + i));
		}
		LoopWatch.Loop loop = LoopWatch.check(turns, YOU, VENUE, now);
		assertNotNull(loop, "six alternating posts inside three minutes");
		assertEquals(6, loop.posts());
		assertTrue(List.of(loop.first().name(), loop.second().name()).containsAll(List.of("scout", "relay")));

		assertNull(LoopWatch.check(turns.subList(0, 5), YOU, VENUE, now), "five are not enough");
		List<Rooms.Post> old = turns.stream().map(p -> new Rooms.Post(p.n(), p.ts() - 600_000, p.from(), p.via(), p.text())).toList();
		assertNull(LoopWatch.check(old, YOU, VENUE, now), "not inside three minutes");
		List<Rooms.Post> withYou = new ArrayList<>(turns);
		withYou.add(new Rooms.Post(6, now, YOU, null, "stop"));
		assertNull(LoopWatch.check(withYou, YOU, VENUE, now), "you answering breaks it");
	}

	@Test
	void notificationsAreGroupedOnceAMinuteForRoomsYouOwnOrPostedIn() {
		Notices notices = new Notices(YOU, VENUE);
		long t = 10_000_000;
		AMap<AString, ACell> mine = room(YOU, 0);
		AMap<AString, ACell> theirs = room(VENUE + ":u:robin", 0);
		assertTrue(notices.offer(view(mine, theirs), t).isEmpty(), "what is already there is never news");

		mine = room(YOU, 500); // a flood: five hundred posts from an outside agent
		theirs = room(VENUE + ":u:robin", 3);
		List<Notices.Notice> first = notices.offer(view(mine, theirs), t + 1000);
		assertEquals(1, first.size(), "one grouped notification, only for the room you own");
		assertEquals("mine", first.get(0).room());
		assertEquals(500, first.get(0).posts());
		assertFalse(first.get(0).text().contains("<"), "plain words");

		mine = room(YOU, 520);
		assertTrue(notices.offer(view(mine, theirs), t + 30_000).isEmpty(), "not again within the minute");
		List<Notices.Notice> later = notices.due(t + 62_000);
		assertEquals(1, later.size(), "the rest, grouped, once the minute is up");
		assertEquals(20, later.get(0).posts());
	}

	private static Rooms.Post post(String from, String via, String text) {
		return new Rooms.Post(0, 0, from, via, text);
	}

	/** A room owned by {@code owner} holding {@code n} posts from an outside agent. */
	private static AMap<AString, ACell> room(String owner, int n) {
		Index<convex.core.data.Blob, ACell> posts = Index.none();
		for (int i = 0; i < n; i++) {
			posts = posts.assoc(convex.core.data.Blobs.forLong(i), Maps.of("n", (long) i, "ts", (long) i, "from", owner,
				"via", "relay", "text", "post " + i));
		}
		return Maps.of("owner", owner, "next", (long) n, "posts", posts);
	}

	private static ACell view(AMap<AString, ACell> mine, AMap<AString, ACell> theirs) {
		return Maps.of(Strings.create("mine"), mine, Strings.create("theirs"), theirs);
	}
}
