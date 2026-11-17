package xyz.erapi0neer.xiaoaibridge;

/** One player's media session, read from `dumpsys media_session` text. */
final class NowPlaying {
    final boolean playing;
    final String title, artist, album;

    NowPlaying(boolean playing, String title, String artist, String album) {
        this.playing = playing;
        this.title = title;
        this.artist = artist;
        this.album = album;
    }

    @Override public String toString() { return (playing ? "playing " : "paused ") + artist + "《" + title + "》 " + album; }

    /** The given package's session (a playing one first); null if it has no metadata. */
    static NowPlaying parse(String dump, String pkg) {
        boolean inPlayer = false, active = false, playing = false;
        NowPlaying best = null;
        for (String row : dump.split("\n")) {
            String line = row.trim();
            if (line.startsWith("package=")) {
                inPlayer = line.equals("package=" + pkg);
                active = false;
                playing = false;
            } else if (inPlayer && line.startsWith("active=")) {
                active = line.equals("active=true");
            } else if (inPlayer && line.startsWith("state=PlaybackState {state=")) {
                playing = line.startsWith("state=PlaybackState {state=3,");
            } else if (inPlayer && line.startsWith("metadata:") && line.contains("description=")) {
                // MediaDescription.toString(): title, subtitle (artist), description (album)
                String[] f = line.substring(line.indexOf("description=") + 12).split(", ", -1);
                if (f.length < 2 || f[0].equals("null")) continue;
                NowPlaying np = new NowPlaying(active && playing, f[0], f[1], f.length > 2 ? f[f.length - 1] : "");
                if (best == null || np.playing) best = np;
            }
        }
        return best;
    }
}
