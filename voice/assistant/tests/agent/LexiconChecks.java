package xyz.erapi0neer.xingyi.agent;

/** The lexicon's own checks, rule by rule (the job lexicon-core fills this in; the oracle is LexiconOracleChecks). */
final class LexiconChecks {
    private LexiconChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, "lexicon: " + label); }

    static void run() {
        if (!Lexicon.IMPLEMENTED) return;
        try {
            checks();
        } catch (Exception e) {
            check(false, "threw " + e);
        }
    }

    static void checks() throws Exception {
    }
}
