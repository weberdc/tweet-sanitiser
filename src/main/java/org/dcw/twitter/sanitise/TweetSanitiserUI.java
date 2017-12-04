/*
 * Copyright 2017 Derek Weber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dcw.twitter.sanitise;

import com.google.common.collect.Lists;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterObjectFactory;

import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>Creates a GUI for fetching and sanitising a single Tweet at a time, displaying
 * the JSON for the Tweet, and a valid JSON subset of the Tweet. The text from the
 * sanitised field can be copied by clicking on the text. Fields which are retained
 * in the subset are passed in via the constructor and can be edited in the UI.</p>
 */
public class TweetSanitiserUI extends JPanel {

    private static final Font TEXT_FONT = new Font("Courier New", Font.PLAIN, 10);
    private static final Font ID_URL_FONT = new Font("Arial", Font.PLAIN, 16);

    private final boolean debug;
    private boolean errorState;

    private JTextField tweetIdText;
    private JTextArea fullJsonTextArea;
    private JTextArea sanitisedJsonTextArea;
    private JCheckBox skipMediaCheckbox;
    private JTextArea ftkTextArea;


    /**
     * Constructor
     *
     * @param twitter The reference to Twitter's API, provided by {@link org.dcw.twitter.sanitise.TweetSanitiserCmdLine}.
     * @param debug If true, print out debug statements.
     */
    public TweetSanitiserUI(
        final Twitter twitter,
        final List<String> cleanFields,
        final boolean debug
    ) {
        this.debug = debug;
        if (debug)
            System.out.println(
                TweetSanitiser.str(TweetSanitiser.buildFieldStructure(cleanFields), 0)
            );
        buildUI(twitter, cleanFields);
    }

    /**
     * Builds the UI.
     *
     * @param twitter The Twitter API instance, used by an event handler.
     * @param cleanFields The initial list of fields to keep in the stripped JSON.
     */
    private void buildUI(final Twitter twitter, final List<String> cleanFields) {

        // STRUCTURE
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(5,5,5,5));


        // Row 1: ID text box and Get button
        int row = 1;
        final JLabel tweetIdLabel = new JLabel();
        tweetIdLabel.setText("ID or URL:");
        tweetIdLabel.setToolTipText("Paste or drag your tweet/status ID or URL here");
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 0, 5);
        this.add(tweetIdLabel, gbc);

        tweetIdText = new JTextField();
        tweetIdText.setToolTipText("Paste or drag your tweet/status ID or URL here");
        tweetIdText.setFont(ID_URL_FONT);
        tweetIdLabel.setLabelFor(tweetIdText);
        tweetIdText.setDragEnabled(true);
        // dragging text to the field _replaces_ the text, rather than inserting it where it's dropped
        tweetIdText.setTransferHandler(new DropToReplaceTransferHandler());

        // example tweet
        tweetIdText.setText("https://twitter.com/ABCaustralia/status/927673379238313984");

        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = row - 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        this.add(tweetIdText, gbc);

        final JButton clearButton = new JButton(UIManager.getIcon("InternalFrame.closeIcon"));
        clearButton.setToolTipText("Clear the ID field");

        gbc = new GridBagConstraints();
        gbc.gridx = 2;
        gbc.gridy = row - 1;
        gbc.insets = new Insets(0, 5, 0, 0);
        this.add(clearButton, gbc);

        final JButton fetchButton = new JButton("Fetch");
        fetchButton.setToolTipText("Fetch the JSON for this tweet, put it below and in the copy buffer");
        fetchButton.requestFocus();

        gbc = new GridBagConstraints();
        gbc.gridx = 3;
        gbc.gridy = row - 1;
        gbc.insets = new Insets(0, 2, 0, 0);
        this.add(fetchButton, gbc);


        // Row 2: titled panel with scrollable JSON text area and copy button
        row++;
        fullJsonTextArea = new JTextArea(); // Row 2.1
        final JPanel fullJsonPanel = makeTitledPanel(" Full JSON ");

        // configure the text area and make it scrollable
        fullJsonTextArea.setFont(TEXT_FONT);
        fullJsonTextArea.setEditable(true);
        fullJsonTextArea.setLineWrap(true);
        fullJsonTextArea.setWrapStyleWord(true);
        fullJsonTextArea.setToolTipText("Click to select all. Responds to Ctrl-V and Ctrl-C like a normal text area");

        final JScrollPane jsonScrollPane1 = new JScrollPane(fullJsonTextArea);
        jsonScrollPane1.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        jsonScrollPane1.setPreferredSize(new Dimension(250, 250));

        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        fullJsonPanel.add(jsonScrollPane1, gbc);

        // Row 2.2: sneak in a paste-from-clipboard button within the group panel
        /* NB I can't rely on selecting within the text area and hitting Ctrl-V, because
         * when you click within the text area, you copy the text area to the global copy
         * buffer, thus getting rid of what you're trying to paste in. Sigh.
         */
        final JButton pasteFromClipboardButton = new JButton("Paste from clipboard");

        gbc = new GridBagConstraints();
        gbc.gridy = 1; // fullJsonTextArea is gridy == 0
        gbc.fill = GridBagConstraints.HORIZONTAL;
        fullJsonPanel.add(pasteFromClipboardButton, gbc);

        // add full JSON titled panel to outer
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row - 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.gridwidth = 4;
        gbc.insets = new Insets(5, 0, 5, 0);
        this.add(fullJsonPanel, gbc);


        // Row 3: skip-media checkbox
        row++;
        skipMediaCheckbox = new JCheckBox("Skip images & videos?");
        skipMediaCheckbox.setSelected(false);
        skipMediaCheckbox.setToolTipText("Select this to remove the Tweet's media field");

        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row - 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 4;
        this.add(skipMediaCheckbox, gbc);


        // Row 4: titled panel with scrollable JSON text area and copy button
        row++;
        sanitisedJsonTextArea = new JTextArea();
        final JPanel sanitisedJsonPanel = makeTitledPanel(
            "<html> Sanitised JSON (<font color=\"red\">Beware</font>: Clicking will copy text) </html>"
        );

        // configure the text area and make it scrollable
        sanitisedJsonTextArea.setFont(TEXT_FONT);
        sanitisedJsonTextArea.setEditable(false);
        sanitisedJsonTextArea.setLineWrap(true);
        sanitisedJsonTextArea.setWrapStyleWord(true);

        final JScrollPane jsonScrollPane = new JScrollPane(sanitisedJsonTextArea);
        jsonScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        jsonScrollPane.setPreferredSize(new Dimension(250, 250));

        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        sanitisedJsonPanel.add(jsonScrollPane, gbc);

        sanitisedJsonPanel.setToolTipText(makeExplanatoryTooltip());
        sanitisedJsonTextArea.setToolTipText(
            "<html><b><font color=\"red\">Beware</font></b>: Clicking the text will select and copy it</html>"
        );

        // add the fields to keep label
        final JLabel ftkLabel = new JLabel("Fields to keep:");

        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 1;
        sanitisedJsonPanel.add(ftkLabel, gbc);

        // add the fields to keep text area
        ftkTextArea = new JTextArea(
            cleanFields.stream().collect(Collectors.joining(", "))
        );
        ftkTextArea.setFont(TEXT_FONT.deriveFont(TEXT_FONT.getSize() + 2.0f));
        ftkTextArea.setLineWrap(true);
        ftkTextArea.setWrapStyleWord(true);

        final JScrollPane ftkScrollPane = new JScrollPane(
            ftkTextArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );

        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weighty = 0.3; // use a bit of space but let the stripped JSON have most of it
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridwidth = 1;
        sanitisedJsonPanel.add(ftkScrollPane, gbc);


        // add stripped JSON titled panel to outer
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row - 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.gridwidth = 4;
        gbc.insets = new Insets(5, 0, 5, 0);
        this.add(sanitisedJsonPanel, gbc);


        // BEHAVIOUR
        // clear the text field
        clearButton.addActionListener(e -> tweetIdText.setText(""));

        // clicking the tweet ID/URL field selects all the text in it
        tweetIdText.addMouseListener(new SelectAllTextOnClickListener(tweetIdText));

        // clicking the full JSON text area selects all the text in it
        fullJsonTextArea.addMouseListener(new SelectAllTextOnClickListener(fullJsonTextArea));

        // update the stripped JSON anytime the checkbox is changed
        skipMediaCheckbox.addActionListener(e -> updateSanitisedJson(fullJsonTextArea.getText()));

        // paste from clipboard to the full json text area
        pasteFromClipboardButton.addActionListener(e -> {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            try {
                // grab the text from the clipboard, safely
                final String hopefullyJSON = (String) clipboard.getData(DataFlavor.stringFlavor);
                if (hopefullyJSON != null) {
                    // update the text areas
                    updateTextArea(fullJsonTextArea, hopefullyJSON);
                    updateSanitisedJson(hopefullyJSON);
                }
            } catch (UnsupportedFlavorException | IOException e1) {
                e1.printStackTrace();
                JOptionPane.showMessageDialog(
                    fullJsonTextArea,
                    "Failed to paste:\n" + e1.getMessage(),
                    "Paste Error",
                    JOptionPane.WARNING_MESSAGE
                );
            }
        });
        // override fullJsonTextArea's paste action to update the sanitised json area too
        final InputMap fullJsonTextAreaInputMap = fullJsonTextArea.getInputMap();

        // - Ctrl-v to paste clipboard
        final KeyStroke ctrlVKey = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_MASK);
        fullJsonTextAreaInputMap.put(ctrlVKey, "paste-and-update-sanitised-panel");

        // - new overriding paste action (not quite as pithy as I had hoped
        final ActionMap fullJsonTextAreaActionMap = fullJsonTextArea.getActionMap();
        fullJsonTextAreaActionMap.put(
            "paste-and-update-sanitised-panel",
            new ProxyTextAction(
                "paste-and-update-sanitised-panel",
                (TextAction) fullJsonTextAreaActionMap.get(DefaultEditorKit.pasteAction),
                new TextAction("update-sanitised-text-area") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        updateSanitisedJson(fullJsonTextArea.getText());
                    }
                }
            )
        );

        // click text area -> selects all text and puts it on the copy clipboard
        final MouseAdapter copyAction = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                sanitisedJsonTextArea.setSelectionStart(0);
                final String text = sanitisedJsonTextArea.getText();
                sanitisedJsonTextArea.setSelectionEnd(text.length());
                if (debug) System.out.println("Pushing '" + text + "' to the clipboard");
                pushToClipboard(text);
            }
        };
        sanitisedJsonTextArea.addMouseListener(copyAction);
        sanitisedJsonPanel.addMouseListener(copyAction);

        // fetch button - fetch tweet JSON and update text areas
        fetchButton.addActionListener(e -> {
            final String txt = tweetIdText.getText();
            // Be nice, do this on a background thread
            new Thread(() -> {
                try {
                    errorState = false;
                    // allow the user to paste in a full status URL or just the ID
                    // e.g. "https://twitter.com/KathViner/status/919984305559961600" or "919984305559961600"
                    final String idStr = txt.contains("/")
                        ? txt.substring(txt.lastIndexOf('/') + 1).trim()
                        : txt.trim();

                    System.out.println("Retrieving tweet: " + idStr);

                    final long tweetID = Long.parseLong(idStr);
                    final Status tweet = twitter.showStatus(tweetID);
                    final String rawJSON = TwitterObjectFactory.getRawJSON(tweet);

                    updateTextArea(fullJsonTextArea, rawJSON);
                    updateSanitisedJson(rawJSON);
                    pushToClipboard(rawJSON);

                } catch (NumberFormatException nfe) {
                    String errMsg = "ERROR: \"" + txt + "\" is not a valid tweet ID or URL.";
                    SwingUtilities.invokeLater(() -> fullJsonTextArea.setText(errMsg));
                    errorState = true;
                } catch (TwitterException twerr) {
                    String errMsg = "ERROR: Failed to retrieve tweet:\n" + twerr.getErrorMessage();
                    SwingUtilities.invokeLater(() -> fullJsonTextArea.setText(errMsg));
                    twerr.printStackTrace();
                    errorState = true;
                }
            }).start();
        });

        // fields to keep text area, commit as you type
        ftkTextArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                updateSanitisedJson(fullJsonTextArea.getText());
            }
        });
    }

    /**
     * Updates the sanitised JSON area, taking into account whether images are wanted
     * and using the fields specified in {@link #ftkTextArea} to decide which fields
     * to keep. Does nothing when the input is invalid. May be called from the Swing
     * thread or off of it.
     *
     * @param rawJSON The raw JSON to consider.
     */
    private void updateSanitisedJson(final String rawJSON) {
        if (errorState || rawJSON == null || rawJSON.length() == 0) return;

        final List<String> fieldsToKeep = Stream.of(ftkTextArea.getText().split("\n"))
            .map(l -> l.contains(",") ? Stream.of(l.split("[, ]")) : Stream.of(l))
            .flatMap(x -> x)
            .map(String::trim)
            .collect(Collectors.toList());

        final List<String> fieldsToKeepNoMedia = Lists.newArrayList(fieldsToKeep);
        fieldsToKeepNoMedia.remove("entities.media"); // media-safe list

        final Map<String, Object> toKeep =
            TweetSanitiser.buildFieldStructure(skipMediaCheckbox.isSelected() ? fieldsToKeepNoMedia : fieldsToKeep);

        final String sanitisedJSON = TweetSanitiser.sanitiseJSON(rawJSON, toKeep);

        updateTextArea(sanitisedJsonTextArea, sanitisedJSON);
    }

    /**
     * Updates a {@link JTextArea} safely, from on or off the UI thread.
     *
     * @param textArea The text area to update.
     * @param text The text to put into it.
     */
    private void updateTextArea(final JTextArea textArea, final String text) {
        SwingUtilities.invokeLater(() -> {
            textArea.setText(text);
            textArea.setCaretPosition(0); // scroll back to top
        });
    }

    /**
     * Creates a String to use as a tooltip to explain which fields are retained
     * in the sanitised version of the JSON.
     *
     * @return An explanatory tooltip.
     */
    private String makeExplanatoryTooltip() {
        return "<html>The JSON is filtered for only the fields below.<br>" +
            "<strong>NB</strong> <code>entities.media</code>" +
            " is not included if images are skipped.<br></html>";
    }

    /**
     * Creates a {@link JPanel} with a titled border and a {@link GridBagLayout}.
     *
     * @param title The title to use for the panel's border.
     * @return A panel with a titled border.
     */
    private JPanel makeTitledPanel(final String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(title),
            BorderFactory.createEmptyBorder(0,5,5,5))
        );
        return panel;
    }


    /**
     * Put the provided text into the global copy buffer.
     *
     * @param text The text to put into the copy buffer.
     */
    private void pushToClipboard(final String text) {
        final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    /**
     * Wraps two {@link TextAction}s and pretends they're one.
     */
    private class ProxyTextAction extends TextAction {
        private final TextAction action1;
        private final TextAction action2;

        ProxyTextAction(final String name, final TextAction firstAction, final TextAction secondAction) {
            super(name);
            action1 = firstAction;
            action2 = secondAction;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            action1.actionPerformed(e);
            action2.actionPerformed(e);
        }
    }

    private class SelectAllTextOnClickListener extends MouseAdapter {
        private final JTextComponent textComponent;

        SelectAllTextOnClickListener(JTextComponent textComponent) {
            this.textComponent = textComponent;
        }

        @Override
        public void mouseClicked(MouseEvent e)  {
            textComponent.setSelectionStart(0);
            textComponent.setSelectionEnd(textComponent.getText().length());
        }
    }
}
