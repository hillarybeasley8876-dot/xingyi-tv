package xyz.erapi0neer.xingyi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Accepts the owner's reviewed transcript from an ADB-authorized computer. */
final class VoiceInputBridge extends BroadcastReceiver {
    static final String ACTION = "xyz.erapi0neer.xingyi.VOICE_QUERY";
    static final String REVIEW = "xyz.erapi0neer.xingyi.VOICE_REVIEW";
    interface Listener { int receiveUtterance(String text, String id); }
    interface Reviewer { void review(String id, String op, String preview, VoiceInputBridge reply); }
    private final Listener listener;
    private final Reviewer reviewer;

    VoiceInputBridge(Listener listener, Reviewer reviewer) { this.listener = listener; this.reviewer = reviewer; }

    static String encoded(String text) {
        return Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    void reply(int status, String encodedText) { setResult(status, encodedText, null); }

    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getPackageName().equals(intent.getPackage())) return;
        String id = intent.getStringExtra("request_id");
        if (id == null || !id.matches("[a-f0-9]{32}")) { setResultCode(4); return; }
        if (REVIEW.equals(intent.getAction())) {
            reviewer.review(id, intent.getStringExtra("op"), intent.getStringExtra("preview_b64"), this);
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;
        String encoded = intent.getStringExtra("text_b64");
        if (encoded == null || encoded.length() > 2048) return;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
                    Base64.decode(encoded, Base64.NO_WRAP))).toString().trim();
            if (text.isEmpty() || text.length() > 320) return;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isISOControl(text.charAt(i))) return;
            }
            setResultCode(listener.receiveUtterance(text, id));
        } catch (IllegalArgumentException | CharacterCodingException invalidTranscript) {
            setResultCode(0);
        }
    }
}
