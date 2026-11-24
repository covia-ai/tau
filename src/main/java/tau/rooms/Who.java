package tau.rooms;

/**
 * Who made a post, read from the post's record and never from its text: you
 * in person, one of your agents, an outside agent (a post through an access
 * token, named by the token), someone else in your Home, or a visitor who is
 * not in your Home (a post through a room's public posting).
 */
public record Who(Kind kind, String name, String key) {

	public enum Kind {
		YOU("You"), AGENT("your agent"), OUTSIDE("outside agent"), HOME("in your Home"), VISITOR("not in your Home");

		/** How the kind reads beside a name. */
		public final String words;

		Kind(String words) {
			this.words = words;
		}
	}

	/**
	 * @param you   the acting principal's DID
	 * @param venue the venue's DID, whose users are in your Home
	 */
	public static Who of(Rooms.Post p, String you, String venue) {
		String from = (p.from() == null) ? "" : p.from();
		if (p.via() != null) return new Who(Kind.OUTSIDE, p.via(), from + "|via|" + p.via());
		if (from.equals(you)) return new Who(Kind.YOU, "You", from);
		int g = from.indexOf(":g:");
		if (g > 0 && from.substring(0, g).equals(you)) return new Who(Kind.AGENT, from.substring(g + 3), from);
		if (venue != null && from.startsWith(venue + ":")) {
			String rest = from.substring(venue.length() + 1);
			String name = rest.startsWith("u:") ? rest.substring(2) : rest;
			if (g > 0) name = from.substring(g + 3);
			return new Who(Kind.HOME, name, from);
		}
		return new Who(Kind.VISITOR, "visitor", from);
	}
}
