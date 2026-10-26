package com.dshpocket;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Installs the embedded PRoot runtime into the app private storage:
 *
 * <pre>
 *   files/proot                 exact copy of assets/runtime/proot, mode 0755
 *   files/rootfs/               extracted from assets/runtime/rootfs.tar.gz
 *   files/rootfs/root/start-dsh.sh   guest entrypoint (staged start-dsh.sh wins)
 *   files/rootfs/.installed     install marker (holds the asset version string)
 * </pre>
 *
 * The rootfs tarball is parsed with a small ustar reader (regular files, dirs, symlinks,
 * hardlinks, PAX and GNU long names); device nodes are skipped. Runs off the UI thread.
 */
public final class RuntimeInstaller {
    private RuntimeInstaller() {}

    public static final String ASSET_DIR = "runtime";
    private static final String MARKER = ".installed";
    private static final String VERSION_ASSET = "runtime/version.txt";

    public interface Progress {
        void onProgress(int percent, String message);

        /** Return true to abort a long install (user pressed Stop). */
        default boolean isCancelled() { return false; }
    }

    public static File prootFile(Context context) {
        return new File(context.getFilesDir(), "proot");
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    public static File workspaceDir(Context context) {
        return new File(rootfsDir(context), "workspace");
    }

    /** Version string of the runtime assets baked into this APK (null for placeholder builds). */
    public static String assetVersion(Context context) {
        try {
            String text = readAssetText(context.getAssets(), VERSION_ASSET);
            return text == null ? null : text.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** Uncompressed size of the rootfs asset, from the size line written by build.ps1 (-1 unknown). */
    public static long assetRootfsSize(Context context) {
        String version = assetVersion(context);
        if (version != null) {
            for (String line : version.split("\\r?\\n")) {
                if (line.startsWith("rootfs=")) {
                    try {
                        return Long.parseLong(line.substring("rootfs=".length()).trim());
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
        }
        return assetFileSize(context.getAssets(), "runtime/rootfs.tar.gz");
    }

    public static File loaderFile(Context context) {
        return new File(context.getFilesDir(), "loader");
    }

    public static File libDir(Context context) {
        return new File(context.getFilesDir(), "lib");
    }

    public static File tmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp");
    }

    public static boolean isInstalled(Context context) {
        File proot = prootFile(context);
        File loader = loaderFile(context);
        File libDir = libDir(context);
        File rootfs = rootfsDir(context);
        File marker = new File(rootfs, MARKER);
        File script = new File(rootfs, "root/start-dsh.sh");
        if (!proot.isFile() || !proot.canExecute()
                || !loader.isFile() || !loader.canExecute()
                || !new File(libDir, "libtalloc.so.2").isFile()
                || !new File(libDir, "libandroid-shmem.so").isFile()
                || !rootfs.isDirectory() || !marker.isFile() || !script.isFile()) {
            return false;
        }
        String expected = assetVersion(context);
        if (expected == null) { return true; }
        try {
            return expected.equals(readFile(marker).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensure the runtime is fully installed. Idempotent: returns quickly when the marker matches
     * the asset version, otherwise wipes files/rootfs and extracts everything again.
     */
    public static void ensureInstalled(Context context, Progress progress) throws Exception {
        if (isInstalled(context)) {
            progress.onProgress(100, "Linux runtime installed");
            DshLog.append("runtime already installed (" + shortVersion(assetVersion(context)) + ")");
            return;
        }

        File filesDir = context.getFilesDir();
        File rootfs = rootfsDir(context);
        File proot = prootFile(context);
        File loader = loaderFile(context);
        File libDir = libDir(context);
        File tmp = tmpDir(context);

        AssetManager assets = context.getAssets();
        List<String> available = new ArrayList<>();
        String[] topLevel = assets.list(ASSET_DIR);
        if (topLevel != null) {
            for (String name : topLevel) {
                if (name != null && !name.isEmpty()) { available.add(name); }
            }
        }
        if (available.isEmpty()) {
            throw new IOException("APK has no embedded runtime (assets/runtime is empty). "
                    + "dsh-pocket/runtime/ must contain proot, loader, lib/, rootfs.tar.gz and start-dsh.sh when building.");
        }
        boolean hasRootfs = available.contains("rootfs.tar.gz");
        boolean hasScript = available.contains("start-dsh.sh");
        DshLog.append("installing runtime; assets=" + available);

        // ~1.25 GB extracted rootfs + headroom; refuse early with a clear message instead of
        // filling the device / failing halfway.
        if (hasRootfs) {
            long freeMb = freeMegabytes(filesDir);
            if (freeMb >= 0 && freeMb < 2200) {
                throw new IOException("Not enough free storage: " + freeMb + " MB available, "
                        + "~2.2 GB needed to extract the Linux runtime. Free up space, then tap Restart.");
            }
        }

        progress.onProgress(1, "Cleaning old runtime\u2026");
        deleteRecursively(rootfs);
        deleteRecursively(libDir);
        if (!rootfs.mkdirs()) { throw new IOException("cannot create " + rootfs); }
        new File(rootfs, "root").mkdirs();
        new File(rootfs, "workspace").mkdirs();
        new File(filesDir, "workspace").mkdirs();
        if (!tmp.isDirectory() && !tmp.mkdirs()) { throw new IOException("cannot create " + tmp); }
        chmod(tmp, 00777);

        // 1) proot + loader + bundled shared libraries (lib/...), mode bits preserved
        progress.onProgress(2, "Installing proot runtime\u2026");
        copyRuntimeTree(context, filesDir, progress);
        if (!proot.isFile()) {
            throw new IOException("APK runtime is missing proot (assets/runtime/proot)");
        }
        chmod(proot, 00755);
        if (loader.isFile()) { chmod(loader, 00755); }
        if (libDir.isDirectory()) {
            File[] libs = libDir.listFiles();
            if (libs != null) {
                for (File lib : libs) { chmod(lib, lib.getName().endsWith(".so") ? 00755 : 00644); }
            }
        }
        DshLog.append("proot installed: " + proot.length() + " bytes; loader="
                + (loader.isFile() ? loader.length() + " bytes" : "MISSING")
                + "; lib=" + (libDir.isDirectory() ? libDir.list().length + " files" : "MISSING"));

        // 2) rootfs tarball
        long rootfsAssetSize = assetRootfsSize(context);
        if (hasRootfs) {
            extractRootfs(context, rootfs, rootfsAssetSize, progress);
        } else {
            DshLog.append("WARNING: no rootfs.tar.gz asset in APK; creating empty rootfs (developer build)");
            progress.onProgress(80, "No rootfs in APK (developer build)");
        }

        // 3) staged entrypoint wins over the copy inside the tarball, so the app can update it
        File script = new File(rootfs, "root/start-dsh.sh");
        if (hasScript) {
            progress.onProgress(96, "Installing start script\u2026");
            copyAssetToFile(assets, "runtime/start-dsh.sh", script, null);
        }
        if (!script.isFile()) {
            throw new IOException("rootfs is missing root/start-dsh.sh and no staged start-dsh.sh asset was bundled");
        }
        chmod(script, 00755);
        chmod(new File(rootfs, "root"), 00755);

        // 4) marker
        String version = assetVersion(context);
        if (version == null) {
            version = "no-version rootfs=" + rootfsAssetSize;
        }
        writeFile(new File(rootfs, MARKER), version + "\n");
        progress.onProgress(100, "Linux runtime installed");
        DshLog.append("runtime installed: " + shortVersion(version));
    }

    /** First line of a version string (version.txt also carries size lines). */
    public static String shortVersion(String version) {
        if (version == null) { return "unknown"; }
        int nl = version.indexOf('\n');
        return (nl >= 0 ? version.substring(0, nl) : version).trim();
    }

    /** Copy assets/runtime/ (recursively, keeping lib/ etc.) into the app files dir. */
    private static void copyRuntimeTree(Context context, File filesDir, Progress progress) throws IOException {
        AssetManager am = context.getAssets();
        String[] entries = am.list(ASSET_DIR);
        if (entries == null) { return; }
        for (String entry : entries) {
            if (entry.equals("rootfs.tar.gz") || entry.equals("start-dsh.sh") || entry.equals("version.txt")) {
                continue;
            }
            copyAssetEntry(am, ASSET_DIR + "/" + entry, entry, filesDir, progress);
        }
    }

    private static void copyAssetEntry(AssetManager am, String assetPath, String relPath,
                                       File destRoot, Progress progress) throws IOException {
        String[] children = null;
        try {
            children = am.list(assetPath);
        } catch (IOException ignored) {
        }
        if (children != null && children.length > 0) {
            File dir = new File(destRoot, relPath);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            for (String child : children) {
                copyAssetEntry(am, assetPath + "/" + child, relPath + "/" + child, destRoot, progress);
            }
        } else {
            File dest = new File(destRoot, relPath);
            try {
                copyAssetToFile(am, assetPath, dest, null);
            } catch (IOException e) {
                // could be an empty directory
                if (!dest.exists()) {
                    dest.mkdirs();
                    DshLog.append("runtime asset dir created: " + relPath);
                }
            }
        }
    }

    /** Environment required by the Termux proot build (set on the child process). */
    public static void applyRuntimeEnv(Map<String, String> env, File filesDir) {
        List<String> remove = new ArrayList<>();
        for (String key : env.keySet()) {
            if (key.startsWith("PROOT_") || key.equals("LD_LIBRARY_PATH") || key.equals("LD_PRELOAD")) {
                remove.add(key);
            }
        }
        for (String key : remove) { env.remove(key); }

        File lib = new File(filesDir, "lib");
        File loader = new File(filesDir, "loader");
        File tmp = new File(filesDir, "tmp");
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("HOME", "/root");
        env.put("TERM", "xterm-256color");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("LANG", "C.UTF-8");
    }

    // ------------------------------------------------------------------ rootfs --

    /**
     * Extract rootfs.tar.gz: fast built-in Java ustar reader first; if that fails for any reason
     * (weird tar entry, disk error) fall back to the bundled toybox binary
     * ({@code toybox gzip -dc | toybox tar -xf -}), which is a battle-tested C implementation.
     */
    private static void extractRootfs(Context context, File rootfs, long assetSize,
                                      Progress progress) throws IOException {
        DshLog.append("extracting rootfs.tar.gz (" + assetSize + " bytes) to " + rootfs);
        long started = System.currentTimeMillis();
        try {
            extractRootfsJava(context, rootfs, assetSize, progress);
            DshLog.append("rootfs extracted in " + (System.currentTimeMillis() - started) + " ms (Java tar)");
        } catch (Throwable t) {
            DshLog.append("Java tar extraction failed after " + (System.currentTimeMillis() - started)
                    + " ms: " + t);
            if (progress.isCancelled()) { throw new IOException("install cancelled"); }
            DshLog.append("Falling back to toybox tar extraction (this copies the tarball first)");
            deleteRecursively(rootfs);
            if (!rootfs.mkdirs()) { throw new IOException("cannot recreate " + rootfs); }
            new File(rootfs, "root").mkdirs();
            extractRootfsToybox(context, rootfs, assetSize, progress);
        }
    }

    private static void extractRootfsJava(Context context, File rootfs, long assetSize,
                                          Progress progress) throws IOException {
        CountingInputStream counter = null;
        GZIPInputStream gzip = null;
        try {
            InputStream raw = context.getAssets().open("runtime/rootfs.tar.gz");
            counter = new CountingInputStream(raw);
            gzip = new GZIPInputStream(counter, 64 * 1024);
            TarResult result = extractTar(gzip, rootfs, assetSize, counter, progress);
            DshLog.append("rootfs extracted: " + result.files + " files, " + result.dirs + " dirs, "
                    + result.symlinks + " symlinks, " + result.hardlinks + " hardlinks, skipped "
                    + result.skipped + " entries (" + result.bytes + " bytes)");
        } finally {
            if (gzip != null) { try { gzip.close(); } catch (Exception ignored) { } }
            else if (counter != null) { try { counter.close(); } catch (Exception ignored) { } }
        }
        File bash = new File(rootfs, "bin/bash");
        File usrBash = new File(rootfs, "usr/bin/bash");
        if (!bash.isFile() && !usrBash.isFile()) {
            DshLog.append("WARNING: /bin/bash not found in extracted rootfs (may still be fine if symlinked)");
        }
    }

    private static void extractRootfsToybox(Context context, File rootfs, long assetSize,
                                            Progress progress) throws IOException {
        File filesDir = context.getFilesDir();
        File tarball = new File(filesDir, "rootfs.tar.gz");
        File toybox = new File(filesDir, "toybox");
        progress.onProgress(5, "Copying rootfs archive\u2026");
        DshLog.append("copying rootfs.tar.gz asset to " + tarball);
        copyAssetToFile(context.getAssets(), "runtime/rootfs.tar.gz", tarball, null);
        if (!toybox.isFile()) {
            copyAssetToFile(context.getAssets(), "probe/toybox", toybox, null);
            chmod(toybox, 00755);
        }
        progress.onProgress(30, "Extracting Linux rootfs (toybox tar)\u2026");
        String pipeline = toybox.getAbsolutePath() + " gzip -dc " + tarball.getAbsolutePath()
                + " | " + toybox.getAbsolutePath() + " tar -xf - -C " + rootfs.getAbsolutePath();
        DshLog.append("exec: /system/bin/sh -c " + pipeline);
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", pipeline);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
        }
        int logged = 0;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (logged < 200) {
                DshLog.append("toybox: " + line);
                logged++;
            }
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        }
        // delete the copied tarball whether or not extraction succeeded
        if (tarball.isFile() && !tarball.delete()) {
            DshLog.append("WARN: could not delete " + tarball);
        }
        if (exit != 0) {
            throw new IOException("toybox tar extraction failed (exit " + exit + ")");
        }
        progress.onProgress(90, "Linux rootfs extracted");
    }

    /** Free space on the volume holding the given path, in MB (-1 if unknown). */
    public static long freeMegabytes(File path) {
        try {
            android.os.StatFs stat = new android.os.StatFs(path.getAbsolutePath());
            return stat.getAvailableBytes() / (1024L * 1024L);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static final class TarResult {
        long files, dirs, symlinks, hardlinks, skipped, bytes;
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        long count;

        CountingInputStream(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) { count++; }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) { count += n; }
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            count += skipped;
            return skipped;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    private static TarResult extractTar(InputStream in, File destDir, long assetSize,
                                        CountingInputStream counter, Progress progress) throws IOException {
        TarResult result = new TarResult();
        byte[] header = new byte[512];
        String pendingLongName = null;
        String pendingLongLink = null;
        Map<String, String> pax = new HashMap<>();
        List<File> dirsToChmod = new ArrayList<>();
        List<Integer> dirModes = new ArrayList<>();
        long lastProgressAt = 0L;
        int lastPercent = -1;

        while (true) {
            if (progress.isCancelled()) { throw new IOException("install cancelled"); }
            if (!readFully(in, header, 512)) { break; }
            if (isZeroBlock(header)) { break; }

            String name = cString(header, 0, 100);
            long mode = parseOctal(header, 100, 8);
            long size = parseOctal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            String linkName = cString(header, 157, 100);
            String magic = cString(header, 257, 6);
            if (magic.startsWith("ustar")) {
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) { name = prefix + "/" + name; }
            }

            if (type == 'x') {                       // PAX extended header (next entry)
                String data = readEntryData(in, size);
                parsePax(data, pax);
                continue;
            }
            if (type == 'g') {                       // PAX global header
                skipData(in, size);
                continue;
            }
            if (type == 'L') {                       // GNU long name
                pendingLongName = readEntryData(in, size).trim();
                continue;
            }
            if (type == 'K') {                       // GNU long link name
                pendingLongLink = readEntryData(in, size).trim();
                continue;
            }
            if (!pax.isEmpty()) {
                if (pax.containsKey("path")) { name = pax.get("path"); }
                if (pax.containsKey("linkpath")) { linkName = pax.get("linkpath"); }
                pax.clear();
            }
            if (pendingLongName != null) { name = pendingLongName; pendingLongName = null; }
            if (pendingLongLink != null) { linkName = pendingLongLink; pendingLongLink = null; }

            File target = safeResolve(destDir, name);
            if (target == null) {
                skipData(in, size);
                result.skipped++;
                continue;
            }

            switch (type) {
                case '0': case '\0': case '7': {     // regular file
                    mkdirs(target.getParentFile());
                    writeStreamToFile(in, target, size);
                    result.files++;
                    result.bytes += size;
                    if ((mode & 07777) != 0) { chmod(target, (int) (mode & 07777)); }
                    break;
                }
                case '5': {                          // directory
                    mkdirs(target);
                    result.dirs++;
                    dirsToChmod.add(target);
                    dirModes.add((int) (mode & 07777));
                    if (size > 0) { skipData(in, size); }
                    break;
                }
                case '2': {                          // symlink
                    mkdirs(target.getParentFile());
                    deleteQuietly(target);
                    try {
                        Os.symlink(linkName, target.getAbsolutePath());
                        result.symlinks++;
                    } catch (Exception e) {
                        result.skipped++;
                        DshLog.append("symlink failed: " + name + " -> " + linkName + ": " + e);
                    }
                    break;
                }
                case '1': {                          // hardlink
                    File linkTarget = safeResolve(destDir, linkName);
                    mkdirs(target.getParentFile());
                    deleteQuietly(target);
                    boolean linked = false;
                    if (linkTarget != null && linkTarget.isFile()) {
                        try {
                            java.nio.file.Files.createLink(target.toPath(), linkTarget.toPath());
                            linked = true;
                        } catch (Exception e) {
                            try {
                                copyFile(linkTarget, target);
                                linked = true;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (linked) { result.hardlinks++; } else { result.skipped++; }
                    break;
                }
                case '3': case '4': case '6': {      // char/block device, fifo: skip
                    skipData(in, size);
                    result.skipped++;
                    break;
                }
                default: {
                    skipData(in, size);
                    result.skipped++;
                    break;
                }
            }

            // throttled progress report (percent of compressed asset consumed)
            long now = System.currentTimeMillis();
            if (assetSize > 0 && now - lastProgressAt > 250) {
                int percent = (int) Math.min(95, counter.count * 90L / assetSize + 5);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onProgress(percent, "Extracting Linux rootfs\u2026 " + percent + "%");
                }
                lastProgressAt = now;
            }
        }

        // apply directory modes last, so restrictive modes don't block extraction
        for (int i = 0; i < dirsToChmod.size(); i++) {
            int m = dirModes.get(i);
            if (m != 0) { chmod(dirsToChmod.get(i), m); }
        }
        return result;
    }

    private static File safeResolve(File destDir, String name) {
        if (name == null) { return null; }
        String cleaned = name;
        while (cleaned.startsWith("./")) { cleaned = cleaned.substring(2); }
        if (cleaned.startsWith("/") || cleaned.isEmpty()) { return null; }
        if (cleaned.contains("\u0000")) { return null; }
        String[] parts = cleaned.split("/");
        for (String part : parts) {
            if (part.equals("..")) { return null; }
        }
        return new File(destDir, cleaned);
    }

    private static void parsePax(String data, Map<String, String> into) {
        int pos = 0;
        while (pos < data.length()) {
            int space = data.indexOf(' ', pos);
            if (space < 0) { break; }
            int len;
            try {
                len = Integer.parseInt(data.substring(pos, space).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length()) { break; }
            String record = data.substring(space + 1, pos + len - 1);   // strip trailing \n
            int eq = record.indexOf('=');
            if (eq > 0) {
                into.put(record.substring(0, eq), record.substring(eq + 1));
            }
            pos += len;
        }
    }

    // ------------------------------------------------------------------- utils --

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) { return false; }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) { return false; }
        }
        return true;
    }

    private static String cString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) { end++; }
        return new String(buf, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long value = 0;
        boolean seen = false;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') {
                if (seen) { break; }
                continue;
            }
            if (b < '0' || b > '7') { break; }
            value = (value << 3) + (b - '0');
            seen = true;
        }
        return value;
    }

    private static String readEntryData(InputStream in, long size) throws IOException {
        if (size <= 0 || size > 4L * 1024 * 1024) {
            skipData(in, size);
            return "";
        }
        byte[] data = new byte[(int) size];
        if (!readFully(in, data, data.length)) { throw new IOException("truncated tar entry"); }
        skipPadding(in, size);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Write one tar file entry. Disk errors (EACCES, ENOSPC, weird names...) are logged and the
     * entry is skipped instead of aborting the whole extraction; the input stream is always
     * drained so the tar reader stays in sync. Only a truncated archive is fatal.
     */
    private static void writeStreamToFile(InputStream in, File file, long size) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
        } catch (Exception e) {
            DshLog.append("skip file (cannot create) " + file + ": " + e);
        }
        byte[] buf = new byte[64 * 1024];
        long remaining = size;
        try {
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n < 0) { throw new IOException("truncated tar data for file entry"); }
                remaining -= n;
                if (out != null) {
                    try {
                        out.write(buf, 0, n);
                    } catch (Exception e) {
                        DshLog.append("skip file (write failed) " + file + ": " + e);
                        try { out.close(); } catch (Exception ignored) { }
                        out = null;
                    }
                }
            }
        } finally {
            if (out != null) {
                try { out.flush(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }
        }
        skipPadding(in, size);
    }

    private static void skipData(InputStream in, long size) throws IOException {
        if (size <= 0) { return; }
        byte[] buf = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) { throw new IOException("truncated tar data"); }
            remaining -= n;
        }
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        if (padding <= 0) { return; }
        long skipped = 0;
        while (skipped < padding) {
            long n = in.skip(padding - skipped);
            if (n <= 0) {
                if (in.read() < 0) { throw new IOException("truncated tar padding"); }
                n = 1;
            }
            skipped += n;
        }
    }

    // ----------------------------------------------------------- file helpers --

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Exception e) {
            // java.io fallback; exec bit is the important one
            boolean executable = (mode & 0111) != 0;
            boolean readable = (mode & 0444) != 0;
            boolean writable = (mode & 0222) != 0;
            file.setExecutable(executable, false);
            file.setReadable(readable, false);
            file.setWritable(writable, false);
        }
    }

    public static void copyAssetToFile(AssetManager assets, String assetPath, File dest,
                                       Progress progress) throws IOException {
        InputStream in = assets.open(assetPath);
        try {
            mkdirs(dest.getParentFile());
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[128 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (progress != null && total % (4 * 1024 * 1024) < buf.length) {
                        progress.onProgress(-1, "Copying " + dest.getName() + "\u2026");
                    }
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        dest.setReadable(true, false);
    }

    static long assetFileSize(AssetManager assets, String assetPath) {
        try {
            android.content.res.AssetFileDescriptor fd = assets.openFd(assetPath);
            try {
                return fd.getLength();
            } finally {
                fd.close();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    public static String readAssetText(AssetManager assets, String assetPath) throws IOException {
        InputStream in = assets.open(assetPath);
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) { buf.write(b, 0, n); }
            return buf.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    public static void mkdirs(File dir) {
        if (dir == null || dir.isDirectory()) { return; }
        dir.mkdirs();
    }

    public static void deleteRecursively(File file) {
        if (file == null) { return; }
        java.nio.file.Path path = file.toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(path)) {
                java.nio.file.Files.deleteIfExists(path);
                return;
            }
            if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // make sure restrictive modes from a previous extraction don't block deletion
                chmod(file, 00755);
                try (java.nio.file.DirectoryStream<java.nio.file.Path> children =
                             java.nio.file.Files.newDirectoryStream(path)) {
                    List<java.nio.file.Path> copy = new ArrayList<>();
                    for (java.nio.file.Path child : children) { copy.add(child); }
                    for (java.nio.file.Path child : copy) { deleteRecursively(child.toFile()); }
                }
                java.nio.file.Files.deleteIfExists(path);
            } else {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            DshLog.append("WARN: could not delete " + file + ": " + e);
        }
    }

    private static void deleteQuietly(File file) {
        try { if (file.exists()) { file.delete(); } } catch (Exception ignored) { }
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    public static String readFile(File file) throws IOException {
        if (!file.isFile()) { return null; }
        InputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) { buf.write(b, 0, n); }
            return buf.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    public static void writeFile(File file, String content) throws IOException {
        mkdirs(file.getParentFile());
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
