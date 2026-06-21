package com.kano.launcher;

import com.kano.launcher.auth.MicrosoftAuth;
import com.kano.launcher.core.AccountManager;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * KanoLauncher main window — Phase 1.
 * Sidebar navigation (Play / Accounts / Settings) + swappable content. The Accounts screen runs
 * the real Microsoft device-code login; until the Azure app is approved it surfaces the approval
 * gate cleanly instead of crashing.
 */
public class MainApp extends Application {

    private final StackPane content = new StackPane();
    private AccountManager accountManager;
    private String clientId;
    private String initError;

    @Override
    public void start(Stage stage) {
        initData();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setLeft(buildSidebar());
        root.setCenter(content);
        showPlay();

        Scene scene = new Scene(root, 1100, 700);
        var css = getClass().getResource("kano.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("KanoLauncher");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();
    }

    private void initData() {
        try {
            accountManager = new AccountManager(resolveDataDir());
        } catch (Exception e) {
            initError = "Could not open account store: " + e.getMessage();
        }
        clientId = resolveClientId();
    }

    // ---- sidebar / navigation ----

    private VBox buildSidebar() {
        VBox side = new VBox(6);
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(220);
        side.setPadding(new Insets(18, 12, 18, 12));

        Label brand = new Label("KANO");
        brand.getStyleClass().add("brand");
        side.getChildren().addAll(brand, spacer(18),
                navButton("Play", this::showPlay),
                navButton("Accounts", this::showAccounts),
                navButton("Settings", this::showSettings));
        return side;
    }

    private Button navButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void showPlay() {
        VBox pane = page("Play");
        boolean hasAccount = accountManager != null && !accountManager.list().isEmpty();
        Label status = new Label(hasAccount
                ? "Instance manager coming next — then this launches the game."
                : "No account yet. Add one in Accounts to get started.");
        status.getStyleClass().add("muted");

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        Button play = new Button("PLAY");
        play.getStyleClass().add("play-button");
        play.setDisable(true); // enabled once instances + launch are wired

        pane.getChildren().addAll(status, grow, new HBox(play));
        content.getChildren().setAll(pane);
    }

    private void showSettings() {
        VBox pane = page("Settings");
        Label info = new Label("Client ID: " + (clientId != null ? clientId : "not set"));
        info.getStyleClass().add("muted");
        Label dir = new Label("Data folder: " + resolveDataDir());
        dir.getStyleClass().add("muted");
        pane.getChildren().addAll(info, dir);
        content.getChildren().setAll(pane);
    }

    // ---- accounts ----

    private void showAccounts() {
        VBox pane = page("Accounts");

        VBox list = new VBox(8);
        Runnable refresh = () -> populateAccounts(list);
        refresh.run();

        Button add = new Button("Add Microsoft Account");
        add.getStyleClass().add("play-button");

        VBox codeBox = new VBox(8);
        codeBox.setVisible(false);
        codeBox.setManaged(false);

        add.setOnAction(e -> startLogin(add, codeBox, refresh));

        if (initError != null) {
            Label err = new Label(initError);
            err.getStyleClass().add("muted");
            pane.getChildren().add(err);
        }
        pane.getChildren().addAll(list, new HBox(add), codeBox);
        content.getChildren().setAll(pane);
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
            name.getStyleClass().add("page-title");
            name.setStyle("-fx-font-size: 15px;");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.getStyleClass().add("nav-button");
            remove.setOnAction(e -> {
                try {
                    accountManager.remove(acc.uuid());
                    populateAccounts(list);
                } catch (Exception ex) {
                    alert(Alert.AlertType.ERROR, "Remove failed", ex.getMessage());
                }
            });
            HBox row = new HBox(12, name, grow, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
    }

    private void startLogin(Button add, VBox codeBox, Runnable refresh) {
        if (clientId == null) {
            alert(Alert.AlertType.WARNING, "No client ID",
                    "Set the KANO_CLIENT_ID environment variable, or put your Azure client ID in "
                    + "login-test/clientid.txt.");
            return;
        }
        if (accountManager == null) {
            alert(Alert.AlertType.ERROR, "No account store", initError);
            return;
        }
        add.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                MicrosoftAuth auth = new MicrosoftAuth();
                var session = auth.loginWithDeviceCode(clientId,
                        (message, userCode, uri) ->
                                Platform.runLater(() -> showCode(codeBox, message, userCode, uri)));
                accountManager.add(session);
                Platform.runLater(() -> {
                    hide(codeBox);
                    refresh.run();
                    add.setDisable(false);
                    alert(Alert.AlertType.INFORMATION, "Signed in", "Added " + session.username() + ".");
                });
            } catch (MicrosoftAuth.AppNotApprovedException ex) {
                Platform.runLater(() -> {
                    hide(codeBox);
                    add.setDisable(false);
                    alert(Alert.AlertType.WARNING, "Waiting on approval",
                            "Your login worked through every Microsoft step — the app just isn't "
                            + "approved for the Minecraft API yet.\n\nSubmit https://aka.ms/mce-reviewappid "
                            + "and try again after approval.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    hide(codeBox);
                    add.setDisable(false);
                    alert(Alert.AlertType.ERROR, "Sign-in failed", String.valueOf(ex.getMessage()));
                });
            }
        }, "ms-login");
        t.setDaemon(true);
        t.start();
    }

    private void showCode(VBox codeBox, String message, String userCode, String uri) {
        Label msg = new Label(message);
        msg.getStyleClass().add("muted");
        msg.setWrapText(true);

        Label code = new Label(userCode);
        code.getStyleClass().add("page-title");

        Button open = new Button("Open sign-in page");
        open.getStyleClass().add("play-button");
        open.setOnAction(e -> getHostServices().showDocument(uri));

        Button copy = new Button("Copy code");
        copy.getStyleClass().add("nav-button");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(userCode);
            Clipboard.getSystemClipboard().setContent(c);
        });

        codeBox.getChildren().setAll(code, msg, new HBox(12, open, copy));
        codeBox.setVisible(true);
        codeBox.setManaged(true);
    }

    private void hide(VBox box) {
        box.getChildren().clear();
        box.setVisible(false);
        box.setManaged(false);
    }

    // ---- helpers ----

    private VBox page(String title) {
        VBox pane = new VBox(16);
        pane.getStyleClass().add("content");
        pane.setPadding(new Insets(28));
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        pane.getChildren().add(t);
        return pane;
    }

    private Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    private void alert(Alert.AlertType type, String title, String body) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(body);
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
