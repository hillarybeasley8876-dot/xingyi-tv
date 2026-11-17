#!/bin/sh
# Acceptance for job song-verify: the pieces are in place and wired. Prints "SONG VERIFY OK".
cd "$(dirname "$0")/.."
S=src/xyz/erapi0neer/xiaoaibridge
ok=1
grep -q "static boolean arrived(NowPlaying before, NowPlaying now, String wantedTitle, String keywords)" $S/SongArrival.java 2>/dev/null || { echo "SongArrival.arrived missing"; ok=0; }
grep -q "static String fallbackKeywords(String pickArtist, String pickTitle, String intentKeywords)" $S/SongArrival.java 2>/dev/null || { echo "SongArrival.fallbackKeywords missing"; ok=0; }
grep -q "import android\." $S/SongArrival.java 2>/dev/null && { echo "SongArrival must stay pure (no android.*)"; ok=0; }
grep -q "SongArrival.arrived" $S/Takeover.java || { echo "Takeover does not use SongArrival.arrived"; ok=0; }
grep -q "SongArrival.fallbackKeywords" $S/Takeover.java || { echo "Takeover does not use SongArrival.fallbackKeywords"; ok=0; }
grep -E -q "engine\.played\([a-zA-Z_]+, *np\.artist, *np\.title\)" $S/Takeover.java || { echo "done() does not pass the user's words to engine.played"; ok=0; }
grep -q "waitFor(gen, 9000, pick.title)" $S/Takeover.java && { echo "verifySong still waits only 9 s first"; ok=0; }
[ -f tests/SongArrivalTest.java ] || { echo "tests/SongArrivalTest.java missing"; ok=0; }
[ $ok = 1 ] && echo SONG VERIFY OK
