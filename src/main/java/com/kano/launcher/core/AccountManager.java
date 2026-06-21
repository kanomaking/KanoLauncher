package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kano.launcher.auth.MinecraftSession;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Stores Microsoft accounts (username, UUID, refresh token) encrypted on disk with AES-256-GCM.
 *
 * <p>SECURITY TODO (per KanoLauncher.md §1): the AES key currently lives in a local file. Before
 * release it must be wrapped by the OS keystore — DPAPI / Credential Manager on Windows,
 * libsecret / Secret Service on Linux — never stored beside the ciphertext. This is the interim
 * Phase-1 form so the login/refresh loop can be developed; do not ship as-is.
 */
public final class AccountManager {

    /** A persisted account. Only the refresh token is kept; the ~24h Minecraft token is never stored. */
    public record StoredAccount(String username, String uuid, String refreshToken) {}

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final Type LIST_TYPE = new TypeToken<ArrayList<StoredAccount>>() {}.getType();

    private final Path accountsFile;
    private final SecretKey key;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final List<StoredAccount> accounts;

    public AccountManager(Path dataDir) throws Exception {
        Files.createDirectories(dataDir);
        this.accountsFile = dataDir.resolve("accounts.enc");
        this.key = loadOrCreateKey(dataDir.resolve("key.bin"));
        this.accounts = load();
    }

    public List<StoredAccount> list() {
        return List.copyOf(accounts);
    }

    /** Add or update an account from a completed sign-in, keyed by UUID. Persists immediately. */
    public void add(MinecraftSession session) throws Exception {
        accounts.removeIf(a -> a.uuid().equals(session.uuid()));
        accounts.add(new StoredAccount(session.username(), session.uuid(), session.refreshToken()));
        save();
    }

    public void remove(String uuid) throws Exception {
        if (accounts.removeIf(a -> a.uuid().equals(uuid))) save();
    }

    // ---- persistence ----

    private List<StoredAccount> load() throws Exception {
        if (!Files.exists(accountsFile)) return new ArrayList<>();
        byte[] blob = Base64.getDecoder().decode(Files.readAllBytes(accountsFile));
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[blob.length - IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, IV_BYTES);
        System.arraycopy(blob, IV_BYTES, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        String json = new String(c.doFinal(ct), StandardCharsets.UTF_8);
        List<StoredAccount> loaded = gson.fromJson(json, LIST_TYPE);
        return loaded != null ? loaded : new ArrayList<>();
    }

    private void save() throws Exception {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv); // fresh nonce every write — never reuse with the same key
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(gson.toJson(accounts, LIST_TYPE).getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        Path tmp = accountsFile.resolveSibling("accounts.enc.tmp");
        Files.write(tmp, Base64.getEncoder().encode(blob));
        Files.move(tmp, accountsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private SecretKey loadOrCreateKey(Path keyFile) throws Exception {
        if (Files.exists(keyFile)) {
            return new SecretKeySpec(Base64.getDecoder().decode(Files.readAllBytes(keyFile)), "AES");
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey k = kg.generateKey();
        Files.write(keyFile, Base64.getEncoder().encode(k.getEncoded()));
        return k;
    }
}
