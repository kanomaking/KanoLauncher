package com.kano.launcher;

import com.kano.launcher.auth.MicrosoftAuth;
import com.kano.launcher.core.AccountManager;
import com.kano.launcher.core.Config;
import com.kano.launcher.core.ContentSource;
import com.kano.launcher.core.CurseForgeClient;
import com.kano.launcher.core.Downloader;
import com.kano.launcher.core.Instance;
import com.kano.launcher.core.FabricSupport;
import com.kano.launcher.core.ForgeVersions;
import com.kano.launcher.core.GameInstaller;
import com.kano.launcher.core.GameLauncher;
import com.kano.launcher.core.InstanceManager;
import com.kano.launcher.core.JreProvider;
import com.kano.launcher.core.Loader;
import com.kano.launcher.core.ModInstaller;
import com.kano.launcher.core.ModpackInstaller;
import com.kano.launcher.core.ModTracker;
import com.kano.launcher.core.ModrinthClient;
import com.kano.launcher.core.ServerPing;
import com.kano.launcher.core.ServerStore;
import com.kano.launcher.core.Stats;
import com.kano.launcher.core.UpdateChecker;
import com.kano.launcher.core.VersionDetail;
import com.kano.launcher.core.VersionManifest;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.StageStyle;
import javafx.geometry.Rectangle2D;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KanoLauncher main window — Phase 1, neon-glow theme.
 * Custom transparent title bar, glowing sidebar nav, card-based Instance Manager.
 */
public class MainApp extends Application {

    private static final String VERSION = "v1.1.0";
    private static final String[] VERSIONS = {"1.21.4", "1.21.1", "1.20.6", "1.20.4", "1.20.1"};

    private final StackPane content = new StackPane();
    private final List<HBox> navItems = new ArrayList<>();
    private final Map<String, HBox> navByName = new HashMap<>();
    private volatile VersionManifest versionManifest;
    private AccountManager accountManager;
    private InstanceManager instanceManager;
    private String clientId;
    private String initError;
    private Stage stage;
    private double dragX, dragY;
    private StackPane appShell;
    private StackPane appPanel;
    private boolean maximized;
    private double restoreX, restoreY, restoreW, restoreH;
    private Label accountChip;
    private HBox avatarBar;
    private Label titleText;
    private javafx.scene.text.TextFlow brandFlow;
    private Stats stats;
    private Label statsLabel;
    private Config config;
    private ContentSource browseSource = new ModrinthClient();
    private ImageView bgView;

    // ---- running games (Playing Now) ----
    private record RunningGame(Instance inst, Process proc, String player, long startEpoch) {}
    private final Map<Long, RunningGame> running = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong runId = new java.util.concurrent.atomic.AtomicLong();

    // ---- command palette (Ctrl+K) ----
    private StackPane paletteOverlay;
    private TextField paletteField;
    private VBox paletteResults;
    private final List<PaletteAction> paletteActions = new ArrayList<>();
    private final List<PaletteAction> paletteFiltered = new ArrayList<>();
    private int paletteSelected = 0;
    private HBox updateBanner;
    private Label updateBannerText;
    private String updateUrl = UpdateChecker.releasesPageUrl();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        initData();

        BorderPane inner = new BorderPane();
        inner.setTop(new VBox(buildTitleBar(), buildUpdateBanner()));
        inner.setLeft(buildSidebar());
        inner.setCenter(new StackPane(content, buildPalette()));
        inner.setBottom(buildStatsBar());

        appPanel = new StackPane();
        appPanel.getStyleClass().add("app-panel");
        Node bg = buildBackground(appPanel);
        if (bg != null) appPanel.getChildren().add(bg);
        appPanel.getChildren().add(inner);

        appShell = new StackPane(appPanel, buildResizeGrip());
        appShell.getStyleClass().add("app-shell");
        StackPane shell = appShell;

        // Subtle fade-in whenever the main view swaps (every show*() does content.setAll).
        content.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            if (config != null && !config.animations()) { content.setOpacity(1.0); return; }
            javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(Duration.millis(170), content);
            ft.setFromValue(0.0);
            ft.setToValue(1.0);
            ft.play();
        });

        showHome();   // dashboard landing

        // Size the window to FIT the screen so the (undecorated) title bar + window buttons are never
        // pushed off-screen on smaller or DPI-scaled displays. Top-LEFT anchored with a generous
        // margin — every corner (incl. the top-right min/max/close buttons) stays inside the work area.
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double winW = Math.min(1180, vb.getWidth() - 80);
        double winH = Math.min(720, vb.getHeight() - 80);

        Scene scene = new Scene(shell, winW, winH);
        scene.setFill(Color.TRANSPARENT);
        var css = getClass().getResource("kano.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN), this::openPalette);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(launcherName());
        // Window / taskbar icon = the king (prefers a square avatar if present, else the render).
        var iconUrl = getClass().getResource("king-avatar.png");
        if (iconUrl == null) iconUrl = getClass().getResource("king-bg.png");
        if (iconUrl != null) stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        stage.setScene(scene);
        stage.setMinWidth(Math.min(960, vb.getWidth() - 40));
        stage.setMinHeight(Math.min(600, vb.getHeight() - 40));
        stage.setWidth(winW);
        stage.setHeight(winH);
        // Top-left anchor with a 40px margin — guarantees the whole window (and its title bar) is on-screen.
        stage.setX(vb.getMinX() + 40);
        stage.setY(vb.getMinY() + 40);
        stage.show();
        logWindowGeometry("after-show");
        // After show, undecorated stages can be nudged off-screen by the platform — clamp back in.
        Platform.runLater(this::clampOnScreen);

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                this::recenterWindow);

        applyTheme();
        checkForUpdate();
    }

    /** Pull the window fully back into the work area of the screen it's actually on (top edge especially). */
    private void clampOnScreen() {
        if (stage == null) return;
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
        Screen scr = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        Rectangle2D vb = scr.getVisualBounds();
        double w = Math.min(stage.getWidth(), vb.getWidth() - 60);
        double h = Math.min(stage.getHeight(), vb.getHeight() - 60);
        stage.setWidth(w);
        stage.setHeight(h);
        double x = Math.max(vb.getMinX() + 30, Math.min(stage.getX(), vb.getMinX() + vb.getWidth() - w - 30));
        double y = Math.max(vb.getMinY() + 30, Math.min(stage.getY(), vb.getMinY() + vb.getHeight() - h - 30));
        stage.setX(x);
        stage.setY(y);
        logWindowGeometry("clamped");
    }

    /** Append screen + window geometry to a debug log so off-screen placement can be diagnosed. */
    private void logWindowGeometry(String when) {
        try {
            StringBuilder sb = new StringBuilder("[").append(when).append("]\n");
            Screen pr = Screen.getPrimary();
            sb.append("  primary.bounds=").append(pr.getBounds()).append("\n");
            sb.append("  primary.visualBounds=").append(pr.getVisualBounds()).append("\n");
            sb.append("  primary.outputScale=").append(pr.getOutputScaleX()).append(" x ").append(pr.getOutputScaleY()).append("\n");
            sb.append("  screen count=").append(Screen.getScreens().size()).append("\n");
            if (stage != null) sb.append("  stage x=").append((int) stage.getX()).append(" y=").append((int) stage.getY())
                    .append(" w=").append((int) stage.getWidth()).append(" h=").append((int) stage.getHeight())
                    .append(" maximized=").append(maximized).append("\n");
            Files.writeString(resolveDataDir().resolve("window-debug.log"), sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** Re-fit + re-center the window (Ctrl+Shift+R) — a manual rescue if it ever ends up off-screen. */
    private void recenterWindow() {
        if (stage == null) return;
        if (maximized) { toggleMaximize(); return; }
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double w = Math.min(Math.max(960, stage.getWidth()), vb.getWidth() - 40);
        double h = Math.min(Math.max(600, stage.getHeight()), vb.getHeight() - 40);
        stage.setWidth(w);
        stage.setHeight(h);
        stage.setX(vb.getMinX() + (vb.getWidth() - w) / 2);
        stage.setY(vb.getMinY() + 28);
    }

    private void toggleMaximize() {
        if (stage == null) return;
        if (!maximized) {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreW = stage.getWidth();
            restoreH = stage.getHeight();
            Screen scr = Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1)
                    .stream().findFirst().orElse(Screen.getPrimary());
            Rectangle2D b = scr.getVisualBounds(); // work area — excludes the taskbar
            stage.setX(b.getMinX());
            stage.setY(b.getMinY());
            stage.setWidth(b.getWidth());
            stage.setHeight(b.getHeight());
            maximized = true;
            shellFlat(true);
        } else {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreW);
            stage.setHeight(restoreH);
            maximized = false;
            shellFlat(false);
        }
    }

    private void shellFlat(boolean flat) {
        if (appShell == null || appPanel == null) return;
        if (flat) {
            appShell.setStyle("-fx-padding: 0;");
            if (!appPanel.getStyleClass().contains("app-panel-flat")) appPanel.getStyleClass().add("app-panel-flat");
        } else {
            appShell.setStyle("");
            appPanel.getStyleClass().remove("app-panel-flat");
        }
    }

    private String launcherName() {
        return config != null ? config.launcherName() : "KanoLauncher";
    }

    // ---- color themes ----

    private record Theme(String name, String accent, String bright, String dark) {}

    private static final java.util.Map<String, Theme> THEMES = new java.util.LinkedHashMap<>();
    static {
        THEMES.put("crimson", new Theme("Crimson", "#D32F2F", "#E53935", "#B71C1C"));
        THEMES.put("ocean", new Theme("Ocean", "#1565C0", "#2196F3", "#0D47A1"));
        THEMES.put("forest", new Theme("Forest", "#2E7D32", "#43A047", "#1B5E20"));
        THEMES.put("amethyst", new Theme("Amethyst", "#7B1FA2", "#9C27B0", "#4A148C"));
        THEMES.put("gold", new Theme("Gold", "#C9A227", "#E0B83A", "#9A7B16"));
        THEMES.put("sunset", new Theme("Sunset", "#E65100", "#FB8C00", "#BF360C"));
        THEMES.put("rose", new Theme("Rose", "#C2185B", "#E91E63", "#880E4F"));
        THEMES.put("teal", new Theme("Teal", "#00838F", "#00ACC1", "#005662"));
        THEMES.put("slate", new Theme("Slate", "#546E7A", "#78909C", "#37474F"));
        THEMES.put("cyber", new Theme("Cyber", "#00BFA5", "#1DE9B6", "#00897B"));
    }

    private String resolveAccent() {
        String key = config != null ? config.themeKey() : "crimson";
        if (THEMES.containsKey(key)) return THEMES.get(key).accent();
        return config != null ? config.themeAccent() : "#D32F2F";
    }

    /** Compute the whole accent palette (solid + glow + border + dark tints) from one accent hex. */
    private void applyTheme() {
        if (stage == null || stage.getScene() == null) return;
        int[] c = hexRgb(resolveAccent());
        String accent = hex(c[0], c[1], c[2]);
        String bright = hex(c[0] + 30, c[1] + 30, c[2] + 30);
        String dark = hex((int) (c[0] * 0.72), (int) (c[1] * 0.72), (int) (c[2] * 0.72));
        String glow = "rgba(" + c[0] + "," + c[1] + "," + c[2] + ",0.55)";
        String border = "rgba(" + c[0] + "," + c[1] + "," + c[2] + ",0.6)";
        String tint = hex((int) (c[0] * 0.11) + 8, (int) (c[1] * 0.11) + 4, (int) (c[2] * 0.11) + 4);
        String tintStrong = hex((int) (c[0] * 0.14) + 10, (int) (c[1] * 0.14) + 5, (int) (c[2] * 0.14) + 5);
        String tintDeep = hex((int) (c[0] * 0.07) + 6, (int) (c[1] * 0.07) + 3, (int) (c[2] * 0.07) + 3);
        stage.getScene().getRoot().setStyle(
                "-kano-accent: " + accent + "; -kano-accent-bright: " + bright + "; -kano-accent-dark: " + dark
                + "; -kano-glow: " + glow + "; -kano-border: " + border
                + "; -kano-tint: " + tint + "; -kano-tint-strong: " + tintStrong
                + "; -kano-tint-deep: " + tintDeep + ";");
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static String hex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", clamp(r), clamp(g), clamp(b));
    }

    private static String toHex(javafx.scene.paint.Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private static int[] hexRgb(String hex) {
        String h = hex == null ? "D32F2F" : (hex.startsWith("#") ? hex.substring(1) : hex);
        if (h.length() < 6) h = "D32F2F";
        return new int[]{
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)};
    }

    // ---- self-update notification (notify only; see UpdateChecker for the bootstrapper design) ----

    private HBox buildUpdateBanner() {
        updateBannerText = new Label();
        updateBannerText.getStyleClass().add("update-banner-text");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Button view = new Button("View release");
        view.getStyleClass().add("btn-filled");
        view.setOnAction(e -> getHostServices().showDocument(updateUrl));
        Button dismiss = new Button("✕");
        dismiss.getStyleClass().add("win-btn");
        dismiss.setOnAction(e -> hideUpdateBanner());
        updateBanner = new HBox(10, updateBannerText, grow, view, dismiss);
        updateBanner.getStyleClass().add("update-banner");
        updateBanner.setAlignment(Pos.CENTER_LEFT);
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
        return updateBanner;
    }

    private void hideUpdateBanner() {
        if (updateBanner == null) return;
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
    }

    private void showUpdateBanner(UpdateChecker.Result res) {
        if (updateBanner == null) return;
        updateUrl = res.htmlUrl();
        updateBannerText.setText("Update available: " + res.latestTag() + "  —  you have " + res.currentVersion());
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
    }

    private void checkForUpdate() {
        Thread t = new Thread(() -> {
            try {
                UpdateChecker.Result res = UpdateChecker.check(VERSION);
                if (res.newer()) Platform.runLater(() -> showUpdateBanner(res));
            } catch (Exception ignored) {
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void initData() {
        try {
            Path dir = resolveDataDir();
            accountManager = new AccountManager(dir);
            instanceManager = new InstanceManager(dir);
            stats = new Stats(dir);
            config = new Config(dir);
        } catch (Exception e) {
            initError = "Init failed: " + e.getMessage();
        }
        clientId = resolveClientId();

        // Fetch the full Mojang version list in the background so the Create dialog shows every version.
        Thread t = new Thread(() -> {
            try { versionManifest = VersionManifest.fetch(); } catch (Exception ignored) { }
        }, "version-manifest");
        t.setDaemon(true);
        t.start();
    }

    // ---- title bar ----

    private HBox buildTitleBar() {
        Label k = new Label("◤K◢");
        k.getStyleClass().add("brand-k");
        Label title = new Label(launcherName() + " " + VERSION);
        title.getStyleClass().add("title-text");
        titleText = title;

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        accountChip = new Label(accountLabel());
        accountChip.getStyleClass().add("account-chip");
        accountChip.setOnMouseClicked(e -> showAccounts());

        avatarBar = new HBox(6);
        avatarBar.getStyleClass().add("avatar-bar");
        avatarBar.setAlignment(Pos.CENTER_LEFT);
        refreshAvatarBar();

        Button min = winButton("—", false);
        min.setOnAction(e -> stage.setIconified(true));
        Button max = winButton("❐", false);
        max.setOnAction(e -> toggleMaximize());
        Button close = winButton("✕", true);
        close.setOnAction(e -> Platform.exit());

        HBox bar = new HBox(10, k, title, grow, avatarBar, accountChip, min, max, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.setOnMousePressed(e -> { dragX = e.getScreenX() - stage.getX(); dragY = e.getScreenY() - stage.getY(); });
        bar.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) { stage.setX(e.getScreenX() - dragX); stage.setY(e.getScreenY() - dragY); }
        });
        return bar;
    }

    private Button winButton(String glyph, boolean isClose) {
        Button b = new Button(glyph);
        b.getStyleClass().add("win-btn");
        if (isClose) b.getStyleClass().add("win-close");
        return b;
    }

    private String accountLabel() {
        var active = activeAccount();
        return active != null ? "● " + active.username() : "Sign in";
    }

    /** Account launch uses: the one matching Config.activeUuid, else the first stored, else null. */
    private AccountManager.StoredAccount activeAccount() {
        if (accountManager == null) return null;
        var accounts = accountManager.list();
        if (accounts.isEmpty()) return null;
        String want = config != null ? config.activeUuid() : "";
        if (want != null && !want.isBlank()) {
            for (var a : accounts) if (a.uuid().equals(want)) return a;
        }
        return accounts.get(0);
    }

    private void setActiveAccount(String uuid) {
        if (config != null) config.setActiveUuid(uuid);
        if (accountChip != null) accountChip.setText(accountLabel());
        refreshAvatarBar();
    }

    private static String avatarUrl(String uuid) {
        return "https://crafatar.com/avatars/" + uuid + "?size=32&overlay";
    }

    /** Rebuild the title-bar avatar row from saved accounts; highlight the active one. */
    private void refreshAvatarBar() {
        if (avatarBar == null) return;
        avatarBar.getChildren().clear();
        if (accountManager == null) return;
        var accounts = accountManager.list();
        if (accounts.isEmpty()) { avatarBar.setManaged(false); avatarBar.setVisible(false); return; }
        avatarBar.setManaged(true);
        avatarBar.setVisible(true);
        var active = activeAccount();
        String activeUuid = active != null ? active.uuid() : "";
        for (var acc : accounts) {
            avatarBar.getChildren().add(avatarTile(acc, acc.uuid().equals(activeUuid)));
        }
    }

    /** A 32px rounded avatar button for one account; click = make active. */
    private StackPane avatarTile(AccountManager.StoredAccount acc, boolean isActive) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("avatar-btn");
        if (isActive) tile.getStyleClass().add("avatar-btn-active");
        tile.setPrefSize(32, 32);
        tile.setMinSize(32, 32);
        tile.setMaxSize(32, 32);
        Label placeholder = new Label(acc.username().isEmpty() ? "?" : acc.username().substring(0, 1).toUpperCase());
        placeholder.getStyleClass().add("avatar-initial");
        tile.getChildren().add(placeholder);
        ImageView iv = new ImageView();
        iv.setFitWidth(32);
        iv.setFitHeight(32);
        Rectangle clip = new Rectangle(32, 32);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        iv.setClip(clip);
        tile.getChildren().add(iv);
        loadIcon(iv, avatarUrl(acc.uuid()));
        Tooltip.install(tile, new Tooltip(acc.username()));
        tile.setOnMouseClicked(e -> setActiveAccount(acc.uuid()));
        return tile;
    }

    // ---- sidebar ----

    private VBox buildSidebar() {
        VBox side = new VBox();
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(230);

        Label logo = new Label("K");
        logo.getStyleClass().add("brand-logo");
        brandFlow = new javafx.scene.text.TextFlow();
        rebuildBrand();
        HBox brand = new HBox(10, logo, brandFlow);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand-row");

        side.getChildren().add(brand);
        side.getChildren().addAll(
                nav("⌂", "Home", this::showHome),
                nav("▤", "Library", this::showLibrary),
                nav("◰", "Instances", this::showInstances),
                nav("▶", "Playing Now", this::showPlayingNow),
                nav("❖", "Browse Mods", this::showBrowse),
                nav("☺", "Skins", this::showSkins),
                nav("⚙", "Settings", this::showSettings));
        return side;
    }

    /** Render the sidebar brand from the configured colour segments (each run its own hex colour). */
    private void rebuildBrand() {
        if (brandFlow == null) return;
        brandFlow.getChildren().clear();
        List<Config.NameSegment> segs = config != null
                ? config.nameSegments()
                : List.of(new Config.NameSegment(launcherName(), "#FFFFFF"));
        for (Config.NameSegment s : segs) {
            javafx.scene.text.Text tx = new javafx.scene.text.Text(s.text());
            String color = s.color() == null || s.color().isBlank() ? "#FFFFFF" : s.color();
            tx.setStyle("-fx-font-size:17px; -fx-font-weight:bold; -fx-fill:" + color + ";");
            brandFlow.getChildren().add(tx);
        }
    }

    private HBox nav(String icon, String text, Runnable action) {
        Label ic = new Label(icon);
        ic.getStyleClass().add("nav-icon");
        Label tx = new Label(text);
        tx.getStyleClass().add("nav-text");
        HBox item = new HBox(ic, tx);
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> { selectNav(item); action.run(); });
        navItems.add(item);
        navByName.put(text, item);
        return item;
    }

    private void selectNav(HBox active) {
        for (HBox n : navItems) n.getStyleClass().remove("nav-item-active");
        if (active != null && !active.getStyleClass().contains("nav-item-active")) {
            active.getStyleClass().add("nav-item-active");
        }
    }

    // ---- Home dashboard ----

    private void showHome() {
        selectNav(navByName.get("Home"));
        VBox page = new VBox(22);
        page.getStyleClass().add("content");

        var acc = activeAccount();
        String who = acc != null ? acc.username() : "player";
        int worlds = Stats.countWorlds(resolveDataDir().resolve("instances"));
        String playtime = stats != null ? stats.playtimeText() : "0h";
        int launches = stats != null ? stats.launches() : 0;
        Instance last = lastPlayedInstance();

        // ---- hero: resume your realm ----
        Label eyebrow = new Label("WELCOME BACK");
        eyebrow.getStyleClass().add("hero-eyebrow");
        Label brand = new Label(launcherName());
        brand.getStyleClass().add("hero-title");
        Label tagline = new Label(last != null
                ? "Pick up where you left off, " + who + "."
                : "Create your first instance and forge your realm, " + who + ".");
        tagline.getStyleClass().add("hero-tagline");
        tagline.setWrapText(true);

        ProgressBar heroBar = progress(0.0);
        heroBar.setPrefWidth(260);
        heroBar.setVisible(false);
        heroBar.setManaged(false);
        Button cta = new Button(last != null ? "▶  Continue · " + last.name() : "Create your first instance");
        cta.getStyleClass().add("btn-filled");
        cta.setStyle("-fx-font-size: 14px; -fx-padding: 11 22 11 22;");
        cta.setOnAction(e -> {
            if (last != null) { heroBar.setVisible(true); heroBar.setManaged(true); onPlay(last, heroBar, cta); }
            else showCreateDialog();
        });
        Button browse = new Button("Browse mods");
        browse.getStyleClass().add("btn-outline");
        browse.setStyle("-fx-padding: 11 18 11 18;");
        browse.setOnAction(e -> showBrowse());
        HBox ctaRow = new HBox(12, cta, browse);
        ctaRow.setAlignment(Pos.CENTER_LEFT);

        HBox statRow = new HBox(36,
                heroStat(String.valueOf(worlds), "Worlds"),
                heroStat(playtime, "Played"),
                heroStat(String.valueOf(launches), "Launches"));
        statRow.setAlignment(Pos.CENTER_LEFT);

        Region heroGap = new Region();
        heroGap.setMinHeight(6);
        VBox heroLeft = new VBox(10, eyebrow, brand, tagline, ctaRow, heroBar, heroGap, statRow);
        heroLeft.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heroLeft, Priority.ALWAYS);

        HBox hero = new HBox(20, heroLeft);
        Node king = heroKing();
        if (king != null) hero.getChildren().add(king);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("hero-panel");
        page.getChildren().add(hero);

        // ---- jump back in: recently played ----
        var recents = instanceManager == null ? List.<Instance>of()
                : instanceManager.list().stream()
                    .filter(i -> i.lastPlayedEpoch() > 0)
                    .sorted((a, b) -> Long.compare(b.lastPlayedEpoch(), a.lastPlayedEpoch()))
                    .limit(4).toList();
        if (!recents.isEmpty()) {
            Label jb = new Label("Jump back in");
            jb.getStyleClass().add("group-title");
            FlowPane recRow = new FlowPane(14, 14);
            for (Instance i : recents) recRow.getChildren().add(recentCard(i));
            page.getChildren().addAll(jb, recRow);
        }

        // ---- quick actions ----
        Label qa = new Label("Quick actions");
        qa.getStyleClass().add("group-title");
        FlowPane grid = new FlowPane(14, 14);
        grid.getChildren().addAll(
                homeCard("◰", "Instances", "Create and launch", this::showInstances),
                homeCard("❖", "Browse Mods", "Find mods on Modrinth", this::showBrowse),
                homeCard("▤", "Library", "Installed content", this::showLibrary),
                homeCard("☺", "Skins", "Change your skin", this::showSkins),
                homeCard("●", "Accounts", "Microsoft accounts", this::showAccounts),
                homeCard("⚙", "Settings", "Launcher options", this::showSettings));
        Label tip = new Label("Tip: press Ctrl+K anywhere for the command palette.");
        tip.getStyleClass().add("muted");
        page.getChildren().addAll(qa, grid, tip);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        content.getChildren().setAll(scroll);
    }

    private Instance lastPlayedInstance() {
        if (instanceManager == null) return null;
        return instanceManager.list().stream()
                .filter(i -> i.lastPlayedEpoch() > 0)
                .max((a, b) -> Long.compare(a.lastPlayedEpoch(), b.lastPlayedEpoch()))
                .orElse(null);
    }

    private VBox heroStat(String value, String label) {
        Label v = new Label(value);
        v.getStyleClass().add("stat-num");
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        VBox b = new VBox(1, v, l);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    private Node heroKing() {
        var url = getClass().getResource("king-avatar.png");
        if (url == null) url = getClass().getResource("king-bg.png");
        if (url == null) return null;
        ImageView iv = new ImageView(new Image(url.toExternalForm(), 250, 250, true, true));
        iv.setEffect(new javafx.scene.effect.DropShadow(30, javafx.scene.paint.Color.web("#000000")));
        StackPane wrap = new StackPane(iv);
        wrap.setMinWidth(250);
        wrap.setAlignment(Pos.CENTER);
        return wrap;
    }

    private Region recentCard(Instance inst) {
        StackPane tile = iconTile(inst, true);
        Label name = new Label(inst.name());
        name.getStyleClass().add("card-title-sm");
        Label meta = new Label(inst.version() + "  ·  " + inst.loader().display());
        meta.getStyleClass().add("card-sub");
        VBox info = new VBox(3, name, meta);
        HBox.setHgrow(info, Priority.ALWAYS);
        ProgressBar bar = progress(0.0);
        bar.setMaxWidth(0);
        bar.setVisible(false);
        bar.setManaged(false);
        Button play = new Button("▶");
        play.getStyleClass().add("play-circle");
        play.setStyle("-fx-min-width:38; -fx-min-height:38; -fx-max-width:38; -fx-max-height:38; -fx-font-size:14px;");
        play.setOnAction(e -> onPlay(inst, bar, play));
        HBox row = new HBox(12, tile, info, play);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane card = new StackPane(row);
        card.getStyleClass().add("card");
        card.setPrefWidth(290);
        card.setStyle("-fx-cursor: hand;");
        card.setOnMouseClicked(e -> showInstanceDetail(inst));
        return card;
    }

    /** A consistent page header: a small accent eyebrow over the page title (matches the Home hero). */
    private VBox pageHeader(String eyebrow, String title) {
        Label e = new Label(eyebrow);
        e.getStyleClass().add("page-eyebrow");
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        return new VBox(1, e, t);
    }

    private Region homeCard(String icon, String title, String desc, Runnable action) {
        Label ic = new Label(icon);
        ic.getStyleClass().add("icon-glyph");
        Label t = new Label(title);
        t.getStyleClass().add("card-title-sm");
        Label d = new Label(desc);
        d.getStyleClass().add("card-sub");
        VBox box = new VBox(6, ic, t, d);
        box.getStyleClass().add("card");
        box.setPrefWidth(250);
        box.setOnMouseClicked(e -> action.run());
        box.setStyle("-fx-cursor: hand;");
        return box;
    }

    // ---- Instances view ----

    private void showInstances() {
        selectNav(navByName.get("Instances"));
        VBox page = new VBox(18);
        page.getStyleClass().add("content");

        VBox title = pageHeader("YOUR GAMES", "Instances");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Button create = new Button("Create Instance");
        create.getStyleClass().add("btn-outline");
        create.setOnAction(e -> showCreateDialog());
        Button importBtn = new Button("Import .mrpack");
        importBtn.getStyleClass().add("btn-filled");
        importBtn.setOnAction(e -> importMrpackFile());
        HBox header = new HBox(14, title, grow, create, importBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        List<Instance> all = instanceManager == null ? List.of() : instanceManager.list();

        TextField search = new TextField();
        search.setPromptText("Search instances…");
        HBox.setHgrow(search, Priority.ALWAYS);
        ChoiceBox<String> sort = new ChoiceBox<>();
        sort.getItems().addAll("Recently played", "Name (A–Z)", "Recently created");
        sort.getSelectionModel().selectFirst();
        Label sortLbl = new Label("Sort:");
        sortLbl.getStyleClass().add("card-sub");
        HBox filterBar = new HBox(10, search, sortLbl, sort);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(16);
        Runnable rebuild = () -> {
            body.getChildren().clear();
            if (all.isEmpty()) {
                Label empty = new Label("No instances yet. Click “Create Instance” to make your first one.");
                empty.getStyleClass().add("muted");
                body.getChildren().add(empty);
                return;
            }
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
            List<Instance> shown = new ArrayList<>();
            for (Instance inst : all) {
                if (q.isEmpty() || inst.name().toLowerCase().contains(q)
                        || inst.version().toLowerCase().contains(q)
                        || inst.groupOrNone().toLowerCase().contains(q)) shown.add(inst);
            }
            switch (sort.getValue()) {
                case "Name (A–Z)" -> shown.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                case "Recently created" -> shown.sort((a, b) -> Long.compare(b.createdEpoch(), a.createdEpoch()));
                default -> shown.sort((a, b) -> Long.compare(b.lastPlayedEpoch(), a.lastPlayedEpoch()));
            }
            if (shown.isEmpty()) {
                Label none = new Label("No instances match “" + search.getText() + "”.");
                none.getStyleClass().add("muted");
                body.getChildren().add(none);
                return;
            }
            // Group by label: named groups first (alphabetical), then everything ungrouped.
            java.util.Map<String, List<Instance>> groups = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            List<Instance> ungrouped = new ArrayList<>();
            for (Instance inst : shown) {
                String g = inst.groupOrNone();
                if (g.isEmpty()) ungrouped.add(inst);
                else groups.computeIfAbsent(g, k -> new ArrayList<>()).add(inst);
            }
            boolean anyNamed = !groups.isEmpty();
            for (var en : groups.entrySet()) body.getChildren().add(groupSection(en.getKey(), en.getValue(), true));
            if (!ungrouped.isEmpty()) body.getChildren().add(groupSection(anyNamed ? "Ungrouped" : "", ungrouped, false));
        };
        rebuild.run();
        search.textProperty().addListener((o, a, b) -> rebuild.run());
        sort.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> rebuild.run());

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        if (all.isEmpty()) page.getChildren().addAll(header, scroll);
        else page.getChildren().addAll(header, filterBar, scroll);
        content.getChildren().setAll(page);
    }

    /** A group's header (name + bulk actions) over its card grid. Pass label "" for a bare grid. */
    private Region groupSection(String label, List<Instance> items, boolean named) {
        VBox section = new VBox(10);
        FlowPane grid = new FlowPane(16, 16);
        for (Instance inst : items) grid.getChildren().add(smallCard(inst));
        if (label == null || label.isEmpty()) { section.getChildren().add(grid); return section; }

        Label name = new Label(label + "  (" + items.size() + ")");
        name.getStyleClass().add("group-title");
        HBox head = new HBox(12, name);
        head.setAlignment(Pos.CENTER_LEFT);
        if (named) {
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button ram = new Button("Set RAM…");
            ram.getStyleClass().add("btn-outline");
            ram.setOnAction(e -> bulkSetRam(label, items));
            Button upd = new Button("⬆ Update mods");
            upd.getStyleClass().add("btn-outline");
            upd.setOnAction(e -> bulkUpdateMods(label, items));
            head.getChildren().addAll(grow, ram, upd);
        }
        section.getChildren().addAll(head, grid);
        return section;
    }

    /** Apply one heap size to every instance in a group. */
    private void bulkSetRam(String group, List<Instance> items) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Set RAM for group");
        d.setHeaderText("Apply heap size to all " + items.size() + " instance(s) in \"" + group + "\"");
        Slider s = new Slider(1024, 16384, items.get(0).ramMb());
        s.setPrefWidth(360);
        s.getStyleClass().add("ram-slider");
        s.setBlockIncrement(512);
        s.setMajorTickUnit(2048);
        s.setMinorTickCount(3);
        s.setSnapToTicks(true);
        s.setShowTickMarks(true);
        Label val = new Label(snapRam(s.getValue()) + " MB");
        val.getStyleClass().add("ram-value");
        val.setMinWidth(80);
        s.valueProperty().addListener((o, a, b) -> val.setText(snapRam(b.doubleValue()) + " MB"));
        VBox box = new VBox(10, new Label("RAM"), new HBox(10, s, val));
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        styleDialog(d);
        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
            int mb = snapRam(s.getValue());
            try {
                for (Instance inst : items) instanceManager.update(inst.withRam(mb));
                showInstances();
                alert(Alert.AlertType.INFORMATION, "RAM updated", "Set " + mb + " MB on " + items.size() + " instance(s).");
            } catch (Exception ex) {
                alert(Alert.AlertType.ERROR, "Failed", String.valueOf(ex.getMessage()));
            }
        }
    }

    /** Run "update all tracked mods" across every instance in a group, with one combined summary. */
    private void bulkUpdateMods(String group, List<Instance> items) {
        Thread t = new Thread(() -> {
            int totUpdated = 0, totChecked = 0;
            java.util.List<String> issues = new java.util.ArrayList<>();
            for (Instance inst : items) {
                try {
                    int[] r = updateInstanceModsSync(inst, issues);
                    totChecked += r[0];
                    totUpdated += r[1];
                } catch (Exception ex) {
                    issues.add(inst.name() + " (" + ex.getMessage() + ")");
                }
            }
            int ch = totChecked, up = totUpdated;
            Platform.runLater(() -> {
                showInstances();
                alert(Alert.AlertType.INFORMATION, "Group update complete",
                        "Group \"" + group + "\": checked " + ch + " tracked item(s) across " + items.size()
                        + " instance(s); updated " + up + "."
                        + (issues.isEmpty() ? "" : "\nSkipped: " + String.join(", ", issues)));
            });
        }, "bulk-update");
        t.setDaemon(true);
        t.start();
    }

    // ---- Playing Now ----

    private boolean isRunning(Instance inst) {
        return running.values().stream().anyMatch(g -> g.inst().dirName().equals(inst.dirName()));
    }

    /** Update the sidebar count and, if the Playing Now page is open, re-render it. */
    private void onRunningChanged() {
        HBox navP = navByName.get("Playing Now");
        if (navP != null && navP.getChildren().size() > 1 && navP.getChildren().get(1) instanceof Label lbl) {
            int n = running.size();
            lbl.setText(n > 0 ? "Playing Now (" + n + ")" : "Playing Now");
        }
        if (navP != null && navP.getStyleClass().contains("nav-item-active")) showPlayingNow();
    }

    private void showPlayingNow() {
        selectNav(navByName.get("Playing Now"));
        VBox page = new VBox(18);
        page.getStyleClass().add("content");
        Label title = new Label("Playing Now");
        title.getStyleClass().add("page-title");

        VBox body = new VBox(12);
        List<RunningGame> games = new ArrayList<>(running.values());
        games.sort(java.util.Comparator
                .comparing((RunningGame g) -> g.player().toLowerCase())
                .thenComparing(g -> g.inst().version()));
        if (games.isEmpty()) {
            Label none = new Label("No games running. Launch an instance and it shows up here.");
            none.getStyleClass().add("muted");
            body.getChildren().add(none);
        } else {
            String lastPlayer = null;
            for (RunningGame g : games) {
                if (!g.player().equals(lastPlayer)) {
                    Label ph = new Label("👤  " + g.player());
                    ph.getStyleClass().add("group-title");
                    body.getChildren().add(ph);
                    lastPlayer = g.player();
                }
                body.getChildren().add(runningCard(g));
            }
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        page.getChildren().addAll(title, scroll);
        content.getChildren().setAll(page);
    }

    private Region runningCard(RunningGame g) {
        Instance inst = g.inst();
        StackPane iconTile = iconTile(inst, true);
        Label name = new Label(inst.name());
        name.getStyleClass().add("card-title-sm");
        long mins = Math.max(0, (System.currentTimeMillis() - g.startEpoch()) / 60000);
        Label sub = new Label("● Running   •   " + inst.version() + "  " + inst.loader().display()
                + "   •   up " + mins + "m   •   " + g.player());
        sub.getStyleClass().add("card-sub");
        sub.setStyle("-fx-text-fill: #5bd75b;");
        VBox info = new VBox(4, name, sub);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button open = new Button("Open Folder");
        open.getStyleClass().add("btn-outline");
        open.setOnAction(e -> openFolder(instanceManager.instanceDir(inst)));
        Button kill = new Button("⏻ Kill");
        kill.getStyleClass().add("btn-outline");
        kill.setOnAction(e -> {
            g.proc().destroy();
            g.proc().destroyForcibly();
            // waitFor() in onPlay will unregister + refresh; nudge the view immediately too.
            Platform.runLater(this::onRunningChanged);
        });
        HBox row = new HBox(14, iconTile, info, open, kill);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane card = new StackPane(row);
        card.getStyleClass().add("card");
        return card;
    }

    private Region featuredCard(Instance inst) {
        StackPane iconTile = iconTile(inst, false);
        Label title = new Label(inst.name());
        title.getStyleClass().add("card-title");
        Button play = new Button("▶");
        play.getStyleClass().add("play-circle");
        HBox titleRow = new HBox(16, title, play);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label meta = new Label(metaLine(inst));
        meta.getStyleClass().add("card-meta");
        Label sub = new Label("Last played: " + lastPlayed(inst));
        sub.getStyleClass().add("card-sub");
        ProgressBar bar = progress(0.0);
        play.setOnAction(e -> onPlay(inst, bar, play));

        VBox info = new VBox(6, titleRow, meta, sub, bar);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(18, iconTile, info);
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane card = new StackPane(row);
        card.getStyleClass().addAll("card", "card-featured");
        card.setStyle("-fx-cursor: hand;");
        card.setOnMouseClicked(e -> showInstanceDetail(inst));
        return card;
    }

    private Region smallCard(Instance inst) {
        StackPane iconTile = iconTile(inst, true);
        Label title = new Label(inst.name());
        title.getStyleClass().add("card-title-sm");
        Label meta = new Label(metaLine(inst));
        meta.getStyleClass().add("card-meta");
        Label sub = new Label(isRunning(inst) ? "● Running now" : "Last played: " + lastPlayed(inst));
        sub.getStyleClass().add("card-sub");
        if (isRunning(inst)) sub.setStyle("-fx-text-fill: #5bd75b;");
        ProgressBar bar = progress(0.0);
        VBox info = new VBox(4, title, meta, sub, bar);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(14, iconTile, info);
        row.setAlignment(Pos.CENTER_LEFT);
        Button play = new Button("▶");
        play.getStyleClass().add("play-circle");
        play.setStyle("-fx-min-width:40; -fx-min-height:40; -fx-max-width:40; -fx-max-height:40; -fx-font-size:15px;");
        play.setOnAction(e -> onPlay(inst, bar, play));
        row.getChildren().add(play);

        StackPane card = new StackPane(row);
        card.getStyleClass().add("card");
        card.setPrefWidth(420);
        card.setStyle("-fx-cursor: hand;");
        card.setOnMouseClicked(e -> showInstanceDetail(inst));
        return card;
    }

    private StackPane iconTile(Instance inst, boolean small) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("icon-tile");
        if (small) tile.getStyleClass().add("icon-tile-sm");
        Path img = instanceManager != null ? instanceManager.instanceDir(inst).resolve("icon.png") : null;
        if (img != null && Files.exists(img)) {
            double sz = small ? 44 : 64;
            ImageView iv = new ImageView(new Image(img.toUri().toString(), sz, sz, true, true));
            Rectangle clip = new Rectangle(sz, sz);
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            iv.setClip(clip);
            tile.getChildren().add(iv);
        } else {
            tile.setStyle("-fx-background-color: " + blockColor(inst.iconOrDefault()) + ";");
        }
        return tile;
    }

    private Button editIcon(Instance inst) {
        Button edit = new Button("✎");
        edit.getStyleClass().add("edit-btn");
        edit.setOnAction(e -> showInstanceDetail(inst));
        StackPane.setAlignment(edit, Pos.TOP_RIGHT);
        return edit;
    }

    // ---- preset profile blocks (stylized, original colors — not Mojang textures) ----

    private record Block(String key, String name, String color) {}

    private static final List<Block> BLOCKS = List.of(
            new Block("grass", "Grass", "#5b8f3a"),
            new Block("stone", "Stone", "#8a8a8a"),
            new Block("dirt", "Dirt", "#7a5a3a"),
            new Block("diamond", "Diamond", "#4aedd9"),
            new Block("gold", "Gold", "#e8b923"),
            new Block("redstone", "Redstone", "#c0301c"),
            new Block("emerald", "Emerald", "#1aa34a"),
            new Block("lapis", "Lapis", "#2452cf"),
            new Block("netherite", "Netherite", "#5a4f55"),
            new Block("obsidian", "Obsidian", "#2a2438"),
            new Block("tnt", "TNT", "#c43b2b"),
            new Block("crafting", "Crafting Table", "#9b6a3f"));

    private static String blockColor(String key) {
        for (Block b : BLOCKS) if (b.key().equals(key)) return b.color();
        return "#5b8f3a";
    }

    // ---- instance detail page ----

    private void showInstanceDetail(Instance inst) {
        selectNav(navByName.get("Instances"));
        VBox page = new VBox(16);
        page.getStyleClass().add("content");

        Button back = new Button("←  Back");
        back.getStyleClass().add("nav-button");
        back.setOnAction(e -> showInstances());

        StackPane icon = iconTile(inst, false);
        Label name = new Label(inst.name());
        name.getStyleClass().add("page-title");
        Label meta = new Label(metaLine(inst) + "    •    "
                + (isRunning(inst) ? "● Running now" : "Last played: " + lastPlayed(inst)));
        meta.getStyleClass().add("card-meta");
        VBox titleBox = new VBox(4, name, meta);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        ProgressBar bar = progress(0.0);
        bar.setPrefWidth(160);
        Button play = new Button("▶  Play");
        play.getStyleClass().add("btn-filled");
        play.setOnAction(e -> onPlay(inst, bar, play));
        VBox playBox = new VBox(6, play, bar);
        playBox.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(16, back, icon, titleBox, grow, playBox);
        header.setAlignment(Pos.CENTER_LEFT);

        // Visible quick actions (also in Settings, but surfaced here so they're easy to find).
        Button hOpen = new Button("Open Folder");
        hOpen.getStyleClass().add("btn-outline");
        hOpen.setOnAction(e -> openFolder(instanceManager.instanceDir(inst)));
        Button hClone = new Button("Clone");
        hClone.getStyleClass().add("btn-outline");
        hClone.setOnAction(e -> cloneInstanceDialog(inst));
        Button hExport = new Button("Export .mrpack");
        hExport.getStyleClass().add("btn-outline");
        hExport.setOnAction(e -> exportInstance(inst));
        HBox actionRow = new HBox(10, hOpen, hClone, hExport);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        // Offer a one-click switch to NeoForge on Forge instances that are performance-starved.
        if (inst.loader() == Loader.FORGE && ForgeVersions.neoForgePreferred(inst.version())) {
            Button conv = new Button("⇪ Convert to NeoForge");
            conv.getStyleClass().add("btn-update");
            conv.setOnAction(e -> convertToNeoForge(inst));
            actionRow.getChildren().add(conv);
        }

        // ---- mod-profile switches: quick on/off without touching individual mods ----
        boolean baseOn = allModsDisabled(inst);
        boolean optOn = optimizationOnly(inst);
        Label sw1Lbl = new Label("Disable all mods (base game)");
        Label sw2Lbl = new Label("Optimization mods only");
        HBox sw1 = new HBox(10, toggleSwitch(baseOn, v -> {
            if (modFiles(inst).isEmpty()) {
                alert(Alert.AlertType.INFORMATION, "No mods",
                        "This instance has no mods yet — add some from Browse first.");
                return;
            }
            setAllModsDisabled(inst, v);
            showInstanceDetail(currentInstance(inst));
        }), sw1Lbl);
        sw1.setAlignment(Pos.CENTER_LEFT);
        HBox sw2 = new HBox(10, toggleSwitch(optOn, v -> {
            if (modFiles(inst).isEmpty()) {
                alert(Alert.AlertType.INFORMATION, "No mods",
                        "This instance has no mods yet — add the Performance Pack or mods from Browse first.");
                return;
            }
            setOptimizationOnly(inst, v);
            showInstanceDetail(currentInstance(inst));
        }), sw2Lbl);
        sw2.setAlignment(Pos.CENTER_LEFT);
        HBox profileRow = new HBox(28, sw1, sw2);
        profileRow.setAlignment(Pos.CENTER_LEFT);

        // Profile-icon picker
        Label profLbl = new Label("Profile Icon");
        profLbl.getStyleClass().add("card-title-sm");
        FlowPane swatches = new FlowPane(8, 8);
        for (Block b : BLOCKS) {
            StackPane sw = new StackPane();
            sw.getStyleClass().add("block-swatch");
            sw.setMinSize(42, 42);
            sw.setMaxSize(42, 42);
            boolean selected = b.key().equals(inst.iconOrDefault());
            sw.setStyle("-fx-background-color: " + b.color() + ";"
                    + (selected ? " -fx-border-color: #FFFFFF; -fx-border-width: 2.5;" : ""));
            Tooltip.install(sw, new Tooltip(b.name()));
            sw.setOnMouseClicked(e -> {
                try {
                    instanceManager.update(inst.withIconKey(b.key()));
                    showInstanceDetail(currentInstance(inst));
                } catch (Exception ex) {
                    alert(Alert.AlertType.ERROR, "Save failed", ex.getMessage());
                }
            });
            swatches.getChildren().add(sw);
        }

        // ---- Settings form ----
        TextField nameF = new TextField(inst.name());
        nameF.setPrefWidth(280);

        Slider ramSlider = new Slider(1024, 16384, inst.ramMb());
        ramSlider.setPrefWidth(420);
        ramSlider.getStyleClass().add("ram-slider");
        ramSlider.setBlockIncrement(512);
        ramSlider.setMajorTickUnit(2048);
        ramSlider.setMinorTickCount(3);
        ramSlider.setSnapToTicks(true);
        ramSlider.setShowTickMarks(true);
        Label ramVal = new Label(inst.ramMb() + " MB");
        ramVal.getStyleClass().add("ram-value");
        ramVal.setMinWidth(80);
        ramSlider.valueProperty().addListener((o, a, b) -> ramVal.setText(snapRam(b.doubleValue()) + " MB"));
        HBox ramBox = new HBox(10, ramSlider, ramVal);
        ramBox.setAlignment(Pos.CENTER_LEFT);

        TextField widthF = new TextField(inst.width() > 0 ? String.valueOf(inst.width()) : "");
        widthF.setPromptText("default");
        widthF.setMaxWidth(90);
        TextField heightF = new TextField(inst.height() > 0 ? String.valueOf(inst.height()) : "");
        heightF.setPromptText("default");
        heightF.setMaxWidth(90);
        Label x = new Label("×");
        x.getStyleClass().add("card-sub");
        HBox resBox = new HBox(8, widthF, x, heightF);
        resBox.setAlignment(Pos.CENTER_LEFT);
        CheckBox fullF = new CheckBox("Launch fullscreen");
        fullF.setSelected(inst.fullscreen());
        TextField jvmF = new TextField(inst.jvmArgsOrEmpty());
        jvmF.setPromptText("-XX:+UseG1GC … (optional)");
        jvmF.setPrefWidth(360);

        TextField groupF = new TextField(inst.groupOrNone());
        groupF.setPromptText("e.g. Performance, Multiplayer (optional)");
        groupF.setPrefWidth(280);

        Button saveSettings = new Button("Save Settings");
        saveSettings.getStyleClass().add("btn-filled");
        saveSettings.setOnAction(e -> {
            try {
                String nm = nameF.getText().isBlank() ? inst.name() : nameF.getText().trim();
                int ramMb = snapRam(ramSlider.getValue());
                int w = widthF.getText().isBlank() ? 0 : Integer.parseInt(widthF.getText().trim());
                int h = heightF.getText().isBlank() ? 0 : Integer.parseInt(heightF.getText().trim());
                instanceManager.update(inst.withSettings(nm, ramMb, w, h, fullF.isSelected(), jvmF.getText().trim(), groupF.getText().trim()));
                showInstanceDetail(currentInstance(inst));
            } catch (NumberFormatException nf) {
                alert(Alert.AlertType.ERROR, "Invalid number", "Resolution must be whole numbers.");
            } catch (Exception ex) {
                alert(Alert.AlertType.ERROR, "Save failed", ex.getMessage());
            }
        });

        Button del = new Button("Delete Instance");
        del.getStyleClass().add("btn-outline");
        del.setOnAction(e -> {
            if (config != null && config.confirmDelete()) {
                Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete “" + inst.name() + "”? This removes it from the launcher.",
                        ButtonType.OK, ButtonType.CANCEL);
                c.setHeaderText(null);
                styleDialog(c);
                if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            }
            try { instanceManager.delete(inst); showInstances(); }
            catch (Exception ex) { alert(Alert.AlertType.ERROR, "Delete failed", ex.getMessage()); }
        });

        Button optifine = new Button("Set up OptiFine");
        optifine.getStyleClass().add("btn-outline");
        optifine.setOnAction(e -> setupOptifine(inst));
        Label optHelp = new Label("Installs OptiFabric, then opens the mods folder so you can drop in the "
                + "OptiFine jar from optifine.net (needed for OptiFine-only capes/skins). Conflicts with Sodium.");
        optHelp.getStyleClass().add("muted");
        optHelp.setWrapText(true);

        Button uploadImg = new Button("Upload image…");
        uploadImg.getStyleClass().add("btn-outline");
        uploadImg.setOnAction(e -> uploadInstanceImage(inst));
        Button clearImg = new Button("Use a block instead");
        clearImg.getStyleClass().add("btn-outline");
        clearImg.setOnAction(e -> {
            try {
                Files.deleteIfExists(instanceManager.instanceDir(inst).resolve("icon.png"));
                showInstanceDetail(currentInstance(inst));
            } catch (Exception ex) { alert(Alert.AlertType.ERROR, "Failed", ex.getMessage()); }
        });
        HBox imgRow = new HBox(10, uploadImg, clearImg);
        imgRow.setAlignment(Pos.CENTER_LEFT);

        VBox settingsContent = new VBox(12, profLbl, swatches, imgRow,
                settingRow("Name", nameF),
                settingRow("RAM", ramBox),
                settingRow("Resolution", resBox),
                settingRow("", fullF),
                settingRow("Extra JVM args", jvmF),
                settingRow("Group", groupF),
                saveSettings,
                new Region(), optifine, optHelp,
                new Region(), del);
        settingsContent.setPadding(new Insets(12));
        ScrollPane settingsScroll = new ScrollPane(settingsContent);
        settingsScroll.setFitToWidth(true);
        settingsScroll.getStyleClass().add("scroll-pane");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("kano-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                contentTab(inst, "Mods", "mods", "Mods"),
                contentTab(inst, "Resource Packs", "resourcepacks", "Resource Packs"),
                contentTab(inst, "Shaders", "shaderpacks", "Shaders"),
                contentTab(inst, "Data Packs", "datapacks", "Data Packs"),
                worldsTab(inst),
                serversTab(inst),
                tab("Settings", settingsScroll));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        page.getChildren().addAll(header, actionRow, profileRow, tabs);
        content.getChildren().setAll(page);
    }

    private void uploadInstanceImage(Instance inst) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose instance image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            Path dest = instanceManager.instanceDir(inst).resolve("icon.png");
            Files.createDirectories(dest.getParent());
            Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            showInstanceDetail(currentInstance(inst));
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Couldn't set image", String.valueOf(ex.getMessage()));
        }
    }

    private static int snapRam(double v) {
        return (int) (Math.round(v / 512.0) * 512);
    }

    private Tab tab(String title, javafx.scene.Node node) {
        Tab t = new Tab(title, node);
        t.setClosable(false);
        return t;
    }

    private Tab contentTab(Instance inst, String title, String folder, String browseTypeLabel) {
        Button add = new Button("+ Add " + title);
        add.getStyleClass().add("btn-outline");
        add.setOnAction(e -> showBrowse(inst, browseTypeLabel));
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox header = new HBox(10, g, add);
        header.setAlignment(Pos.CENTER_LEFT);
        if ("mods".equals(folder)) {
            Button updateAll = new Button("⬆ Update All");
            updateAll.getStyleClass().add("btn-update");
            updateAll.setOnAction(e -> updateAllMods(inst));
            updateAll.setVisible(false);
            updateAll.setManaged(false); // takes no layout space until we know updates exist
            header.getChildren().add(1, updateAll); // [spacer, Update All, + Add]
            // Check sources off-thread; only reveal the button if something is actually out of date.
            Thread chk = new Thread(() -> {
                int n = countModUpdates(inst);
                if (n > 0) Platform.runLater(() -> {
                    updateAll.setText("⬆ Update All (" + n + ")");
                    updateAll.setVisible(true);
                    updateAll.setManaged(true);
                });
            }, "mod-update-check");
            chk.setDaemon(true);
            chk.start();
        }
        VBox listBox = new VBox(8);
        populateFolderItems(inst, folder, listBox);
        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        VBox.setVgrow(sp, Priority.ALWAYS);
        Label dropHint = new Label("Tip: drag files here to add them.");
        dropHint.getStyleClass().add("muted");
        VBox box = new VBox(10, header, sp, dropHint);
        box.setPadding(new Insets(12));

        // Drag-and-drop: drop any jar/zip onto the tab and it lands in the instance's folder.
        box.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        box.setOnDragDropped(e -> {
            var db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                try {
                    Path d = instanceManager.instanceDir(inst).resolve(folder);
                    Files.createDirectories(d);
                    int n = 0;
                    for (java.io.File file : db.getFiles()) {
                        if (file.isFile()) {
                            Files.copy(file.toPath(), d.resolve(file.getName()),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            n++;
                        }
                    }
                    ok = n > 0;
                    populateFolderItems(inst, folder, listBox);
                } catch (Exception ex) {
                    alert(Alert.AlertType.ERROR, "Drop failed", String.valueOf(ex.getMessage()));
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        return tab(title, box);
    }

    private HBox settingRow(String label, javafx.scene.Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("card-sub");
        l.setMinWidth(120);
        HBox h = new HBox(10, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Instance currentInstance(Instance inst) {
        if (instanceManager == null) return inst;
        return instanceManager.list().stream()
                .filter(x -> x.dirName().equals(inst.dirName())).findFirst().orElse(inst);
    }

    private void populateResourcePacks(Instance inst, VBox list) {
        list.getChildren().clear();
        Path rpDir = instanceManager.instanceDir(inst).resolve("resourcepacks");
        List<Path> packs = new ArrayList<>();
        if (Files.isDirectory(rpDir)) {
            try (var s = Files.list(rpDir)) {
                s.filter(pth -> {
                    String n = pth.toString().toLowerCase();
                    return n.endsWith(".zip") || Files.isDirectory(pth);
                }).sorted().forEach(packs::add);
            } catch (Exception ignored) {
            }
        }
        if (packs.isEmpty()) {
            Label none = new Label("No resource packs. Drop .zip packs into the instance's resourcepacks/ folder.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
        for (Path pack : packs) {
            Label nm = new Label(pack.getFileName().toString());
            nm.getStyleClass().add("card-title-sm");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.getStyleClass().add("btn-outline");
            remove.setOnAction(e -> {
                try { Files.deleteIfExists(pack); populateResourcePacks(inst, list); }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Remove failed", ex.getMessage()); }
            });
            HBox row = new HBox(12, nm, grow, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);
        }
    }

    private ProgressBar progress(double v) {
        ProgressBar bar = new ProgressBar(v);
        bar.getStyleClass().add("kano-prog");
        bar.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private String metaLine(Instance inst) {
        return inst.version() + "  |  " + inst.loader().display() + "  |  " + inst.ramMb() + " MB";
    }

    private String lastPlayed(Instance inst) {
        if (inst.lastPlayedEpoch() <= 0) return "Never";
        return DateTimeFormatter.ofPattern("MMM d, HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(inst.lastPlayedEpoch()));
    }

    private void onPlay(Instance inst, ProgressBar bar, Button play) {
        onPlay(inst, bar, play, null, null);
    }

    private void onPlay(Instance inst, ProgressBar bar, Button play, String quickWorld) {
        onPlay(inst, bar, play, quickWorld, null);
    }

    private void onPlay(Instance inst, ProgressBar bar, Button play, String quickWorld, String quickServer) {
        if (instanceManager == null) { alert(Alert.AlertType.ERROR, "Error", initError); return; }
        play.getStyleClass().add("loading"); // amber = preparing, before the bar even moves
        Thread t = new Thread(() -> {
            try {
                Platform.runLater(() -> bar.setProgress(0));
                VersionManifest vm = versionManifest;
                if (vm == null) { vm = VersionManifest.fetch(); versionManifest = vm; }
                VersionManifest.VersionEntry entry = vm.find(inst.version());
                if (entry == null) throw new RuntimeException("Version " + inst.version() + " not in manifest.");
                VersionDetail vd = VersionDetail.fetch(entry);
                Path dataDir = resolveDataDir();

                // Resolve Fabric loader if this is a Fabric instance.
                FabricSupport.Profile fabric = inst.loader() == Loader.FABRIC
                        ? FabricSupport.resolve(inst.version()) : null;
                java.util.List<com.kano.launcher.core.VersionDetail.Dl> extraLibs =
                        fabric != null ? fabric.libraries() : java.util.List.of();

                // 1. Download everything (cached after first time).
                new GameInstaller(dataDir).install(vd, extraLibs, (d, tot, label) -> {
                    if (d % 25 == 0 || d == tot) {
                        double frac = tot == 0 ? 0 : (double) d / tot;
                        Platform.runLater(() -> bar.setProgress(frac));
                    }
                });
                Platform.runLater(() -> bar.setProgress(1.0));

                // 2. Make sure the right Java is available (downloads from Adoptium if needed).
                Path javaExe = JreProvider.javaExecutable(dataDir, vd.javaMajor(), msg -> {});

                // 2b. NeoForge/Forge: install (download installer + run client processors) + resolve profile.
                com.kano.launcher.core.ForgeSupport.Profile forge = null;
                if (inst.loader() == Loader.NEOFORGE || inst.loader() == Loader.FORGE) {
                    Path vanillaClient = GameInstaller.clientJar(dataDir, vd.id());
                    forge = com.kano.launcher.core.ForgeSupport.resolve(
                            dataDir, inst.version(), inst.loader(), javaExe, vanillaClient, msg -> {});
                }

                // 3. Launch (offline mode — works before app approval).
                var activeAcc = activeAccount();
                String player = activeAcc != null ? activeAcc.username() : "Player";
                long sessionStart = System.currentTimeMillis();
                Process proc = GameLauncher.launch(inst, vd, javaExe, dataDir, player, fabric, forge, quickWorld, quickServer);
                Platform.runLater(() -> play.getStyleClass().remove("loading"));

                // Register as a running game (Playing Now tab + per-card indicator).
                long id = runId.incrementAndGet();
                running.put(id, new RunningGame(inst, proc, player, System.currentTimeMillis()));
                Platform.runLater(this::onRunningChanged);
                if (config != null && config.minimizeOnPlay())
                    Platform.runLater(() -> { if (stage != null) stage.setIconified(true); });

                // Mark last-played and refresh the view.
                instanceManager.update(inst.withLastPlayed(System.currentTimeMillis()));
                Platform.runLater(this::showInstances);

                // Surface an early crash with the log tail; silent on normal exit.
                int code = proc.waitFor();
                running.remove(id);
                Platform.runLater(this::onRunningChanged);
                if (stats != null) {
                    stats.addSession(System.currentTimeMillis() - sessionStart);
                    Platform.runLater(this::updateStatsBar);
                }
                if (code != 0) {
                    String tail = readTail(dataDir.resolve("instances").resolve(inst.dirName())
                            .resolve("launcher-run.log"), 1800);
                    Platform.runLater(() -> alert(Alert.AlertType.ERROR,
                            "Game exited (code " + code + ")", tail));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    play.getStyleClass().remove("loading");
                    alert(Alert.AlertType.ERROR, "Launch failed", String.valueOf(ex.getMessage()));
                });
            }
        }, "play-" + inst.dirName());
        t.setDaemon(true);
        t.start();
    }

    private String readTail(Path f, int max) {
        try {
            String s = Files.readString(f);
            return s.length() > max ? "…" + s.substring(s.length() - max) : s;
        } catch (Exception e) {
            return "(no launch log found)";
        }
    }

    // ---- stats bar (bottom) ----

    private HBox buildStatsBar() {
        statsLabel = new Label();
        statsLabel.getStyleClass().add("stats-text");
        HBox bar = new HBox(statsLabel);
        bar.getStyleClass().add("stats-bar");
        updateStatsBar();
        return bar;
    }

    private void updateStatsBar() {
        if (statsLabel == null || stats == null) return;
        int worlds = Stats.countWorlds(resolveDataDir().resolve("instances"));
        statsLabel.setText("👑  " + worlds + " worlds   •   " + stats.playtimeText()
                + " played   •   " + stats.launches() + " launches");
    }

    // ---- create / edit dialogs ----

    private void showCreateDialog() {
        if (instanceManager == null) { alert(Alert.AlertType.ERROR, "Error", initError); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Create Instance");
        d.setHeaderText("New instance");

        TextField name = new TextField();
        name.setPromptText("Name");

        CheckBox snapshots = new CheckBox("Show snapshots & old versions");
        ChoiceBox<String> ver = new ChoiceBox<>();
        Label verCount = new Label();
        verCount.getStyleClass().add("card-sub");
        Runnable fillVersions = () -> {
            List<String> ids = versionManifest != null
                    ? versionManifest.ids(snapshots.isSelected())
                    : Arrays.asList(VERSIONS);
            ver.getItems().setAll(ids);
            if (!ver.getItems().isEmpty()) ver.getSelectionModel().selectFirst();
            verCount.setText(versionManifest != null
                    ? ids.size() + " versions available (latest: " + versionManifest.latestRelease() + ")"
                    : "Loading the full version list…");
        };
        snapshots.setOnAction(e -> fillVersions.run());
        fillVersions.run();
        // If the manifest hasn't finished loading yet (dialog opened early), fetch it now and refill —
        // otherwise the user only sees the short offline fallback (no latest releases / snapshots).
        if (versionManifest == null) {
            Thread vt = new Thread(() -> {
                try {
                    VersionManifest vm = VersionManifest.fetch();
                    versionManifest = vm;
                    Platform.runLater(fillVersions);
                } catch (Exception ignored) {
                    Platform.runLater(() -> verCount.setText("Offline — showing a short fallback list"));
                }
            }, "version-manifest-dialog");
            vt.setDaemon(true);
            vt.start();
        }

        ChoiceBox<Loader> loader = new ChoiceBox<>();
        loader.getItems().addAll(Loader.VANILLA, Loader.FABRIC, Loader.NEOFORGE, Loader.FORGE);
        loader.getSelectionModel().selectFirst();
        if (config != null) {
            try { loader.getSelectionModel().select(Loader.valueOf(config.defaultLoader())); }
            catch (Exception ignore) {}
        }

        TextField group = new TextField();
        group.setPromptText("Group (optional) — e.g. Performance, Multiplayer");

        VBox box = new VBox(10,
                new Label("Name"), name,
                new Label("Version"), ver, snapshots, verCount,
                new Label("Loader"), loader,
                new Label("Group"), group);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialog(d);

        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            Loader chosen = loader.getValue();
            String version = ver.getValue();
            String typedName = name.getText().isBlank() ? null : name.getText();
            String grp = group.getText().trim();
            // Force Forge → NeoForge on 1.20.2+ (Forge has no performance mods there — they moved to
            // NeoForge). We verify NeoForge actually has a build off-thread, then create.
            boolean route = chosen == Loader.FORGE && ForgeVersions.neoForgePreferred(version)
                    && (config == null || config.autoNeoForge());
            if (!route) {
                createInstanceFinal(chosen, version, typedName, grp, null);
                return;
            }
            Thread t = new Thread(() -> {
                Loader fl;
                String note;
                try {
                    if (ForgeVersions.latestNeoForge(version) != null) {
                        fl = Loader.NEOFORGE;
                        note = "Forge has no performance mods on " + version + " — created as NeoForge instead "
                                + "(same Forge-family mods, far higher FPS).";
                    } else {
                        fl = Loader.FORGE;
                        note = "Heads up: Forge on " + version + " has almost no performance mods, and NeoForge "
                                + "doesn't have a build for this version yet.";
                    }
                } catch (Exception e) {
                    fl = Loader.NEOFORGE;
                    note = "Created as NeoForge (recommended for " + version + ").";
                }
                Loader finalLoader = fl;
                String finalNote = note;
                Platform.runLater(() -> createInstanceFinal(finalLoader, version, typedName, grp, finalNote));
            }, "create-route");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Create an instance (applying the default-RAM override), refresh, and show an optional note. */
    private void createInstanceFinal(Loader loader, String version, String typedName, String group, String note) {
        try {
            String nm = (typedName == null || typedName.isBlank()) ? (loader.display() + " " + version) : typedName;
            Instance created = instanceManager.create(nm, version, loader, group);
            if (config != null && config.defaultRamMb() != 4096)
                instanceManager.update(created.withRam(config.defaultRamMb()));
            showInstances();
            if (note != null) alert(Alert.AlertType.INFORMATION, "Using " + loader.display(), note);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Create failed", String.valueOf(ex.getMessage()));
        }
    }

    private void showEditDialog(Instance inst) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Edit Instance");
        d.setHeaderText(inst.name());
        TextField ram = new TextField(String.valueOf(inst.ramMb()));
        VBox box = new VBox(10, new Label("RAM (MB)"), ram);
        box.setPadding(new Insets(12));
        ButtonType deleteBtn = new ButtonType("Delete", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, deleteBtn, ButtonType.CANCEL);
        styleDialog(d);

        ButtonType result = d.showAndWait().orElse(ButtonType.CANCEL);
        try {
            if (result == ButtonType.APPLY) {
                int mb = Integer.parseInt(ram.getText().trim());
                instanceManager.update(inst.withRam(mb));
                showInstances();
            } else if (result == deleteBtn) {
                instanceManager.delete(inst);
                showInstances();
            }
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Edit failed", ex.getMessage());
        }
    }

    // ---- other views ----

    private void showSkins() { selectNav(navByName.get("Skins")); simplePage("Skins", "Skin changer is a later milestone (needs app approval)."); }

    // ---- Library: installed mods per instance ----

    private void showLibrary() {
        selectNav(navByName.get("Library"));
        VBox page = new VBox(14);
        page.getStyleClass().add("content");
        VBox title = pageHeader("INSTALLED CONTENT", "Library");

        var insts = instanceManager == null ? List.<Instance>of() : instanceManager.list();
        if (insts.isEmpty()) {
            Label none = new Label("No instances yet — create one to start installing mods.");
            none.getStyleClass().add("muted");
            page.getChildren().addAll(title, none);
            content.getChildren().setAll(page);
            return;
        }

        ChoiceBox<Instance> pick = new ChoiceBox<>();
        pick.getItems().addAll(insts);
        pick.setConverter(new StringConverter<>() {
            public String toString(Instance i) { return i == null ? "" : i.name() + " (" + i.version() + ")"; }
            public Instance fromString(String s) { return null; }
        });
        pick.getSelectionModel().selectFirst();

        VBox list = new VBox(8);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        pick.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> populateFolderItems(b, "mods", list));
        page.getChildren().addAll(title, pick, scroll);
        content.getChildren().setAll(page);
        populateFolderItems(pick.getValue(), "mods", list);
    }

    /** A titled content section with an "+ Add" button (opens Browse for this instance + type) and a file list. */
    private VBox contentSection(Instance inst, String title, String folder, String browseTypeLabel) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("card-title-sm");
        Button add = new Button("+ Add " + title);
        add.getStyleClass().add("btn-outline");
        add.setOnAction(e -> showBrowse(inst, browseTypeLabel));
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox header = new HBox(10, lbl, g, add);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox listBox = new VBox(8);
        populateFolderItems(inst, folder, listBox);
        return new VBox(8, header, listBox);
    }

    /** List files in an instance subfolder; Remove deletes the file. */
    // ---- mod-profile switches ----

    /** A small sliding on/off switch. Clicking calls {@code onChange} with the new state. */
    private Region toggleSwitch(boolean on, java.util.function.Consumer<Boolean> onChange) {
        Region thumb = new Region();
        thumb.getStyleClass().add("switch-thumb");
        StackPane track = new StackPane(thumb);
        track.getStyleClass().add("switch-track");
        if (on) track.getStyleClass().add("switch-on");
        StackPane.setAlignment(thumb, on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        track.setOnMouseClicked(e -> onChange.accept(!on));
        return track;
    }

    // Filename keywords identifying performance/optimization mods + the common dependencies they
    // need (kept enabled so the perf stack still loads). Matched against the separator-stripped name.
    private static final String[] OPT_KEYWORDS = {
            "sodium", "sodiumextra", "reesessodiumoptions", "embeddium", "lithium", "ferrite", "modernfix",
            "scalablelux", "c2me", "moreculling", "cullleaves", "entityculling", "immediatelyfast",
            "dynamicfps", "threadtweak", "krypton", "debugify", "fastquit", "fastipping",
            "enhancedblockentities", "vmpfabric",
            "fabricapi", "fabriclanguagekotlin", "clothconfig", "architectury", "indium", "mixinextras"};

    private static boolean isOptMod(String filename) {
        String n = filename.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String k : OPT_KEYWORDS) if (n.contains(k)) return true;
        return false;
    }

    private List<Path> modFiles(Instance inst) {
        Path mods = instanceManager.instanceDir(inst).resolve("mods");
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(mods)) {
            try (var s = Files.list(mods)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> { String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".jar") || n.endsWith(".jar.disabled"); })
                        .forEach(out::add);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean isEnabled(Path p) { return p.getFileName().toString().endsWith(".jar"); }

    private boolean allModsDisabled(Instance inst) {
        List<Path> f = modFiles(inst);
        return !f.isEmpty() && f.stream().noneMatch(MainApp::isEnabled);
    }

    private boolean optimizationOnly(Instance inst) {
        List<Path> f = modFiles(inst);
        List<Path> enabled = f.stream().filter(MainApp::isEnabled).toList();
        if (enabled.isEmpty()) return false;
        boolean allEnabledOpt = enabled.stream().allMatch(p -> isOptMod(p.getFileName().toString()));
        boolean someNonOptDisabled = f.stream().filter(p -> !isEnabled(p))
                .anyMatch(p -> !isOptMod(baseName(p)));
        return allEnabledOpt && someNonOptDisabled;
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".disabled") ? n.substring(0, n.length() - ".disabled".length()) : n;
    }

    /** Enable/disable every mod (rename .jar ↔ .jar.disabled). */
    private void setAllModsDisabled(Instance inst, boolean disabled) {
        for (Path p : modFiles(inst)) setEnabled(p, !disabled);
    }

    /** ON: keep only optimization mods enabled, disable the rest. OFF: enable everything. */
    private void setOptimizationOnly(Instance inst, boolean on) {
        for (Path p : modFiles(inst)) {
            boolean wantEnabled = !on || isOptMod(baseName(p));
            setEnabled(p, wantEnabled);
        }
    }

    private void setEnabled(Path p, boolean enabled) {
        try {
            boolean cur = isEnabled(p);
            if (cur == enabled) return;
            Path target = enabled
                    ? p.resolveSibling(baseName(p))
                    : p.resolveSibling(p.getFileName().toString() + ".disabled");
            Files.move(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private void populateFolderItems(Instance inst, String folder, VBox list) {
        list.getChildren().clear();
        if (inst == null) return;
        Path dir = instanceManager.instanceDir(inst).resolve(folder);
        List<Path> items = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.filter(Files::isRegularFile).sorted().forEach(items::add);
            } catch (Exception ignored) {
            }
        }
        if (items.isEmpty()) {
            Label none = new Label("Nothing here yet — use “+ Add”, or drag files in.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
        java.util.Map<String, HBox> rowByFile = new java.util.HashMap<>();
        for (Path f : items) {
            String fn = f.getFileName().toString();
            boolean disabled = fn.toLowerCase().endsWith(".disabled");
            String display = disabled ? fn.replaceAll("(?i)\\.disabled$", "") : fn;
            Label name = new Label(display + (disabled ? "   (disabled)" : ""));
            name.getStyleClass().add("card-title-sm");
            if (disabled) name.setStyle("-fx-text-fill: #7d7d7d;");
            Label size = new Label(fileSize(f));
            size.getStyleClass().add("card-sub");
            VBox info = new VBox(2, name, size);
            HBox.setHgrow(info, Priority.ALWAYS);

            Button toggle = new Button(disabled ? "Enable" : "Disable");
            toggle.getStyleClass().add("btn-outline");
            toggle.setOnAction(e -> {
                try {
                    Path target = disabled ? f.resolveSibling(display) : f.resolveSibling(fn + ".disabled");
                    Files.move(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    populateFolderItems(inst, folder, list);
                } catch (Exception ex) { alert(Alert.AlertType.ERROR, "Toggle failed", ex.getMessage()); }
            });
            Button remove = new Button("Remove");
            remove.getStyleClass().add("btn-outline");
            remove.setOnAction(e -> {
                try { Files.deleteIfExists(f); populateFolderItems(inst, folder, list); }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Remove failed", ex.getMessage()); }
            });
            HBox row = new HBox(10, info, toggle, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            rowByFile.put(display, row);
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);
        }

        // Per-row update: check tracked items in this folder off-thread; reveal a gold "⬆ Update"
        // button on each row whose newest compatible build differs from what's installed.
        Thread chk = new Thread(() -> {
            try {
                for (ModTracker.Entry en : new ModTracker(instanceManager.instanceDir(inst)).list()) {
                    if (!folder.equals(en.folder())) continue;
                    HBox row = rowByFile.get(en.filename());
                    if (row == null) continue;
                    ContentSource src = sourceForTracked(en.source());
                    String loader = modLoaderTag(inst, en.type());
                    ModrinthClient.ModFile pf = primaryFile(srcLatest(src, en.projectId(), inst.version(), loader));
                    if (pf != null && !pf.filename().equals(en.filename())) {
                        Platform.runLater(() -> {
                            Button up = new Button("⬆ Update");
                            up.getStyleClass().add("btn-update");
                            up.setOnAction(e -> updateSingleMod(inst, en, folder, list));
                            row.getChildren().add(1, up);
                        });
                    }
                }
            } catch (Exception ignored) {
            }
        }, "row-update-check");
        chk.setDaemon(true);
        chk.start();
    }

    /** Update one tracked item to its newest compatible build, then refresh the folder list. */
    private void updateSingleMod(Instance inst, ModTracker.Entry en, String folder, VBox list) {
        Thread t = new Thread(() -> {
            try {
                Path instDir = instanceManager.instanceDir(inst);
                ContentSource src = sourceForTracked(en.source());
                String loader = modLoaderTag(inst, en.type());
                ModrinthClient.ModFile pf = primaryFile(srcLatest(src, en.projectId(), inst.version(), loader));
                if (pf == null) {
                    Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, "No update",
                            "No compatible build found for " + en.name() + "."));
                    return;
                }
                Path d = instDir.resolve(en.folder());
                Files.deleteIfExists(d.resolve(en.filename()));
                Files.deleteIfExists(d.resolve(en.filename() + ".disabled"));
                ModInstaller.install(src, en.projectId(), inst.version(), loader, d, m -> {});
                new ModTracker(instDir).record(new ModTracker.Entry(
                        en.projectId(), en.source(), en.type(), en.folder(), pf.filename(), en.name()));
                Platform.runLater(() -> populateFolderItems(inst, folder, list));
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Update failed", String.valueOf(ex.getMessage())));
            }
        }, "update-single");
        t.setDaemon(true);
        t.start();
    }

    // ---- worlds ----

    private Tab worldsTab(Instance inst) {
        Label hint = new Label("Single-player worlds in this instance. Play jumps straight in.");
        hint.getStyleClass().add("muted");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        VBox listBox = new VBox(8);
        Button restore = new Button("Restore backup…");
        restore.getStyleClass().add("btn-outline");
        restore.setOnAction(e -> restoreBackup(inst, listBox));
        HBox header = new HBox(10, hint, g, restore);
        header.setAlignment(Pos.CENTER_LEFT);
        populateWorlds(inst, listBox);
        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(10, header, sp);
        box.setPadding(new Insets(12));
        return tab("Worlds", box);
    }

    private void populateWorlds(Instance inst, VBox list) {
        list.getChildren().clear();
        Path saves = instanceManager.instanceDir(inst).resolve("saves");
        List<Path> worlds = new ArrayList<>();
        if (Files.isDirectory(saves)) {
            try (var s = Files.list(saves)) {
                s.filter(Files::isDirectory).sorted().forEach(worlds::add);
            } catch (Exception ignored) {
            }
        }
        if (worlds.isEmpty()) {
            Label none = new Label("No worlds yet — launch this instance and create one.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
        for (Path w : worlds) {
            Label name = new Label(w.getFileName().toString());
            name.getStyleClass().add("card-title-sm");
            Label sub = new Label(dirSize(w) + "   •   last played " + lastModified(w));
            sub.getStyleClass().add("card-sub");
            VBox info = new VBox(2, name, sub);
            HBox.setHgrow(info, Priority.ALWAYS);

            ProgressBar wbar = progress(0.0);
            wbar.setMaxWidth(0);
            wbar.setVisible(false);
            wbar.setManaged(false);
            Button playW = new Button("▶ Play");
            playW.getStyleClass().add("btn-filled");
            playW.setOnAction(e -> onPlay(inst, wbar, playW, w.getFileName().toString()));
            Button openF = new Button("Open Folder");
            openF.getStyleClass().add("btn-outline");
            openF.setOnAction(e -> openFolder(w));
            Button backup = new Button("Create Backup");
            backup.getStyleClass().add("btn-outline");
            backup.setOnAction(e -> backupWorld(inst, w));
            Button del = new Button("Delete");
            del.getStyleClass().add("btn-outline");
            del.setOnAction(e -> {
                try { deleteDir(w); populateWorlds(inst, list); }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Delete failed", ex.getMessage()); }
            });

            HBox row = new HBox(10, info, wbar, playW, openF, backup, del);
            row.setAlignment(Pos.CENTER_LEFT);
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);
        }
    }

    // ---- servers ----

    private Tab serversTab(Instance inst) {
        Button add = new Button("+ Add Server");
        add.getStyleClass().add("btn-outline");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        Label hint = new Label("Favourite servers — live status, double-click Play to jump straight in.");
        hint.getStyleClass().add("muted");
        HBox header = new HBox(10, hint, g, add);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox listBox = new VBox(8);
        populateServers(inst, listBox);
        add.setOnAction(e -> addServerDialog(inst, listBox));

        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(10, header, sp);
        box.setPadding(new Insets(12));
        return tab("Servers", box);
    }

    private void populateServers(Instance inst, VBox list) {
        list.getChildren().clear();
        var servers = new ServerStore(instanceManager.instanceDir(inst)).list();
        if (servers.isEmpty()) {
            Label none = new Label("No servers yet — use “+ Add Server”.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
        for (ServerStore.Server srv : servers) {
            Label name = new Label(srv.name());
            name.getStyleClass().add("card-title-sm");
            Label status = new Label(srv.address() + "   •   pinging…");
            status.getStyleClass().add("card-sub");
            VBox info = new VBox(2, name, status);
            HBox.setHgrow(info, Priority.ALWAYS);

            ProgressBar sbar = progress(0.0);
            sbar.setMaxWidth(0);
            sbar.setVisible(false);
            sbar.setManaged(false);
            Button play = new Button("▶ Play");
            play.getStyleClass().add("btn-filled");
            play.setOnAction(e -> onPlay(inst, sbar, play, null, srv.address()));
            Button remove = new Button("Remove");
            remove.getStyleClass().add("btn-outline");
            remove.setOnAction(e -> {
                new ServerStore(instanceManager.instanceDir(inst)).remove(srv.address());
                populateServers(inst, list);
            });
            HBox row = new HBox(10, info, sbar, play, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);

            // Live status off-thread.
            Thread t = new Thread(() -> {
                try {
                    ServerPing.Status st = ServerPing.ping(srv.address(), 4000);
                    Platform.runLater(() -> status.setText(srv.address()
                            + "   •   ● " + st.online() + "/" + st.max() + " online"
                            + (st.latencyMs() >= 0 ? "   •   " + st.latencyMs() + " ms" : "")
                            + (st.version().isBlank() ? "" : "   •   " + st.version())));
                } catch (Exception ex) {
                    Platform.runLater(() -> status.setText(srv.address() + "   •   offline / unreachable"));
                }
            }, "server-ping");
            t.setDaemon(true);
            t.start();
        }
    }

    private void addServerDialog(Instance inst, VBox list) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Add Server");
        d.setHeaderText("Add a server to " + inst.name());
        TextField nm = new TextField();
        nm.setPromptText("Name");
        TextField addr = new TextField();
        addr.setPromptText("address (e.g. mc.hypixel.net or host:port)");
        VBox box = new VBox(10, new Label("Name"), nm, new Label("Address"), addr);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialog(d);
        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            String a = addr.getText().trim();
            if (a.isBlank()) { alert(Alert.AlertType.ERROR, "Missing address", "Enter a server address."); return; }
            String n = nm.getText().isBlank() ? a : nm.getText().trim();
            new ServerStore(instanceManager.instanceDir(inst)).add(new ServerStore.Server(n, a));
            populateServers(inst, list);
        }
    }

    private void backupWorld(Instance inst, Path world) {
        Thread t = new Thread(() -> {
            try {
                Path backups = instanceManager.instanceDir(inst).resolve("backups");
                Files.createDirectories(backups);
                String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneId.systemDefault()).format(Instant.now());
                String wn = world.getFileName().toString();
                Path zip = backups.resolve(wn + "_" + ts + ".zip");
                try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                    try (var walk = Files.walk(world)) {
                        for (Path p : (Iterable<Path>) walk::iterator) {
                            if (!Files.isRegularFile(p)) continue;
                            String rel = wn + "/" + world.relativize(p).toString().replace('\\', '/');
                            z.putNextEntry(new java.util.zip.ZipEntry(rel));
                            Files.copy(p, z);
                            z.closeEntry();
                        }
                    }
                }
                // Keep only the newest 3 backups per world (delete the oldest beyond that).
                int kept = pruneBackups(backups, wn, 3);
                Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, "World backed up",
                        wn + " → backups/" + zip.getFileName() + "\nKeeping the newest " + kept + " backup(s) of this world."));
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Backup failed", String.valueOf(ex.getMessage())));
            }
        }, "world-backup");
        t.setDaemon(true);
        t.start();
    }

    /** Keep the newest {@code max} backups named {@code <world>_*.zip}; delete older ones. Returns kept count. */
    private static int pruneBackups(Path backups, String worldName, int max) {
        try (var s = Files.list(backups)) {
            List<Path> mine = new ArrayList<>();
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(worldName + "_")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted() // timestamp suffix sorts chronologically
                    .forEach(mine::add);
            while (mine.size() > max) {
                Path oldest = mine.remove(0);
                Files.deleteIfExists(oldest);
            }
            return mine.size();
        } catch (Exception e) {
            return max;
        }
    }

    private void restoreBackup(Instance inst, VBox list) {
        Path backups = instanceManager.instanceDir(inst).resolve("backups");
        FileChooser fc = new FileChooser();
        fc.setTitle("Restore world backup");
        if (Files.isDirectory(backups)) fc.setInitialDirectory(backups.toFile());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("World backup", "*.zip"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null) return;
        Thread t = new Thread(() -> {
            try {
                Path saves = instanceManager.instanceDir(inst).resolve("saves");
                Files.createDirectories(saves);
                // Top-level folder in the zip; don't clobber an existing world of the same name.
                String topDir = null;
                try (var zf = new java.util.zip.ZipFile(f)) {
                    var en = zf.entries();
                    if (en.hasMoreElements()) {
                        String n = en.nextElement().getName().replace('\\', '/');
                        int slash = n.indexOf('/');
                        topDir = slash > 0 ? n.substring(0, slash) : n;
                    }
                }
                String target = (topDir != null && Files.exists(saves.resolve(topDir))) ? topDir + "-restored" : topDir;
                Path savesNorm = saves.normalize();
                try (var zf = new java.util.zip.ZipFile(f)) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        var e = entries.nextElement();
                        if (e.isDirectory()) continue;
                        String n = e.getName().replace('\\', '/');
                        String rel = (topDir != null && n.startsWith(topDir + "/"))
                                ? target + n.substring(topDir.length()) : n;
                        Path dest = saves.resolve(rel).normalize();
                        if (!dest.startsWith(savesNorm)) continue; // zip-slip guard
                        Files.createDirectories(dest.getParent());
                        try (var in = zf.getInputStream(e)) {
                            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                String restored = target;
                Platform.runLater(() -> {
                    populateWorlds(inst, list);
                    alert(Alert.AlertType.INFORMATION, "Restored", "World restored as “" + restored + "”.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Restore failed", String.valueOf(ex.getMessage())));
            }
        }, "world-restore");
        t.setDaemon(true);
        t.start();
    }

    private void openFolder(Path dir) {
        try { java.awt.Desktop.getDesktop().open(dir.toFile()); }
        catch (Exception ex) { alert(Alert.AlertType.ERROR, "Can't open folder", String.valueOf(ex.getMessage())); }
    }

    private static String dirSize(Path dir) {
        try (var s = Files.walk(dir)) {
            long bytes = s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0L; }
            }).sum();
            if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
            if (bytes >= 1_048_576) return String.format("%.0f MB", bytes / 1_048_576.0);
            return String.format("%.0f KB", bytes / 1024.0);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String lastModified(Path dir) {
        try {
            return DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
                    .format(Files.getLastModifiedTime(dir).toInstant());
        } catch (Exception e) {
            return "—";
        }
    }

    private static void deleteDir(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }

    private static String fileSize(Path p) {
        try {
            long b = Files.size(p);
            if (b >= 1_048_576) return String.format("%.1f MB", b / 1_048_576.0);
            if (b >= 1024) return String.format("%.0f KB", b / 1024.0);
            return b + " B";
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Browse Mods (Modrinth) ----

    private ContentSource sourceFor(String label) {
        if ("CurseForge".equals(label)) {
            return new CurseForgeClient(config != null ? config.curseforgeApiKey() : "");
        }
        return new ModrinthClient();
    }

    private StringConverter<Instance> instanceConverter() {
        return new StringConverter<>() {
            public String toString(Instance i) { return i == null ? "" : i.name() + " (" + i.version() + ")"; }
            public Instance fromString(String s) { return null; }
        };
    }

    private void showBrowse() { showBrowse(null, null); }

    private void showBrowse(Instance preselect, String preTypeLabel) {
        selectNav(navByName.get("Browse Mods"));
        VBox page = new VBox(14);
        page.getStyleClass().add("content");
        VBox title = pageHeader("DISCOVER", "Browse Content");

        var insts = instanceManager == null ? List.<Instance>of() : instanceManager.list();
        if (insts.isEmpty()) {
            Label none = new Label("Create an instance first — content installs into an instance.");
            none.getStyleClass().add("muted");
            page.getChildren().addAll(title, none);
            content.getChildren().setAll(page);
            return;
        }

        ChoiceBox<Instance> instPick = new ChoiceBox<>();
        instPick.getItems().addAll(insts);
        instPick.setConverter(instanceConverter());
        instPick.getSelectionModel().selectFirst();
        if (preselect != null) {
            for (Instance it : instPick.getItems()) {
                if (it.dirName().equals(preselect.dirName())) { instPick.getSelectionModel().select(it); break; }
            }
        }

        ChoiceBox<String> typeBox = new ChoiceBox<>();
        typeBox.getItems().addAll("Mods", "Modpacks", "Resource Packs", "Shaders", "Data Packs");
        typeBox.getSelectionModel().selectFirst();
        if (preTypeLabel != null && typeBox.getItems().contains(preTypeLabel)) {
            typeBox.getSelectionModel().select(preTypeLabel);
        }

        ChoiceBox<String> sortBox = new ChoiceBox<>();
        sortBox.getItems().addAll("Most downloads", "Relevance", "Most followers", "Newest", "Recently updated");
        sortBox.getSelectionModel().selectFirst();

        ChoiceBox<String> sourceBox = new ChoiceBox<>();
        sourceBox.getItems().addAll("Modrinth", "CurseForge");
        sourceBox.getSelectionModel().selectFirst();

        TextField q = new TextField();
        q.setPromptText("Search…");
        HBox.setHgrow(q, Priority.ALWAYS);

        VBox results = new VBox(10);
        Button loadMore = new Button("Load more");
        loadMore.getStyleClass().add("btn-outline");
        loadMore.setVisible(false);
        loadMore.setManaged(false);
        VBox resultsWrap = new VBox(12, results, loadMore);
        ScrollPane scroll = new ScrollPane(resultsWrap);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        int[] offset = {0};
        java.util.function.Consumer<Boolean> page2 = reset -> {
            Instance inst = instPick.getValue();
            if (inst == null) return;
            String type = typeKey(typeBox.getValue());
            String sort = sortKey(sortBox.getValue());
            String loaderFacet = ("mod".equals(type) || "modpack".equals(type)) ? "fabric" : null;
            String query = q.getText();
            browseSource = sourceFor(sourceBox.getValue());
            final ContentSource src = browseSource;
            if (reset) { offset[0] = 0; results.getChildren().clear(); }
            int startOffset = offset[0];
            loadMore.setDisable(true);
            loadMore.setText("Loading…");
            Thread t = new Thread(() -> {
                try {
                    var res = src.search(query, inst.version(), loaderFacet, type, sort, startOffset, 20);
                    // Fallback (Modrinth only): its search index occasionally returns nothing — if you
                    // typed a name, try a direct project lookup by slug so you can still find/install it.
                    ModrinthClient.Hit fb = null;
                    if ("modrinth".equals(src.id()) && reset && res.hits().isEmpty() && query != null && !query.isBlank()) {
                        try {
                            var pd = src.project(query.trim().toLowerCase().replaceAll("\\s+", "-"));
                            fb = new ModrinthClient.Hit(pd.id(), pd.slug(), pd.title(), pd.summary(),
                                    pd.downloads(), pd.iconUrl(), type);
                        } catch (Exception ignore) {
                        }
                    }
                    final ModrinthClient.Hit fallback = fb;
                    Platform.runLater(() -> {
                        if (reset && res.hits().isEmpty()) {
                            if (fallback != null) {
                                results.getChildren().add(modCard(fallback, inst, type));
                            } else {
                                Label none = new Label(query == null || query.isBlank()
                                        ? "No results — Modrinth search may be temporarily unavailable. Try again shortly, or type an exact name (e.g. sodium)."
                                        : "No compatible " + typeBox.getValue().toLowerCase() + " found for this version.");
                                none.getStyleClass().add("muted");
                                none.setWrapText(true);
                                results.getChildren().add(none);
                            }
                        }
                        for (var h : res.hits()) results.getChildren().add(modCard(h, inst, type));
                        offset[0] = startOffset + res.hits().size();
                        boolean more = offset[0] < res.totalHits() && !res.hits().isEmpty();
                        loadMore.setVisible(more);
                        loadMore.setManaged(more);
                        loadMore.setDisable(false);
                        loadMore.setText("Load more  (" + offset[0] + " / " + res.totalHits() + ")");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (reset) {
                            Label err = new Label("Search failed: " + ex.getMessage());
                            err.getStyleClass().add("muted");
                            results.getChildren().setAll(err);
                        }
                        loadMore.setDisable(false);
                        loadMore.setText("Load more");
                    });
                }
            }, "mod-search");
            t.setDaemon(true);
            t.start();
        };

        loadMore.setOnAction(e -> page2.accept(false));
        PauseTransition debounce = new PauseTransition(Duration.millis(350));
        debounce.setOnFinished(e -> page2.accept(true));
        q.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        instPick.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> page2.accept(true));
        typeBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> page2.accept(true));
        sortBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> page2.accept(true));
        sourceBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> page2.accept(true));

        Button perfBtn = new Button("⚡ Performance Pack");
        perfBtn.getStyleClass().add("btn-update"); // gold/yellow — stands out as the recommended action
        perfBtn.setOnAction(e -> installPerfPack(instPick.getValue(), perfBtn));

        Button importPack = new Button("📦 Import .mrpack file");
        importPack.getStyleClass().add("btn-outline");
        importPack.setOnAction(e -> importMrpackFile());

        Label srcLbl = new Label("Source:");
        srcLbl.getStyleClass().add("card-sub");
        Label typeLbl = new Label("Type:");
        typeLbl.getStyleClass().add("card-sub");
        Label sortLbl = new Label("Sort:");
        sortLbl.getStyleClass().add("card-sub");
        HBox bar1 = new HBox(10, instPick, q);
        bar1.setAlignment(Pos.CENTER_LEFT);
        HBox bar2 = new HBox(10, srcLbl, sourceBox, typeLbl, typeBox, sortLbl, sortBox, perfBtn, importPack);
        bar2.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().addAll(title, bar1, bar2, scroll);
        content.getChildren().setAll(page);

        page2.accept(true);
    }

    private static String sortKey(String label) {
        return switch (label) {
            case "Relevance" -> "relevance";
            case "Most followers" -> "follows";
            case "Newest" -> "newest";
            case "Recently updated" -> "updated";
            default -> "downloads";
        };
    }

    private static String typeKey(String label) {
        return switch (label) {
            case "Modpacks" -> "modpack";
            case "Resource Packs" -> "resourcepack";
            case "Shaders" -> "shader";
            case "Data Packs" -> "datapack";
            default -> "mod";
        };
    }

    /** Target folder for a content type, or null if single-file install isn't supported. */
    private static String typeFolder(String type) {
        return switch (type) {
            case "mod" -> "mods";
            case "resourcepack" -> "resourcepacks";
            case "shader" -> "shaderpacks";
            case "datapack" -> "datapacks"; // holding folder (copy into a world's datapacks/ to use)
            default -> null; // modpack isn't a single-file install — handled by installModpack()
        };
    }

    private Region modCard(ModrinthClient.Hit hit, Instance inst, String type) {
        Label name = new Label(hit.title());
        name.getStyleClass().add("card-title-sm");
        Label desc = new Label(hit.description());
        desc.getStyleClass().add("card-sub");
        desc.setWrapText(true);
        Label dl = new Label("⬇ " + formatDownloads(hit.downloads()) + " downloads");
        dl.getStyleClass().add("card-downloads");
        VBox info = new VBox(4, name, desc, dl);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button install = new Button("Install");
        install.getStyleClass().add("btn-outline");
        install.setOnAction(e -> installMod(hit, inst, type, install));

        HBox row = new HBox(14, modIcon(hit.iconUrl()), info, install);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane card = new StackPane(row);
        card.getStyleClass().add("card");
        card.setStyle("-fx-cursor: hand;");
        card.setOnMouseClicked(e -> showModDetail(hit, inst, type));
        return card;
    }

    private static final java.util.Map<String, Image> ICON_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService ICON_POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "icon-load");
        t.setDaemon(true);
        return t;
    });

    /** 48x48 rounded mod icon from Modrinth. Decodes PNG/JPG/GIF/WebP; glyph fallback if it can't. */
    private Region modIcon(String url) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("mod-icon");
        tile.setMinSize(48, 48);
        tile.setMaxSize(48, 48);
        Label placeholder = new Label("❖");
        placeholder.getStyleClass().add("icon-glyph");
        placeholder.setStyle("-fx-font-size: 20px;");
        tile.getChildren().add(placeholder);
        if (url != null && !url.isBlank()) {
            ImageView iv = new ImageView();
            iv.setFitWidth(48);
            iv.setFitHeight(48);
            Rectangle clip = new Rectangle(48, 48);
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            iv.setClip(clip);
            tile.getChildren().add(iv);
            loadIcon(iv, url);
        }
        return tile;
    }

    /** Fetch + decode an image (any format ImageIO supports, incl. WebP) off-thread; cache by URL. */
    private void loadIcon(ImageView iv, String url) {
        Image cached = ICON_CACHE.get(url);
        if (cached != null) { iv.setImage(cached); return; }
        ICON_POOL.submit(() -> {
            try {
                HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) return;
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(r.body()));
                if (bi == null) return;
                Image img = SwingFXUtils.toFXImage(bi, null);
                ICON_CACHE.put(url, img);
                Platform.runLater(() -> iv.setImage(img));
            } catch (Exception ignored) {
            }
        });
    }

    private void installMod(ModrinthClient.Hit hit, Instance inst, String type, Button install) {
        if ("modpack".equals(type)) { installModpack(hit, inst, install); return; }
        String folder = typeFolder(type);
        if (folder == null) {
            alert(Alert.AlertType.INFORMATION, "Not supported yet",
                    "Installing " + type + "s isn't supported yet — only mods, resource packs, and shaders.");
            return;
        }
        String depLoader = "mod".equals(type) ? "fabric" : null;
        install.setDisable(true);
        install.setText("Installing…");
        Thread t = new Thread(() -> {
            try {
                Path dir = instanceManager.instanceDir(inst).resolve(folder);
                int n = ModInstaller.install(browseSource, hit.projectId(), inst.version(),
                        depLoader, dir, msg -> {});
                try {
                    var vers = browseSource.versions(hit.projectId(), inst.version(), depLoader);
                    ModrinthClient.ModFile pf = primaryFile(vers.isEmpty() ? null : vers.get(0));
                    if (pf != null) new ModTracker(instanceManager.instanceDir(inst)).record(
                            new ModTracker.Entry(hit.projectId(), browseSource.id(), type, folder, pf.filename(), hit.title()));
                } catch (Exception ignore) {
                }
                Platform.runLater(() -> {
                    install.setText("Installed ✓");
                    alert(Alert.AlertType.INFORMATION, "Installed",
                            hit.title() + " → " + inst.name() + "\n" + n + " file(s) added (incl. dependencies).");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    install.setDisable(false);
                    install.setText("Install");
                    alert(Alert.AlertType.ERROR, "Install failed", String.valueOf(ex.getMessage()));
                });
            }
        }, "mod-install");
        t.setDaemon(true);
        t.start();
    }

    // Curated, conflict-free, loader-correct performance stacks (researched + verified against the
    // live Modrinth API — see KanoLauncher.md §11). Each mod's deps are resolved automatically; any
    // slug with no build for the instance's exact version is skipped gracefully.
    //
    // Fabric/Quilt — full Sodium stack: renderer + companions, tick logic, lighting, chunk IO,
    // memory, culling, micro-rendering, networking, threading, QoL.
    private static final String[] PERF_MODS_FABRIC = {
            "sodium", "sodium-extra", "reeses-sodium-options",
            "lithium", "scalablelux", "c2me-fabric",
            "ferrite-core", "modernfix", "threadtweak", "krypton",
            "moreculling", "entityculling", "immediatelyfast", "dynamic-fps",
            "debugify", "fastquit", "fast-ip-ping", "vmp-fabric"};
    // NeoForge — Embeddium renderer (NOT Sodium, so no sodium-extra/reese's which would pull Sodium);
    // Lithium + ScalableLux + MoreCulling + C2ME all have first-class NeoForge builds.
    private static final String[] PERF_MODS_NEOFORGE = {
            "embeddium", "lithium", "scalablelux", "c2me-neoforge",
            "ferrite-core", "modernfix",
            "moreculling", "entityculling", "immediatelyfast", "dynamic-fps",
            "enhanced-block-entities-neoforged", "fast-ip-ping"};
    // Forge — lighter (no Forge Lithium/ScalableLux/MoreCulling exist); Embeddium + memory + culling.
    private static final String[] PERF_MODS_FORGE = {
            "embeddium", "ferrite-core", "modernfix",
            "cull-leaves", "entityculling", "immediatelyfast", "dynamic-fps",
            "c2mef", "debugify", "fast-ip-ping"};

    private void installPerfPack(Instance inst, Button btn) {
        if (inst == null) return;
        if (inst.loader() == Loader.VANILLA) {
            alert(Alert.AlertType.INFORMATION, "Needs a mod loader",
                    "The Performance Pack installs mods — make this a Fabric, NeoForge, or Forge instance first.");
            return;
        }
        String[] mods = switch (inst.loader()) {
            case NEOFORGE -> PERF_MODS_NEOFORGE;
            case FORGE -> PERF_MODS_FORGE;
            default -> PERF_MODS_FABRIC; // Fabric / Quilt
        };
        String loaderTag = modLoaderTag(inst, "mod"); // fabric/quilt/forge/neoforge from the instance
        btn.setDisable(true);
        btn.setText("Installing…");
        Thread t = new Thread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            Path modsDir = instanceManager.instanceDir(inst).resolve("mods");
            ModrinthClient client = new ModrinthClient();
            for (String slug : mods) {
                try {
                    ok += ModInstaller.install(client, slug, inst.version(), loaderTag, modsDir, m -> {});
                    try {
                        var vers = client.versions(slug, inst.version(), loaderTag);
                        ModrinthClient.ModFile pf = primaryFile(vers.isEmpty() ? null : vers.get(0));
                        if (pf != null) new ModTracker(instanceManager.instanceDir(inst)).record(
                                new ModTracker.Entry(slug, "modrinth", "mod", "mods", pf.filename(), slug));
                    } catch (Exception ignore) {
                    }
                } catch (Exception ex) {
                    failed.add(slug);
                }
            }
            int total = ok;
            Platform.runLater(() -> {
                btn.setDisable(false);
                btn.setText("⚡ Performance Pack");
                String hint = (inst.loader() == Loader.FORGE && failed.size() >= 4)
                        ? "\n\nHeads up: most performance mods (Embeddium, Lithium, etc.) only ship for Forge up to "
                          + "~1.20.1. For " + inst.version() + " use NeoForge instead — it has the full stack and far higher FPS."
                        : "";
                alert(Alert.AlertType.INFORMATION, "Performance Pack",
                        "Added " + total + " file(s) to " + inst.name() + " (" + inst.loader().display() + ")."
                        + (failed.isEmpty() ? "" : "\nSkipped (no compatible build): " + String.join(", ", failed))
                        + hint);
            });
        }, "perf-pack");
        t.setDaemon(true);
        t.start();
    }

    // ---- modpack (.mrpack) import ----

    /**
     * Install a Modrinth modpack from a Browse hit: resolve its newest {@code .mrpack} for the
     * selected instance's version, download it, then build a fresh instance from the pack's own
     * declared MC + loader and unpack its files + overrides. Modrinth-only (CurseForge packs aren't
     * the .mrpack format and aren't supported here).
     */
    private void installModpack(ModrinthClient.Hit hit, Instance ctx, Button install) {
        if (instanceManager == null) { alert(Alert.AlertType.ERROR, "Error", initError); return; }
        if (browseSource != null && !"modrinth".equals(browseSource.id())) {
            alert(Alert.AlertType.INFORMATION, "Modrinth only",
                    "Modpack import works with Modrinth's .mrpack format only.\n\n"
                    + "For a CurseForge pack, download its .mrpack (or a Modrinth export) and use \"Import .mrpack file\".");
            return;
        }
        install.setDisable(true);
        install.setText("Importing…");
        Thread t = new Thread(() -> {
            try {
                ModrinthClient mc = new ModrinthClient();
                var vers = mc.versions(hit.projectId(), ctx.version(), null); // newest pack build for this MC version
                ModrinthClient.ModFile pack = primaryFile(vers.isEmpty() ? null : vers.get(0));
                if (pack == null || pack.url() == null || pack.url().isBlank())
                    throw new RuntimeException("No downloadable .mrpack for " + hit.title() + " on " + ctx.version() + ".");
                Path mrpack = resolveDataDir().resolve("cache").resolve("modpacks").resolve(pack.filename());
                Downloader.download(pack.url(), null, mrpack);
                doModpackImport(mrpack, hit.title(), () -> {
                    install.setDisable(false);
                    install.setText("Install");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    install.setDisable(false);
                    install.setText("Install");
                    alert(Alert.AlertType.ERROR, "Modpack import failed", String.valueOf(ex.getMessage()));
                });
            }
        }, "modpack-install");
        t.setDaemon(true);
        t.start();
    }

    /** Import a local .mrpack the user picked from disk (offline / CurseForge-exported packs). */
    private void importMrpackFile() {
        if (instanceManager == null) { alert(Alert.AlertType.ERROR, "Error", initError); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Import modpack (.mrpack)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modrinth modpack", "*.mrpack"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null) return;
        Thread t = new Thread(() -> doModpackImport(f.toPath(), f.getName().replaceAll("\\.mrpack$", ""), null),
                "modpack-import-file");
        t.setDaemon(true);
        t.start();
    }

    /** Shared back half: read index, create the instance, unpack, report. Runs off the FX thread. */
    private void doModpackImport(Path mrpack, String fallbackName, Runnable onReset) {
        try {
            ModpackInstaller.Index idx = ModpackInstaller.readIndex(mrpack);
            String name = idx.name() != null && !idx.name().isBlank() ? idx.name() : fallbackName;
            Instance inst = instanceManager.create(name, idx.mcVersion(), idx.loader());
            int n = ModpackInstaller.installInto(mrpack, idx, instanceManager.instanceDir(inst), msg -> {});
            boolean forgey = idx.loader() == Loader.FORGE || idx.loader() == Loader.NEOFORGE;
            Platform.runLater(() -> {
                if (onReset != null) onReset.run();
                showInstances();
                String warn = forgey
                        ? "\n\nNote: " + idx.loader().display() + " runs its installer on first Play (downloads + "
                        + "patches the client) — the first launch takes longer, and this loader path is new."
                        : "";
                alert(Alert.AlertType.INFORMATION, "Modpack imported",
                        name + " → new " + idx.loader().display() + " " + idx.mcVersion() + " instance.\n"
                        + n + " file(s) installed (mods + overrides)." + warn);
            });
        } catch (Exception ex) {
            Platform.runLater(() -> {
                if (onReset != null) onReset.run();
                alert(Alert.AlertType.ERROR, "Modpack import failed", String.valueOf(ex.getMessage()));
            });
        }
    }

    private void cloneInstanceDialog(Instance inst) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Clone Instance");
        d.setHeaderText("Duplicate \"" + inst.name() + "\"");
        TextField nm = new TextField(inst.name() + " copy");
        CheckBox keep = new CheckBox("Copy worlds too (saves/)");
        VBox box = new VBox(10, new Label("New name"), nm, keep);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialog(d);
        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            Thread t = new Thread(() -> {
                try {
                    instanceManager.cloneInstance(inst, nm.getText(), keep.isSelected());
                    Platform.runLater(() -> {
                        showInstances();
                        alert(Alert.AlertType.INFORMATION, "Cloned", "Created a copy of " + inst.name() + ".");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Clone failed", String.valueOf(ex.getMessage())));
                }
            }, "clone-instance");
            t.setDaemon(true);
            t.start();
        }
    }

    private void convertToNeoForge(Instance inst) {
        Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                "Switch “" + inst.name() + "” from Forge to NeoForge?\n\n"
                + "NeoForge has the performance mods Forge lacks on " + inst.version() + ". Your worlds and "
                + "settings stay. Existing mods carry over, but Forge-only jars may need replacing — "
                + "reinstall the Performance Pack afterwards.",
                ButtonType.OK, ButtonType.CANCEL);
        c.setHeaderText(null);
        styleDialog(c);
        if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Thread t = new Thread(() -> {
            try {
                if (ForgeVersions.latestNeoForge(inst.version()) == null) {
                    Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, "No NeoForge build",
                            "NeoForge doesn’t have a build for " + inst.version() + " yet."));
                    return;
                }
                instanceManager.update(inst.withLoader(Loader.NEOFORGE));
                Platform.runLater(() -> {
                    showInstanceDetail(currentInstance(inst));
                    alert(Alert.AlertType.INFORMATION, "Converted",
                            inst.name() + " is now a NeoForge instance.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Convert failed", String.valueOf(ex.getMessage())));
            }
        }, "convert-neoforge");
        t.setDaemon(true);
        t.start();
    }

    private void exportInstance(Instance inst) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export instance as .mrpack");
        fc.setInitialFileName(inst.name().replaceAll("[^a-zA-Z0-9-_ ]", "").trim() + ".mrpack");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modrinth modpack", "*.mrpack"));
        java.io.File f = fc.showSaveDialog(stage);
        if (f == null) return;
        Thread t = new Thread(() -> {
            try {
                Path out = f.toPath();
                ModpackInstaller.export(instanceManager.instanceDir(inst), inst.name(), inst.version(), inst.loader(), out);
                Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, "Exported",
                        inst.name() + " → " + out.getFileName()
                        + "\nSelf-contained (mods + config bundled). Worlds excluded."));
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Export failed", String.valueOf(ex.getMessage())));
            }
        }, "export-instance");
        t.setDaemon(true);
        t.start();
    }

    private void setupOptifine(Instance inst) {
        if (inst.loader() != Loader.FABRIC) {
            alert(Alert.AlertType.WARNING, "Fabric needed",
                    "OptiFine on Fabric uses OptiFabric — make this a Fabric instance first.");
            return;
        }
        Path modsDir = instanceManager.instanceDir(inst).resolve("mods");
        try { Files.createDirectories(modsDir); } catch (Exception ignore) {}
        openFolder(modsDir); // open right away so the user can drop the OptiFine jar in
        // Auto-install OptiFabric from Modrinth if available (then only the OptiFine jar is manual).
        Thread t = new Thread(() -> {
            String status;
            try {
                int n = ModInstaller.install(new ModrinthClient(), "optifabric", inst.version(), "fabric", modsDir, m -> {});
                status = n > 0 ? "✓ OptiFabric installed automatically."
                        : "OptiFabric: no compatible build auto-installed — grab it from the link below.";
            } catch (Exception ex) {
                status = "OptiFabric couldn't auto-install — grab it from the link below.";
            }
            String s = status;
            Platform.runLater(() -> optifineDialog(inst, modsDir, s));
        }, "optifabric");
        t.setDaemon(true);
        t.start();
    }

    private void optifineDialog(Instance inst, Path modsDir, String ofabricStatus) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Set up OptiFine");
        d.setHeaderText("OptiFine for " + inst.version() + " (Fabric)");
        Label step = new Label(ofabricStatus + "\n\nThe OptiFine jar itself is gated behind optifine.net "
                + "and can't be auto-downloaded — download it, drop it in the mods folder, then Play.");
        step.setWrapText(true);
        Hyperlink ofLink = new Hyperlink("⬇  Download OptiFine — optifine.net/downloads");
        ofLink.setOnAction(e -> openUrl("https://optifine.net/downloads"));
        Hyperlink ofabLink = new Hyperlink("⬇  OptiFabric (if it didn't auto-install) — CurseForge");
        ofabLink.setOnAction(e -> openUrl("https://www.curseforge.com/minecraft/mc-mods/optifabric/files"));
        Button openMods = new Button("Open mods folder");
        openMods.getStyleClass().add("btn-outline");
        openMods.setOnAction(e -> openFolder(modsDir));
        Label warn = new Label("Note: brand-new MC versions often aren't supported yet (use 1.21.1 or 1.20.1). "
                + "OptiFine conflicts with Sodium — disable Sodium in this instance.");
        warn.getStyleClass().add("muted");
        warn.setWrapText(true);
        VBox box = new VBox(10, step, ofLink, ofabLink, openMods, warn);
        box.setPadding(new Insets(12));
        box.setPrefWidth(470);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        styleDialog(d);
        d.showAndWait();
    }

    private void openUrl(String url) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
        catch (Exception ex) { alert(Alert.AlertType.ERROR, "Can't open link", url); }
    }

    private static ModrinthClient.ModFile primaryFile(ModrinthClient.ModVersion v) {
        if (v == null || v.files().isEmpty()) return null;
        return v.files().stream().filter(ModrinthClient.ModFile::primary).findFirst().orElse(v.files().get(0));
    }

    /** Pick the right content source for a tracked entry's origin id ("curseforge"/"modrinth"). */
    private ContentSource sourceForTracked(String source) {
        return "curseforge".equals(source)
                ? new CurseForgeClient(config != null ? config.curseforgeApiKey() : "")
                : new ModrinthClient();
    }

    /** Loader facet to query for an entry type, based on the instance's loader. */
    private static String modLoaderTag(Instance inst, String type) {
        if (!"mod".equals(type) && !"modpack".equals(type)) return null;
        return switch (inst.loader()) {
            case QUILT -> "quilt";
            case FORGE -> "forge";
            case NEOFORGE -> "neoforge";
            default -> "fabric"; // FABRIC, or a vanilla instance that somehow has a tracked mod
        };
    }

    /**
     * Background-safe (no UI): how many tracked mods have a newer compatible build than what's
     * installed. Uses the same source/loader resolution as {@link #updateAllMods} so the badge
     * count and the actual update agree. Returns 0 on any error so the button just stays hidden.
     */
    private int countModUpdates(Instance inst) {
        try {
            ModTracker tr = new ModTracker(instanceManager.instanceDir(inst));
            int n = 0;
            for (ModTracker.Entry e : tr.list()) {
                try {
                    ContentSource src = sourceForTracked(e.source());
                    String loader = modLoaderTag(inst, e.type());
                    ModrinthClient.ModFile pf = primaryFile(srcLatest(src, e.projectId(), inst.version(), loader));
                    if (pf != null && !pf.filename().equals(e.filename())) n++;
                } catch (Exception ignored) {
                }
            }
            return n;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** Re-install every tracked item to its newest compatible version (replacing the old file). */
    private void updateAllMods(Instance inst) {
        Thread t = new Thread(() -> {
            try {
                if (new ModTracker(instanceManager.instanceDir(inst)).list().isEmpty()) {
                    Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, "No tracked mods",
                            "Nothing tracked yet. Content installed from Browse is tracked going forward — "
                            + "mods added before tracking existed won't update here."));
                    return;
                }
                java.util.List<String> issues = new java.util.ArrayList<>();
                int[] r = updateInstanceModsSync(inst, issues);
                int ch = r[0], up = r[1];
                Platform.runLater(() -> {
                    showInstanceDetail(currentInstance(inst));
                    alert(Alert.AlertType.INFORMATION, "Update complete",
                            "Checked " + ch + " tracked item(s); updated " + up + "."
                            + (issues.isEmpty() ? "" : "\nSkipped: " + String.join(", ", issues)));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Update failed", String.valueOf(ex.getMessage())));
            }
        }, "update-mods");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronously update one instance's tracked mods to their newest compatible builds.
     * Returns {@code {checked, updated}}; appends skipped item names to {@code issues}. Runs on the
     * caller's thread (always a background thread).
     */
    private int[] updateInstanceModsSync(Instance inst, java.util.List<String> issues) throws Exception {
        Path instDir = instanceManager.instanceDir(inst);
        ModTracker tr = new ModTracker(instDir);
        int updated = 0, checked = 0;
        for (ModTracker.Entry e : tr.list()) {
            try {
                ContentSource src = sourceForTracked(e.source());
                String loader = modLoaderTag(inst, e.type());
                ModrinthClient.ModFile pf = primaryFile(srcLatest(src, e.projectId(), inst.version(), loader));
                if (pf == null) { issues.add(e.name() + " (no compatible build)"); continue; }
                checked++;
                if (!pf.filename().equals(e.filename())) {
                    Path dir = instDir.resolve(e.folder());
                    Files.deleteIfExists(dir.resolve(e.filename()));
                    Files.deleteIfExists(dir.resolve(e.filename() + ".disabled"));
                    ModInstaller.install(src, e.projectId(), inst.version(), loader, dir, m -> {});
                    tr.record(new ModTracker.Entry(e.projectId(), e.source(), e.type(), e.folder(), pf.filename(), e.name()));
                    updated++;
                }
            } catch (Exception ex) {
                issues.add(e.name());
            }
        }
        return new int[]{checked, updated};
    }

    private static ModrinthClient.ModVersion srcLatest(ContentSource src, String projectId, String gv, String loader)
            throws Exception {
        var vers = src.versions(projectId, gv, loader);
        return vers.isEmpty() ? null : vers.get(0);
    }

    private static String formatDownloads(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.0fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ---- mod "about" page ----

    private void showModDetail(ModrinthClient.Hit hit, Instance inst, String type) {
        VBox page = new VBox(16);
        page.getStyleClass().add("content");
        Button back = new Button("←  Back");
        back.getStyleClass().add("nav-button");
        back.setOnAction(e -> showBrowse());
        Label loading = new Label("Loading " + hit.title() + "…");
        loading.getStyleClass().add("muted");
        page.getChildren().addAll(back, loading);
        content.getChildren().setAll(page);

        Thread t = new Thread(() -> {
            try {
                var pd = browseSource.project(hit.projectId());
                Platform.runLater(() -> renderModDetail(pd, hit, inst, type));
            } catch (Exception ex) {
                Platform.runLater(() -> loading.setText("Failed to load: " + ex.getMessage()));
            }
        }, "mod-detail");
        t.setDaemon(true);
        t.start();
    }

    private void renderModDetail(ModrinthClient.ProjectDetail pd, ModrinthClient.Hit hit, Instance inst, String type) {
        VBox page = new VBox(14);
        page.getStyleClass().add("content");
        Button back = new Button("←  Back");
        back.getStyleClass().add("nav-button");
        back.setOnAction(e -> showBrowse());

        StackPane icon = new StackPane();
        icon.getStyleClass().add("mod-icon");
        icon.setMinSize(72, 72);
        icon.setMaxSize(72, 72);
        Label ph = new Label("❖");
        ph.getStyleClass().add("icon-glyph");
        icon.getChildren().add(ph);
        if (!pd.iconUrl().isBlank()) {
            ImageView iv = new ImageView();
            iv.setFitWidth(72);
            iv.setFitHeight(72);
            Rectangle c = new Rectangle(72, 72);
            c.setArcWidth(14);
            c.setArcHeight(14);
            iv.setClip(c);
            icon.getChildren().add(iv);
            loadIcon(iv, pd.iconUrl());
        }

        Label nm = new Label(pd.title());
        nm.getStyleClass().add("page-title");
        Label summary = new Label(pd.summary());
        summary.getStyleClass().add("card-meta");
        summary.setWrapText(true);
        Label statsL = new Label("⬇ " + formatDownloads(pd.downloads()) + " downloads     ♥ "
                + formatDownloads(pd.followers()) + " followers");
        statsL.getStyleClass().add("card-downloads");
        VBox titleBox = new VBox(6, nm, summary, statsL);
        HBox header = new HBox(16, icon, titleBox);
        header.setAlignment(Pos.CENTER_LEFT);

        FlowPane chips = new FlowPane(6, 6);
        for (String c : pd.categories()) {
            Label chip = new Label(c);
            chip.getStyleClass().add("chip");
            chips.getChildren().add(chip);
        }

        Button install = new Button("Install to " + inst.name());
        install.getStyleClass().add("btn-filled");
        install.setOnAction(e -> installMod(hit, inst, type, install));
        boolean cf = "curseforge".equals(browseSource.id());
        Button open = new Button(cf ? "Open on CurseForge" : "Open on Modrinth");
        open.getStyleClass().add("btn-outline");
        String slug = pd.slug().isBlank() ? hit.slug() : pd.slug();
        String openUrl = cf
                ? "https://www.curseforge.com/minecraft/search?search="
                        + java.net.URLEncoder.encode(slug, java.nio.charset.StandardCharsets.UTF_8)
                : "https://modrinth.com/" + type + "/" + slug;
        open.setOnAction(e -> getHostServices().showDocument(openUrl));
        HBox btns = new HBox(10, install, open);

        TextArea body = new TextArea(stripMarkdown(pd.body()));
        body.setEditable(false);
        body.setWrapText(true);
        body.getStyleClass().add("about-text");
        VBox.setVgrow(body, Priority.ALWAYS);

        page.getChildren().addAll(back, header, chips, btns, body);
        content.getChildren().setAll(page);
    }

    /** Rough markdown → plain text so the description body is readable in a TextArea. */
    private static String stripMarkdown(String md) {
        if (md == null || md.isBlank()) return "(No description provided.)";
        return md.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")     // images
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")    // links -> text
                .replaceAll("<[^>]+>", "")                          // html tags
                .replaceAll("(?m)^[#>\\-\\*\\s]{1,}", "")           // leading md markers
                .replaceAll("[`*_~|]", "")                          // inline md symbols
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private void showSettings() {
        selectNav(navByName.get("Settings"));
        VBox page = new VBox(12);
        page.getStyleClass().add("content");
        VBox t = pageHeader("LAUNCHER", "Settings");
        Label cid = new Label("Client ID: " + (clientId != null ? clientId : "not set"));
        cid.getStyleClass().add("muted");
        Label dir = new Label("Data folder: " + resolveDataDir());
        dir.getStyleClass().add("muted");

        Label cfLbl = new Label("CurseForge API key");
        cfLbl.getStyleClass().add("card-title-sm");
        boolean bundledCf = config != null && config.hasBundledCfKey();
        Label cfHelp = new Label(bundledCf
                ? "This build ships with a built-in CurseForge key — CurseForge already works. Paste your "
                  + "own key below only if you want to override it. Stored locally only."
                : "Lets you browse CurseForge as a second source. Get a free key at "
                  + "console.curseforge.com → API Keys. Stored locally only.");
        cfHelp.getStyleClass().add("muted");
        cfHelp.setWrapText(true);
        TextField cfKey = new TextField(config != null ? config.userCurseforgeApiKey() : "");
        cfKey.setPromptText(bundledCf ? "using the built-in key — paste to override" : "paste your CurseForge API key");
        cfKey.setPrefWidth(420);
        Button saveCf = new Button("Save Key");
        saveCf.getStyleClass().add("btn-filled");
        saveCf.setOnAction(e -> {
            if (config != null) config.setCurseforgeApiKey(cfKey.getText());
            alert(Alert.AlertType.INFORMATION, "Saved", "CurseForge API key saved.");
        });
        VBox cfBox = new VBox(8, cfLbl, cfHelp, cfKey, saveCf);
        cfBox.setStyle("-fx-padding: 12 0 0 0;");

        Label bgLbl = new Label("Launcher background");
        bgLbl.getStyleClass().add("card-title-sm");
        Label bgHelp = new Label("Replace the corner watermark with your own image.");
        bgHelp.getStyleClass().add("muted");
        Button chooseBg = new Button("Choose Image…");
        chooseBg.getStyleClass().add("btn-outline");
        chooseBg.setOnAction(e -> chooseBackground());
        Button resetBg = new Button("Reset to default");
        resetBg.getStyleClass().add("btn-outline");
        resetBg.setOnAction(e -> resetBackground());
        VBox bgBox = new VBox(8, bgLbl, bgHelp, new HBox(10, chooseBg, resetBg));
        bgBox.setStyle("-fx-padding: 12 0 0 0;");

        // Launcher name & colours — split the name into coloured parts.
        Label nameLbl = new Label("Launcher name & colours");
        nameLbl.getStyleClass().add("card-title-sm");
        Label nameHelp = new Label("Break the name into parts, each with its own colour (shown in the sidebar).");
        nameHelp.getStyleClass().add("muted");
        VBox segRows = new VBox(8);
        java.util.function.BiConsumer<String, String> addRow = (text, color) -> {
            TextField tf = new TextField(text);
            tf.setPrefWidth(180);
            ColorPicker cp = new ColorPicker(Color.web(color == null || color.isBlank() ? "#FFFFFF" : color));
            Button rm = new Button("✕");
            rm.getStyleClass().add("btn-outline");
            HBox row = new HBox(8, tf, cp, rm);
            row.setAlignment(Pos.CENTER_LEFT);
            rm.setOnAction(ev -> { if (segRows.getChildren().size() > 1) segRows.getChildren().remove(row); });
            segRows.getChildren().add(row);
        };
        for (Config.NameSegment s : (config != null ? config.nameSegments()
                : List.of(new Config.NameSegment(launcherName(), "#FFFFFF"))))
            addRow.accept(s.text(), s.color());
        Button addPart = new Button("+ Add part");
        addPart.getStyleClass().add("btn-outline");
        addPart.setOnAction(e -> addRow.accept("Text", toHex(Color.web(resolveAccent()))));
        Button saveName = new Button("Save Name");
        saveName.getStyleClass().add("btn-filled");
        saveName.setOnAction(e -> {
            List<Config.NameSegment> segs = new ArrayList<>();
            for (Node n : segRows.getChildren()) {
                if (n instanceof HBox row && row.getChildren().get(0) instanceof TextField tf
                        && row.getChildren().get(1) instanceof ColorPicker cp && !tf.getText().isEmpty()) {
                    segs.add(new Config.NameSegment(tf.getText(), toHex(cp.getValue())));
                }
            }
            if (segs.isEmpty()) { alert(Alert.AlertType.ERROR, "Empty name", "Add at least one part with text."); return; }
            if (config != null) config.setNameSegments(segs);
            rebuildBrand();
            String nm = launcherName();
            if (titleText != null) titleText.setText(nm + " " + VERSION);
            if (stage != null) stage.setTitle(nm);
            alert(Alert.AlertType.INFORMATION, "Saved", "Launcher name updated.");
        });
        VBox nameBox = new VBox(8, nameLbl, nameHelp, segRows, new HBox(10, addPart, saveName));
        nameBox.setStyle("-fx-padding: 12 0 0 0;");

        // Color scheme
        Label themeLbl = new Label("Color scheme");
        themeLbl.getStyleClass().add("card-title-sm");
        ChoiceBox<String> themeBox = new ChoiceBox<>();
        for (var en : THEMES.entrySet()) themeBox.getItems().add(en.getValue().name());
        String curKey = config != null ? config.themeKey() : "crimson";
        themeBox.getSelectionModel().select(THEMES.getOrDefault(curKey, THEMES.get("crimson")).name());
        themeBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            for (var en : THEMES.entrySet()) {
                if (en.getValue().name().equals(b)) {
                    if (config != null) config.setThemeKey(en.getKey());
                    applyTheme();
                    break;
                }
            }
        });
        ColorPicker colorPicker = new ColorPicker(Color.web(resolveAccent()));
        colorPicker.valueProperty().addListener((o, a, b) -> {
            if (config != null) { config.setThemeKey("custom"); config.setThemeAccent(toHex(b)); }
            applyTheme();
        });
        Label customLbl = new Label("Custom:");
        customLbl.getStyleClass().add("muted");
        HBox themeRow = new HBox(10, themeBox, customLbl, colorPicker);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        Label bgScaleLbl = new Label("Background size");
        bgScaleLbl.getStyleClass().add("card-sub");
        Slider bgScaleSlider = new Slider(120, 1200, config != null ? config.bgScale() : 540);
        bgScaleSlider.setPrefWidth(300);
        bgScaleSlider.valueProperty().addListener((o, a, b) -> {
            if (bgView != null) bgView.setFitHeight(b.doubleValue());
            if (config != null) config.setBgScale(b.doubleValue());
        });
        Label bgOpLbl = new Label("Background visibility");
        bgOpLbl.getStyleClass().add("card-sub");
        Slider bgOpSlider = new Slider(0, 1.0, config != null ? config.bgOpacity() : 0.30);
        bgOpSlider.setPrefWidth(300);
        bgOpSlider.valueProperty().addListener((o, a, b) -> {
            if (bgView != null) bgView.setOpacity(b.doubleValue());
            if (config != null) config.setBgOpacity(b.doubleValue());
        });

        CheckBox animChk = new CheckBox("UI animations (fade between views)");
        animChk.setSelected(config == null || config.animations());
        animChk.setOnAction(e -> { if (config != null) config.setAnimations(animChk.isSelected()); });

        VBox themeSection = new VBox(8, themeLbl, themeRow, bgScaleLbl, bgScaleSlider, bgOpLbl, bgOpSlider, animChk);
        themeSection.setStyle("-fx-padding: 12 0 0 0;");

        // ---- Global defaults ----
        Label gdLbl = new Label("Global defaults");
        gdLbl.getStyleClass().add("card-title-sm");

        Slider defRam = new Slider(1024, 16384, config != null ? config.defaultRamMb() : 4096);
        defRam.setPrefWidth(360);
        defRam.getStyleClass().add("ram-slider");
        defRam.setSnapToTicks(true);
        defRam.setMajorTickUnit(2048);
        defRam.setMinorTickCount(3);
        defRam.setBlockIncrement(512);
        defRam.setShowTickMarks(true);
        Label defRamVal = new Label((config != null ? config.defaultRamMb() : 4096) + " MB");
        defRamVal.getStyleClass().add("ram-value");
        defRamVal.setMinWidth(80);
        defRam.valueProperty().addListener((o, a, b) -> {
            int mb = snapRam(b.doubleValue());
            defRamVal.setText(mb + " MB");
            if (config != null) config.setDefaultRamMb(mb);
        });

        ChoiceBox<Loader> defLoader = new ChoiceBox<>();
        defLoader.getItems().addAll(Loader.VANILLA, Loader.FABRIC, Loader.NEOFORGE, Loader.FORGE);
        try { defLoader.getSelectionModel().select(Loader.valueOf(config != null ? config.defaultLoader() : "FABRIC")); }
        catch (Exception ex) { defLoader.getSelectionModel().select(Loader.FABRIC); }
        defLoader.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (config != null && b != null) config.setDefaultLoader(b.name());
        });

        CheckBox minChk = new CheckBox("Minimize launcher when a game launches");
        minChk.setSelected(config != null && config.minimizeOnPlay());
        minChk.setOnAction(e -> { if (config != null) config.setMinimizeOnPlay(minChk.isSelected()); });
        CheckBox confChk = new CheckBox("Confirm before deleting an instance");
        confChk.setSelected(config == null || config.confirmDelete());
        confChk.setOnAction(e -> { if (config != null) config.setConfirmDelete(confChk.isSelected()); });
        CheckBox neoChk = new CheckBox("Auto-create as NeoForge instead of Forge on 1.20.2+ (Forge has no perf mods there)");
        neoChk.setSelected(config == null || config.autoNeoForge());
        neoChk.setOnAction(e -> { if (config != null) config.setAutoNeoForge(neoChk.isSelected()); });

        VBox gdSection = new VBox(8, gdLbl,
                settingRow("Default RAM", new HBox(10, defRam, defRamVal)),
                settingRow("Default loader", defLoader),
                minChk, confChk, neoChk);
        gdSection.setStyle("-fx-padding: 12 0 0 0;");

        Label tip = new Label("Tip: press Ctrl+K anywhere to open the command palette.");
        tip.getStyleClass().add("muted");

        page.getChildren().addAll(t, tip, nameBox, themeSection, gdSection, cid, dir, cfBox, bgBox);
        ScrollPane settingsScroll = new ScrollPane(page);
        settingsScroll.setFitToWidth(true);
        settingsScroll.getStyleClass().add("scroll-pane");
        content.getChildren().setAll(settingsScroll);
    }

    private void chooseBackground() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose background image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            Path dest = resolveDataDir().resolve("background.png");
            Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (bgView != null) bgView.setImage(new Image(dest.toUri().toString()));
            alert(Alert.AlertType.INFORMATION, "Background updated", "Your image is now the launcher background.");
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Couldn't set background", String.valueOf(ex.getMessage()));
        }
    }

    private void resetBackground() {
        try {
            Files.deleteIfExists(resolveDataDir().resolve("background.png"));
            if (bgView != null) bgView.setImage(loadBackgroundImage());
            alert(Alert.AlertType.INFORMATION, "Background reset", "Back to the default king watermark.");
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Reset failed", String.valueOf(ex.getMessage()));
        }
    }

    private void simplePage(String title, String sub) {
        VBox page = new VBox(12);
        page.getStyleClass().add("content");
        Label s = new Label(sub);
        s.getStyleClass().add("muted");
        s.setWrapText(true);
        page.getChildren().addAll(pageHeader(title.toUpperCase(java.util.Locale.ROOT), title), s);
        content.getChildren().setAll(page);
    }

    // ---- accounts ----

    private void showAccounts() {
        VBox page = new VBox(16);
        page.getStyleClass().add("content");
        VBox t = pageHeader("WHO'S PLAYING", "Accounts");

        VBox list = new VBox(8);
        Runnable refresh = () -> { populateAccounts(list); if (accountChip != null) accountChip.setText(accountLabel()); refreshAvatarBar(); };
        refresh.run();

        Button add = new Button("Add Microsoft Account");
        add.getStyleClass().add("btn-filled");
        VBox codeBox = new VBox(8);
        codeBox.setVisible(false);
        codeBox.setManaged(false);
        add.setOnAction(e -> startLogin(add, codeBox, refresh));

        page.getChildren().addAll(t, list, new HBox(add), codeBox);
        content.getChildren().setAll(page);
    }

    private void populateAccounts(VBox list) {
        list.getChildren().clear();
        if (accountManager == null) return;
        var accounts = accountManager.list();
        if (accounts.isEmpty()) {
            Label none = new Label("No accounts saved yet.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
        var active = activeAccount();
        String activeUuid = active != null ? active.uuid() : "";
        for (var acc : accounts) {
            boolean isActive = acc.uuid().equals(activeUuid);
            StackPane face = avatarTile(acc, isActive);
            Label name = new Label(acc.username());
            name.getStyleClass().add("card-title-sm");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button setActive = new Button(isActive ? "Active" : "Set active");
            setActive.getStyleClass().add(isActive ? "btn-filled" : "btn-outline");
            setActive.setDisable(isActive);
            setActive.setOnAction(e -> { setActiveAccount(acc.uuid()); populateAccounts(list); });
            Button remove = new Button("Remove");
            remove.getStyleClass().add("btn-outline");
            remove.setOnAction(e -> {
                try {
                    accountManager.remove(acc.uuid());
                    if (config != null && acc.uuid().equals(config.activeUuid())) config.setActiveUuid("");
                    populateAccounts(list);
                    if (accountChip != null) accountChip.setText(accountLabel());
                    refreshAvatarBar();
                }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Remove failed", ex.getMessage()); }
            });
            HBox row = new HBox(12, face, name, grow, setActive, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("card");
            list.getChildren().add(row);
        }
    }

    private void startLogin(Button add, VBox codeBox, Runnable refresh) {
        if (clientId == null) {
            alert(Alert.AlertType.WARNING, "No client ID",
                    "Set KANO_CLIENT_ID or put your Azure client ID in login-test/clientid.txt.");
            return;
        }
        if (accountManager == null) { alert(Alert.AlertType.ERROR, "No account store", initError); return; }
        add.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                var session = new MicrosoftAuth().loginWithDeviceCode(clientId,
                        (message, userCode, uri) -> Platform.runLater(() -> showCode(codeBox, message, userCode, uri)));
                accountManager.add(session);
                if (config != null && config.activeUuid().isBlank()) config.setActiveUuid(session.uuid());
                Platform.runLater(() -> { hide(codeBox); refresh.run(); add.setDisable(false);
                        alert(Alert.AlertType.INFORMATION, "Signed in", "Added " + session.username() + "."); });
            } catch (MicrosoftAuth.AppNotApprovedException ex) {
                Platform.runLater(() -> { hide(codeBox); add.setDisable(false);
                        alert(Alert.AlertType.WARNING, "Waiting on approval",
                                "Login worked through every Microsoft step — the app just isn't approved for the "
                                + "Minecraft API yet. Submit https://aka.ms/mce-reviewappid and try again after approval."); });
            } catch (Exception ex) {
                Platform.runLater(() -> { hide(codeBox); add.setDisable(false);
                        alert(Alert.AlertType.ERROR, "Sign-in failed", String.valueOf(ex.getMessage())); });
            }
        }, "ms-login");
        t.setDaemon(true);
        t.start();
    }

    private void showCode(VBox codeBox, String message, String userCode, String uri) {
        Label code = new Label(userCode);
        code.getStyleClass().add("page-title");
        Label msg = new Label(message);
        msg.getStyleClass().add("muted");
        msg.setWrapText(true);
        Button open = new Button("Open sign-in page");
        open.getStyleClass().add("btn-filled");
        open.setOnAction(e -> getHostServices().showDocument(uri));
        Button copy = new Button("Copy code");
        copy.getStyleClass().add("btn-outline");
        copy.setOnAction(e -> { ClipboardContent c = new ClipboardContent(); c.putString(userCode);
                Clipboard.getSystemClipboard().setContent(c); });
        codeBox.getChildren().setAll(code, msg, new HBox(12, open, copy));
        codeBox.setVisible(true);
        codeBox.setManaged(true);
    }

    private void hide(VBox box) { box.getChildren().clear(); box.setVisible(false); box.setManaged(false); }

    // ---- command palette (Ctrl+K) ----

    private record PaletteAction(String icon, String label, String keywords, Runnable run) {}

    private StackPane buildPalette() {
        paletteField = new TextField();
        paletteField.setPromptText("Type a command…  (Esc to close)");
        paletteField.getStyleClass().add("palette-field");

        paletteResults = new VBox(2);
        paletteResults.getStyleClass().add("palette-results");
        ScrollPane scroll = new ScrollPane(paletteResults);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("scroll-pane", "palette-scroll");
        scroll.setPrefHeight(320);

        VBox box = new VBox(10, paletteField, scroll);
        box.getStyleClass().add("palette-box");
        box.setMaxWidth(560);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(box, Pos.TOP_CENTER);
        StackPane.setMargin(box, new Insets(70, 0, 0, 0));

        paletteOverlay = new StackPane(box);
        paletteOverlay.getStyleClass().add("palette-overlay");
        paletteOverlay.setVisible(false);
        paletteOverlay.setManaged(false);
        paletteOverlay.setOnMouseClicked(e -> { if (e.getTarget() == paletteOverlay) closePalette(); });

        paletteField.textProperty().addListener((o, a, b) -> filterPalette(b));
        paletteField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ESCAPE -> { closePalette(); e.consume(); }
                case DOWN -> { movePalette(1); e.consume(); }
                case UP -> { movePalette(-1); e.consume(); }
                case ENTER -> { runSelectedPalette(); e.consume(); }
                default -> { }
            }
        });
        return paletteOverlay;
    }

    private void openPalette() {
        if (paletteOverlay == null) return;
        buildPaletteActions();
        paletteOverlay.setVisible(true);
        paletteOverlay.setManaged(true);
        paletteField.clear();
        filterPalette("");
        paletteField.requestFocus();
    }

    private void closePalette() {
        if (paletteOverlay == null) return;
        paletteOverlay.setVisible(false);
        paletteOverlay.setManaged(false);
    }

    private void buildPaletteActions() {
        paletteActions.clear();
        paletteActions.add(new PaletteAction("◰", "Open Instances", "instance manager games", this::showInstances));
        paletteActions.add(new PaletteAction("❖", "Open Browse Mods", "browse mods modrinth curseforge content", this::showBrowse));
        paletteActions.add(new PaletteAction("▤", "Open Library", "library installed mods", this::showLibrary));
        paletteActions.add(new PaletteAction("⌂", "Open Home", "home dashboard start", this::showHome));
        paletteActions.add(new PaletteAction("☺", "Open Skins", "skins skin changer", this::showSkins));
        paletteActions.add(new PaletteAction("⚙", "Open Settings", "settings options config preferences", this::showSettings));
        paletteActions.add(new PaletteAction("＋", "Create Instance", "new create instance add", this::showCreateDialog));
        paletteActions.add(new PaletteAction("●", "Add Microsoft Account", "account login sign in microsoft", this::showAccounts));
        if (instanceManager != null) {
            for (Instance inst : instanceManager.list()) {
                paletteActions.add(new PaletteAction("▶", "Play " + inst.name(),
                        "play launch run " + inst.version() + " " + inst.loader().display(),
                        () -> onPlay(inst, progress(0.0), new Button("▶"))));
                paletteActions.add(new PaletteAction("◰", "Open " + inst.name(),
                        "instance detail edit settings " + inst.version(),
                        () -> showInstanceDetail(inst)));
            }
        }
    }

    private void filterPalette(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase();
        paletteFiltered.clear();
        for (PaletteAction a : paletteActions) {
            if (q.isEmpty() || a.label().toLowerCase().contains(q) || a.keywords().toLowerCase().contains(q)) {
                paletteFiltered.add(a);
            }
        }
        paletteSelected = 0;
        renderPalette();
    }

    private void renderPalette() {
        paletteResults.getChildren().clear();
        if (paletteFiltered.isEmpty()) {
            Label none = new Label("No matching commands.");
            none.getStyleClass().add("muted");
            none.setPadding(new Insets(10));
            paletteResults.getChildren().add(none);
            return;
        }
        for (int i = 0; i < paletteFiltered.size(); i++) {
            PaletteAction a = paletteFiltered.get(i);
            Label ic = new Label(a.icon());
            ic.getStyleClass().add("palette-icon");
            ic.setMinWidth(26);
            Label tx = new Label(a.label());
            tx.getStyleClass().add("palette-label");
            HBox row = new HBox(10, ic, tx);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("palette-row");
            if (i == paletteSelected) row.getStyleClass().add("palette-row-active");
            final int idx = i;
            row.setOnMouseClicked(e -> { paletteSelected = idx; runSelectedPalette(); });
            row.setOnMouseEntered(e -> { paletteSelected = idx; highlightPalette(); });
            paletteResults.getChildren().add(row);
        }
    }

    private void highlightPalette() {
        for (int i = 0; i < paletteResults.getChildren().size(); i++) {
            Node n = paletteResults.getChildren().get(i);
            n.getStyleClass().remove("palette-row-active");
            if (i == paletteSelected) n.getStyleClass().add("palette-row-active");
        }
    }

    private void movePalette(int delta) {
        if (paletteFiltered.isEmpty()) return;
        int n = paletteFiltered.size();
        paletteSelected = ((paletteSelected + delta) % n + n) % n;
        highlightPalette();
    }

    private void runSelectedPalette() {
        if (paletteSelected < 0 || paletteSelected >= paletteFiltered.size()) return;
        PaletteAction a = paletteFiltered.get(paletteSelected);
        closePalette();
        a.run().run();
    }

    // ---- window resize grip ----

    private Region buildResizeGrip() {
        Region grip = new Region();
        grip.getStyleClass().add("resize-grip");
        grip.setPrefSize(18, 18);
        grip.setMaxSize(18, 18);
        StackPane.setAlignment(grip, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(grip, new Insets(0, 24, 24, 0));
        grip.setOnMouseDragged(e -> {
            double w = e.getScreenX() - stage.getX();
            double h = e.getScreenY() - stage.getY();
            if (w >= stage.getMinWidth()) stage.setWidth(w);
            if (h >= stage.getMinHeight()) stage.setHeight(h);
        });
        return grip;
    }

    /** Dim king-render watermark, contained in the bottom-right corner so it never covers the UI.
     *  No-op if king-bg.png isn't present in resources. */
    private Node buildBackground(Region panel) {
        bgView = new ImageView();
        bgView.setPreserveRatio(true);
        bgView.setFitHeight(config != null ? config.bgScale() : 540);
        bgView.setOpacity(config != null ? config.bgOpacity() : 0.30);
        bgView.setMouseTransparent(true);
        Image img = loadBackgroundImage();
        if (img != null) bgView.setImage(img);
        StackPane holder = new StackPane(bgView);
        StackPane.setAlignment(bgView, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bgView, new Insets(0, 26, 22, 0));
        holder.setMouseTransparent(true);
        holder.setPickOnBounds(false);
        return holder;
    }

    private Image loadBackgroundImage() {
        Path custom = resolveDataDir().resolve("background.png");
        try {
            if (Files.exists(custom)) return new Image(custom.toUri().toString());
        } catch (Exception ignored) {
        }
        var url = getClass().getResource("king-bg.png");
        return url != null ? new Image(url.toExternalForm()) : null;
    }

    /** Apply the launcher theme to a dialog/alert so it matches (no white Modena style). */
    private void styleDialog(Dialog<?> d) {
        DialogPane dp = d.getDialogPane();
        var css = getClass().getResource("kano.css");
        if (css != null && !dp.getStylesheets().contains(css.toExternalForm())) {
            dp.getStylesheets().add(css.toExternalForm());
        }
        if (!dp.getStyleClass().contains("kano-dialog")) dp.getStyleClass().add("kano-dialog");
        d.initOwner(stage);
    }

    // ---- helpers ----

    private void alert(Alert.AlertType type, String title, String body) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        if (type == Alert.AlertType.ERROR) {
            // Selectable text + a one-click Copy All for crash logs.
            TextArea ta = new TextArea(body);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.getStyleClass().add("error-text");
            ta.setPrefColumnCount(46);
            ta.setPrefRowCount(Math.min(16, Math.max(3, body.split("\n").length + 1)));
            Button copy = new Button("📋  Copy All");
            copy.getStyleClass().add("btn-outline");
            copy.setOnAction(ev -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(body);
                Clipboard.getSystemClipboard().setContent(cc);
                copy.setText("Copied ✓");
            });
            VBox boxc = new VBox(8, copy, ta);
            a.getDialogPane().setContent(boxc);
        } else {
            a.setContentText(body);
        }
        styleDialog(a);
        a.showAndWait();
    }

    private static Path resolveDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "KanoLauncher");
        return Path.of(System.getProperty("user.home"), ".kanolauncher");
    }

    private String resolveClientId() {
        String env = System.getenv("KANO_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        Path f = Path.of("login-test", "clientid.txt");
        try {
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) return s;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
