package xyz.erapi0neer.xingyi;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs RouteContractTest on the PC (2026-11-14). Until now it only ran on the TV, through the DUMP-only
 * HarnessContractTest broadcast, so a change to ModelRouter had no offline gate at all. Same package as the test, so
 * the package-private run() is reachable. Prints "ROUTE OK n scenarios" or every failure, and exits 1 on any.
 */
public final class RouteContractRun {
    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        int total = RouteContractTest.run(failures);
        for (String f : failures) System.out.println("FAIL " + f);
        if (!failures.isEmpty()) {
            System.out.println("route contract: " + failures.size() + " of " + total + " scenarios failed");
            System.exit(1);
        }
        System.out.println("ROUTE OK " + total + " scenarios");
    }
}
