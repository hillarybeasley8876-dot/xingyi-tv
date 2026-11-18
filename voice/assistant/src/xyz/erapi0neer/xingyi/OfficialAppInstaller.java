package xyz.erapi0neer.xingyi;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.agent.OfficialApps;

/** Installs one vetted app (OfficialApps) from its own maker's channel: the fixed URL or the vendor's own GitHub
 * release, the signing key the lead checked, and the running app's own cache directory — nothing else is ever fetched,
 * and a package whose certificate is not the pinned one is refused before Android sees it. */
final class OfficialAppInstaller {
    static final String BILIBILI = "tv.danmaku.bili";
    static final String PREVIEW = "安装哔哩哔哩官方 Android 客户端（tv.danmaku.bili）";
    private static final String UA = "Mozilla/5.0";
    /** A release download goes through GitHub's own asset hosts; anything past this many hops is not the vendor. */
    private static final int HOPS = 5;
    private static final int API_LIMIT = 512 * 1024;
    private final Context context;
    private final OfficialApps.App app;
    private final PackageManager pm;
    private final BooleanSupplier cancelled;
    interface Progress { void report(String stage)throws Exception; }
    private final Progress progress;

    OfficialAppInstaller(Context context, OfficialApps.App app, BooleanSupplier cancelled, Progress progress) {
        this.context=context; this.app=app; this.pm=context.getPackageManager(); this.cancelled=cancelled; this.progress=progress;
    }

    /** The 0.x assistant's own install tool (ToolRegistry) still installs 哔哩哔哩 and nothing else. */
    OfficialAppInstaller(Context context, BooleanSupplier cancelled, Progress progress) {
        this(context, OfficialApps.BILIBILI, cancelled, progress);
    }

    /** Whether the TV already carries this app signed by its own key: then install_app opens it instead of asking. */
    static boolean installedTrusted(Context context, OfficialApps.App app) {
        try { return trusted(context.getPackageManager().getPackageInfo(app.pkg, flags()), app); }
        catch (Exception e) { return false; }
    }

    private static int flags() {
        // Android 9's archive parser collects certificates only with the legacy flag set.
        return Build.VERSION.SDK_INT>=28 ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES : PackageManager.GET_SIGNATURES;
    }
    private PackageInfo installed() {
        try { return pm.getPackageInfo(app.pkg,flags()); }
        catch (PackageManager.NameNotFoundException absent) { return null; }
    }
    private static String hex(byte[] data) {
        StringBuilder s=new StringBuilder(data.length*2);
        for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private static boolean trusted(PackageInfo info, OfficialApps.App app)throws Exception {
        if(info==null || !app.pkg.equals(info.packageName)) return false;
        Signature[] signers=Build.VERSION.SDK_INT>=28 && info.signingInfo!=null ?
                info.signingInfo.getApkContentsSigners() : info.signatures;
        if(signers==null || signers.length!=1) return false;
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        return OfficialApps.trusted(app,digest);
    }
    private void checkActive()throws IOException {
        if(cancelled.getAsBoolean()) throw new IOException("已取消安装");
    }
    private HttpsURLConnection open(String url, String referer)throws IOException {
        HttpsURLConnection net=(HttpsURLConnection)new URL(url).openConnection();
        net.setInstanceFollowRedirects(false);
        net.setRequestProperty("User-Agent",UA); net.setRequestProperty("Accept-Encoding","identity");
        if(referer!=null) net.setRequestProperty("Referer",referer);
        net.setConnectTimeout(12000); net.setReadTimeout(20000);
        return net;
    }
    private static String read(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192]; int n;
        for(int total=0; (n=in.read(buf))!=-1; ) {
            total+=n;
            if(total>limit) throw new IOException("官方发布页的回应过长");
            out.write(buf,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    /** Where the bytes come from: the maker's fixed URL, or the asset its own GitHub release publishes for this TV. */
    private String resolveSource()throws Exception {
        if(app.repo==null) return app.url;
        progress.report("正在读取 "+app.name+" 的官方发布页");
        HttpsURLConnection net=open(app.url,null);
        net.setRequestProperty("Accept","application/vnd.github+json");
        try {
            int code=net.getResponseCode();
            if(code!=200) throw new IOException(app.name+" 的官方发布页没有回应（HTTP "+code+"）");
            String body=read(net.getInputStream(),API_LIMIT);
            String asset=OfficialApps.githubAsset(new JSONObject(body),Build.SUPPORTED_ABIS,app);
            if(asset==null) throw new IOException(app.name+" 的官方发布页没有这台电视（"+abi()+"）能用的版本");
            return asset;
        } finally { net.disconnect(); }
    }
    private static String abi() {
        String[] abis=Build.SUPPORTED_ABIS;
        return abis==null || abis.length==0 ? "未知架构" : abis[0];
    }
    /** Downloads the whole package, following only the redirects this app's own source is allowed to make. */
    private String download(File out,String start)throws Exception {
        String url=start;
        for(int hop=0; ; hop++) {
            if(hop>=HOPS) throw new IOException("官方下载地址跳转次数过多，已停止");
            checkActive();
            HttpsURLConnection net=open(url,app.referer);
            net.setRequestProperty("Range","bytes=0-");
            try {
                int code=net.getResponseCode();
                if(code==301 || code==302 || code==307 || code==308) {
                    String to=net.getHeaderField("Location");
                    if(!OfficialApps.redirectAllowed(app,to))
                        throw new IOException("官方下载地址跳转到了不是官方的站点，已停止");
                    url=to; continue;
                }
                return copy(net,out,code);
            } finally { net.disconnect(); }
        }
    }
    private String copy(HttpsURLConnection net,File out,int code)throws Exception {
        long size=net.getContentLengthLong();
        if((code!=200 && code!=206) || size<1 || size>app.limit ||
                (code==206 && !("bytes 0-"+(size-1)+"/"+size).equals(net.getHeaderField("Content-Range"))))
            throw new IOException("官方服务器没有返回完整的 Android 安装包（HTTP "+code+"）");
        if(out.getParentFile().getUsableSpace()<size*3) throw new IOException("电视可用空间不足");
        // 6 分钟，再加每 MiB 一秒：TV Bro 的包有 150 MiB，慢网络上也要能下完
        long deadline=SystemClock.elapsedRealtime()+360000+size/1024;
        MessageDigest sha=MessageDigest.getInstance("SHA-256"); long n=0; int step=0;
        progress.report("正在从"+app.source+"下载，共 "+(size/1048576)+" MiB");
        try(InputStream in=net.getInputStream(); OutputStream file=new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf=new byte[64*1024]; int read;
            while((read=in.read(buf))!=-1) {
                checkActive(); n+=read;
                if(n>size || SystemClock.elapsedRealtime()>deadline) throw new IOException("官方下载超时或数据过长");
                file.write(buf,0,read); sha.update(buf,0,read);
                int percent=(int)(n*100/size);
                if(percent/10>step) {
                    step=percent/10; progress.report("已下载 "+percent+"%（"+(n/1048576)+" / "+(size/1048576)+" MiB）");
                }
            }
        }
        if(n!=size) throw new IOException("官方安装包下载不完整");
        return hex(sha.digest());
    }
    JSONObject install()throws Exception {
        progress.report("核对电视上已安装的包名和签名");
        PackageInfo existing=installed();
        if(existing!=null) {
            if(!trusted(existing,app)) throw new IOException("电视上的同名软件包与"+app.name+"官方签名不符");
            return verified(existing,"already_installed");
        }
        File cache=context.getExternalCacheDir();
        if(cache==null)throw new IOException("电视安装缓存不可用");
        // The running app's own cache directory (星仪 or the bridge that ships it): LocalAdb copies from that one only.
        File apk=File.createTempFile("xingyi-"+app.key+"-", ".apk", cache);
        String stage="官方下载";
        try {
            String digest=download(apk,resolveSource()); checkActive(); stage="包名和签名校验";
            progress.report("下载完成，正在校验官方包名和签名");
            PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags());
            if(!trusted(archive,app))throw new IOException("安装包名称或签名未通过"+app.name+"官方身份校验");
            progress.report("已验证官方签名，版本 "+archive.versionName);
            stage="Android 系统安装";
            progress.report("正在把已校验的安装包交给 Android 安装器");
            LocalAdb adb=new LocalAdb(context);
            String installResult;
            checkActive();
            // pm install of a ~150 MiB package says nothing for minutes (2026-11-08 01:50: 316 s of silence on the TV,
            // 2026-11-18 audit): say how long it has been waiting, every 15 s, on this thread's own progress channel.
            final boolean[] waiting={true};
            Thread beat=new Thread(() -> {
                long began=System.currentTimeMillis();
                while(waiting[0]) {
                    try { Thread.sleep(15000); } catch(InterruptedException stop) { return; }
                    if(!waiting[0]) return;
                    try { progress.report("Android 安装器还在装，已经等了 "+(System.currentTimeMillis()-began)/1000+" 秒（大应用的安装包要几分钟）"); }
                    catch(Exception ignored) { }
                }
            }, "install-beat");
            beat.setDaemon(true);
            beat.start();
            try { installResult=adb.installVerified(context.getPackageName(),apk.getName(),digest); }
            catch(IOException lostReceipt) { installResult="未收到系统回执"; }
            finally { waiting[0]=false; beat.interrupt(); adb.cancel(); }
            stage="安装结果核对";
            progress.report("正在核对电视实际安装的包名、版本和签名");
            PackageInfo info=installed();
            if(!trusted(info,app) || info.versionCode!=archive.versionCode) throw new IOException("电视没有安装目标版本（"+installResult+"）");
            progress.report("核对通过，已安装 "+app.pkg+" "+info.versionName);
            return verified(info,"installed");
        } catch(Exception e) {
            String reason=e instanceof IOException ? e.getMessage() : e.getClass().getSimpleName();
            if(reason==null || reason.length()>160)reason="处理失败";
            String message=stage+"未完成："+reason;
            progress.report(message);
            return new JSONObject().put("ok",false).put("status","install_failed").put("message",message);
        } finally { apk.delete(); }
    }
    private JSONObject verified(PackageInfo info,String status)throws Exception {
        return new JSONObject().put("ok",true).put("status",status).put("package",app.pkg).put("key",app.key)
            .put("version",info.versionName).put("message","电视已安装"+app.name+"官方 Android 客户端，版本 "+info.versionName+"。");
    }
}
