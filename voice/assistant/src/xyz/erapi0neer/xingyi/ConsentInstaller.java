package xyz.erapi0neer.xingyi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.agent.ApkConsent;

/**
 * 「你点头就装」 (2026-11-07): an app that is in neither the Xiaomi store's catalog nor the vetted OfficialApps may be
 * fetched from the maker's own site, but nothing is installed until the user has heard the source site, the developer
 * in the signing certificate, the package and the size, and said 「好的」. Two calls of install_app: this prepares
 * (downloads into the running app's own cache and reads the archive, {@link #prepare}), then installs the file that
 * call left behind ({@link #install}).
 *
 * The TV installs through the local ADB (`pm install`), which never shows Android's own 「安装未知应用」 prompt — the
 * sentence {@code ApkConsent.sentence} builds is the only confirmation the user gets, so every part of it is read off
 * the downloaded file itself, never off what the page claimed.
 */
public final class ConsentInstaller {
    private static final String UA = "Mozilla/5.0";
    /** A maker's own download may redirect to its CDN; anything past this many hops is not the maker. */
    private static final int HOPS = 5;
    /** The running app's own cache directory is the only place LocalAdb copies an install from. */
    private static final String PREFIX = "xingyi-consent-";
    /** package → the signing key it was first installed with (files/agent/installed_signers.json). */
    private static final String SIGNERS = "installed_signers.json";

    /** What one prepared download is: the file, its digest as taken while downloading, and the facts said to the user. */
    private static final class Prepared {
        final File file;
        final String digest, pkg;
        final int versionCode;
        final JSONObject facts;
        final long at;
        Prepared(File file, String digest, String pkg, int versionCode, JSONObject facts, long at) {
            this.file = file; this.digest = digest; this.pkg = pkg; this.versionCode = versionCode;
            this.facts = facts; this.at = at;
        }
    }

    /** The ten minutes' worth of downloads waiting for an answer, in this process only: a restart drops them. */
    private static final Map<String, Prepared> PREPARED = new LinkedHashMap<>();

    /** What the host shows while this runs. */
    public interface Progress { void report(String stage) throws Exception; }

    /** A refusal the user is told the reason for — not a crash and not a network failure. */
    private static final class Refused extends Exception {
        Refused(String why) { super(why); }
    }

    private ConsentInstaller() { }

    /** The facts of a prepared download, for the sentence the user is asked to agree to; null when unknown or expired. */
    public static synchronized JSONObject facts(String token) {
        Prepared p = token == null ? null : PREPARED.get(token);
        return p == null || ApkConsent.expired(p.at, System.currentTimeMillis()) ? null : p.facts;
    }

    /** What one download came back with. */
    private static final class Fetch {
        final String digest;
        final long size;
        Fetch(String digest, long size) { this.digest = digest; this.size = size; }
    }

    /** What the model is told to do with the facts: say them, then install only on the user's 「好的」. */
    // The confirmation is the sensitive gate of install_app {token}: it reads these facts to the user and waits for 「好的」.
    // A text reply would end the task and lose the token (2026-11-07 review).
    private static final String NOTE = "还没安装。签名上的公司是这个应用的开发者，就直接调用 install_app {\"token\":…}（只能用一次）："
            + "系统会把来源网站、开发者签名、包名和大小念给用户并请他说「好的」，同意了才装。签名上的公司明显不是开发者"
            + "（个人名字、下载站、破解者），就不要调用，直接告诉用户这个安装包不可信。";

    /**
     * Downloads the installer the model found on the maker's own site and reads what it really is. Nothing is
     * installed: the file waits in the cache for ten minutes under a token, and the facts the user has to hear come
     * back with it.
     */
    public static synchronized JSONObject prepare(Context context, String url, String page,
            BooleanSupplier cancelled, Progress progress) throws Exception {
        sweep(context);
        String host = ApkConsent.host(url);
        if (!ApkConsent.urlOk(url)) return failed("refused", "下载地址不是官网上 https:// 开头的直链，没有下载");
        if (ApkConsent.blocked(host)) return failed("refused", host + " 是下载站或免费托管，不是开发者的官网，没有下载");
        String pageHost = page == null || page.trim().isEmpty() ? "" : ApkConsent.host(page);
        File cache = context.getExternalCacheDir();
        if (cache == null) return failed("refused", "电视安装缓存不可用，没有下载");
        String token = token();
        File apk = new File(cache, PREFIX + token + ".apk");
        boolean keep = false;
        try {
            progress.report("正在从 " + host + " 下载");
            Fetch fetched = download(url, apk, cancelled, progress);
            progress.report("下载完成，正在读取安装包里的包名和签名");
            Prepared p = inspect(context, apk, fetched, host, pageHost, token, progress);
            PREPARED.put(token, p);
            keep = true;
            String version = p.facts.optString("version");
            return new JSONObject(p.facts.toString()).put("ok", true).put("status", "needs_consent").put("note", NOTE)
                    .put("message", "「" + p.facts.optString("label") + "」已经下载好（" + p.pkg
                            + (version.isEmpty() ? "" : " " + version) + "，" + p.facts.optLong("size_mb")
                            + " MB，从 " + host + " 下载），还没有安装");
        } catch (Refused why) {
            return failed("refused", why.getMessage());
        } catch (Exception e) {
            String why = e.getMessage() == null || e.getMessage().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
            return failed("download_failed", "没能从 " + host + " 下载安装包：" + why);
        } finally {
            if (!keep) apk.delete();
        }
    }

    /** Installs the file {@link #prepare} left behind — and only that file, only with the digest taken while it came
     * down, only if Android really installed that version with that signer afterwards. */
    public static synchronized JSONObject install(Context context, String token,
            BooleanSupplier cancelled, Progress progress) throws Exception {
        Prepared p = token == null ? null : PREPARED.get(token);
        sweep(context); // also drops a download whose ten minutes are up, and its file
        if (p != null) PREPARED.remove(token); // a token is used once
        if (p == null) return failed("unknown_token",
                "这个安装确认不存在或已经用过：重新下载，并把来源、签名、包名和大小告诉用户之后再装");
        if (ApkConsent.expired(p.at, System.currentTimeMillis())) {
            p.file.delete();
            return failed("expired", "下载好的安装包已经放了超过 10 分钟，为了安全没有安装：重新下载并让用户确认");
        }
        String label = p.facts.optString("label");
        progress.report("正在安装「" + label + "」");
        String receipt;
        LocalAdb adb = new LocalAdb(context);
        try {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            receipt = adb.installVerified(context.getPackageName(), p.file.getName(), p.digest);
        } catch (IOException lostReceipt) {
            receipt = "未收到系统回执";
        } finally {
            adb.cancel();
            p.file.delete();
        }
        progress.report("正在核对电视实际装上的版本和签名");
        PackageInfo now = installed(context.getPackageManager(), p.pkg);
        // p.digest is the file's hash (what pm install was handed); the signer is in the facts
        String signer = p.facts.optString("signer_sha256");
        if (now == null || now.versionCode != p.versionCode || !signer.equals(digest(signers(now))))
            return failed("unconfirmed", "电视没有装上「" + label + "」（" + receipt + "）：没有确认成功，不要说装好了");
        remember(context, p.pkg, signer);
        return new JSONObject(p.facts.toString()).put("ok", true).put("status", "installed")
                .put("message", "已安装「" + label + "」（" + p.pkg + " " + now.versionName + "），在首页的「全部」里，"
                        + "也可以直接说“打开" + label + "”");
    }

    /** Reads what the downloaded file really is; every refusal says why, in the user's words. */
    private static Prepared inspect(Context context, File apk, Fetch fetched, String host, String pageHost,
            String token, Progress progress) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags());
        if (info == null || info.packageName == null || info.packageName.isEmpty() || info.applicationInfo == null)
            throw new Refused("下载到的文件不是能读的 Android 安装包，没有安装");
        String pkg = info.packageName;
        ApplicationInfo ai = info.applicationInfo;
        // loadLabel reads the name out of the archive only once it knows where the archive is
        ai.sourceDir = apk.getAbsolutePath();
        ai.publicSourceDir = apk.getAbsolutePath();
        String label = label(pm, ai, pkg);
        int min = minSdk(ai);
        if (min > Build.VERSION.SDK_INT)
            throw new Refused("「" + label + "」要求 Android " + min + " 以上，这台电视是 Android " + Build.VERSION.SDK_INT + "，装不了");
        Signature[] signers = signers(info);
        if (signers.length != 1)
            throw new Refused("这个安装包有 " + signers.length + " 个签名，不是开发者唯一的正式签名，没有安装");
        String key = digest(signers);
        String dn = subjectDn(signers[0]);
        if (ApkConsent.debugKey(dn, key))
            throw new Refused("这个安装包是用调试或测试密钥签名的，不是开发者的正式签名，没有安装");
        PackageInfo onTv = installed(pm, pkg);
        if (onTv != null && !key.equals(digest(signers(onTv))))
            throw new Refused("电视上的「" + label + "」（" + pkg + "）是另一个签名，来源不明，没有安装");
        String first = remembered(context).optString(pkg, "");
        if (!first.isEmpty() && !first.equals(key))
            throw new Refused("「" + label + "」上次安装时的签名和这次不一样，没有安装");
        List<String> entries = entries(apk);
        if (!ApkConsent.abiOk(entries, Build.SUPPORTED_ABIS))
            throw new Refused("「" + label + "」里的原生代码只有这台电视跑不了的架构（" + abis(entries) + "），装上会打不开");
        String[] who = ApkConsent.subject(dn);
        String version = info.versionName == null ? "" : info.versionName;
        JSONObject facts = new JSONObject().put("token", token).put("label", label).put("package", pkg)
                .put("version", version).put("size_mb", Math.round(fetched.size / 1048576.0))
                .put("signer_org", who[0]).put("signer_cn", who[1]).put("signer_sha256", key)
                .put("host", host).put("page_host", pageHost);
        progress.report("已读取：" + pkg + " " + version + "，" + (who[0].isEmpty() ? who[1] : who[0]));
        return new Prepared(apk, fetched.digest, pkg, info.versionCode, facts, System.currentTimeMillis());
    }

    /** Downloads the whole package, following only https redirects that stay off the download sites. */
    private static Fetch download(String start, File out, BooleanSupplier cancelled, Progress progress) throws Exception {
        String url = start;
        for (int hop = 0; ; hop++) {
            if (hop >= HOPS) throw new IOException("下载地址跳转次数过多，已停止");
            if (cancelled.getAsBoolean()) throw new IOException("已取消下载");
            HttpsURLConnection net = (HttpsURLConnection) new URL(url).openConnection();
            net.setInstanceFollowRedirects(false);
            net.setRequestProperty("User-Agent", UA);
            net.setRequestProperty("Accept-Encoding", "identity");
            net.setConnectTimeout(12000);
            net.setReadTimeout(20000);
            try {
                int code = net.getResponseCode();
                if (code != 301 && code != 302 && code != 307 && code != 308) return copy(net, out, code, cancelled, progress);
                String location = net.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) throw new IOException("下载地址跳转没有目标，已停止");
                String next = new URL(new URL(url), location.trim()).toString();
                if (!ApkConsent.urlOk(next) || ApkConsent.blocked(ApkConsent.host(next)))
                    throw new IOException("下载地址跳转到了不是开发者官网的站点（" + ApkConsent.host(next) + "），已停止");
                url = next;
            } finally {
                net.disconnect();
            }
        }
    }

    private static Fetch copy(HttpsURLConnection net, File out, int code,
            BooleanSupplier cancelled, Progress progress) throws Exception {
        long size = net.getContentLengthLong();
        // no Range is ever asked for, so anything but a whole 200 could be a truncated package
        if (code != 200 || size < 1 || size > ApkConsent.LIMIT)
            throw new IOException("服务器没有返回完整的 Android 安装包（HTTP " + code + "）");
        File dir = out.getParentFile();
        if (dir == null || dir.getUsableSpace() < size * 3) throw new IOException("电视可用空间不足");
        // 6 分钟，再加每 MiB 一秒：一个 300 MiB 的包在慢网络上也要能下完
        long deadline = SystemClock.elapsedRealtime() + 360000 + size / 1024;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long n = 0;
        int step = 0;
        progress.report("开始下载，共 " + (size / 1048576) + " MiB");
        try (InputStream in = net.getInputStream(); OutputStream file = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            for (int read; (read = in.read(buf)) != -1; ) {
                if (cancelled.getAsBoolean()) throw new IOException("已取消下载");
                n += read;
                if (n > size || SystemClock.elapsedRealtime() > deadline) throw new IOException("下载超时或数据过长");
                file.write(buf, 0, read);
                sha.update(buf, 0, read);
                int percent = (int) (n * 100 / size);
                if (percent / 10 > step) {
                    step = percent / 10;
                    progress.report("已下载 " + percent + "%（" + (n / 1048576) + " / " + (size / 1048576) + " MiB）");
                }
            }
        }
        if (n != size) throw new IOException("安装包下载不完整");
        return new Fetch(hex(sha.digest()), size);
    }

    /** Prepared files older than ten minutes, and the ones a dead process left behind (no token can reach them again):
     * deleted the next time install_app runs. */
    private static void sweep(Context context) {
        File cache = context.getExternalCacheDir();
        File[] files = cache == null ? null : cache.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        Set<String> known = new HashSet<>();
        for (Iterator<Map.Entry<String, Prepared>> it = PREPARED.entrySet().iterator(); it.hasNext(); ) {
            Prepared p = it.next().getValue();
            if (ApkConsent.expired(p.at, now)) { it.remove(); p.file.delete(); continue; }
            known.add(p.file.getName());
        }
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(PREFIX) && name.endsWith(".apk") && !known.contains(name)) f.delete();
        }
    }

    /** Android 9's archive parser collects certificates only with the legacy flag set. */
    private static int flags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES : PackageManager.GET_SIGNATURES;
    }

    private static PackageInfo installed(PackageManager pm, String pkg) {
        try { return pm.getPackageInfo(pkg, flags()); }
        catch (PackageManager.NameNotFoundException absent) { return null; }
    }

    private static Signature[] signers(PackageInfo info) {
        Signature[] s = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        return s == null ? new Signature[0] : s;
    }

    private static String digest(Signature[] signers) throws Exception {
        if (signers.length != 1) return "";
        return hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
    }

    /** The signer's name as the certificate itself carries it (RFC 2253), read through a real X.509 parse. */
    private static String subjectDn(Signature signature) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(signature.toByteArray()));
        return cert.getSubjectX500Principal().getName();
    }

    private static String label(PackageManager pm, ApplicationInfo ai, String pkg) {
        try {
            CharSequence name = ai.loadLabel(pm);
            if (name != null && !name.toString().trim().isEmpty()) return name.toString().trim();
        } catch (RuntimeException unreadable) {
            // a package with no readable name is still a package: the user hears its name
        }
        return pkg;
    }

    /** applicationInfo.minSdkVersion only exists from Android 7 on; 0 when this platform cannot say. */
    private static int minSdk(ApplicationInfo ai) {
        try { return ai.minSdkVersion; }
        catch (Throwable absent) { return 0; }
    }

    private static List<String> entries(File apk) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) out.add(e.nextElement().getName());
        }
        return out;
    }

    /** The ABIs the package ships native code for, for the refusal the user reads. */
    private static String abis(List<String> entries) {
        Set<String> libs = new TreeSet<>();
        for (String e : entries) {
            if (!e.startsWith("lib/")) continue;
            String rest = e.substring(4);
            int slash = rest.indexOf('/');
            if (slash > 0 && slash < rest.length() - 1) libs.add(rest.substring(0, slash));
        }
        return libs.isEmpty() ? "无" : libs.toString();
    }

    /** package → the key it was first installed with; a later install with another key is refused. */
    private static JSONObject remembered(Context context) {
        File file = new File(context.getFilesDir(), "agent/" + SIGNERS);
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(read(in));
        } catch (Exception absent) {
            return new JSONObject();
        }
    }

    private static void remember(Context context, String pkg, String digest) throws IOException, JSONException {
        JSONObject all = remembered(context);
        if (all.has(pkg)) return; // the first install wins: an update never rewrites the key it must match
        all.put(pkg, digest);
        File dir = new File(context.getFilesDir(), "agent");
        dir.mkdirs();
        File tmp = new File(dir, SIGNERS + ".tmp"), dst = new File(dir, SIGNERS);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(all.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(dst)) throw new IOException("写入安装签名记录失败");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 16 hex characters: the token is the only handle the model has on the file it just downloaded. */
    private static String token() {
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        return hex(raw);
    }

    private static String hex(byte[] data) {
        StringBuilder s = new StringBuilder(data.length * 2);
        for (byte b : data) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    private static JSONObject failed(String status, String why) throws JSONException {
        return new JSONObject().put("ok", false).put("status", status).put("message", why);
    }
}
