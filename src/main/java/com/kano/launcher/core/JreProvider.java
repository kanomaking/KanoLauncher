package com.kano.launcher.core;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Provides a Java runtime for a required major version. Uses the launcher's own JRE if it matches,
 * otherwise downloads a matching JRE from the Adoptium API into a shared per-major store and reuses
 * it thereafter. This is what lets the launcher boot versions needing a different Java than the host.
 */
public final class JreProvider {

    public interface Progress { void status(String message); }

    private JreProvider() {}

    public static Path javaExecutable(Path dataDir, int major, Progress p) throws Exception {
        // 1. Host JRE already the right major?
        if (Runtime.version().feature() == major) {
            Path j = Path.of(System.getProperty("java.home"), "bin", javaBin());
            if (Files.exists(j)) return j;
        }
        // 2. Already downloaded?
        Path dir = dataDir.resolve("runtimes").resolve(String.valueOf(major));
        Path cached = findJava(dir);
        if (cached != null) return cached;

        // 3. Download from Adoptium.
        p.status("Downloading Java " + major + " (one-time)...");
        Files.createDirectories(dir);
        String os = osTag();
        String arch = archTag();
        String url = "https://api.adoptium.net/v3/binary/latest/" + major
                + "/ga/" + os + "/" + arch + "/jre/hotspot/normal/eclipse";
        boolean zip = os.equals("windows");
        Path archive = dir.resolve(zip ? "jre.zip" : "jre.tar.gz");
        downloadFollowing(url, archive);

        p.status("Extracting Java " + major + "...");
        if (zip) extractZip(archive, dir);
        else extractTarGz(archive, dir);
        Files.deleteIfExists(archive);

        Path java = findJava(dir);
        if (java == null) throw new RuntimeException("Java " + major + " download did not yield a runnable JRE.");
        return java;
    }

    private static void downloadFollowing(String url, Path dest) throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<Path> r = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(dest));
        if (r.statusCode() != 200) throw new RuntimeException("Adoptium download failed: " + r.statusCode());
    }

    /** Recursively find bin/java(.exe) under {@code dir}. */
    private static Path findJava(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(pth -> pth.getFileName() != null
                            && pth.getFileName().toString().equals(javaBin())
                            && pth.getParent() != null
                            && pth.getParent().getFileName().toString().equals("bin"))
                    .findFirst().orElse(null);
        }
    }

    private static void extractZip(Path archive, Path target) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) continue; // zip-slip guard
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Linux/macOS: delegate to the system tar (present on modern distros + macOS + Win10+, but we only hit this off-Windows). */
    private static void extractTarGz(Path archive, Path target) throws Exception {
        Process pr = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString())
                .inheritIO().start();
        if (pr.waitFor() != 0) throw new RuntimeException("tar extraction failed");
    }

    private static String javaBin() {
        return osTag().equals("windows") ? "java.exe" : "java";
    }

    private static String osTag() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        return "linux";
    }

    private static String archTag() {
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return "aarch64";
        return "x64";
    }
}
