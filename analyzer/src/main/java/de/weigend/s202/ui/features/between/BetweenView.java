/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.features.between;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewAnchor;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * WFX side-panel view that answers what lies between two elements: the whole of
 * the view as a tree on the left, the using side, and again on the right, the
 * used side. What is selected on one side greys out on the other what it has
 * nothing to do with. The dependencies themselves are shown by whoever listens,
 * see {@link #setOnQuestion}.
 */
public final class BetweenView implements View {

    public static final String VIEW_ID = "s202-from-to";

    /** Receives the question and its answer. */
    @FunctionalInterface
    interface QuestionHandler {
        /** {@code from} or {@code to} may be {@code null}, for everything else. */
        void asked(String from, String to, List<DependencyEdge> edges);
    }

    private record Question(String from, String to) {
        private static final Question NONE = new Question(null, null);
    }

    private final BorderPane root = new BorderPane();
    private final ElementTree fromTree = new ElementTree("From: search");
    private final ElementTree toTree = new ElementTree("To: search");
    private final Label answer = new Label();
    private final Button back = button("Back", "The question before this one", this::goBack);
    private final Button users = button("◀ Users", "Who uses the left side? It becomes the right one",
            () -> ask(new Question(null, question().from()), true));
    private final Button used = button("Uses ▶", "What does the right side use? It becomes the left one",
            () -> ask(new Question(question().to(), null), true));
    private final Button swap = button("⇄", "Ask for the opposite direction",
            () -> ask(new Question(question().to(), question().from()), true));
    private final Button clear = button("Clear", "Select nothing", () -> ask(Question.NONE, true));

    private final Deque<Question> history = new ArrayDeque<>();
    private Between between = Between.NOTHING;
    private Question shown = Question.NONE;
    private QuestionHandler handler = (from, to, edges) -> { };

    public BetweenView() {
        root.getStyleClass().add("between-view");
        answer.getStyleClass().add("between-answer");
        HBox buttons = new HBox(6, back, users, swap, used, clear);
        buttons.setAlignment(Pos.CENTER_LEFT);
        FlowPane bar = new FlowPane(6, 3, buttons, answer);
        bar.setPadding(new Insets(3, 6, 3, 6));
        SplitPane split = new SplitPane(fromTree, toTree);
        split.setOrientation(Orientation.HORIZONTAL);
        root.setTop(bar);
        root.setCenter(split);
        fromTree.setOnSelected(fqn -> ask(new Question(fqn, shown.to()), true));
        toTree.setOnSelected(fqn -> ask(new Question(shown.from(), fqn), true));
        showAnswer();
    }

    private static Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("between-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }

    /** Shows the elements of a view; {@code null} empties the panel. */
    void setElements(ArchitectureNode rootNode, Between newBetween) {
        between = newBetween;
        history.clear();
        Question before = shown;
        shown = Question.NONE;
        fromTree.setElements(rootNode, newBetween.external());
        toTree.setElements(rootNode, newBetween.external());
        // The same view with another plan: the question stays.
        show(new Question(known(before.from()), known(before.to())));
    }

    private String known(String element) {
        return element != null && between.knows(element) ? element : null;
    }

    void setOnQuestion(QuestionHandler newHandler) {
        handler = newHandler;
    }

    /** Asks from outside; {@code null} leaves a side as it is. The answer is given as if the user had asked here. */
    void ask(String from, String to) {
        ask(new Question(from == null ? shown.from() : known(from), to == null ? shown.to() : known(to)), true);
    }

    /** Shows a question that was asked and answered elsewhere. */
    void follow(String from, String to) {
        if (known(from) != null && known(to) != null) {
            ask(new Question(from, to), false);
        }
    }

    String from() {
        return shown.from();
    }

    String to() {
        return shown.to();
    }

    private Question question() {
        return shown;
    }

    private void goBack() {
        if (!history.isEmpty()) {
            show(history.pop());
            tell();
        }
    }

    private void ask(Question question, boolean tell) {
        if (!Objects.equals(question, shown)) {
            history.push(shown);
            show(question);
        }
        if (tell) {
            tell();
        }
    }

    private void tell() {
        handler.asked(shown.from(), shown.to(), between.edges(shown.from(), shown.to()));
    }

    private void show(Question question) {
        shown = question;
        fromTree.select(question.from());
        toTree.select(question.to());
        fromTree.setConnected(between.usersOf(question.to()));
        toTree.setConnected(between.usedBy(question.from()));
        showAnswer();
    }

    private void showAnswer() {
        back.setDisable(history.isEmpty());
        users.setDisable(shown.from() == null);
        used.setDisable(shown.to() == null);
        swap.setDisable(shown.equals(Question.NONE));
        clear.setDisable(shown.equals(Question.NONE));
        if (shown.equals(Question.NONE)) {
            answer.setText("Select what uses on the left, what is used on the right, or both");
            return;
        }
        int count = between.edges(shown.from(), shown.to()).size();
        answer.setText(label(shown.from()) + " → " + label(shown.to()) + ": "
                + (count == 0 ? "no dependency" : count == 1 ? "1 class dependency" : count + " class dependencies"));
    }

    /** A package outside the analysis is known by its full name only. */
    private String label(String element) {
        return between.isExternal(element) && !Between.EXTERNAL.equals(element) ? element : name(element);
    }

    static String name(String element) {
        return element == null ? "everything else" : element.substring(element.lastIndexOf('.') + 1);
    }

    // ===== View interface =====

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "From / To";
    }

    @Override
    public String getToolTipInfo() {
        return "What lies between two packages or classes";
    }

    /** A tab beside the Dependencies panel, so that the Dependency Details stay in sight. */
    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    @Override
    public ViewAnchor getDefaultAnchor() {
        return ViewAnchor.EDITOR;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.50;
    }
}
