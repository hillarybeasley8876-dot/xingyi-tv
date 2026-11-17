package xyz.erapi0neer.xingyi;

import android.content.Context;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.agent.OfficialApps;

/** Public entry points into the verified 0.x flows (song playback, official install, XiaoAi hand-off),
 * so the agent package can call them without widening the original classes. */
public final class Legacy {
    public static final String BILIBILI = OfficialAppInstaller.BILIBILI;
    public static final String INSTALL_PREVIEW = OfficialAppInstaller.PREVIEW;
    private Legacy() { }

    /** Catalog lookup, NetEase player launch and playback check. title or artist may be null, not both. */
    public static JSONObject playNetease(Context context, String title, String artist, BooleanSupplier cancelled) throws Exception {
        return NeteaseMusic.play(context, title, artist, cancelled);
    }

    /** Downloads, verifies and installs one vetted app (OfficialApps) from its own maker's channel. */
    public static JSONObject installOfficial(Context context, String key, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        return new OfficialAppInstaller(context, OfficialApps.byKey(key), cancelled, stage -> progress.accept(stage)).install();
    }

    /** Whether the TV already carries this vetted app, signed by its own key. */
    public static boolean installedOfficial(Context context, String key) {
        OfficialApps.App app = OfficialApps.byKey(key);
        return app != null && OfficialAppInstaller.installedTrusted(context, app);
    }

    /** Downloads the installer the model found on an app's own maker's site and reads what it really is (source,
     * signer, package, size); nothing is installed until the user has heard those and agreed. */
    public static JSONObject prepareConsent(Context context, String url, String page,
            BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        return ConsentInstaller.prepare(context, url, page, cancelled, stage -> progress.accept(stage));
    }

    /** Installs the package {@link #prepareConsent} left in the cache, and only that one. */
    public static JSONObject installConsent(Context context, String token,
            BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        return ConsentInstaller.install(context, token, cancelled, stage -> progress.accept(stage));
    }

    /** The facts of a prepared download, for the sentence the user is asked to agree to; null when unknown or expired. */
    public static org.json.JSONObject consentFacts(String token) {
        return ConsentInstaller.facts(token);
    }

    /** Hands text to XiaoAi; the callback gets the dispatch status only. */
    public static void askXiaoai(Context context, String text, VendorQuery.Callback callback) {
        VendorQuery.dispatch(context, text, callback);
    }
}
