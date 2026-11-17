package xyz.erapi0neer.xingyi;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets Android 9 MediaSessionRecord.dump without exposing other apps' metadata. */
final class MusicPlaybackEvidence {
    private static final String PLAYER = "com.netease.cloudmusic.tv";
    private static final Pattern PLAYING = Pattern.compile("^state=PlaybackState \\{state=3,.*");
    private static final Pattern DESCRIPTION = Pattern.compile("^metadata:\\s*size=[1-9]\\d*, description=(.*)$");

    static boolean matches(String dump, List<String> displayTitles, String creditedArtist, String album) {
        if (dump == null || displayTitles == null || displayTitles.isEmpty()
                || creditedArtist == null || creditedArtist.trim().isEmpty()) return false;
        boolean requireAlbum = album != null && !album.trim().isEmpty();
        boolean player = false, active = false, playing = false;
        for (String row : dump.split("\n")) {
            String line = row.trim();
            if (line.startsWith("package=")) {
                player = line.equals("package=" + PLAYER);
                active = false;
                playing = false;
            } else if (player && line.startsWith("active=")) {
                active = line.equals("active=true");
            } else if (player && line.startsWith("state=")) {
                playing = PLAYING.matcher(line).matches();
            } else if (player && active && playing && line.startsWith("metadata:")) {
                Matcher description = DESCRIPTION.matcher(line);
                if (!description.matches()) continue;
                // MediaDescription.toString() renders title, subtitle, description.
                // An embedded separator makes field boundaries ambiguous: fail closed.
                String[] fields = description.group(1).split(", ", -1);
                if (fields.length == 3 && !fields[0].isEmpty() && !fields[0].equals("null")
                        && displayTitles.contains(fields[0]) && !fields[1].equals("null")
                        && creditedArtist.equals(fields[1])
                        && (!requireAlbum || album.equals(fields[2]) && !fields[2].equals("null"))) return true;
            }
        }
        return false;
    }

}
