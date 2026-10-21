package forge.mtga.net;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Session capture log (-Dmtga.capture=path): every client message, every server message summary, unhandled events. */
public final class Capture {
    private static PrintWriter out;
    private static long t0;

    private Capture() { }

    public static synchronized void open(String path) {
        try {
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            out = new PrintWriter(new FileWriter(f, true), true);
            t0 = System.currentTimeMillis();
            final java.io.PrintStream orig = System.out;
            System.setOut(new java.io.PrintStream(orig, true) {
                @Override public void println(String x) {
                    super.println(x);
                    if (x != null && x.startsWith("[mtga]")) log("SERVER " + x);
                }
            });
            log("== capture started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        } catch (IOException e) {
            System.out.println("[mtga] cannot open capture file " + path + ": " + e);
        }
    }

    public static synchronized void log(String line) {
        String s = String.format("%7.2f %s", (System.currentTimeMillis() - t0) / 1000.0, line);
        if (out != null) out.println(s);
    }

    public static String flat(Object message, int max) {
        String s = String.valueOf(message).replace("\r", "").replace("\n", " ").replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
