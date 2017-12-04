package org.dcw.twitter.sanitise;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.text.JTextComponent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * Custom {@link TransferHandler} which causes the text of the target
 * field to be replaced, rather than allowing dropped text to be inserted
 * where it's dropped.
 *
 * @see <a href="https://stackoverflow.com/questions/7976972/swing-jtextfield-dnd-replace-the-existing-text-with-the-imported-text">drag and drop - Swing JTextfield DnD replace the existing text with the imported text - Stack Overflow</a>
 */
public class DropToReplaceTransferHandler extends TransferHandler {
    public int getSourceActions(JComponent c) {
        return COPY_OR_MOVE;
    }

    public Transferable createTransferable(JComponent c) {
        return new StringSelection(((JTextComponent) c).getSelectedText());
    }

    public void exportDone(JComponent c, Transferable t, int action) {
        if (action == MOVE) ((JTextComponent) c).replaceSelection("");
    }

    public boolean canImport(TransferSupport ts) {
        return ts.getComponent() instanceof JTextComponent;
    }

    public boolean importData(TransferSupport ts) {
        try {
            ((JTextComponent) ts.getComponent()).setText(
                (String) ts.getTransferable().getTransferData(DataFlavor.stringFlavor)
            );
            return true;
        } catch (UnsupportedFlavorException | IOException e) {
            return false;
        }
    }
}