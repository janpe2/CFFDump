/* Copyright 2021 Jani Pehkonen
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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;

/**
 * Main GUI frame.
 */
public class CFFDumpFrame extends JFrame implements ActionListener
{
    private JButton buttonStart;
    private JButton buttonAbout;
    private JButton buttonExit;
    private final JButton buttonChooseFile;
    private final JCheckBox checkBoxAnalyzeCharstrings;
    private final JComboBox comboInputType;
    private final JComboBox comboFilter;
    private final JTextField textFieldOffset;
    private final JTextField textFieldFile;
    private final JTextArea textAreaOutput;
    private File lastUsedDirectory;

    private static final String INPUT_TYPE_PDF_OR_RAW = "PDF or Raw CFF File";
    private static final String INPUT_TYPE_OTF = "OpenType Font File";

    private static final String FILTER_NONE = "None";
    private static final String FILTER_FLATE = "/FlateDecode";
    private static final String FILTER_A85 = "/ASCII85Decode";
    private static final String FILTER_A85_FLATE = "[ /ASCII85Decode /FlateDecode ]";
    private static final String FILTER_HEX = "/ASCIIHexDecode";

    public CFFDumpFrame()
    {
        super("CFFDump");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
        setLayout(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        buttonChooseFile = new JButton("Input File:");
        buttonChooseFile.addActionListener(this);
        add(buttonChooseFile, gc);

        gc = new GridBagConstraints(1, GridBagConstraints.RELATIVE,
            1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        textFieldFile = new JTextField("", 50);
        add(textFieldFile, gc);

        gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            2, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        add(new JLabel("Input File Type:"), gc);
        comboInputType = new JComboBox(
            new String[] {
                INPUT_TYPE_PDF_OR_RAW,
                INPUT_TYPE_OTF
            }
        );
        comboInputType.addActionListener(this);
        add(comboInputType, gc);

        add(new JLabel("PDF Data Filter:"), gc);
        comboFilter = new JComboBox(
            new String[] {
                FILTER_NONE,
                FILTER_FLATE,
                FILTER_A85,
                FILTER_A85_FLATE,
                FILTER_HEX
            }
        );
        add(comboFilter, gc);

        add(new JLabel("Data Start Offset:"), gc);
        textFieldOffset = new JTextField("0", 20);
        add(textFieldOffset, gc);

        checkBoxAnalyzeCharstrings = new JCheckBox("Analyze CharStrings and Subroutines", true);
        add(checkBoxAnalyzeCharstrings, gc);

        gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            2, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.HORIZONTAL, new Insets(2, 5, 2, 5), 0, 0);
        JPanel buttonPanel = createButtonPanel();
        add(buttonPanel, gc);

        gc = new GridBagConstraints(0, GridBagConstraints.RELATIVE,
            2, 1, 1.0, 1.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 0, 0);
        textAreaOutput = new JTextArea();
        textAreaOutput.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textAreaOutput);
        scrollPane.setPreferredSize(new Dimension(300, 300));
        add(scrollPane, gc);


        pack();
    }

    public static void main(String[] args)
    {
        launchGUI();
    }

    public static void launchGUI()
    {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            System.err.println("Unable to load look and feel");
        }
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new CFFDumpFrame().setVisible(true);
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

        } else if (source == comboInputType) {
            Object item = comboInputType.getSelectedItem();
            boolean isPDFOrRaw = INPUT_TYPE_PDF_OR_RAW.equals(item);
            comboFilter.setEnabled(isPDFOrRaw);
            textFieldOffset.setEnabled(isPDFOrRaw);

        } else if (source == buttonExit) {
            System.exit(0);

        } else if (source == buttonAbout) {
            showAbout();

        }
    }

    private void startDump()
    {
        try {
            if (textFieldFile.getText().length() == 0) {
                throw new Exception("Please choose an input file.");
            }

            Object fileType = comboInputType.getSelectedItem();
            String offset = INPUT_TYPE_PDF_OR_RAW.equals(fileType) ? textFieldOffset.getText() : "0";

            CFFDumpDataHandler handler = new CFFDumpDataHandler(
                new File(textFieldFile.getText()),
                INPUT_TYPE_OTF.equals(fileType),
                getFilter(),
                offset, checkBoxAnalyzeCharstrings.isSelected());
            String dump = handler.analyze();
            textAreaOutput.setText(dump);
            textAreaOutput.setCaretPosition(0);

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
            "CFFDump\n" +
            "Copyright 2023 Jani Pehkonen\n" +
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
