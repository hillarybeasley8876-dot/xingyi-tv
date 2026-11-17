package xyz.erapi0neer.xingyi;

/** LocalAdb.late: which packets for another stream are a finished stream's leftovers (skipped) and which are a broken
 * connection (2026-11-08: adbd's CLSE for an already closed stream ended the next command with 「错误的通道」). */
public final class LocalAdbStrayTest {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static int pass, fail;

    private static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) {
        // the case seen on the TV: the reply to our OKAY of stream 1, arriving while stream 2 reads
        check(LocalAdb.late(CLSE, 1, 2), "adbd's CLSE for the finished stream 1 is skipped");
        check(LocalAdb.late(CLSE, 3, 9), "a CLSE for any earlier stream is skipped");
        check(LocalAdb.late(OKAY, 4, 5), "a late OKAY for the stream before is skipped");
        check(LocalAdb.late(WRTE, 4, 5), "a late WRTE for the stream before is skipped (and closed on the TV)");
        check(!LocalAdb.late(CLSE, 5, 5), "the current stream's own packet is not a leftover");
        check(!LocalAdb.late(CLSE, 6, 5), "a packet for a stream not opened yet is broken");
        check(!LocalAdb.late(WRTE, 0, 5), "a packet for stream 0 is broken");
        check(!LocalAdb.late(CLSE, -3, 5), "a negative stream id is broken");
        check(!LocalAdb.late(OPEN, 1, 5), "an OPEN from the TV is broken");
        check(!LocalAdb.late(AUTH, 1, 5), "an AUTH in the middle of a command is broken");
        check(!LocalAdb.late(CNXN, 1, 5), "a CNXN in the middle of a command is broken");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (LocalAdbStray)");
        if (fail > 0) System.exit(1);
    }
}
