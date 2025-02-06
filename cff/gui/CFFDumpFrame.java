/* Copyright 2025 Jani Pehkonen
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

package cff.gui;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import static cff.CFFDump.DUMPER_VERSION;

/**
 * Main GUI frame.
 */
public class CFFDumpFrame extends JFrame implements ActionListener
{
    private JButton buttonStart;
    private JButton buttonAbout;
    private JButton buttonExit;
    private JButton buttonClearOutput;
    private JButton buttonChooseFile;
    private JCheckBox checkBoxAnalyzeCharstrings;
    private JCheckBox checkBoxExplainHintMaskBits;
    private JCheckBox checkBoxDumpUnusedSubrs;
    private JComboBox comboFilter;
    private JTextField textFieldOffset;
    private JTextField textFieldFile;
    private JTextArea textAreaOutput;
    private JRadioButton radioFileRawCFF;
    private JRadioButton radioFileCFFPDF;
    private JRadioButton radioFileOTF;
    private JRadioButton radioFileType1PDF;
    private JRadioButton radioFileRawType1;
    private File lastUsedDirectory;
    private String fontType = "cff";

    private static final String FILTER_NONE = "None";
    private static final String FILTER_FLATE = "/FlateDecode";
    private static final String FILTER_A85 = "/ASCII85Decode";
    private static final String FILTER_A85_FLATE = "[ /ASCII85Decode /FlateDecode ]";
    private static final String FILTER_HEX = "/ASCIIHexDecode";

    private static final String COPYRIGHT_YEAR = "2025";

    public CFFDumpFrame(String fontFile)
    {
        super("CFFDump");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
        setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints(0, 0,
            2, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        add(createFilePanel(fontFile), gc);

        gc = new GridBagConstraints(0, 1,
            1, 1, 0.5, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        add(createFileTypeRadioGroup(), gc);

        gc = new GridBagConstraints(1, 1,
            1, 1, 0.5, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        add(createRightPanel(), gc);

        gc = new GridBagConstraints(0, 2,
            2, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(8, 5, 8, 5), 0, 0);
        JPanel buttonPanel = createButtonPanel();
        add(buttonPanel, gc);

        gc = new GridBagConstraints(0, 3,
            2, 1, 1.0, 1.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 0, 0);
        textAreaOutput = new JTextArea();
        textAreaOutput.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textAreaOutput);
        scrollPane.setPreferredSize(new Dimension(300, 300));
        add(scrollPane, gc);


        pack();
    }

    private JPanel createFilePanel(String fontFile)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints(0, 0,
            1, 1, 0.0, 0.0, GridBagConstraints.CENTER,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);

        panel.add(new JLabel("Input File:"), gc);

        gc = new GridBagConstraints(1, 0,
            1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        textFieldFile = new JTextField(fontFile != null ? fontFile : "", 50);
        panel.add(textFieldFile, gc);

        gc = new GridBagConstraints(2, 0,
            1, 1, 0.0, 0.0, GridBagConstraints.CENTER,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        buttonChooseFile = new JButton("Browse");
        buttonChooseFile.addActionListener(this);
        panel.add(buttonChooseFile, gc);

        return panel;
    }

    private JPanel createRightPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(1, 0, 2, 0), 0, 0);

        panel.add(new JLabel("PDF Data Filter:"), gc);
        comboFilter = new JComboBox(
            new String[] {
                FILTER_NONE,
                FILTER_FLATE,
                FILTER_A85,
                FILTER_A85_FLATE,
                FILTER_HEX
            }
        );
        panel.add(comboFilter, gc);

        panel.add(new JLabel("Data Start Offset:"), gc);
        textFieldOffset = new JTextField("0", 20);
        panel.add(textFieldOffset, gc);

        checkBoxAnalyzeCharstrings = new JCheckBox("Analyze CharStrings and Subroutines", true);
        panel.add(checkBoxAnalyzeCharstrings, gc);
        checkBoxExplainHintMaskBits = new JCheckBox("Explain Mask Bits of CFF 'hintmask'", false);
        panel.add(checkBoxExplainHintMaskBits, gc);
        checkBoxDumpUnusedSubrs = new JCheckBox("Dump Unused Subroutines in CFF", false);
        panel.add(checkBoxDumpUnusedSubrs, gc);

        comboFilter.setEnabled(false);
        textFieldOffset.setEnabled(false);

        return panel;
    }

    private JPanel createFileTypeRadioGroup()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0);
        panel.setBorder(BorderFactory.createTitledBorder("Input File Type"));
        ButtonGroup group = new ButtonGroup();

        radioFileRawCFF = getRadioButton("Raw CFF", true, group, panel, gc);
        radioFileCFFPDF = getRadioButton("CFF Embedded in PDF", false, group, panel, gc);
        radioFileOTF = getRadioButton("OpenType CFF", false, group, panel, gc);
        radioFileType1PDF = getRadioButton("Type1 Embedded in PDF", false, group, panel, gc);
        radioFileRawType1 = getRadioButton("Type1 Raw, PFA, or PFB", false, group, panel, gc);

        return panel;
    }

    private JRadioButton getRadioButton(String text, boolean selected, ButtonGroup group,
    JPanel panel, GridBagConstraints gc)
    {
        JRadioButton button = new JRadioButton(text, selected);
        group.add(button);
        panel.add(button, gc);
        button.addActionListener(this);
        return button;
    }

    public static void main(String[] args)
    {
        launchGUI(null);
    }

    public static void launchGUI(final String fontFile)
    {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            System.err.println("Unable to load look and feel");
        }
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new CFFDumpFrame(fontFile).setVisible(true);
            }
        });
    }

    @Override
    public void actionPerformed(ActionEvent e)
    {
        Object source = e.getSource();

        if (source == buttonStart) {
            startDump();

        } else if (source == buttonChooseFile) {
            JFileChooser dialog = new JFileChooser(lastUsedDirectory);
            dialog.setDialogTitle("Analyze CFF Font");
            dialog.setAcceptAllFileFilterUsed(true);
            if (dialog.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                lastUsedDirectory = dialog.getCurrentDirectory();
                textFieldFile.setText(dialog.getSelectedFile().toString());
            }

        } else if (source == buttonExit) {
            System.exit(0);

        } else if (source == buttonClearOutput) {
            textAreaOutput.setText("");

        } else if (source == buttonAbout) {
            showAbout();

        } else if (source == radioFileRawCFF) {
            fontType = "cff";
            textFieldOffset.setEnabled(false);
            comboFilter.setEnabled(false);

        } else if (source == radioFileCFFPDF) {
            fontType = "cff";
            textFieldOffset.setEnabled(true);
            comboFilter.setEnabled(true);

        } else if (source == radioFileOTF) {
            fontType = "cff";
            textFieldOffset.setEnabled(false);
            comboFilter.setEnabled(false);

        } else if (source == radioFileType1PDF) {
            fontType = "type1";
            textFieldOffset.setEnabled(true);
            comboFilter.setEnabled(true);

        } else if (source == radioFileRawType1) {
            fontType = "type1";
            textFieldOffset.setEnabled(false);
            comboFilter.setEnabled(false);
        }
    }

    private void startDump()
    {
        try {
            if (textFieldFile.getText().length() == 0) {
                throw new Exception("Please choose an input file.");
            }

            String offset = textFieldOffset.isEnabled() ? textFieldOffset.getText() : "0";
            offset = "".equals(offset) ? "0" : offset;
            boolean isOTF = radioFileOTF.isSelected();

            CFFDumpDataHandler handler = new CFFDumpDataHandler(
                new File(textFieldFile.getText()),
                fontType,
                isOTF,
                getFilter(),
                offset,
                checkBoxAnalyzeCharstrings.isSelected(),
                checkBoxExplainHintMaskBits.isSelected(),
                checkBoxDumpUnusedSubrs.isSelected()
            );
            CFFDumpDataHandler.DumpResult result = handler.analyze();
            textAreaOutput.setText(result.dump);
            textAreaOutput.setCaretPosition(0);
            if (result.hasErrors) {
                JOptionPane.showMessageDialog(this,
                    "There were one or more errors. See end of dump.", "CFF Errors",
                    JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message != null) {
                message = message + " (" + ex.getClass().getName() + ")";
            } else {
                message = ex.toString();
            }
            textAreaOutput.setText(message);
        }
    }

    private CFFDumpDataHandler.PDFFilter getFilter()
    {
        Object filter = comboFilter.getSelectedItem();

        if (FILTER_A85.equals(filter)) {
            return CFFDumpDataHandler.PDFFilter.ASCII85;
        }
        if (FILTER_FLATE.equals(filter)) {
            return CFFDumpDataHandler.PDFFilter.FLATE;
        }
        if (FILTER_A85_FLATE.equals(filter)) {
            return CFFDumpDataHandler.PDFFilter.ASCII85_FLATE;
        }
        if (FILTER_HEX.equals(filter)) {
            return CFFDumpDataHandler.PDFFilter.ASCIIHEX;
        }
        return CFFDumpDataHandler.PDFFilter.NONE;
    }

    private JPanel createButtonPanel()
    {
        JPanel buttonPanel = new JPanel();

        buttonStart = new JButton("Analyze");
        buttonPanel.add(buttonStart);
        buttonStart.addActionListener(this);

        buttonClearOutput = new JButton("Clear");
        buttonPanel.add(buttonClearOutput);
        buttonClearOutput.addActionListener(this);

        buttonAbout = new JButton("About");
        buttonPanel.add(buttonAbout);
        buttonAbout.addActionListener(this);

        buttonExit = new JButton("Exit");
        buttonPanel.add(buttonExit);
        buttonExit.addActionListener(this);

        return buttonPanel;
    }

    private void showAbout()
    {
        String text =
            "CFFDump " + DUMPER_VERSION + "\n" +
            "Copyright " + COPYRIGHT_YEAR + " Jani Pehkonen\n" +
            "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
            "you may not use this file except in compliance with the License.\n" +
            "You may obtain a copy of the License at\n" +
            "\n" +
            "    http://www.apache.org/licenses/LICENSE-2.0\n" +
            "\n" +
            "Unless required by applicable law or agreed to in writing, software\n" +
            "distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
            "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
            "See the License for the specific language governing permissions and\n" +
            "limitations under the License.";

        JOptionPane.showMessageDialog(this, text, "About CFFDump",
            JOptionPane.INFORMATION_MESSAGE);
    }

}
