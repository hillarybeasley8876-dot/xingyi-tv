package xyz.erapi0neer.xingyi.agent;

import org.json.JSONObject;

/** One capability the agent can call. Implementations live in agent.tv; AgentLoop only sees this. */
public interface Tool {
    String name();

    /** Decides, before anything runs, whether this call only reads, acts, needs the user's consent, or is refused. */
    Gate gate(JSONObject args, Turn turn);

    /** Short Chinese description of the call for the progress panel and consent prompts. */
    String describe(JSONObject args, Turn turn);

    /** Returns {ok:boolean, status, message, ...}. Exceptions become ok=false results for the model. */
    JSONObject run(JSONObject args, Turn turn) throws Exception;

    /** What a tool may ask of the running task. */
    interface Turn {
        String utterance();
        /** Where the request came from: xiaoai, ui, phone, pc, mcp, debug. */
        String source();
        boolean cancelled();
        /** Sub-status for long tools, e.g. download progress. */
        void progress(String text);
        /** A short line the host may speak right now, e.g. 我查一下. Who hears it is the host's decision. */
        void say(String text);
        /** The model round (1-based) this call belongs to; tools that budget themselves per round read it. */
        int round();
    }

    final class Gate {
        public enum Risk { READ, ACT, SENSITIVE, DENY }
        public final Risk risk;
        /** For SENSITIVE: what the user is asked to approve. For DENY: why it is refused. */
        public final String reason;
        private Gate(Risk risk, String reason) { this.risk = risk; this.reason = reason == null ? "" : reason; }
        private static final Gate READ = new Gate(Risk.READ, ""), ACT = new Gate(Risk.ACT, "");
        public static Gate read() { return READ; }
        public static Gate act() { return ACT; }
        public static Gate sensitive(String what) { return new Gate(Risk.SENSITIVE, what); }
        public static Gate deny(String why) { return new Gate(Risk.DENY, why); }
    }
}
