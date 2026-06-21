import com.google.gson.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * KanoLauncher login tester.
 *
 * Walks the full Minecraft sign-in chain so you can prove your Azure app works:
 *   1. Microsoft device-code login   -> Microsoft token
 *   2. Xbox Live auth                -> Xbox token + userhash
 *   3. XSTS auth                     -> Minecraft-scoped token
 *   4. Minecraft login               -> Minecraft token (~24h)
 *   5. Get profile                   -> username + UUID   (403 here = app not approved yet)
 *
 * It reads your Azure "Application (client) ID" from clientid.txt (same folder),
 * or from the KANO_CLIENT_ID environment variable. No code editing required.
 */
public class LoginTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String clientId = readClientId();
        if (clientId == null || clientId.isBlank() || clientId.contains("PASTE")) {
            System.out.println("""
                ------------------------------------------------------------
                No client ID yet.
                Open clientid.txt and paste your Azure "Application (client) ID"
                (Section 1 of KanoLauncher.md tells you how to get it), then run again.
                ------------------------------------------------------------""");
            return;
        }

        System.out.println("[1/5] Starting Microsoft device-code login...");
        JsonObject device = startDeviceCode(clientId);
        System.out.println();
        System.out.println("  >>> " + device.get("message").getAsString());
        System.out.println();
        System.out.println("      Waiting for you to finish in the browser...");

        String msaToken = pollForMsaToken(clientId, device);
        System.out.println("[1/5] Microsoft login OK.");

        System.out.println("[2/5] Xbox Live...");
        JsonObject xbl = xboxLive(msaToken);
        String xblToken = xbl.get("Token").getAsString();
        String userHash = xbl.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        System.out.println("[2/5] Xbox Live OK.");

        System.out.println("[3/5] XSTS...");
        String xstsToken = xsts(xblToken);
        System.out.println("[3/5] XSTS OK.");

        System.out.println("[4/5] Minecraft login...");
        String mcToken = minecraftLogin(userHash, xstsToken);
        if (mcToken == null) return; // approval gate hit; message already printed
        System.out.println("[4/5] Minecraft token acquired.");

        System.out.println("[5/5] Fetching profile...");
        profile(mcToken);
    }

    // ---- Step 1: device code ----

    static JsonObject startDeviceCode(String clientId) throws Exception {
        String body = "client_id=" + enc(clientId)
                + "&scope=" + enc("XboxLive.signin offline_access");
        HttpResponse<String> r = post(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "application/x-www-form-urlencoded", body);
        if (r.statusCode() != 200) {
            throw new RuntimeException("devicecode failed (" + r.statusCode() + "): " + r.body());
        }
        return GSON.fromJson(r.body(), JsonObject.class);
    }

    static String pollForMsaToken(String clientId, JsonObject device) throws Exception {
        String deviceCode = device.get("device_code").getAsString();
        int interval = device.has("interval") ? device.get("interval").getAsInt() : 5;
        String body = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + enc(clientId)
                + "&device_code=" + enc(deviceCode);

        while (true) {
            Thread.sleep((interval + 1) * 1000L);
            HttpResponse<String> r = post(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "application/x-www-form-urlencoded", body);
            JsonObject j = GSON.fromJson(r.body(), JsonObject.class);
            if (r.statusCode() == 200) {
                return j.get("access_token").getAsString();
            }
            String error = j.has("error") ? j.get("error").getAsString() : "unknown";
            switch (error) {
                case "authorization_pending" -> { /* keep waiting */ }
                case "slow_down" -> interval += 5;
                case "expired_token" ->
                        throw new RuntimeException("The code expired before you signed in. Run again.");
                case "authorization_declined" ->
                        throw new RuntimeException("Sign-in was declined.");
                default -> throw new RuntimeException("Login error: " + error + " -> " + r.body());
            }
        }
    }

    // ---- Step 2: Xbox Live ----

    static JsonObject xboxLive(String msaToken) throws Exception {
        String body = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",
            "RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
                .formatted(msaToken);
        HttpResponse<String> r = postJson("https://user.auth.xboxlive.com/user/authenticate", body);
        if (r.statusCode() != 200) {
            throw new RuntimeException("Xbox Live failed (" + r.statusCode() + "): " + r.body());
        }
        return GSON.fromJson(r.body(), JsonObject.class);
    }

    // ---- Step 3: XSTS ----

    static String xsts(String xblToken) throws Exception {
        String body = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},
            "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
                .formatted(xblToken);
        HttpResponse<String> r = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", body);
        if (r.statusCode() == 401) {
            JsonObject j = GSON.fromJson(r.body(), JsonObject.class);
            long xerr = j.has("XErr") ? j.get("XErr").getAsLong() : 0;
            String hint = switch ((int) (xerr % 1000000000L)) {
                case 148916233 -> "This Microsoft account has no Xbox profile. Sign in once at xbox.com first.";
                case 148916238 -> "This is a child account; it needs to be added to a Family by an adult.";
                default -> "XSTS error code " + xerr;
            };
            throw new RuntimeException(hint);
        }
        if (r.statusCode() != 200) {
            throw new RuntimeException("XSTS failed (" + r.statusCode() + "): " + r.body());
        }
        return GSON.fromJson(r.body(), JsonObject.class).get("Token").getAsString();
    }

    // ---- Step 4: Minecraft login ----

    static String minecraftLogin(String userHash, String xstsToken) throws Exception {
        String identityToken = "XBL3.0 x=" + userHash + ";" + xstsToken;
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", identityToken);
        HttpResponse<String> r = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox", GSON.toJson(payload));
        if (r.statusCode() == 403 || r.body().contains("Invalid app registration")) {
            System.out.println("""

                ============================================================
                APPROVAL GATE (403: Invalid app registration).
                Steps 1-3 all WORKED -- your Azure app and login chain are correct.
                Microsoft has not yet approved this app for the Minecraft API.
                  -> Submit the approval form: https://aka.ms/mce-reviewappid
                  -> Provide your Application (client) ID; you have already
                     generated the sign-in activity it wants to see.
                  -> Wait for approval, then run this again.
                (Approval for new hobby apps is not guaranteed -- this is the
                 project's biggest risk. See Section 1 of KanoLauncher.md.)
                ============================================================""");
            return null;
        }
        if (r.statusCode() != 200) {
            throw new RuntimeException("Minecraft login failed (" + r.statusCode() + "): " + r.body());
        }
        return GSON.fromJson(r.body(), JsonObject.class).get("access_token").getAsString();
    }

    // ---- Step 5: profile (the approval gate) ----

    static void profile(String mcToken) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + mcToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (r.statusCode() == 403) {
            System.out.println("""

                ============================================================
                Got a 403 on the profile call.
                Steps 1-4 all WORKED -- your login chain is correct.
                A 403 here almost always means your Azure app is not yet
                approved by Microsoft (submit https://aka.ms/mce-reviewappid
                and wait), or this account doesn't own/has not migrated Minecraft.
                Re-run after approval; it should print your name + UUID.
                ============================================================""");
            return;
        }
        if (r.statusCode() != 200) {
            throw new RuntimeException("Profile failed (" + r.statusCode() + "): " + r.body());
        }
        JsonObject p = GSON.fromJson(r.body(), JsonObject.class);
        System.out.println("""

            ============================================================
            SUCCESS - you are signed in.
              Username: %s
              UUID:     %s
            These three things launch the game: username, uuid, and the
            Minecraft token this program just obtained.
            ============================================================"""
                .formatted(p.get("name").getAsString(), p.get("id").getAsString()));
    }

    // ---- helpers ----

    static String readClientId() throws Exception {
        String env = System.getenv("KANO_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        Path f = Path.of("clientid.txt");
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f)) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) return t;
            }
        }
        return null;
    }

    static HttpResponse<String> post(String url, String contentType, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", contentType)
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> postJson(String url, String body) throws Exception {
        return post(url, "application/json", body);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
