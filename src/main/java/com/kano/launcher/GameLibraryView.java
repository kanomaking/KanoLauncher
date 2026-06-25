package com.kano.launcher;

import com.kano.launcher.core.games.Game;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** The unified game hub: a grid of game tiles (cover + name) plus an "Add game" tile. */
public final class GameLibraryView {
    private final List<Game> games;
    private final Consumer<Game> onOpen;
    private final Runnable onAddGame;

    public GameLibraryView(List<Game> games, Consumer<Game> onOpen, Runnable onAddGame) {
        this.games = games;
        this.onOpen = onOpen;
        this.onAddGame = onAddGame;
    }

    public Region build() {
        FlowPane grid = new FlowPane(18, 18);
        grid.setPadding(new Insets(20));
        for (Game g : games) grid.getChildren().add(tile(g));
        grid.getChildren().add(addTile());

        Label header = new Label("YOUR GAMES");
        header.getStyleClass().add("page-eyebrow");
        Label title = new Label("Library");
        title.getStyleClass().add("page-title");
        VBox top = new VBox(2, header, title);
        top.setPadding(new Insets(20, 20, 0, 20));

        VBox wrap = new VBox(top, grid);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        return scroll;
    }

    private Region tile(Game g) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPrefWidth(180);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(10));

        StackPane cover = new StackPane();
        cover.setPrefSize(150, 200);
        Color accent = g.accentHex() != null ? safeColor(g.accentHex()) : Color.web("#6E7681");
        cover.setBackground(new Background(new BackgroundFill(accent.deriveColor(0, 1, 0.5, 0.35), new CornerRadii(8), Insets.EMPTY)));
        if (g.coverArtPath() != null && new File(g.coverArtPath()).exists()) {
            ImageView iv = new ImageView(new Image(new File(g.coverArtPath()).toURI().toString(), 150, 200, true, true));
            cover.getChildren().add(iv);
        } else {
            Label initial = new Label(g.name() == null || g.name().isEmpty() ? "?" : g.name().substring(0, 1).toUpperCase());
            initial.setStyle("-fx-font-size: 48px; -fx-font-weight: bold; -fx-text-fill: white;");
            cover.getChildren().add(initial);
        }

        Label name = new Label(g.name());
        name.getStyleClass().add("card-title-sm");
        name.setWrapText(true);
        name.setAlignment(Pos.CENTER);

        card.getChildren().addAll(cover, name);
        card.setOnMouseClicked(e -> onOpen.accept(g));
        return card;
    }

    private Region addTile() {
        VBox card = new VBox();
        card.getStyleClass().add("card");
        card.setPrefSize(180, 240);
        card.setAlignment(Pos.CENTER);
        Label plus = new Label("+ Add game");
        plus.getStyleClass().add("card-title-sm");
        card.getChildren().add(plus);
        card.setOnMouseClicked(e -> onAddGame.run());
        return card;
    }

    private static Color safeColor(String hex) {
        try { return Color.web(hex); } catch (Exception e) { return Color.web("#6E7681"); }
    }
}
