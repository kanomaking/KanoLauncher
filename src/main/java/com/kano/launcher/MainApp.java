package com.kano.launcher;

import com.kano.launcher.auth.MicrosoftAuth;
import com.kano.launcher.core.AccountManager;
import com.kano.launcher.core.Instance;
import com.kano.launcher.core.GameInstaller;
import com.kano.launcher.core.InstanceManager;
import com.kano.launcher.core.Loader;
import com.kano.launcher.core.VersionDetail;
import com.kano.launcher.core.VersionManifest;

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
import javafx.scene.control.TextField;
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
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        initData();

        BorderPane inner = new BorderPane();
        inner.setTop(buildTitleBar());
        inner.setLeft(buildSidebar());
        inner.setCenter(content);

        StackPane panel = new StackPane();
        panel.getStyleClass().add("app-panel");
        Node bg = buildBackground(panel);
        if (bg != null) panel.getChildren().add(bg);
        panel.getChildren().add(inner);

        StackPane shell = new StackPane(panel, buildResizeGrip());
        shell.getStyleClass().add("app-shell");

        showInstances();

        Scene scene = new Scene(shell, 1180, 720);
        scene.setFill(Color.TRANSPARENT);
        var css = getClass().getResource("kano.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("KanoLauncher");
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
                nav("❖", "Browse Modpacks", this::showBrowse),
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
                homeCard("❖", "Browse Modpacks", "Find mods on Modrinth", this::showBrowse),
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
        play.setOnAction(e -> onInstall(inst, bar));

        VBox info = new VBox(6, titleRow, meta, sub, bar);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(18, iconTile, info);
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane card = new StackPane(row, editIcon(inst));
        card.getStyleClass().addAll("card", "card-featured");
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
        play.setOnAction(e -> onInstall(inst, bar));
        row.getChildren().add(play);

        StackPane card = new StackPane(row, editIcon(inst));
        card.getStyleClass().add("card");
        card.setPrefWidth(420);
        return card;
    }

    private StackPane iconTile(Instance inst, boolean small) {
        Label g = new Label(switch (inst.loader()) {
            case FABRIC, QUILT -> "⛏"; // pickaxe-ish
            case FORGE, NEOFORGE -> "⚒"; // hammer & pick
            default -> "■"; // block
        });
        g.getStyleClass().add("icon-glyph");
        StackPane tile = new StackPane(g);
        tile.getStyleClass().add("icon-tile");
        if (small) tile.getStyleClass().add("icon-tile-sm");
        return tile;
    }

    private Button editIcon(Instance inst) {
        Button edit = new Button("✎");
        edit.getStyleClass().add("edit-btn");
        edit.setOnAction(e -> showEditDialog(inst));
        StackPane.setAlignment(edit, Pos.TOP_RIGHT);
        return edit;
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

    private void onInstall(Instance inst, ProgressBar bar) {
        if (instanceManager == null) { alert(Alert.AlertType.ERROR, "Error", initError); return; }
        Thread t = new Thread(() -> {
            try {
                Platform.runLater(() -> bar.setProgress(0));
                VersionManifest vm = versionManifest;
                if (vm == null) { vm = VersionManifest.fetch(); versionManifest = vm; }
                VersionManifest.VersionEntry entry = vm.find(inst.version());
                if (entry == null) throw new RuntimeException("Version " + inst.version() + " not in manifest.");
                VersionDetail vd = VersionDetail.fetch(entry);
                GameInstaller gi = new GameInstaller(resolveDataDir());
                gi.install(vd, (d, tot, label) -> {
                    if (d % 25 == 0 || d == tot) {
                        double frac = tot == 0 ? 0 : (double) d / tot;
                        Platform.runLater(() -> bar.setProgress(frac));
                    }
                });
                Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    alert(Alert.AlertType.INFORMATION, "Files ready",
                            "Downloaded " + inst.version() + " (" + inst.loader().display() + ").\n\n"
                            + "Booting the game is the final step — coming next.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Install failed", String.valueOf(ex.getMessage())));
            }
        }, "install-" + inst.dirName());
        t.setDaemon(true);
        t.start();
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
                instanceManager.update(new Instance(inst.name(), inst.version(), inst.loader(),
                        inst.dirName(), inst.createdEpoch(), inst.lastPlayedEpoch(), mb));
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

    private void showLibrary() { selectNav(navByName.get("Library")); simplePage("Library", "Your installed content will live here."); }
    private void showBrowse() { selectNav(navByName.get("Browse Modpacks")); simplePage("Browse Modpacks", "Modrinth browsing comes with the content integration."); }
    private void showSkins() { selectNav(navByName.get("Skins")); simplePage("Skins", "Skin changer is a later milestone (needs app approval)."); }

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
        iv.setFitHeight(600);
        iv.setOpacity(0.22);
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
        a.setContentText(body);
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
