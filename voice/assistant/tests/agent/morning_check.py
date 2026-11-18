"""The checks of the 2026-11-06 overnight pass that need XiaoAi's voice (they speak at the TV's volume — not at night):
a long answer read page by page from its first page on (streaming), and the calendar's quick answers.

python tests/agent/morning_check.py --yes
Listen for: (1) the first page starting about 3 s after the question, the next pages following without a long gap,
the panel turning 1/… → 2/n → …; (2) 「今天是农历…」 at once; (3) 「还有…天」 with the days off and moved working days;
(4) 「早上好。今天是…」 with the weather; (5) the air: how XiaoAi reads 「AQI 约 63（PM2.5 45）」.
Prints when each answer first showed and was first spoken (perf_recent).
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = [r"H:\tools\platform-tools\adb.exe", "-s", "192.0.2.10:5555"]
QUESTIONS = ["详细讲讲为什么天空是蓝的，晚霞又为什么是红的", "今天农历几号", "还有几天过年", "早上好", "今天空气质量怎么样",
             # how XiaoAi reads 「20:58–21:03」 (the usage answer) and the sunset in words: 「比」 would need Speech to say times
             "昨天晚上电视开了多久", "几点天黑"]


def main():
    if "--yes" not in sys.argv:
        print(__doc__)
        print("Not run: add --yes when it is all right for the TV to speak.")
        return
    power = subprocess.run(ADB + ["shell", "dumpsys", "power"], capture_output=True, text=True).stdout
    if "mWakefulness=Awake" not in power:
        print("The screen is off: the panel will not be seen (the voice still works). Wake it first if you want to watch.")
    for q in QUESTIONS:
        print("\n>>>", q, flush=True)
        subprocess.run([sys.executable, os.path.join(HERE, "tvtask.py"), q, "--source", "debug", "--timeout", "150"])
    subprocess.run([sys.executable, os.path.join(HERE, "perf_recent.py"), str(len(QUESTIONS))])


if __name__ == "__main__":
    main()
