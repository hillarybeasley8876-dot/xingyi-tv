package xyz.erapi0neer.xingyi.agent;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Almanac against lunar_python, every day of 1950-2100: the lunar year, month (negative: leap), day and the term.
 * python tests/agent/almanac_gen.py --expect, then sh tests/agent/run_core.sh (compiles this) and
 * java -cp "build/agent-core-test;tests/lib/json-20240303.jar" xyz.erapi0neer.xingyi.agent.AlmanacVerify <expect file>
 */
final class AlmanacVerify {
    private AlmanacVerify() { }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "H:/tools/mitv/voice/assistant-out/tmp/almanac-expect.txt";
        int days = 0, bad = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.trim().split(" ");
                if (f.length < 5) continue;
                int y = Integer.parseInt(f[0].substring(0, 4)), m = Integer.parseInt(f[0].substring(5, 7)), d = Integer.parseInt(f[0].substring(8, 10));
                Almanac.Lunar l = Almanac.lunar(y, m, d);
                String term = Almanac.term(y, m, d);
                String got = l == null ? "null" : l.year + " " + (l.leap ? -l.month : l.month) + " " + l.day;
                String want = f[1] + " " + f[2] + " " + f[3];
                boolean ok = got.equals(want) && ("-".equals(f[4]) ? term == null : f[4].equals(term));
                long n = Almanac.days(y, m, d);
                int[] back = Almanac.civil(n);
                ok &= back[0] == y && back[1] == m && back[2] == d;
                if (!ok && bad++ < 20) System.out.println("MISMATCH " + f[0] + " want " + want + " " + f[4] + " got " + got + " " + term);
                days++;
            }
        }
        System.out.println("almanac verify: " + days + " days, " + bad + " mismatches");
        if (bad > 0) System.exit(1);
    }
}
