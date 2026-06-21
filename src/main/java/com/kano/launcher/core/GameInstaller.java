package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads everything needed to run a version: client jar, libraries, native jars, the asset index,
 * and all asset objects (content-addressed, deduped across instances). Reports progress and skips
 * files already present and valid, so re-installs are cheap.
 */
public final class GameInstaller {

    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final int THREADS = 8;

    /** Progress callback: {@code done}/{@code total} files, with a short {@code label}. */
    public interface Progress {
        void update(int done, int total, String label);
    }

    private final Path cacheDir;

    public GameInstaller(Path dataDir) {
        this.cacheDir = dataDir.resolve("cache");
    }

    public static Path clientJar(Path dataDir, String id) {
        return dataDir.resolve("cache").resolve("versions").resolve(id).resolve(id + ".jar");
    }

    public static Path librariesDir(Path dataDir) {
        return dataDir.resolve("cache").resolve("libraries");
    }

    public static Path assetsDir(Path dataDir) {
        return dataDir.resolve("cache").resolve("assets");
    }

    public void install(VersionDetail vd, Progress progress) throws Exception {
        install(vd, java.util.List.of(), progress);
    }

    /** Install vanilla files plus any extra libraries (e.g. Fabric loader libs). */
    public void install(VersionDetail vd, List<VersionDetail.Dl> extraLibs, Progress progress) throws Exception {
        Path libsDir = cacheDir.resolve("libraries");
        Path assets = cacheDir.resolve("assets");
        Path indexFile = assets.resolve("indexes").resolve(vd.assetIndexId() + ".json");

        // Fetch the asset index first so we know the total object count up front.
        Downloader.download(vd.assetIndexUrl(), vd.assetIndexSha1(), indexFile);
        JsonObject index = new Gson().fromJson(
                Files.readString(indexFile, StandardCharsets.UTF_8), JsonObject.class);
        JsonObject objects = index.getAsJsonObject("objects");

        int total = 1 + vd.libraries().size() + vd.natives().size() + extraLibs.size() + objects.size();
        AtomicInteger done = new AtomicInteger();

        // Client jar (do it on this thread first).
        Path clientJar = cacheDir.resolve("versions").resolve(vd.id()).resolve(vd.id() + ".jar");
        Downloader.download(vd.client().url(), vd.client().sha1(), clientJar);
        progress.update(done.incrementAndGet(), total, "client");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();

        for (VersionDetail.Dl lib : vd.libraries()) {
            futures.add(pool.submit(() -> {
                Downloader.download(lib.url(), lib.sha1(), libsDir.resolve(lib.path()));
                progress.update(done.incrementAndGet(), total, "libraries");
                return null;
            }));
        }
        for (VersionDetail.Dl nat : vd.natives()) {
            futures.add(pool.submit(() -> {
                Downloader.download(nat.url(), nat.sha1(), libsDir.resolve(nat.path()));
                progress.update(done.incrementAndGet(), total, "natives");
                return null;
            }));
        }
        for (VersionDetail.Dl lib : extraLibs) {
            futures.add(pool.submit(() -> {
                Downloader.download(lib.url(), lib.sha1(), libsDir.resolve(lib.path()));
                progress.update(done.incrementAndGet(), total, "fabric");
                return null;
            }));
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : objects.entrySet()) {
            String hash = e.getValue().getAsJsonObject().get("hash").getAsString();
            String sub = hash.substring(0, 2);
            Path dest = assets.resolve("objects").resolve(sub).resolve(hash);
            futures.add(pool.submit(() -> {
                Downloader.download(RESOURCES + sub + "/" + hash, hash, dest);
                progress.update(done.incrementAndGet(), total, "assets");
                return null;
            }));
        }

        pool.shutdown();
        Exception failure = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ex) {
                if (failure == null) failure = ex;
            }
        }
        if (failure != null) throw new RuntimeException("Install failed: " + failure.getMessage(), failure);
    }
}
