package com.kano.launcher;

import com.kano.launcher.auth.MicrosoftAuth;
import com.kano.launcher.core.AccountManager;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.paint.Color;
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
    private Stats stats;
    private Label statsLabel;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        initData();

        BorderPane inner = new BorderPane();
        inner.setTop(buildTitleBar());
        inner.setLeft(buildSidebar());
        inner.setCenter(content);
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
    }

    private void initData() {
        try {
            Path dir = resolveDataDir();
            accountManager = new AccountManager(dir);
            instanceManager = new InstanceManager(dir);
            stats = new Stats(dir);
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

        Button min = winButton("—", false);
        min.setOnAction(e -> stage.setIconified(true));
        Button max = winButton("❐", false);
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        Button close = winButton("✕", true);
        close.setOnAction(e -> Platform.exit());

        HBox bar = new HBox(10, k, title, grow, accountChip, min, max, close);
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
        if (accountManager != null && !accountManager.list().isEmpty()) {
            return "● " + accountManager.list().get(0).username();
        }
        return "Sign in";
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
        ramSlider.setPrefWidth(260);
        ramSlider.setBlockIncrement(512);
        ramSlider.setMajorTickUnit(2048);
        ramSlider.setMinorTickCount(3);
        ramSlider.setSnapToTicks(true);
        ramSlider.setShowTickMarks(true);
        Label ramVal = new Label(inst.ramMb() + " MB");
        ramVal.getStyleClass().add("card-sub");
        ramVal.setMinWidth(72);
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

        VBox settingsContent = new VBox(12, profLbl, swatches,
                settingRow("Name", nameF),
                settingRow("RAM", ramBox),
                settingRow("Resolution", resBox),
                settingRow("", fullF),
                settingRow("Extra JVM args", jvmF),
                saveSettings, new Region(), del);
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
                String player = (accountManager != null && !accountManager.list().isEmpty())
                        ? accountManager.list().get(0).username() : "Player";
                long sessionStart = System.currentTimeMillis();
                Process proc = GameLauncher.launch(inst, vd, javaExe, dataDir, player, fabric);
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
                    Files.move(f, target);
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
            if (reset) { offset[0] = 0; results.getChildren().clear(); }
            int startOffset = offset[0];
            loadMore.setDisable(true);
            loadMore.setText("Loading…");
            Thread t = new Thread(() -> {
                try {
                    ModrinthClient client = new ModrinthClient();
                    var res = client.search(query, inst.version(), loaderFacet, type, sort, startOffset, 20);
                    // Fallback: Modrinth's search index occasionally returns nothing — if you typed a
                    // name, try a direct project lookup by slug so you can still find/install it.
                    ModrinthClient.Hit fb = null;
                    if (reset && res.hits().isEmpty() && query != null && !query.isBlank()) {
                        try {
                            var pd = client.project(query.trim().toLowerCase().replaceAll("\\s+", "-"));
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

        Button perfBtn = new Button("⚡ Performance Pack");
        perfBtn.getStyleClass().add("btn-outline");
        perfBtn.setOnAction(e -> installPerfPack(instPick.getValue(), perfBtn));

        Label typeLbl = new Label("Type:");
        typeLbl.getStyleClass().add("card-sub");
        Label sortLbl = new Label("Sort:");
        sortLbl.getStyleClass().add("card-sub");
        HBox bar1 = new HBox(10, instPick, q);
        bar1.setAlignment(Pos.CENTER_LEFT);
        HBox bar2 = new HBox(10, typeLbl, typeBox, sortLbl, sortBox, perfBtn);
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
                int n = ModInstaller.install(new ModrinthClient(), hit.projectId(), inst.version(),
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
                var pd = new ModrinthClient().project(hit.projectId());
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
        Button open = new Button("Open on Modrinth");
        open.getStyleClass().add("btn-outline");
        String slug = pd.slug().isBlank() ? hit.slug() : pd.slug();
        open.setOnAction(e -> getHostServices().showDocument("https://modrinth.com/" + type + "/" + slug));
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
        page.getChildren().addAll(t, cid, dir);
        content.getChildren().setAll(page);
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
        Runnable refresh = () -> { populateAccounts(list); if (accountChip != null) accountChip.setText(accountLabel()); };
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
        for (var acc : accounts) {
            Label name = new Label(acc.username());
            name.getStyleClass().add("card-title-sm");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.getStyleClass().add("btn-outline");
            remove.setOnAction(e -> {
                try { accountManager.remove(acc.uuid()); populateAccounts(list); if (accountChip != null) accountChip.setText(accountLabel()); }
                catch (Exception ex) { alert(Alert.AlertType.ERROR, "Remove failed", ex.getMessage()); }
            });
            HBox row = new HBox(12, name, grow, remove);
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
        var url = getClass().getResource("king-bg.png");
        if (url == null) return null;
        ImageView iv = new ImageView(new Image(url.toExternalForm()));
        iv.setPreserveRatio(true);
        iv.setFitHeight(540);
        iv.setOpacity(0.30);
        iv.setMouseTransparent(true);
        StackPane holder = new StackPane(iv);
        StackPane.setAlignment(iv, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(iv, new Insets(0, 26, 22, 0));
        holder.setMouseTransparent(true);
        holder.setPickOnBounds(false);
        return holder;
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
            // Selectable + copyable text (Ctrl+C / Ctrl+A) for crash logs.
            TextArea ta = new TextArea(body);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.getStyleClass().add("error-text");
            ta.setPrefColumnCount(46);
            ta.setPrefRowCount(Math.min(16, Math.max(3, body.split("\n").length + 1)));
            a.getDialogPane().setContent(ta);
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
