/**
 * Determine which teams in an MLB division are mathematically eliminated from
 * wining the division by the end of the regular season.
 * <p>
 * Usage: java BaseballElimination inputfile.txt
 *
 * @author William Schwartz
 */
public class BaseballElimination {
	private final HashMap<String, Integer> teams; // Map team name to index
	private final String[] ids; // Map index to team name
	private final int[] w, l, r; // Wins, losses, remaining games
	private final int[][] g; // Games left between teams i and j
	private Result last; // Cached last result
	private final int mostWins, leader; // For making trivialSearch() O(1)

	/**
	 * Create a baseball division from given filename in format specified below.
	 * <p>
	 * The input format is the number of teams in the division N followed by one
	 * line for each team. Each line contains the team name (with no internal
	 * whitespace characters), the number of wins, the number of losses, the
	 * number of remaining games, and the number of remaining games against each
	 * team in the divsion.
	 *
	 * @param filename The name of the file to read in as input.
	 */
	public BaseballElimination(String filename) {
		In file = new In(filename);
		int n = file.readInt();
		teams = new HashMap<String, Integer>(n + 1, 1.0); // Will never rehash
		w = new int[n];
		l = new int[n];
		r = new int[n];
		g = new int[n][n]; // XXX may be able to cut this in half (upper triangle)
		ids = new String[n];
		String name;
		int mostWins = 0, leader = 0;
		for (int i = 0; i < n; i++) {
			name = file.readString();
			teams.put(name, i);
			ids[i] = name;
			w[i] = file.readInt();
			l[i] = file.readInt();
			r[i] = file.readInt();
			for (int j = 0; j < n; j++)
				g[i][j] = file.readInt();
			if (w[i] > mostWins) {
				mostWins = w[i];
				leader = i;
			}
		}
		this.mostWins = mostWins;
		this.leader = leader;
	}

	/**
	 * Return the number of teams in the division.
	 */
	public int numberOfTeams() { return teams.size(); }

	/**
	 * Return iterable of team names.
	 */
	public Iterable<String> teams() { return teams.keySet(); }

	/**
	 * Return the number of wins <code>team</code> has already had.
	 */
	public int wins(String team) {
		isTeam(team);
		return w[teams.get(team)];
	}

	/**
	 * Return the number of losses <code>team</code> has already had.
	 */
	public int losses(String team) {
		isTeam(team);
		return l[teams.get(team)];
	}

	/**
	 * Return the number of remaining games <code>team</code> has already had
	 * left.
	 */
	public int remaining(String team) {
		isTeam(team);
		return r[teams.get(team)];
	}

	/**
	 * Return the number of games that were already remaining left to play
	 * between <code>team1</code> and <code>team2</code>.
	 */
	public int against(String team1, String team2) {
		isTeam(team);
		return g[teams.get(team1)][teams.get(team2)];
	}

	/**
	 * Return true if the given team cannot finish the season in first place in
	 * the division.
	 */
	public boolean isEliminated(String team) {
		solve(team);
		return last.eliminated;
	}

	/**
	 * If the given team cannot finish the season in first place in the division
	 * return an iterable of the names of the teams that beat it out. Otherwise
	 * return <code>null</code>.
	 */
	public Iterable<String> certificateOfElimination(String team) {
		solve(team);
		return last.betterTeams;
	}

	// Throw IllegalArgumentException if team name not recognized.
	private void isTeam(String team) {
		if (!teams.containsKey(team))
			throw new IllegalArgumentException("Unrecognized team: " + team);
	}

	// For holding the result of an elimination search. Default/"zeroed" Result
	// is an uneliminated team. To eliminate the team, simply add teams better
	// than it.
	private class Result {
		private final String team;
		private boolean eliminated;
		private Bag<String> betterTeams;

		public Result(int id) { this.team = ids[id]; }

		public void addBetterTeam(int id) {
			assert id != teams.get(team);
			if (cert == null) {
				betterTeams = new Bag<String>();
				eliminated = true;
			}
			betterTeams.add(ids[id]);
		}
	}

	// If no or wrong solution cached, first try a trivial solution, then do a
	// full search, and cache the result.
	private void solve(String team) {
		if (last != null && last.team == team)
			return;
		isTeam(team);
		int id = teams.get(team);
		last = trivialSearch(id);
		if (last == null)
			last = fullSearch(id);
	}

	// Check if a team is trivially eliminated, i.e., some other team has
	// already won more than this team could in the rest of the season. The
	// running time is constant in the worst case.
	private Result trivialSearch(int id) {
		if (id != leader && w[id] + r[id] < mostWins) {
			Result result = new Result(id);
			result.addBetterTeam(leader);
			return Result;
		}
		return null;
	}

	// Do a full max-flow/min-cut search to determine if the team is eliminated
	// The running time is O(n * (n + 3 * (n - 1) * (n - 2) / 2)) in the worst
	// case (i.e., O(n^3)).
	private Result fullSearch(int id) {
		FordFulkerson maxFlow = buildGraphFor(id);
		Result result = new Result(team);
		if (isEliminated(id, maxFlow.value()))
			// The better teams are those on the source side of the min cut
			for (int i = 0; i < numberOfTeams(); i++)
				if (maxFlow.inCut(i))
					result.addBetterTeam(i);
		return result;
	}

	/**
	 * Build a <code>FlowNetwork</code> graph representing the ways the division
	 * could beat team <code>id</code> and return the <code>FordFulkerson</code>
	 * object initialized with it.
	 * <p>
	 * In the graph, the source connects to games among <code>id<code>'s
	 * opponents, games connect to the teams playing them, and the teams connect
	 * to a sink.
	 * <p>
	 * Souce->game edges have capacity <code>g[i][j] <code> for teams
	 * <code>i</code>, <code>j</code>. Game->team edges are unrestricted.
	 * Team->sink edges have capacity equal to the difference between the most
	 * games team <code>id</code> <em>could</em> win and the number of games
	 * team <code>i</code> <em>has</em> won.
	 * <p>
	 * We return a <code>FordFulkerson<code> object rather than a
	 * <code>FlowNetwork</code> object to hide implementation: how we number the
	 * source and sink verticies.
	 * <p>
	 * The only guarantee about node numbering is that the nodes 0 to
	 * <code>numberOfTeams() - 1</code> correspond to the teams in
	 * <code>teams</code> and <code>ids</code>.
	 *
	 * @param id The id of the team we're trying eliminate
	 * @return A FordFulkerson object to query for max flow
	 */
	private FordFulkerson buildGraphFor(int id) {
		// 0 to n - 1 are team nodes, n and n + 1 are source and sink, and n + 2
		// to v (at the end of the loop) will be id's-opponents' team match ups.
		int n = numberOfTeams(), source = n, sink = n + 1, v = n + 2,
			maxcap = w[id] + r[id];
		double INF = Double.POSITIVE_INFINITY;
		Bag<FlowEdge> edges = new Bag<FlowEdge>();
		// Below, v is a game node, i and j are team nodes
		for (int i = 0; i < n; i++) {
			if (i == id)
				continue;
			for (int j = i + 1; j < n; j++) {
				if (j == id || g[i][j] == 0)
					continue;
				edges.add(new FlowEdge(source, v, g[i][j]));
				edges.add(new FlowEdge(v, i, INF));
				edges.add(new FlowEdge(v, j, INF));
				v++;
			}
			edges.add(new FlowEdge(i, sink, maxcap - w[i]));
		}
		FlowNetwork g = new FlowNetwork(v);
		for (FlowEdge e : edges)
			g.addEdge(e);
		return new FordFulkerson(g, source, sink);
	}

	/**
	 * Read in a sports division from an input file and print out whether each
	 * team is eliminated and a certificate of elimination for each such team.
	 *
	 * @author Kevin Wayne
	 */
	public static void main(String[] args) {
		BaseballElimination division = new BaseballElimination(args[0]);
		for (String team : division.teams()) {
			if (division.isEliminated(team)) {
				StdOut.print(team + " is eliminated by the subset R = { ");
				for (String t : division.certificateOfElimination(team))
					StdOut.print(t + " ");
				StdOut.println("}");
			}
			else
				StdOut.println(team + " is not eliminated");
		}
	}
}
