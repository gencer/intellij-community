package org.jetbrains.plugins.ipnb.editor.panels;

import com.google.common.collect.Lists;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.ipnb.editor.IpnbEditorUtil;
import org.jetbrains.plugins.ipnb.editor.IpnbFileEditor;
import org.jetbrains.plugins.ipnb.editor.panels.code.IpnbCodePanel;
import org.jetbrains.plugins.ipnb.format.IpnbFile;
import org.jetbrains.plugins.ipnb.format.IpnbParser;
import org.jetbrains.plugins.ipnb.format.cells.*;
import org.jetbrains.plugins.ipnb.format.cells.output.IpnbOutputCell;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class IpnbFilePanel extends JPanel implements Scrollable, DataProvider {
  private IpnbFile myIpnbFile;

  private Project myProject;
  @NotNull private IpnbFileEditor myParent;
  @NotNull private final IpnbFileEditor.CellSelectionListener myListener;

  private final List<IpnbEditablePanel> myIpnbPanels = Lists.newArrayList();

  private IpnbEditablePanel mySelectedCell;
  private IpnbEditablePanel myBufferPanel;
  private int myIncrement = 10;
  private int myInitialSelection = 0;
  private int myInitialPosition = 0;

  public IpnbFilePanel(@NotNull final Project project, @NotNull final IpnbFileEditor parent, @NotNull final VirtualFile vFile,
                       @NotNull final IpnbFileEditor.CellSelectionListener listener) {
    super(new VerticalFlowLayout(VerticalFlowLayout.TOP, 100, 5, true, false));
    myProject = project;
    myParent = parent;
    myListener = listener;
    setBackground(IpnbEditorUtil.getBackground());

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        try {
          myIpnbFile = IpnbParser.parseIpnbFile(vFile);
          if (myIpnbFile.getCells().isEmpty()) {
            final IpnbCodeCell cell = new IpnbCodeCell("python", new String[]{""}, null, new ArrayList<IpnbOutputCell>());
            myIpnbFile.addCell(cell, 0);
          }
          layoutFile();
          addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              updateCellSelection(e);
            }
          });
          setFocusable(true);

        }
        catch (IOException e) {
          Messages.showErrorDialog(project, e.getMessage(), "Can't open " + vFile.getPath());
        }
      }
    });

    UIUtil.requestFocus(this);
  }

  public List<IpnbEditablePanel> getIpnbPanels() {
    return myIpnbPanels;
  }

  private void layoutFile() {
    final List<IpnbCell> cells = myIpnbFile.getCells();
    for (IpnbCell cell : cells) {
      addCellToPanel(cell);
    }

    if (myInitialSelection >= 0 && myIpnbPanels.size() > myInitialSelection) {
      final IpnbEditablePanel toSelect = myIpnbPanels.get(myInitialSelection);
      setSelectedCell(toSelect);
      myParent.getScrollPane().getViewport().setViewPosition(new Point(0, myInitialPosition));
    }
    add(createEmptyPanel());
  }

  private void addCellToPanel(IpnbCell cell) {
    IpnbEditablePanel panel;
    if (cell instanceof IpnbCodeCell) {
      panel = new IpnbCodePanel(myProject, myParent, (IpnbCodeCell)cell);
      add(panel);
      myIpnbPanels.add(panel);
    }
    else if (cell instanceof IpnbMarkdownCell) {
      panel = new IpnbMarkdownPanel((IpnbMarkdownCell)cell);
      addComponent(panel);
    }
    else if (cell instanceof IpnbHeadingCell) {
      panel = new IpnbHeadingPanel((IpnbHeadingCell)cell);
      addComponent(panel);
    }
    else {
      throw new UnsupportedOperationException(cell.getClass().toString());
    }
  }

  public void createAndAddCell(boolean below) {
    removeAll();
    final IpnbCodeCell cell = new IpnbCodeCell("python", new String[]{""}, null, new ArrayList<IpnbOutputCell>());
    final IpnbCodePanel codePanel = new IpnbCodePanel(myProject, myParent, cell);

    addCell(codePanel, below);
  }

  private void addCell(@NotNull final IpnbEditablePanel panel, boolean below) {
    removeAll();
    final IpnbEditablePanel selectedCell = getSelectedCell();
    int index = myIpnbPanels.indexOf(selectedCell);
    if (below) {
      index += 1;
    }
    final IpnbEditableCell cell = panel.getCell();
    myIpnbFile.addCell(cell, index);
    myIpnbPanels.add(index, panel);

    for (IpnbPanel comp : myIpnbPanels) {
      add(comp);
    }
    add(createEmptyPanel());

    setSelectedCell(panel);
    requestFocus();
    revalidate();
    repaint();
  }

  public void cutCell() {
    myBufferPanel = getSelectedCell();
    if (myBufferPanel == null) return;
    deleteSelectedCell();
  }

  public void moveCell(boolean down) {
    final IpnbEditablePanel selectedCell = getSelectedCell();
    if (selectedCell == null) return;

    final int index = getSelectedIndex();
    int siblingIndex = down ? index + 1 : index - 1;

    if (myIpnbPanels.size() <= siblingIndex && down) {
      return;
    }
    if (siblingIndex < 0 && !down) {
      return;
    }

    if (down) {
      deleteSelectedCell();
      addCell(selectedCell, true);
    }
    else {
      final IpnbEditablePanel siblingPanel = myIpnbPanels.get(siblingIndex);
      deleteCell(siblingPanel);
      addCell(siblingPanel, true);
      setSelectedCell(selectedCell);
    }
  }

  public void deleteSelectedCell() {
    final IpnbEditablePanel cell = getSelectedCell();
    deleteCell(cell);
  }

  private void deleteCell(@NotNull final IpnbEditablePanel cell) {
    selectNextOrPrev(cell);
    final int index = myIpnbPanels.indexOf(cell);
    if (index < 0) return;
    myIpnbPanels.remove(index);
    myIpnbFile.removeCell(index);

    remove(cell);
    if (myIpnbPanels.isEmpty()) {
      createAndAddCell(true);
    }
  }

  public void copyCell() {
    myBufferPanel = getSelectedCell();
  }

  public void pasteCell() {
    if (myBufferPanel == null) return;
    removeAll();
    final IpnbEditablePanel editablePanel = (IpnbEditablePanel)myBufferPanel.clone();
    addCell(editablePanel, true);
  }

  public void replaceComponent(@NotNull final IpnbEditablePanel from, @NotNull final IpnbCell cell) {
    removeAll();
    final int index = myIpnbPanels.indexOf(from);
    IpnbEditablePanel panel;
    if (cell instanceof IpnbCodeCell) {
      panel = new IpnbCodePanel(myProject, myParent, (IpnbCodeCell)cell);
    }
    else if (cell instanceof IpnbMarkdownCell) {
      panel = new IpnbMarkdownPanel((IpnbMarkdownCell)cell);
    }
    else if (cell instanceof IpnbHeadingCell) {
      panel = new IpnbHeadingPanel((IpnbHeadingCell)cell);
    }
    else {
      throw new UnsupportedOperationException(cell.getClass().toString());
    }
    if (index >= 0) {
      myIpnbPanels.remove(index);
      myIpnbPanels.add(index, panel);
    }
    for (IpnbPanel comp : myIpnbPanels) {
      add(comp);
    }
    add(createEmptyPanel());

    if (from instanceof IpnbCodePanel) {
      panel.switchToEditing();
    }
    setSelectedCell(panel);
    remove(from);
    revalidate();
    repaint();
  }

  private void addComponent(@NotNull final IpnbEditablePanel comp) {
    add(comp);
    myIpnbPanels.add(comp);
  }

  private static JPanel createEmptyPanel() {
    JPanel panel = new JPanel();
    panel.setBackground(IpnbEditorUtil.getBackground());
    return panel;
  }

  @Override
  protected void processKeyEvent(KeyEvent e) {
    if (mySelectedCell != null && e.getID() == KeyEvent.KEY_PRESSED) {
      if (e.getKeyCode() == KeyEvent.VK_ENTER) {
        mySelectedCell.switchToEditing();
        repaint();
      }
      int index = myIpnbPanels.indexOf(mySelectedCell);
      final Rectangle rect = getVisibleRect();


      if (e.getKeyCode() == KeyEvent.VK_UP) {
        selectPrev(mySelectedCell);
        if (index > 0) {
          final Rectangle cellBounds = mySelectedCell.getBounds();
          if (cellBounds.getY() <= rect.getY()) {
            myIncrement = rect.y - cellBounds.y;
            getParent().dispatchEvent(e);
          }
        }
      }
      else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
        selectNext(mySelectedCell);
        if (index < myIpnbPanels.size() - 1) {
          final Rectangle cellBounds = mySelectedCell.getBounds();
          if (cellBounds.getY() + cellBounds.getHeight() > rect.getY() + rect.getHeight()) {
            myIncrement = cellBounds.y + cellBounds.height - rect.y - rect.height;
            getParent().dispatchEvent(e);
          }
        }
      }
      else {
        getParent().dispatchEvent(e);
      }
    }
  }

  public void selectPrev(@NotNull IpnbEditablePanel cell) {
    int index = myIpnbPanels.indexOf(cell);
    if (index > 0) {
      setSelectedCell(myIpnbPanels.get(index - 1));
    }
  }

  public void selectNext(@NotNull IpnbEditablePanel cell) {
    int index = myIpnbPanels.indexOf(cell);
    if (index < myIpnbPanels.size() - 1) {
      setSelectedCell(myIpnbPanels.get(index + 1));
    }
  }

  public void selectNextOrPrev(@NotNull IpnbEditablePanel cell) {
    int index = myIpnbPanels.indexOf(cell);
    if (index < myIpnbPanels.size() - 1) {
      setSelectedCell(myIpnbPanels.get(index + 1));
    }
    else if (index > 0) {
      setSelectedCell(myIpnbPanels.get(index - 1));
    }
    else {
      mySelectedCell = null;
      repaint();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (mySelectedCell != null) {
      g.setColor(mySelectedCell.isEditing() ? JBColor.GREEN : JBColor.GRAY);
      g.drawRoundRect(mySelectedCell.getX() - 50, mySelectedCell.getTop() - 1,
                        mySelectedCell.getWidth() + 145 - IpnbEditorUtil.PROMPT_SIZE.width, mySelectedCell.getHeight() + 2, 5, 5);
    }
  }

  private void updateCellSelection(MouseEvent e) {
    if (e.getClickCount() > 0) {
      IpnbEditablePanel ipnbPanel = getIpnbPanelByClick(e.getPoint());
      if (ipnbPanel != null) {
        ipnbPanel.setEditing(false);
        ipnbPanel.requestFocus();
        repaint();
        setSelectedCell(ipnbPanel);
      }
    }
  }

  public void setInitialPosition(int index, int position) {
    myInitialSelection = index;
    myInitialPosition = position;
  }

  public void setSelectedCell(@NotNull final IpnbEditablePanel ipnbPanel) {
    if (ipnbPanel.equals(mySelectedCell)) return;
    if (mySelectedCell != null)
      mySelectedCell.setEditing(false);
    mySelectedCell = ipnbPanel;
    revalidate();
    UIUtil.requestFocus(this);
    repaint();
    myListener.selectionChanged(ipnbPanel);
  }

  public IpnbEditablePanel getSelectedCell() {
    return mySelectedCell;
  }

  public int getSelectedIndex() {
    final IpnbEditablePanel selectedCell = getSelectedCell();
    return myIpnbPanels.indexOf(selectedCell);
  }

  @Nullable
  private IpnbEditablePanel getIpnbPanelByClick(@NotNull final Point point) {
    for (IpnbEditablePanel c: myIpnbPanels) {
      if (c.contains(point.y)) {
        return c;
      }
    }
    return null;
  }

  public IpnbFile getIpnbFile() {
    return myIpnbFile;
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return null;
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
    return myIncrement;

  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
    return 100;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return false;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }

  @Nullable
  @Override
  public Object getData(String dataId) {
    final IpnbEditablePanel cell = getSelectedCell();
    if (OpenFileDescriptor.NAVIGATE_IN_EDITOR.is(dataId)) {
      if (cell instanceof IpnbCodePanel) {
        return ((IpnbCodePanel)cell).getEditor();
      }
    }
    return null;
  }
}
