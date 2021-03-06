/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.debugger.array;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.jetbrains.python.console.PydevConsoleCommunication;
import com.jetbrains.python.debugger.PyDebugValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.TreePath;

/**
 * @author amarch
 */

public class PyViewArrayAction extends XDebuggerTreeActionBase {

  @Override
  protected void perform(XValueNodeImpl node, @NotNull String nodeName, AnActionEvent e) {
    final MyDialog dialog = new MyDialog(e.getProject());
    dialog.setValue(node);
    dialog.show();
  }

  @Nullable
  private static TreePath[] getSelectedNodes(DataContext dataContext) {
    XDebuggerTree tree = XDebuggerTree.getTree(dataContext);
    return tree == null ? null : tree.getSelectionPaths();
  }

  @Override
  public void update(AnActionEvent e) {
    TreePath[] paths = getSelectedNodes(e.getDataContext());
    if (paths != null) {
      if(paths.length > 1) {
        e.getPresentation().setVisible(false);
        return;
      }

      XValueNodeImpl node  = getSelectedNode(e.getDataContext());
      if (node != null && node.getValueContainer() instanceof PyDebugValue && node.isComputed()) {
        PyDebugValue debugValue = (PyDebugValue) node.getValueContainer();
        if (debugValue.getFrameAccessor() instanceof PydevConsoleCommunication) {
          e.getPresentation().setVisible(false);
          return;
        }

        String nodeType = debugValue.getType();
        if ("ndarray".equals(nodeType)) {
          e.getPresentation().setVisible(true);
          return;
        }
      }
    }
    e.getPresentation().setVisible(false);
  }

  protected class MyDialog extends DialogWrapper {
    private Project myProject;
    private ArrayTableForm myComponent;

    private MyDialog(Project project) {
      super(project, false);
      setModal(false);
      setCancelButtonText("Close");
      setCrossClosesWindow(true);

      myProject = project;
      myComponent = new ArrayTableForm(project);

      init();
    }

    public void setValue(XValueNodeImpl node) {

      if (node.getValueContainer() instanceof PyDebugValue) {
        PyDebugValue debugValue = (PyDebugValue)node.getValueContainer();

        if ("ndarray".equals(debugValue.getType())) {
          myComponent.setDefaultStatus();
          final NumpyArrayValueProvider valueProvider = new NumpyArrayValueProvider(node, this, myProject);
          try {
            valueProvider.startFillTable();
          }
          catch (Exception e) {
            setErrorText(e.getMessage());
          }
        }
        else {
          myComponent.setNotApplicableStatus(node);
        }
      }
    }

    public void setError(String text) {
      //todo: think about this usage
      setErrorText(text);
    }

    @Override
    @NotNull
    protected Action[] createActions() {
      return new Action[]{getCancelAction()};
    }

    @Override
    protected String getDimensionServiceKey() {
      return "#com.jetbrains.python.actions.view.array.PyViewArrayAction";
    }

    @Override
    protected JComponent createCenterPanel() {
      return myComponent.getMainPanel();
    }

    public ArrayTableForm getComponent() {
      return myComponent;
    }
  }
}
