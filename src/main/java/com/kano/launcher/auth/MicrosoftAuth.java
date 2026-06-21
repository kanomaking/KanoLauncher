package com.kano.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Microsoft / Minecraft authentication: device-code login and silent refresh.
 *
 * Flow: MSA -> Xbox Live -> XSTS -> Minecraft -> profile. Validated working through XSTS; the final
 * Minecraft step requires this app's Azure registration to be approved (see KanoLauncher.md §1) —
 * until then it throws {@link AppNotApprovedException}.
 */
public final class MicrosoftAuth {

    /** Callback used to show the user the device code + URL to authenticate. */
    public interface DeviceCodePrompt {
        void show(String message, String userCode, String verificationUri);
    }

    /** Thrown when Minecraft rejects the app registration (403, "Invalid app registration"). */
    public static final class AppNotApprovedException extends RuntimeException {
        public AppNotApprovedException(String m) { super(m); }
    }

    private static final String DEVICECODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    /** Interactive device-code login. Blocks until the user authenticates (or it fails). */
    public MinecraftSession loginWithDeviceCode(String clientId, DeviceCodePrompt prompt) throws Exception {
        JsonObject device = startDeviceCode(clientId);
        prompt.show(device.get("message").getAsString(),
                device.get("user_code").getAsString(),
                device.get("verification_uri").getAsString());
        JsonObject token = pollForToken(clientId, device);
        return completeChain(token.get("access_token").getAsString(),
                token.has("refresh_token") ? token.get("refresh_token").getAsString() : null);
    }

    /** Silent re-login using a stored refresh token. Use this on launch before showing a device code. */
    public MinecraftSession refresh(String clientId, String refreshToken) throws Exception {
        String body = "grant_type=refresh_token"
                + "&client_id=" + enc(clientId)
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc(SCOPE);
        HttpResponse<String> r = post(TOKEN_URL, "application/x-www-form-urlencoded", body);
        if (r.statusCode() != 200) {
            throw new RuntimeException("Refresh failed (" + r.statusCode() + "): " + r.body());
        }
        JsonObject j = gson.fromJson(r.body(), JsonObject.class);
        String newRefresh = j.has("refresh_token") ? j.get("refresh_token").getAsString() : refreshToken;
        return completeChain(j.get("access_token").getAsString(), newRefresh);
    }

    // ---- chain: MSA token -> session ----

    private MinecraftSession completeChain(String msaToken, String refreshToken) throws Exception {
        JsonObject xbl = xboxLive(msaToken);
        String xblToken = xbl.get("Token").getAsString();
        String userHash = xbl.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        String xstsToken = xsts(xblToken);
        String mcToken = minecraftLogin(userHash, xstsToken);
        JsonObject profile = profile(mcToken);
        return new MinecraftSession(
                profile.get("name").getAsString(),
                profile.get("id").getAsString(),
                mcToken, refreshToken);
    }

    private JsonObject startDeviceCode(String clientId) throws Exception {
        String body = "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE);
        HttpResponse<String> r = post(DEVICECODE_URL, "application/x-www-form-urlencoded", body);
        if (r.statusCode() != 200) throw new RuntimeException("devicecode failed: " + r.body());
        return gson.fromJson(r.body(), JsonObject.class);
    }

    private JsonObject pollForToken(String clientId, JsonObject device) throws Exception {
        String deviceCode = device.get("device_code").getAsString();
        int interval = device.has("interval") ? device.get("interval").getAsInt() : 5;
        String body = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + enc(clientId) + "&device_code=" + enc(deviceCode);
        while (true) {
            Thread.sleep((interval + 1) * 1000L);
            HttpResponse<String> r = post(TOKEN_URL, "application/x-www-form-urlencoded", body);
            JsonObject j = gson.fromJson(r.body(), JsonObject.class);
            if (r.statusCode() == 200) return j;
            String error = j.has("error") ? j.get("error").getAsString() : "unknown";
            switch (error) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                case "expired_token" -> throw new RuntimeException("Device code expired; retry.");
                case "authorization_declined" -> throw new RuntimeException("Sign-in declined.");
                default -> throw new RuntimeException("Login error: " + error);
            }
        }
    }

    private JsonObject xboxLive(String msaToken) throws Exception {
        String body = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",
            "RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
                .formatted(msaToken);
        HttpResponse<String> r = post("https://user.auth.xboxlive.com/user/authenticate",
                "application/json", body);
        if (r.statusCode() != 200) throw new RuntimeException("Xbox Live failed: " + r.body());
        return gson.fromJson(r.body(), JsonObject.class);
    }

    private String xsts(String xblToken) throws Exception {
        String body = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},
            "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
                .formatted(xblToken);
        HttpResponse<String> r = post("https://xsts.auth.xboxlive.com/xsts/authorize",
                "application/json", body);
        if (r.statusCode() == 401) {
            JsonObject j = gson.fromJson(r.body(), JsonObject.class);
            long xerr = j.has("XErr") ? j.get("XErr").getAsLong() : 0;
            throw new RuntimeException(switch ((int) (xerr % 1000000000L)) {
                case 148916233 -> "No Xbox profile on this account — sign in at xbox.com once.";
                case 148916238 -> "Child account — must be added to a Family by an adult.";
                default -> "XSTS error " + xerr;
            });
        }
        if (r.statusCode() != 200) throw new RuntimeException("XSTS failed: " + r.body());
        return gson.fromJson(r.body(), JsonObject.class).get("Token").getAsString();
    }

    private String minecraftLogin(String userHash, String xstsToken) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        HttpResponse<String> r = post(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                "application/json", gson.toJson(payload));
        if (r.statusCode() == 403 || r.body().contains("Invalid app registration")) {
            throw new AppNotApprovedException(
                    "Azure app not yet approved for the Minecraft API. Submit https://aka.ms/mce-reviewappid and wait.");
        }
        if (r.statusCode() != 200) throw new RuntimeException("Minecraft login failed: " + r.body());
        return gson.fromJson(r.body(), JsonObject.class).get("access_token").getAsString();
    }

    private JsonObject profile(String mcToken) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + mcToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 403) {
            throw new AppNotApprovedException("Profile 403 — app not approved or game not owned.");
        }
        if (r.statusCode() != 200) throw new RuntimeException("Profile failed: " + r.body());
        return gson.fromJson(r.body(), JsonObject.class);
    }

    private HttpResponse<String> post(String url, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", contentType)
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
