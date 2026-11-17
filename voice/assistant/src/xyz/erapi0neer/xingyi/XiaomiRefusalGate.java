package xyz.erapi0neer.xingyi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Route a single declined Xiaomi turn. A refusal does not authorize a device operation. */
final class XiaomiRefusalGate {
    static final int MAX_ROWS = 64;
    private static final Pattern COMPOUND = Pattern.compile(
            "[；;\\r\\n]|然后|接着|并且|同时|顺便|之后再|以及|再帮我|再给我|完了再|并(?:打开|关闭|播放|把|将|设置)|(?:^|\\s)(?:and|then|also)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    static final class Assessment {
        String query, reason = "no_query";
        final List<String> types = new ArrayList<>();
        final List<String> categories = new ArrayList<>();
        boolean explicit;
        boolean musicSelection;
        String musicTitle;
    }

    static Assessment assess(String dialog, List<String> rows) {
        return assess(dialog,rows,null);
    }
    static Assessment assess(String dialog, List<String> rows, String precedingMusicTitle) {
        Assessment a = new Assessment();
        boolean veto = false;
        boolean hasPresentation = false;
        boolean hasSpokenText = false;
        boolean markedMulti = false;
        String nativeSpeech = null;
        int queries = 0, refusals = 0, speaks = 0;
        List<String> answers = new ArrayList<>();
        if (rows.size() > MAX_ROWS) { a.reason = "too_many_rows"; return a; }
        for (String row : rows) {
            try {
                if (row == null || row.length() > 32768) { a.reason = "invalid_row"; return a; }
                JSONTokener parser = new JSONTokener(row);
                Object parsed = parser.nextValue();
                if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) { a.reason = "invalid_row"; return a; }
                JSONObject record = (JSONObject)parsed;
                JSONObject header = record.getJSONObject("header"), payload = record.getJSONObject("payload");
                if (!dialog.equals(header.opt("dialog_id"))) { a.reason = "dialog_mismatch"; return a; }
                String type = header.optString("namespace") + "." + header.optString("name");
                if (hasAction(payload)) { a.reason = "interactive_presentation"; return a; }
                if (flagged(payload, "multi_intention") || flagged(payload, "multi_intention_non_speak")) {
                    for (String key : new String[]{"multi_intention","multi_intention_non_speak"}) {
                        if (payload.has(key) && !(payload.opt(key) instanceof Boolean)) { a.reason = "compound_instruction"; return a; }
                    }
                    markedMulti = true;
                }
                // Diagnostics must never copy unrecognized free-form fields.
                String safeType = type.matches("[A-Za-z][A-Za-z0-9]{0,40}\\.[A-Za-z][A-Za-z0-9]{0,48}") ? type : "Other";
                if (!a.types.contains(safeType)) a.types.add(safeType);
                if ("Template.Query".equals(type)) {
                    queries++;
                    Object q = payload.opt("text");
                    if (!(q instanceof String) || SharedTextDraft.text((String)q) == null) {
                        a.reason = "invalid_query"; return a;
                    }
                    a.query = (String)q;
                } else if ("SpeechSynthesizer.Speak".equals(type)) {
                    speaks++;
                    Object category = payload.opt("tts_category");
                    String safe = category instanceof String && ((String)category).matches(
                            "SUCC|SAME_STATE|NEED_CONFIRM|LIMIT|UNSUPPORT|EXCEPTION|CANCEL|UNKNOWN")
                            ? (String)category : category instanceof Number ? "numeric" : "missing_or_unknown";
                    if (!a.categories.contains(safe)) a.categories.add(safe);
                    Object spoken = payload.opt("text");
                    if (spoken != null && !(spoken instanceof String)) { a.reason = "malformed_spoken_answer"; return a; }
                    boolean audibleText = spoken instanceof String && !((String)spoken).trim().isEmpty();
                    // Some TTS rows carry audio without a transcript; then the whole displayed
                    // answer must independently pass the read-only grammar or the gate stays shut.
                    if (audibleText) { nativeSpeech = (String)spoken; answers.add(nativeSpeech); hasSpokenText = true; }
                    if ("UNSUPPORT".equals(safe) && audibleText) {
                        refusals++;
                    } else if (payload.has("tts_category")) {
                        veto = true;
                        a.reason = "not_exclusive_unsupported";
                    }
                } else if ("Template.Toast".equals(type)) {
                    hasPresentation = true;
                    if (payload.has("launcher") || payload.has("clickable_text") ||
                            payload.has("image") || payload.has("custom_background")) {
                        a.reason = "interactive_presentation"; return a;
                    }
                    int before = answers.size();
                    if (payload.has("text")) answers.add(payload.getString("text"));
                    if (payload.has("texts")) {
                        JSONArray parts = payload.getJSONArray("texts");
                        if (parts.length() > 12) { a.reason = "long_presentation"; return a; }
                        for (int i=0; i<parts.length(); i++) {
                            JSONObject part = parts.getJSONObject(i);
                            if (hasAction(part)) { a.reason = "interactive_presentation"; return a; }
                            answers.add(part.getString("text"));
                        }
                    }
                    if (before == answers.size()) { a.reason = "empty_presentation"; return a; }
                } else if ("Template.ToastStream".equals(type)) {
                    hasPresentation = true;
                    String markdown = payload.getString("markdown_text");
                    answers.add(streamText(markdown));
                } else if ("Nlp.LargeLanguageModelContent".equals(type)) {
                    hasPresentation = true;
                    if (flagged(payload,"bottom_button_display")) { a.reason = "interactive_presentation"; return a; }
                    // This card contains footer/explanation metadata, not an execution instruction.
                    Iterator<String> keys = payload.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (!"bottom_button_display".equals(key) && !"bottom_explain".equals(key)) {
                            a.reason = "unknown_presentation"; return a;
                        }
                    }
                } else {
                    veto = true;
                    a.reason = "additional_instruction";
                }
            } catch (Exception malformed) {
                a.reason = "invalid_row"; return a;
            }
        }
        if (queries != 1) { a.reason = queries == 0 ? "no_query" : "multiple_queries"; return a; }
        GenericRefusalText.Scope scope = GenericRefusalText.scope(a.query);
        if (scope==null && GenericRefusalText.looksLikeBiliSearch(a.query)) { a.reason="unbound_search"; return a; }
        if ((scope == null || scope.kind != GenericRefusalText.Kind.SEARCH) && (markedMulti || COMPOUND.matcher(a.query.toLowerCase(Locale.ROOT)).find())) {
            a.reason = "compound_query"; return a;
        }
        if (scope != null) a.musicTitle = scope.title;
        if (scope == null && speaks == 1) {
            GenericRefusalText.Scope selection = GenericRefusalText.musicSelection(a.query);
            if (selection != null) {
                boolean musical = selection.title.equalsIgnoreCase(precedingMusicTitle) || GenericRefusalText.nativeMusicReply(selection,nativeSpeech);
                if (musical) {
                    a.musicSelection = true;
                    scope = selection;
                }
            }
        }
        if (veto) return a;
        if (speaks != 1) { a.reason = speaks == 0 ? "no_complete_spoken_result" : "multiple_answers"; return a; }
        if (refusals == 1 && !hasPresentation) {
            a.reason = "explicit_unsupported";
        } else {
            String spokenOrDisplayed = null;
            for (String answer : answers) {
                String match = ReadOnlyRefusalText.classify(a.query, answer);
                if (match == null) match = GenericRefusalText.classify(scope, answer);
                if (match == null && scope == null && hasSpokenText && GenericRefusalText.isUnscopedRefusal(a.query,answer))
                    match = "native_capability_unavailable";
                if (match == null) { a.reason = "no_exclusive_supported_refusal"; return a; }
                spokenOrDisplayed = match;
            }
            if (spokenOrDisplayed == null) { a.reason = "no_explicit_refusal"; return a; }
            if ("native_capability_unavailable".equals(spokenOrDisplayed))
                a.reason = "explicit_harness_refusal";
            else
                a.reason = (scope != null && scope.kind != GenericRefusalText.Kind.READ
                        ? "explicit_scoped_action:" : "explicit_readonly_text:") + spokenOrDisplayed;
        }
        a.explicit = true;
        return a;
    }
    private static boolean flagged(JSONObject payload, String key) {
        return payload.has(key) && !Boolean.FALSE.equals(payload.opt(key));
    }
    private static boolean hasAction(JSONObject payload) {
        return payload.has("action") || payload.has("actions") || payload.has("launcher") || payload.has("clickable_text");
    }

    private static String streamText(String markdown) throws Exception {
        if (markdown.length() <= 1600 && markdown.length() % 4 == 0 &&
                markdown.matches("[A-Za-z0-9+/]+={0,2}")) {
            byte[] bytes = Base64.getDecoder().decode(markdown);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        return markdown;
    }
    private XiaomiRefusalGate() {}
}
