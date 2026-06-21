package com.kano.launcher.core;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;

/** Downloads files with SHA-1 verification and a content-addressed skip (don't re-fetch valid files). */
public final class Downloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Downloader() {}

    /** Download {@code url} to {@code dest}, verifying {@code sha1}. Skips if the file already matches. */
    public static void download(String url, String sha1, Path dest) throws Exception {
        if (Files.exists(dest) && (sha1 == null || sha1.equalsIgnoreCase(sha1(dest)))) return;
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpResponse<Path> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new RuntimeException("Download failed (" + r.statusCode() + "): " + url);
        }
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) {
            Files.deleteIfExists(tmp);
            throw new RuntimeException("Checksum mismatch: " + url);
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        String hex = new BigInteger(1, md.digest()).toString(16);
        return "0".repeat(40 - hex.length()) + hex;
    }
}
