package car.salt.player;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded, app-private history. Callers supply event descriptions, never raw request data. */
final class Journal {
    private static final int LIMIT = 128 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    interface Reader { void accept(String snapshot, boolean success); }

    static void event(Context context, String message) {
        File dir = context.getFilesDir();
        Date when = new Date();
        worker.execute(() -> {
            try {
                File current = new File(dir, "events.txt");
                File previous = new File(dir, "events.previous.txt");
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(when);
                byte[] entry = (stamp + "  " + message + "\n").getBytes(StandardCharsets.UTF_8);
                if (current.length() + entry.length > LIMIT) {
                    if (previous.exists() && !previous.delete()) throw new IOException("rotate");
                    if (!current.renameTo(previous)) throw new IOException("rotate");
                }
                try (FileOutputStream out = new FileOutputStream(current, true)) { out.write(entry); }
            } catch (IOException ex) { Log.w("NewSalt", "日志写入失败", ex); }
        });
    }

    static String version(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception e){return "未知";}}
    static void read(Context context, Reader reader) {
        File dir = context.getFilesDir();
        worker.execute(() -> {
            StringBuilder text = new StringBuilder("新椒盐 "+version(context)+" · Android " + Build.VERSION.RELEASE
                    + " · API " + Build.VERSION.SDK_INT + "\n\n");
            try {
                for (String name : new String[]{"events.previous.txt", "events.txt"}) {
                    File file = new File(dir, name);
                    if (file.exists()) try (InputStream in = new FileInputStream(file)) {
                        text.append(new String(Library.bytes(in, LIMIT), StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ex) { reader.accept("", false); return; }
            reader.accept(text.toString(), true);
        });
    }
}
