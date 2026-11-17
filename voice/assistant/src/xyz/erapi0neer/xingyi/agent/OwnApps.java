package xyz.erapi0neer.xingyi.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The developer's own test apps on this TV: never listed for the model, never opened by it.
 *
 * <p>2026-11-11 on the TV: 「为什么现在HARNESS面板没有打开」 was taken for a request to open an app — list_apps,
 * explore_app, open_app 星仪电视助手, open_app Fluid Lab. Fluid Lab is a test app whose screen is black, so the user
 * was left looking at a black TV. Nothing 星仪 needs to answer a question about its own panel lives in an app.
 */
public final class OwnApps {
    private static final Set<String> HIDDEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "xyz.erapi0neer.fluidlab", "xyz.erapi0neer.xingyi.voicecheck")));

    private OwnApps() { }

    /** Whether this package is one of the developer's own test apps. null and anything else: false. */
    public static boolean hidden(String pkg) {
        return pkg != null && HIDDEN.contains(pkg.trim());
    }
}
