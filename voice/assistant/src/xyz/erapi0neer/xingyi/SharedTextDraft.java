package xyz.erapi0neer.xingyi;

/** A user-initiated, single-line shared draft shown in the editor until the user sends it. */
final class SharedTextDraft {
    enum Choice { IGNORE, FILL, ASK_TO_REPLACE }

    static Choice choice(String draft, String current, String last, boolean showingDialog) {
        if (draft == null || showingDialog || draft.equals(last)) return Choice.IGNORE;
        if (current.isEmpty() || current.equals(draft)) return Choice.FILL;
        return Choice.ASK_TO_REPLACE;
    }

    static boolean canReceive(boolean restored, boolean history, boolean working, boolean pending, boolean destroyed) {
        return !restored && !history && !working && !pending && !destroyed;
    }

    static String text(String raw) {
        if (raw == null || raw.length() > 320) return null;
        for (int offset = 0; offset < raw.length();) {
            int code = raw.codePointAt(offset);
            int kind = Character.getType(code);
            if (Character.isISOControl(code) || kind == Character.LINE_SEPARATOR || kind == Character.PARAGRAPH_SEPARATOR
                    || kind == Character.SURROGATE || (code >= 0x202a && code <= 0x202e)
                    || (code >= 0x2066 && code <= 0x2069)) return null;
            offset += Character.charCount(code);
        }
        int start = 0, end = raw.length();
        while (start < end && Character.isSpaceChar(raw.charAt(start))) start++;
        while (end > start && Character.isSpaceChar(raw.charAt(end - 1))) end--;
        return start == end ? null : raw.substring(start, end);
    }

    private SharedTextDraft() {}
}
