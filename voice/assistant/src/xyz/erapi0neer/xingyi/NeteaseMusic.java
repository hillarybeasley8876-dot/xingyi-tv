package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** One confirmed request resolves a catalog recording, opens it in NetEase TV, and checks the player. */
final class NeteaseMusic {
    private static final String PLAYER = "com.netease.cloudmusic.tv";

    interface Progress { void report(String stage, long recordingId); }

    static JSONObject play(Context activity, String title, String artist, BooleanSupplier cancelled)
            throws Exception {
        return execute(activity,title,artist,cancelled,null);
    }

    /** Only for a single-use MCP ticket. A null title denotes an explicit request for the artist's music. */
    static JSONObject playAutomatic(Context activity, String title, String artist, BooleanSupplier cancelled,
            Progress progress) throws Exception {
        if (progress == null) throw new IllegalArgumentException("点歌回执回调缺失");
        return execute(activity,title,artist,cancelled,progress);
    }

    private static JSONObject execute(Context activity, String title, String artist, BooleanSupplier cancelled,
            Progress progress) throws Exception {
        if (activity.getPackageManager().getLaunchIntentForPackage(PLAYER) == null) {
            return result(false, "unavailable", "电视上没有可打开的网易云音乐。");
        }
        NeteaseCatalog.Match recording;
        try {
            if (artist == null) recording = NeteaseCatalog.findByTitle(title);
            else if (title == null) recording = NeteaseCatalog.findByArtist(artist);
            else recording = NeteaseCatalog.find(title, artist);
        } catch (IOException e) {
            return result(false, "lookup_failed", "无法从网易云曲库查到结果，请稍后再试。");
        }
        if (recording == null) {
            String missing = artist == null ? "网易云没有找到这个歌名。" : title == null
                    ? "网易云没有找到这位歌手可点播的歌曲。" : "网易云曲库没有找到同时符合歌名和歌手的版本。";
            return result(false, "not_found", missing);
        }
        if (cancelled.getAsBoolean()) return result(false, "cancelled", "点歌已取消。");
        if (progress != null) {
            progress.report("checking_playback",recording.id);
            JSONObject stop = checkBeforeStarting(activity,recording,title,artist,cancelled);
            if (stop != null) return stop;
        }
        Uri uri = new Uri.Builder().scheme("nmcloudmusictv").authority("ng").path("/player")
                .appendQueryParameter("RESOURCE_ID", Long.toString(recording.id)).build();
        Intent play = new Intent(Intent.ACTION_VIEW, uri).setPackage(PLAYER)
                .putExtra("RESOURCE_ID", Long.toString(recording.id));
        try {
            if (!launch(activity,play,cancelled)) return result(false, "cancelled", "点歌已取消。");
            if (progress != null) progress.report("intent_submitted",recording.id);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ActivityNotFoundException || e.getCause() instanceof SecurityException)
                return result(false, "unavailable", "当前网易云音乐版本未提供可用的点播入口。");
            throw e;
        } catch (TimeoutException e) {
            return result(false, "unknown", "网易云点播入口未及时响应，无法确认播放状态。");
        }

        LocalAdb adb = new LocalAdb(activity);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        int readErrors = 0;
        try {
            do {
                if (cancelled.getAsBoolean()) return result(false, "cancelled", "已停止核对播放状态。");
                boolean playing = false;
                try {
                    playing = adb.isNetEasePlaying(recording.displayTitles, recording.artist, recording.album);
                } catch (IOException e) {
                    if (++readErrors >= 3 || SystemClock.elapsedRealtime() >= deadline) {
                        return result(false, "unconfirmed", "已请求网易云点播，无法核对播放状态：" + e.getMessage());
                    }
                    // Only reopen the observation socket. Never dispatch the play intent twice.
                    adb.cancel();
                    adb = new LocalAdb(activity);
                }
                if (playing) {
                    return observed(recording,"observed");
                }
                Thread.sleep(600);
            } while (SystemClock.elapsedRealtime() < deadline);
        } finally {
            adb.cancel();
        }
        // 「虽然他要付费你没法播放，但是你要和我说没法播放」(2026-11-07): a members-only or paid-album song that never
        // started is said to be one, not left at 「没能确认」
        if (recording.fee == 1 || recording.fee == 4)
            return result(false, "paid", "网易云没有放出" + recording.artist + "的《" + recording.title + "》：" + paid(recording.fee)
                    + "，电视上的网易云账号放不了。").put("title", recording.title).put("artist", recording.artist);
        return result(false, "unconfirmed", "已请求网易云点播，但播放器尚未报告目标歌曲正在播放。请查看网易云界面上的提示。");
    }

    /** What NetEase's fee type means for someone without a membership, in words. */
    static String paid(int fee) {
        return fee == 1 ? "这首是网易云会员歌曲" : fee == 4 ? "这首要单独购买数字专辑才能听" : "";
    }

    private static boolean launch(Context activity, Intent intent, BooleanSupplier cancelled) throws Exception {
        FutureTask<Boolean> request = new FutureTask<>(() -> {
            if (cancelled.getAsBoolean() || (activity instanceof Activity && ((Activity) activity).isDestroyed())) return false;
            // The agent engine calls this from the background; outside an activity a new task is required.
            if (!(activity instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        });
        new Handler(Looper.getMainLooper()).post(request);
        try { return request.get(4,TimeUnit.SECONDS); }
        catch (TimeoutException e) { request.cancel(false); throw e; }
    }

    private static JSONObject result(boolean ok, String status, String message) throws JSONException {
        return new JSONObject().put("ok", ok).put("status", status).put("message", message);
    }

    private static JSONObject observed(NeteaseCatalog.Match recording,String status) throws JSONException {
        // a members-only song plays a 30-second sample to an account without a membership: say so up front
        String note = recording.fee == 1 ? "这首是网易云会员歌曲：账号不是会员的话，只能听 30 秒左右的试听。" : "";
        return result(true,status,"网易云音乐正在播放" + recording.artist + "的《" + recording.title + "》。" + note)
                .put("title",recording.title).put("artist",recording.artist)
                .put("album",recording.album).put("track_id",recording.id).put("members_only", recording.fee == 1);
    }

    /** A missing acknowledgement or buffering player cannot authorize a second play intent. */
    private static JSONObject checkBeforeStarting(Context activity, NeteaseCatalog.Match recording, String title, String artist,
            BooleanSupplier cancelled) throws Exception {
        LocalAdb reader = new LocalAdb(activity);
        long deadline = SystemClock.elapsedRealtime() + 14000;
        boolean playerRaised = false;
        String reason="播放器尚未提供两次稳定的曲目信息";
        // One snapshot first: with no player (or one stopped or paused) nothing can be double-started, so the intent goes
        // out at once. Only a player that is playing needs the two samples below. Until 2026-11-18 every song started
        // from silence walked the whole 14 s budget (2 s a round) and the user heard 11-14 s of nothing (perf: 11 of 16
        // netease_play calls at 11-14 s; the same sentence with the song already playing took 805 ms).
        try {
            SongOutcome.Observation atStart = reader.readNetEasePlayback();
            if (atStart == null || atStart.state != 3) return null;
        } catch (IOException unavailable) {
            // can't tell: the samples below still decide, and their own error path is unchanged
        }
        try {
            do {
                if (cancelled.getAsBoolean()) return result(false,"cancelled","点歌已取消。");
                SongOutcome.Observation[] pair;
                try {
                    pair = reader.readNetEasePair();
                } catch (IOException unavailable) {
                    reason="读取网易云播放会话失败：" + unavailable.getMessage();
                    reader.cancel();
                    reader = new LocalAdb(activity);
                    Thread.sleep(250);
                    continue;
                }
                if (cancelled.getAsBoolean()) return result(false,"cancelled","点歌已取消。");
                SongOutcome.Observation first=pair[0], state=pair[1];
                if (first==null || state==null) {
                    reason="网易云播放会话存在缺失或冲突的读数";
                    continue;
                }
                boolean exact = SongOutcome.isExact(state,recording.displayTitles,recording.artist,recording.album);
                boolean artistAlready = title == null && artist != null && state != null && state.state == 3
                        && artist.equalsIgnoreCase(state.artist.split("/",2)[0].trim());
                boolean titleAlready = artist == null && SongOutcome.plausibleCredit(state,title,null);
                if (exact || artistAlready || titleAlready) {
                    if (!playerRaised) {
                        // The voice overlay can return a stale PLAYING state then pause the background
                        // player. Bring its existing task forward without issuing a play command.
                        Intent show = activity.getPackageManager().getLaunchIntentForPackage(PLAYER);
                        if (show == null || !launch(activity,show,cancelled))
                            return result(false,"unconfirmed","未能回到网易云核对已有播放。");
                        playerRaised = true;
                        continue;
                    } else if (stablePlaying(first,state)) {
                        if (exact) return observed(recording,"already_playing");
                        return result(true,artistAlready ? "artist_already_playing" : "title_already_playing","网易云音乐正在播放" + state.artist + "的《" + state.title + "》。")
                                .put("title",state.title).put("artist",state.artist).put("album",state.album);
                    }
                    continue;
                }
                // Another release can contain the same studio recording. Do not restart it
                // if the published title and full artist credit already match.
                if (SongOutcome.isExact(state,recording.displayTitles,recording.artist,null))
                    return result(false,"edition_uncertain","网易云正在播放同名、同歌手的其他专辑版本，已保留当前播放。");
                if (stableAlternative(first,state)) return null;
            } while (SystemClock.elapsedRealtime() < deadline);
        } finally {
            reader.cancel();
        }
        return result(false,"precheck_unknown",reason+"，本次没有发送点播指令。");
    }

    private static boolean sameTrack(SongOutcome.Observation first, SongOutcome.Observation second) {
        return java.util.Objects.equals(first.artist,second.artist) && java.util.Objects.equals(first.title,second.title)
                && java.util.Objects.equals(first.album,second.album) && second.updatedElapsedMs >= first.updatedElapsedMs;
    }

    static boolean stablePlaying(SongOutcome.Observation first, SongOutcome.Observation second) {
        return first != null && second != null && first.state == 3 && second.state == 3
                && second.sampledElapsedMs - first.sampledElapsedMs >= 1800
                && second.sampledElapsedMs - first.sampledElapsedMs <= 5000 && sameTrack(first,second);
    }

    static boolean stableAlternative(SongOutcome.Observation first, SongOutcome.Observation second) {
        if (first == null || second == null || first.state != second.state) return false;
        if (second.sampledElapsedMs - first.sampledElapsedMs < 600
                || second.sampledElapsedMs - first.sampledElapsedMs > 5000) return false;
        switch (second.state) {
            case 0: // no active player
            case 1: // stopped
            case 2: // paused
            case 3: // verified different recording
            case 7: // error
                return sameTrack(first,second);
            default: return false;
        }
    }

    private NeteaseMusic() {}
}
