package com.kano.launcher;

import com.kano.launcher.auth.MicrosoftAuth;
import com.kano.launcher.core.AccountManager;
import com.kano.launcher.core.Config;
import com.kano.launcher.core.ContentSource;
import com.kano.launcher.core.CurseForgeClient;
import com.kano.launcher.core.Instance;
import com.kano.launcher.core.FabricSupport;
import com.kano.launcher.core.GameInstaller;
import com.kano.launcher.core.GameLauncher;
import com.kano.launcher.core.InstanceManager;
import com.kano.launcher.core.JreProvider;
import com.kano.launcher.core.Loader;
import com.kano.launcher.core.ModInstaller;
import com.kano.launcher.core.ModrinthClient;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
import javafx.stage.StageStyle;
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

    private static final String VERSION = "v1.0.0";
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
    private Label accountChip;
    private HBox avatarBar;
    private Stats stats;
    private Label statsLabel;
    private Config config;
    private ContentSource browseSource = new ModrinthClient();
    private ImageView bgView;

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

        StackPane panel = new StackPane();
        panel.getStyleClass().add("app-panel");
        Node bg = buildBackground(panel);
        if (bg != null) panel.getChildren().add(bg);
        panel.getChildren().add(inner);

        StackPane shell = new StackPane(panel, buildResizeGrip());
        shell.getStyleClass().add("app-shell");

        // Maximize: drop the glow padding + rounding so the window truly fills the screen (no edge gap).
        stage.maximizedProperty().addListener((o, was, now) -> {
            if (now) {
                shell.setStyle("-fx-padding: 0;");
                if (!panel.getStyleClass().contains("app-panel-flat")) panel.getStyleClass().add("app-panel-flat");
            } else {
                shell.setStyle("");
                panel.getStyleClass().remove("app-panel-flat");
            }
        });

        showInstances();

        Scene scene = new Scene(shell, 1180, 720);
        scene.setFill(Color.TRANSPARENT);
        var css = getClass().getResource("kano.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN), this::openPalette);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("KanoLauncher");
        // Window / taskbar icon = the king (prefers a square avatar if present, else the render).
        var iconUrl = getClass().getResource("king-avatar.png");
        if (iconUrl == null) iconUrl = getClass().getResource("king-bg.png");
        if (iconUrl != null) stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.show();

        checkForUpdate();
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
        Label title = new Label("KanoLauncher " + VERSION);
        title.getStyleClass().add("title-text");

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
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
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
        Label word = new Label("KanoLauncher");
        word.getStyleClass().add("brand-word");
        HBox brand = new HBox(10, logo, word);
        brand.getStyleClass().add("brand-row");

        side.getChildren().add(brand);
        side.getChildren().addAll(
                nav("⌂", "Home", this::showHome),
                nav("▤", "Library", this::showLibrary),
                nav("◰", "Instances", this::showInstances),
                nav("❖", "Browse Mods", this::showBrowse),
                nav("☺", "Skins", this::showSkins),
                nav("⚙", "Settings", this::showSettings));
        return side;
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
        VBox page = new VBox(18);
        page.getStyleClass().add("content");
        Label title = new Label("Home");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Jump to anything:");
        sub.getStyleClass().add("muted");

        FlowPane grid = new FlowPane(16, 16);
        grid.getChildren().addAll(
                homeCard("◰", "Instances", "Create and launch your games", this::showInstances),
                homeCard("▤", "Library", "Your installed content", this::showLibrary),
                homeCard("❖", "Browse Mods", "Find mods on Modrinth", this::showBrowse),
                homeCard("☺", "Skins", "Change your skin", this::showSkins),
                homeCard("●", "Accounts", "Manage Microsoft accounts", this::showAccounts),
                homeCard("⚙", "Settings", "Launcher options", this::showSettings));

        page.getChildren().addAll(title, sub, grid);
        content.getChildren().setAll(page);
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

        Label title = new Label("Instance Manager");
        title.getStyleClass().add("page-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Button create = new Button("Create Instance");
        create.getStyleClass().add("btn-outline");
        create.setOnAction(e -> showCreateDialog());
        Button importBtn = new Button("Import");
        importBtn.getStyleClass().add("btn-filled");
        importBtn.setOnAction(e -> alert(Alert.AlertType.INFORMATION, "Import",
                "Import (.mrpack) comes with the Modrinth integration."));
        HBox header = new HBox(14, title, grow, create, importBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(16);
        List<Instance> all = instanceManager == null ? List.of() : instanceManager.list();
        if (all.isEmpty()) {
            Label empty = new Label("No instances yet. Click “Create Instance” to make your first one.");
            empty.getStyleClass().add("muted");
            body.getChildren().add(empty);
        } else {
            body.getChildren().add(featuredCard(all.get(0)));
            if (all.size() > 1) {
                FlowPane grid = new FlowPane(16, 16);
                for (int i = 1; i < all.size(); i++) grid.getChildren().add(smallCard(all.get(i)));
                body.getChildren().add(grid);
            }
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        page.getChildren().addAll(header, scroll);
        content.getChildren().setAll(page);
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

        StackPane card = new StackPane(row, editIcon(inst));
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
        Label sub = new Label("Last played: " + lastPlayed(inst));
        sub.getStyleClass().add("card-sub");
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

        StackPane card = new StackPane(row, editIcon(inst));
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
        tile.setStyle("-fx-background-color: " + blockColor(inst.iconOrDefault()) + ";");
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

        StackPane icon = new StackPane();
        icon.getStyleClass().add("icon-tile");
        icon.setStyle("-fx-background-color: " + blockColor(inst.iconOrDefault()) + ";");
        Label name = new Label(inst.name());
        name.getStyleClass().add("page-title");
        Label meta = new Label(metaLine(inst) + "    •    Last played: " + lastPlayed(inst));
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

        Button saveSettings = new Button("Save Settings");
        saveSettings.getStyleClass().add("btn-filled");
        saveSettings.setOnAction(e -> {
            try {
                String nm = nameF.getText().isBlank() ? inst.name() : nameF.getText().trim();
                int ramMb = snapRam(ramSlider.getValue());
                int w = widthF.getText().isBlank() ? 0 : Integer.parseInt(widthF.getText().trim());
                int h = heightF.getText().isBlank() ? 0 : Integer.parseInt(heightF.getText().trim());
                instanceManager.update(inst.withSettings(nm, ramMb, w, h, fullF.isSelected(), jvmF.getText().trim()));
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

        VBox settingsContent = new VBox(12, profLbl, swatches,
                settingRow("Name", nameF),
                settingRow("RAM", ramBox),
                settingRow("Resolution", resBox),
                settingRow("", fullF),
                settingRow("Extra JVM args", jvmF),
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
                tab("Settings", settingsScroll));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        page.getChildren().addAll(header, tabs);
        content.getChildren().setAll(page);
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
        VBox listBox = new VBox(8);
        populateFolderItems(inst, folder, listBox);
        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(10, header, sp);
        box.setPadding(new Insets(12));
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
        onPlay(inst, bar, play, null);
    }

    private void onPlay(Instance inst, ProgressBar bar, Button play, String quickWorld) {
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

                // 3. Launch (offline mode — works before app approval).
                var activeAcc = activeAccount();
                String player = activeAcc != null ? activeAcc.username() : "Player";
                long sessionStart = System.currentTimeMillis();
                Process proc = GameLauncher.launch(inst, vd, javaExe, dataDir, player, fabric, quickWorld);
                Platform.runLater(() -> play.getStyleClass().remove("loading"));

                // Mark last-played and refresh the view.
                instanceManager.update(inst.withLastPlayed(System.currentTimeMillis()));
                Platform.runLater(this::showInstances);

                // Surface an early crash with the log tail; silent on normal exit.
                int code = proc.waitFor();
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
                    ? ids.size() + " versions available"
                    : "Offline — showing a short fallback list");
        };
        snapshots.setOnAction(e -> fillVersions.run());
        fillVersions.run();

        ChoiceBox<Loader> loader = new ChoiceBox<>();
        loader.getItems().addAll(Loader.VANILLA, Loader.FABRIC);
        loader.getSelectionModel().selectFirst();

        VBox box = new VBox(10,
                new Label("Name"), name,
                new Label("Version"), ver, snapshots, verCount,
                new Label("Loader"), loader);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialog(d);

        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                String nm = name.getText().isBlank() ? (loader.getValue().display() + " " + ver.getValue()) : name.getText();
                instanceManager.create(nm, ver.getValue(), loader.getValue());
                showInstances();
            } catch (Exception ex) {
                alert(Alert.AlertType.ERROR, "Create failed", ex.getMessage());
            }
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
        Label title = new Label("Library — Installed Mods");
        title.getStyleClass().add("page-title");

        var insts = instanceManager == null ? List.<Instance>of() : instanceManager.list();
        if (insts.isEmpty()) {
            Label none = new Label("No instances yet.");
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
            Label none = new Label("Nothing here yet — use “+ Add”.");
            none.getStyleClass().add("muted");
            list.getChildren().add(none);
            return;
        }
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
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);
        }
    }

    // ---- worlds ----

    private Tab worldsTab(Instance inst) {
        Label hint = new Label("Single-player worlds in this instance. Play jumps straight in.");
        hint.getStyleClass().add("muted");
        VBox listBox = new VBox(8);
        populateWorlds(inst, listBox);
        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(10, hint, sp);
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
            Button del = new Button("Delete");
            del.getStyleClass().add("btn-outline");
            del.setOnAction(e -> {
                try { deleteDir(w); populateWorlds(inst, list); }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Delete failed", ex.getMessage()); }
            });

            HBox row = new HBox(10, info, wbar, playW, openF, del);
            row.setAlignment(Pos.CENTER_LEFT);
            StackPane card = new StackPane(row);
            card.getStyleClass().add("card");
            list.getChildren().add(card);
        }
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
        Label title = new Label("Browse Content");
        title.getStyleClass().add("page-title");

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
        perfBtn.getStyleClass().add("btn-outline");
        perfBtn.setOnAction(e -> installPerfPack(instPick.getValue(), perfBtn));

        Label srcLbl = new Label("Source:");
        srcLbl.getStyleClass().add("card-sub");
        Label typeLbl = new Label("Type:");
        typeLbl.getStyleClass().add("card-sub");
        Label sortLbl = new Label("Sort:");
        sortLbl.getStyleClass().add("card-sub");
        HBox bar1 = new HBox(10, instPick, q);
        bar1.setAlignment(Pos.CENTER_LEFT);
        HBox bar2 = new HBox(10, srcLbl, sourceBox, typeLbl, typeBox, sortLbl, sortBox, perfBtn);
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
            default -> null; // modpack needs .mrpack extraction — not yet
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

    private static final String[] PERF_MODS =
            {"sodium", "lithium", "ferrite-core", "modernfix", "entityculling", "immediatelyfast", "dynamic-fps"};

    private void installPerfPack(Instance inst, Button btn) {
        btn.setDisable(true);
        btn.setText("Installing…");
        Thread t = new Thread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            Path modsDir = instanceManager.instanceDir(inst).resolve("mods");
            ModrinthClient client = new ModrinthClient();
            for (String slug : PERF_MODS) {
                try {
                    ok += ModInstaller.install(client, slug, inst.version(), "fabric", modsDir, m -> {});
                } catch (Exception ex) {
                    failed.add(slug);
                }
            }
            int total = ok;
            Platform.runLater(() -> {
                btn.setDisable(false);
                btn.setText("⚡ Performance Pack");
                alert(Alert.AlertType.INFORMATION, "Performance Pack",
                        "Added " + total + " file(s) to " + inst.name() + "."
                        + (failed.isEmpty() ? "" : "\nSkipped (no compatible version): " + String.join(", ", failed)));
            });
        }, "perf-pack");
        t.setDaemon(true);
        t.start();
    }

    private void setupOptifine(Instance inst) {
        if (inst.loader() != Loader.FABRIC) {
            alert(Alert.AlertType.WARNING, "Fabric needed",
                    "OptiFine on Fabric uses OptiFabric — make this a Fabric instance first.");
            return;
        }
        try {
            Path modsDir = instanceManager.instanceDir(inst).resolve("mods");
            Files.createDirectories(modsDir);
            openFolder(modsDir);
            alert(Alert.AlertType.INFORMATION, "OptiFine setup",
                    "OptiFine needs TWO jars in this mods folder (just opened):\n\n"
                    + "1) OptiFabric — for " + inst.version() + ", from CurseForge (search \"OptiFabric\").\n"
                    + "2) OptiFine — for " + inst.version() + ", from optifine.net.\n\n"
                    + "Drop both .jars here, then Play.\n\n"
                    + "Heads up: brand-new versions (e.g. 1.21.11) usually aren't supported yet — use 1.21.1 or "
                    + "1.20.1 for OptiFine. OptiFine conflicts with Sodium, so disable Sodium in this instance.");
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "OptiFine setup failed", String.valueOf(ex.getMessage()));
        }
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
        Label t = new Label("Settings");
        t.getStyleClass().add("page-title");
        Label cid = new Label("Client ID: " + (clientId != null ? clientId : "not set"));
        cid.getStyleClass().add("muted");
        Label dir = new Label("Data folder: " + resolveDataDir());
        dir.getStyleClass().add("muted");

        Label cfLbl = new Label("CurseForge API key");
        cfLbl.getStyleClass().add("card-title-sm");
        Label cfHelp = new Label("Lets you browse CurseForge as a second source. Get a free key at "
                + "console.curseforge.com → API Keys. Stored locally only.");
        cfHelp.getStyleClass().add("muted");
        cfHelp.setWrapText(true);
        TextField cfKey = new TextField(config != null ? config.curseforgeApiKey() : "");
        cfKey.setPromptText("paste your CurseForge API key");
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

        page.getChildren().addAll(t, cid, dir, cfBox, bgBox);
        content.getChildren().setAll(page);
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
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        Label s = new Label(sub);
        s.getStyleClass().add("muted");
        page.getChildren().addAll(t, s);
        content.getChildren().setAll(page);
    }

    // ---- accounts ----

    private void showAccounts() {
        VBox page = new VBox(16);
        page.getStyleClass().add("content");
        Label t = new Label("Accounts");
        t.getStyleClass().add("page-title");

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
        bgView.setFitHeight(540);
        bgView.setOpacity(0.30);
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
