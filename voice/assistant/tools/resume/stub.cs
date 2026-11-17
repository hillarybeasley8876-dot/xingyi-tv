// tools/resume/stub.cs — the fake `claude` the rehearsal drives tools/m31w/run.mjs with.
//
// run.mjs spawns $SKYRUSH_CLAUDE with `spawn(CLAUDE, args)` and no shell, so the stub has to be a real executable:
// Node 24 answers `spawn(x.cmd)` with EINVAL, and node.exe itself dies on run.mjs's `--model` before any preload runs
// (both measured, 2026-11-12). So this is compiled to claude-stub.exe by the .NET Framework csc that ships with
// Windows (C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe) — no download, no install.
//
// What it must do for the runner to see a finished round: read the prompt on stdin, answer with one assistant message
// (the model it was asked for, so run.mjs does not call it a wrong model) and one result event, exit 0.
// What it must do for the rehearsal: write down the argv and the stdin it was given, so the report can say whether the
// runner passed --resume or --session-id and which prompt it chose; leave a marker file in the working directory (the
// throwaway worktree) that the job's acceptance command looks for; and sleep first, so the rehearsal can kill it
// mid-round.
//
// Everything it writes is base64 or ASCII: the prompt is read as raw bytes and never decoded here, so no console code
// page can corrupt a Chinese brief on its way into the report.
//
//   REHEARSE_STUB_LOG    file to append the records to (required)
//   REHEARSE_STUB_SLEEP  seconds to sleep before answering (default 0)
//   REHEARSE_STUB_MARK   marker file to write into the working directory (default rehearse-round.txt)
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;

static class Stub {
    static int Main(string[] args) {
        string log = Environment.GetEnvironmentVariable("REHEARSE_STUB_LOG");
        string sleep = Environment.GetEnvironmentVariable("REHEARSE_STUB_SLEEP");
        string mark = Environment.GetEnvironmentVariable("REHEARSE_STUB_MARK");
        if (string.IsNullOrEmpty(mark)) mark = "rehearse-round.txt";
        if (string.IsNullOrEmpty(log)) { Console.Error.WriteLine("REHEARSE_STUB_LOG is not set"); return 3; }

        byte[] stdin = ReadAll(Console.OpenStandardInput());
        int n = Runs(log) + 1;
        var rec = new StringBuilder();
        rec.Append("BEGIN ").Append(n).Append("\n");
        rec.Append("ARGV ").Append(B64(string.Join(" ", args))).Append("\n");
        rec.Append("STDIN ").Append(Convert.ToBase64String(stdin)).Append("\n");
        rec.Append("MARK ").Append(B64(Path.GetFullPath(mark))).Append("\n");
        Append(log, rec.ToString());

        // the marker is what the job's acceptance command checks: proof that this round really ran in the worktree
        try { File.WriteAllText(mark, "rehearse round " + n + "\n", new UTF8Encoding(false)); } catch (IOException) { }

        int sec;
        if (!string.IsNullOrEmpty(sleep) && int.TryParse(sleep, out sec) && sec > 0) Thread.Sleep(sec * 1000);

        string model = "?";
        for (int i = 0; i + 1 < args.Length; i++) if (args[i] == "--model") model = args[i + 1];
        Console.Out.WriteLine("{\"type\":\"assistant\",\"message\":{\"model\":\"" + model
            + "\",\"content\":[{\"type\":\"text\",\"text\":\"rehearsal round " + n + " done\"}]}}");
        Console.Out.WriteLine("{\"type\":\"result\",\"subtype\":\"success\",\"total_cost_usd\":0.01,\"num_turns\":1,"
            + "\"duration_ms\":1,\"result\":\"rehearsal round " + n + " done\"}");
        Console.Out.Flush();
        Append(log, "END " + n + "\n");
        return 0;
    }

    static byte[] ReadAll(Stream s) {
        var all = new List<byte>();
        var buf = new byte[8192];
        int n;
        while ((n = s.Read(buf, 0, buf.Length)) > 0) for (int i = 0; i < n; i++) all.Add(buf[i]);
        return all.ToArray();
    }

    static string B64(string s) { return Convert.ToBase64String(new UTF8Encoding(false).GetBytes(s)); }

    static void Append(string path, string text) {
        for (int i = 0; ; i++) {
            try { File.AppendAllText(path, text, new UTF8Encoding(false)); return; }
            catch (IOException) { if (i >= 20) throw; Thread.Sleep(50); }
        }
    }

    /** How many runs the log already holds, so this one can number itself. */
    static int Runs(string path) {
        if (!File.Exists(path)) return 0;
        int n = 0;
        foreach (string line in File.ReadAllLines(path)) if (line.StartsWith("BEGIN ")) n++;
        return n;
    }
}
