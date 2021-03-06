package com.intellij.structuralsearch.plugin.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.template.impl.Variable;
import com.intellij.find.FindBundle;
import com.intellij.find.FindSettings;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.impl.FileTypeRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.structuralsearch.*;
import com.intellij.structuralsearch.impl.matcher.MatcherImpl;
import com.intellij.structuralsearch.plugin.StructuralSearchPlugin;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ListCellRendererWrapper;
import com.intellij.ui.TitledSeparator;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.*;
import java.util.List;

/**
 *  Class to show the user the request for search
 */
@SuppressWarnings({"RefusedBequest", "AssignmentToStaticFieldFromInstanceMethod"})
public class SearchDialog extends DialogWrapper implements ConfigurationCreator {
  protected SearchContext searchContext;

  // text for search
  protected Editor searchCriteriaEdit;

  // options of search scope
  private ScopeChooserCombo myScopeChooserCombo;

  private JCheckBox recursiveMatching;
  private JCheckBox caseSensitiveMatch;

  private JComboBox fileTypes;
  private JComboBox contexts;
  private JComboBox dialects;
  private JLabel status;
  private JLabel statusText;

  protected SearchModel model;
  private JCheckBox openInNewTab;
  private final Alarm myAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);

  public static final String USER_DEFINED = SSRBundle.message("new.template.defaultname");
  protected final ExistingTemplatesComponent existingTemplatesComponent;

  private boolean useLastConfiguration;

  @NonNls private FileType ourFtSearchVariant = StructuralSearchUtil.getDefaultFileType();
  private static Language ourDialect = null;
  private static String ourContext = null;

  private final boolean myShowScopePanel;
  private final boolean myRunFindActionOnClose;
  private boolean myDoingOkAction;

  private String mySavedEditorText;
  private JPanel myContentPanel;
  private JComponent myEditorPanel;

  public SearchDialog(SearchContext searchContext) {
    this(searchContext, true, true);
  }

  public SearchDialog(SearchContext searchContext, boolean showScope, boolean runFindActionOnClose) {
    super(searchContext.getProject(), true);

    if (showScope) setModal(false);
    myShowScopePanel = showScope;
    myRunFindActionOnClose = runFindActionOnClose;
    this.searchContext = (SearchContext)searchContext.clone();
    setTitle(getDefaultTitle());

    if (runFindActionOnClose) {
      setOKButtonText(FindBundle.message("find.dialog.find.button"));
    }

    existingTemplatesComponent = ExistingTemplatesComponent.getInstance(this.searchContext.getProject());
    model = new SearchModel(createConfiguration());

    init();
  }

  public void setUseLastConfiguration(boolean useLastConfiguration) {
    this.useLastConfiguration = useLastConfiguration;
  }

  public void setSearchPattern(final Configuration config) {
    model.setShadowConfig(config);
    setValuesFromConfig(config);
    initiateValidation();
  }

  protected Editor createEditor(final SearchContext searchContext, String text) {
    Editor editor = null;

    if (fileTypes != null) {
      final FileType fileType = (FileType)fileTypes.getSelectedItem();
      final Language dialect = (Language)dialects.getSelectedItem();

      final StructuralSearchProfile profile = StructuralSearchUtil.getProfileByFileType(fileType);
      if (profile != null) {
        editor = profile.createEditor(searchContext, fileType, dialect, text, useLastConfiguration);
      }
    }

    if (editor == null) {
      final EditorFactory factory = EditorFactory.getInstance();
      final Document document = factory.createDocument("");
      editor = factory.createEditor(document, searchContext.getProject());
      editor.getSettings().setFoldingOutlineShown(false);
    }

    editor.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void beforeDocumentChange(final DocumentEvent event) {
      }

      @Override
      public void documentChanged(final DocumentEvent event) {
        initiateValidation();
      }
    });

    return editor;
  }

  private void initiateValidation() {
    myAlarm.cancelAllRequests();
    myAlarm.addRequest(new Runnable() {

      @Override
      public void run() {
        try {
          new WriteAction(){
            @Override
            protected void run(Result result) {
              if (!isValid()) {
                getOKAction().setEnabled(false);
              }
              else {
                getOKAction().setEnabled(true);
                reportMessage(null, null);
              }
            }
          }.execute();
        }
        catch (RuntimeException e) {
          Logger.getInstance(SearchDialog.class).error(e);
        }
      }
    }, 500);
  }

  protected void buildOptions(JPanel searchOptions) {
    recursiveMatching = new JCheckBox(SSRBundle.message("recursive.matching.checkbox"), true);
    if (isRecursiveSearchEnabled()) {
      searchOptions.add(UIUtil.createOptionLine(recursiveMatching));
    }

    caseSensitiveMatch = new JCheckBox(FindBundle.message("find.options.case.sensitive"), true);
    searchOptions.add(UIUtil.createOptionLine(caseSensitiveMatch));

    final List<FileType> types = new ArrayList<FileType>();

    for (FileType fileType : StructuralSearchUtil.getSuitableFileTypes()) {
      if (StructuralSearchUtil.getProfileByFileType(fileType) != null) {
        types.add(fileType);
      }
    }
    Collections.sort(types, new Comparator<FileType>() {
      @Override
      public int compare(FileType o1, FileType o2) {
        return o1.getName().compareToIgnoreCase(o2.getName());
      }
    });

    final DefaultComboBoxModel comboBoxModel = new DefaultComboBoxModel(types.toArray(new FileType[types.size()]));
    comboBoxModel.setSelectedItem(ourFtSearchVariant);
    fileTypes = new ComboBox(comboBoxModel);
    fileTypes.setRenderer(new FileTypeRenderer());
    new ComboboxSpeedSearch(fileTypes) {
      @Override
      protected String getElementText(Object element) {
        return ((FileType)element).getName();
      }
    };
    fileTypes.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        updateDialectsAndContexts();
        updateEditor();
      }
    });

    contexts = new JComboBox(new DefaultComboBoxModel());
    contexts.setPreferredSize(new Dimension(60, -1));

    dialects = new JComboBox(new DefaultComboBoxModel());
    dialects.setRenderer(new ListCellRendererWrapper() {
      @Override
      public void customize(JList list, Object value, int index, boolean selected, boolean hasFocus) {
        if (value == null) {
          setText("None");
        }
        else if (value instanceof Language) {
          setText(((Language)value).getDisplayName());
        }
      }
    });
    dialects.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        updateEditor();
      }
    });
    new ComboboxSpeedSearch(dialects);
    dialects.setPreferredSize(new Dimension(120, -1));

    final JLabel jLabel = new JLabel(SSRBundle.message("search.dialog.file.type.label"));
    final JLabel jLabel2 = new JLabel(SSRBundle.message("search.dialog.context.label"));
    final JLabel jLabel3 = new JLabel(SSRBundle.message("search.dialog.file.dialect.label"));
    searchOptions.add(
      UIUtil.createOptionLine(
        new JComponent[]{
          jLabel,
          fileTypes,
          (JComponent)Box.createHorizontalStrut(8),
          jLabel2,
          contexts,
          (JComponent)Box.createHorizontalStrut(8),
          jLabel3,
          dialects,
        }
      )
    );

    jLabel.setLabelFor(fileTypes);
    jLabel2.setLabelFor(contexts);
    jLabel3.setLabelFor(dialects);

    detectFileTypeAndDialect();

    fileTypes.setSelectedItem(ourFtSearchVariant);
    fileTypes.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        if (e.getStateChange() == ItemEvent.SELECTED) initiateValidation();
      }
    });

    dialects.setSelectedItem(ourDialect);
    contexts.setSelectedItem(ourContext);

    updateDialectsAndContexts();
  }

  private void updateEditor() {
    if (myContentPanel != null) {
      if (myEditorPanel != null) {
        myContentPanel.remove(myEditorPanel);
      }
      disposeEditorContent();
      myEditorPanel = createEditorContent();
      myContentPanel.add(myEditorPanel, BorderLayout.CENTER);
      myContentPanel.revalidate();
    }
  }

  private void updateDialectsAndContexts() {
    final FileType fileType = (FileType)fileTypes.getSelectedItem();
    if (fileType instanceof LanguageFileType) {
      Language language = ((LanguageFileType)fileType).getLanguage();
      Language[] languageDialects = LanguageUtil.getLanguageDialects(language);
      Arrays.sort(languageDialects, new Comparator<Language>() {
        @Override
        public int compare(Language o1, Language o2) {
          return o1.getDisplayName().compareTo(o2.getDisplayName());
        }
      });
      Language[] variants = new Language[languageDialects.length + 1];
      variants[0] = null;
      System.arraycopy(languageDialects, 0, variants, 1, languageDialects.length);
      dialects.setModel(new DefaultComboBoxModel(variants));
      dialects.setEnabled(variants.length > 1);
    }

    final StructuralSearchProfile profile = StructuralSearchUtil.getProfileByFileType(fileType);

    if (profile instanceof StructuralSearchProfileBase) {
      final String[] contextNames = ((StructuralSearchProfileBase)profile).getContextNames();
      if (contextNames.length > 0) {
        contexts.setModel(new DefaultComboBoxModel(contextNames));
        contexts.setSelectedItem(contextNames[0]);
        contexts.setEnabled(true);
        return;
      }
    }
    contexts.setSelectedItem(null);
    contexts.setEnabled(false);
  }

  private void detectFileTypeAndDialect() {
    final PsiFile file = searchContext.getFile();
    if (file != null) {
      PsiElement context = null;

      if (searchContext.getEditor() != null) {
        context = file.findElementAt(searchContext.getEditor().getCaretModel().getOffset());
        if (context != null) {
          context = context.getParent();
        }
      }
      if (context == null) {
        context = file;
      }

      FileType detectedFileType = null;

      StructuralSearchProfile profile = StructuralSearchUtil.getProfileByPsiElement(context);
      if (profile != null) {
        FileType fileType = profile.detectFileType(context);
        if (fileType != null) {
          detectedFileType = fileType;
        }
      }

      if (detectedFileType == null) {
        for (FileType fileType : StructuralSearchUtil.getSuitableFileTypes()) {
          if (fileType instanceof LanguageFileType && ((LanguageFileType)fileType).getLanguage().equals(context.getLanguage())) {
            detectedFileType = fileType;
            break;
          }
        }
      }

      ourFtSearchVariant = detectedFileType != null ?
                           detectedFileType :
                           StructuralSearchUtil.getDefaultFileType();

      // todo: detect dialect

      /*if (file.getLanguage() == StdLanguages.HTML ||
          (file.getFileType() == StdFileTypes.JSP &&
           contextLanguage == StdLanguages.HTML
          )
        ) {
        ourFileType = "html";
      }
      else if (file.getLanguage() == StdLanguages.XHTML ||
               (file.getFileType() == StdFileTypes.JSPX &&
                contextLanguage == StdLanguages.HTML
               )) {
        ourFileType = "xml";
      }
      else {
        ourFileType = DEFAULT_TYPE_NAME;
      }*/
    }
  }

  protected boolean isRecursiveSearchEnabled() {
    return true;
  }

  public void setValuesFromConfig(Configuration configuration) {
    //searchCriteriaEdit.putUserData(SubstitutionShortInfoHandler.CURRENT_CONFIGURATION_KEY, configuration);

    setDialogTitle(configuration);
    final MatchOptions matchOptions = configuration.getMatchOptions();

    UIUtil.setContent(
      searchCriteriaEdit,
      matchOptions.getSearchPattern(),
      0,
      searchCriteriaEdit.getDocument().getTextLength(),
      searchContext.getProject()
    );

    model.getConfig().getMatchOptions().setSearchPattern(
      matchOptions.getSearchPattern()
    );

    recursiveMatching.setSelected(
      isRecursiveSearchEnabled() && matchOptions.isRecursiveSearch()
    );

    caseSensitiveMatch.setSelected(
      matchOptions.isCaseSensitiveMatch()
    );

    model.getConfig().getMatchOptions().clearVariableConstraints();
    if (matchOptions.hasVariableConstraints()) {
      for (Iterator<String> i = matchOptions.getVariableConstraintNames(); i.hasNext(); ) {
        final MatchVariableConstraint constraint = (MatchVariableConstraint)matchOptions.getVariableConstraint(i.next()).clone();
        model.getConfig().getMatchOptions().addVariableConstraint(constraint);
      }
    }

    MatchOptions options = configuration.getMatchOptions();
    StructuralSearchProfile profile = StructuralSearchUtil.getProfileByFileType(options.getFileType());
    assert profile != null;
    fileTypes.setSelectedItem(options.getFileType());
    dialects.setSelectedItem(options.getDialect());
    if (options.getPatternContext() != null) {
      contexts.setSelectedItem(options.getPatternContext());
    }
  }

  private void setDialogTitle(final Configuration configuration) {
    setTitle(getDefaultTitle() + " - " + configuration.getName());
  }

  @Override
  public Configuration createConfiguration() {
    SearchConfiguration configuration = new SearchConfiguration();
    configuration.setName(USER_DEFINED);
    return configuration;
  }

  protected void addOrReplaceSelection(final String selection) {
    addOrReplaceSelectionForEditor(selection, searchCriteriaEdit);
  }

  protected final void addOrReplaceSelectionForEditor(final String selection, Editor editor) {
    final Project project = searchContext.getProject();
    UIUtil.setContent(editor, selection, 0, -1, project);
    final Document document = editor.getDocument();
    editor.getSelectionModel().setSelection(0, document.getTextLength());
    final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
    documentManager.commitDocument(document);
    final PsiFile file = documentManager.getPsiFile(document);
    if (file == null) return;

    new WriteCommandAction(project, file) {
      @Override protected void run(@NotNull Result result) throws Throwable {
        CodeStyleManager.getInstance(project).adjustLineIndent(file, new TextRange(0, document.getTextLength()));
      }
    }.execute();
  }

  protected void startSearching() {
    new SearchCommand(model.getConfig(), searchContext).startSearching();
  }

  protected String getDefaultTitle() {
    return SSRBundle.message("structural.search.title");
  }

  protected JComponent createEditorContent() {
    JPanel result = new JPanel(new BorderLayout());

    result.add(BorderLayout.NORTH, new JLabel(SSRBundle.message("search.template")));
    searchCriteriaEdit = createEditor(searchContext, mySavedEditorText != null ? mySavedEditorText : "");
    result.add(BorderLayout.CENTER, searchCriteriaEdit.getComponent());
    result.setMinimumSize(new Dimension(150, 100));

    return result;
  }

  protected int getRowsCount() {
    return 4;
  }

  @Override
  protected JComponent createCenterPanel() {
    myContentPanel = new JPanel(new BorderLayout());
    myEditorPanel = createEditorContent();
    myContentPanel.add(BorderLayout.CENTER, myEditorPanel);
    myContentPanel.add(BorderLayout.SOUTH, Box.createVerticalStrut(8));
    JComponent centerPanel = new JPanel(new BorderLayout());
    {
      JPanel panel = new JPanel(new BorderLayout());
      panel.add(BorderLayout.CENTER, myContentPanel);
      panel.add(BorderLayout.SOUTH, createTemplateManagementButtons());
      centerPanel.add(BorderLayout.CENTER, panel);
    }

    JPanel optionsContent = new JPanel(new BorderLayout());
    centerPanel.add(BorderLayout.SOUTH, optionsContent);

    JPanel searchOptions = new JPanel();
    searchOptions.setLayout(new GridLayout(getRowsCount(), 1, 0, 0));
    searchOptions.setBorder(IdeBorderFactory.createTitledBorder(SSRBundle.message("ssdialog.options.group.border"),
                                                                true));

    myScopeChooserCombo = new ScopeChooserCombo(
      searchContext.getProject(),
      true,
      false,
      FindSettings.getInstance().getDefaultScopeName()
    );
    Disposer.register(myDisposable, myScopeChooserCombo);
    JPanel allOptions = new JPanel(new BorderLayout());
    if (myShowScopePanel) {
      JPanel scopePanel = new JPanel(new GridBagLayout());

      TitledSeparator separator = new TitledSeparator(SSRBundle.message("search.dialog.scope.label"), myScopeChooserCombo.getComboBox());
      scopePanel.add(separator, new GridBagConstraints(0, 0, 1, 1, 1, 1, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
                                                       new Insets(5, 0, 0, 0), 0, 0));

      scopePanel.add(myScopeChooserCombo, new GridBagConstraints(0, 1, 1, 1, 1, 1, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
                                                                 new Insets(0, 10, 0, 0), 0, 0));

      allOptions.add(
        scopePanel,
        BorderLayout.SOUTH
      );

      myScopeChooserCombo.getComboBox().addItemListener(new ItemListener() {
        @Override
        public void itemStateChanged(ItemEvent e) {
          initiateValidation();
        }
      });
    }

    buildOptions(searchOptions);

    allOptions.add(searchOptions, BorderLayout.CENTER);
    optionsContent.add(allOptions, BorderLayout.CENTER);

    if (myRunFindActionOnClose) {
      JPanel panel = new JPanel(new BorderLayout());
      panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
      openInNewTab = new JCheckBox(FindBundle.message("find.open.in.new.tab.checkbox"));
      openInNewTab.setSelected(FindSettings.getInstance().isShowResultsInSeparateView());
      ToolWindow findWindow = ToolWindowManager.getInstance(searchContext.getProject()).getToolWindow(ToolWindowId.FIND);
      openInNewTab.setEnabled(findWindow != null && findWindow.isAvailable());
      panel.add(openInNewTab, BorderLayout.EAST);

      optionsContent.add(BorderLayout.SOUTH, panel);
    }

    updateEditor();
    return centerPanel;
  }


  @Override
  protected JComponent createSouthPanel() {
    final JPanel statusPanel = new JPanel(new BorderLayout(5, 0));
    statusPanel.add(super.createSouthPanel(), BorderLayout.NORTH);
    statusPanel.add(statusText = new JLabel(SSRBundle.message("status.message")), BorderLayout.WEST);
    statusPanel.add(status = new JLabel(), BorderLayout.CENTER);
    return statusPanel;
  }

  private JPanel createTemplateManagementButtons() {
    JPanel panel = new JPanel(null);
    panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
    panel.add(Box.createHorizontalGlue());

    panel.add(
      createJButtonForAction(new AbstractAction() {
        {
          putValue(NAME, SSRBundle.message("save.template.text.button"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
          String name = showSaveTemplateAsDialog();

          if (name != null) {
            final Project project = searchContext.getProject();
            final ConfigurationManager configurationManager = StructuralSearchPlugin.getInstance(project).getConfigurationManager();
            final Collection<Configuration> configurations = configurationManager.getConfigurations();

            if (configurations != null) {
              name = ConfigurationManager.findAppropriateName(configurations, name, project);
              if (name == null) return;
            }

            model.getConfig().setName(name);
            setValuesToConfig(model.getConfig());
            setDialogTitle(model.getConfig());

            if (model.getShadowConfig() == null ||
                model.getShadowConfig().isPredefined()) {
              existingTemplatesComponent.addConfigurationToUserTemplates(model.getConfig());
            }
            else {  // ???
              setValuesToConfig(model.getShadowConfig());
              model.getShadowConfig().setName(name);
            }
          }
        }
      })
    );

    panel.add(
      Box.createHorizontalStrut(8)
    );

    panel.add(
      createJButtonForAction(
        new AbstractAction() {
          {
            putValue(NAME, SSRBundle.message("edit.variables.button"));
          }

          @Override
          public void actionPerformed(ActionEvent e) {
            EditVarConstraintsDialog.setProject(searchContext.getProject());
            new EditVarConstraintsDialog(
              searchContext.getProject(),
              model, getVariablesFromListeners(),
              isReplaceDialog(),
              (FileType)fileTypes.getSelectedItem()
            ).show();
            initiateValidation();
            EditVarConstraintsDialog.setProject(null);
          }
        }
      )
    );

    panel.add(
      Box.createHorizontalStrut(8)
    );

    panel.add(
      createJButtonForAction(
        new AbstractAction() {
          {
            putValue(NAME, SSRBundle.message("history.button"));
          }

          @Override
          public void actionPerformed(ActionEvent e) {
            SelectTemplateDialog dialog = new SelectTemplateDialog(searchContext.getProject(), true, isReplaceDialog());
            dialog.show();

            if (!dialog.isOK()) {
              return;
            }
            Configuration[] configurations = dialog.getSelectedConfigurations();
            if (configurations.length == 1) {
              setSearchPattern(configurations[0]);
            }
          }
        }
      )
    );

    panel.add(
      Box.createHorizontalStrut(8)
    );

    panel.add(
      createJButtonForAction(
        new AbstractAction() {
          {
            putValue(NAME, SSRBundle.message("copy.existing.template.button"));
          }

          @Override
          public void actionPerformed(ActionEvent e) {
            SelectTemplateDialog dialog = new SelectTemplateDialog(searchContext.getProject(), false, isReplaceDialog());
            dialog.show();

            if (!dialog.isOK()) {
              return;
            }
            Configuration[] configurations = dialog.getSelectedConfigurations();
            if (configurations.length == 1) {
              setSearchPattern(configurations[0]);
            }
          }
        }
      )
    );

    return panel;
  }

  protected List<Variable> getVariablesFromListeners() {
    return getVarsFrom(searchCriteriaEdit);
  }

  protected static ArrayList<Variable> getVarsFrom(Editor searchCriteriaEdit) {
    SubstitutionShortInfoHandler handler = searchCriteriaEdit.getUserData(UIUtil.LISTENER_KEY);
    return new ArrayList<Variable>(handler.getVariables());
  }

  public final Project getProject() {
    return searchContext.getProject();
  }

  public String showSaveTemplateAsDialog() {
    return ConfigurationManager.showSaveTemplateAsDialog(
      model.getShadowConfig() != null ? model.getShadowConfig().getName() : SSRBundle.message("user.defined.category"),
      searchContext.getProject()
    );
  }

  protected boolean isReplaceDialog() {
    return false;
  }

  @Override
  public void show() {
    StructuralSearchPlugin.getInstance(getProject()).setDialogVisible(true);
    Configuration.setActiveCreator(this);
    searchCriteriaEdit.putUserData(
      SubstitutionShortInfoHandler.CURRENT_CONFIGURATION_KEY,
      model.getConfig()
    );

    if (!useLastConfiguration) {
      final Editor editor = FileEditorManager.getInstance(searchContext.getProject()).getSelectedTextEditor();
      boolean setSomeText = false;

      if (editor != null) {
        final SelectionModel selectionModel = editor.getSelectionModel();

        if (selectionModel.hasSelection()) {
          addOrReplaceSelection(selectionModel.getSelectedText());
          existingTemplatesComponent.getPatternTree().setSelectionPath(null);
          existingTemplatesComponent.getHistoryList().setSelectedIndex(-1);
          setSomeText = true;
        }
      }

      if (!setSomeText) {
        int selection = existingTemplatesComponent.getHistoryList().getSelectedIndex();
        if (selection != -1) {
          setValuesFromConfig(
            (Configuration)existingTemplatesComponent.getHistoryList().getSelectedValue()
          );
        }
      }
    }

    initiateValidation();

    super.show();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return searchCriteriaEdit.getContentComponent();
  }

  // Performs ok action
  @Override
  protected void doOKAction() {
    SearchScope selectedScope = getSelectedScope();
    if (selectedScope == null) return;

    myDoingOkAction = true;
    boolean result = isValid();
    myDoingOkAction = false;
    if (!result) return;

    myAlarm.cancelAllRequests();
    super.doOKAction();
    if (!myRunFindActionOnClose) return;

    final FindSettings findSettings = FindSettings.getInstance();
    findSettings.setDefaultScopeName(selectedScope.getDisplayName());
    findSettings.setShowResultsInSeparateView(openInNewTab.isSelected());

    try {
      if (model.getShadowConfig() != null) {
        if (model.getShadowConfig().isPredefined()) {
          model.getConfig().setName(
            model.getShadowConfig().getName()
          );
        } //else {
        //  // user template, save it
        //  setValuesToConfig(model.getShadowConfig());
        //}
      }
      existingTemplatesComponent.addConfigurationToHistory(model.getConfig());

      startSearching();
    }
    catch (MalformedPatternException ex) {
      reportMessage("this.pattern.is.malformed.message", searchCriteriaEdit, ex.getMessage());
    }
  }

  public Configuration getConfiguration() {
    return model.getConfig();
  }

  private SearchScope getSelectedScope() {
    return myScopeChooserCombo.getSelectedScope();
  }

  protected boolean isValid() {
    setValuesToConfig(model.getConfig());
    boolean result = true;

    try {
      MatcherImpl.validate(searchContext.getProject(), model.getConfig().getMatchOptions());
    }
    catch (MalformedPatternException ex) {
      if (myRunFindActionOnClose) {
        reportMessage(
          "this.pattern.is.malformed.message",
          searchCriteriaEdit,
          ex.getMessage() != null ? ex.getMessage() : ""
        );
        result = false;
      }
    }
    catch (UnsupportedPatternException ex) {
      reportMessage("this.pattern.is.unsupported.message", searchCriteriaEdit, ex.getMessage());
      result = false;
    }

    //getOKAction().setEnabled(result);
    return result;
  }

  protected void reportMessage(@NonNls String messageId, Editor editor, Object... params) {
    final String message = messageId != null ? SSRBundle.message(messageId, params) : "";
    status.setText(message);
    status.setToolTipText(message);
    status.revalidate();
    statusText.setLabelFor(editor != null ? editor.getContentComponent() : null);
  }

  protected void setValuesToConfig(Configuration config) {

    MatchOptions options = config.getMatchOptions();

    boolean searchWithinHierarchy = IdeBundle.message("scope.class.hierarchy").equals(myScopeChooserCombo.getSelectedScopeName());
    // We need to reset search within hierarchy scope during online validation since the scope works with user participation
    options.setScope(
      searchWithinHierarchy && !myDoingOkAction ? GlobalSearchScope.projectScope(getProject()) : myScopeChooserCombo.getSelectedScope());
    options.setLooseMatching(true);
    options.setRecursiveSearch(isRecursiveSearchEnabled() && recursiveMatching.isSelected());

    ourFtSearchVariant = (FileType)fileTypes.getSelectedItem();
    ourDialect = (Language)dialects.getSelectedItem();
    ourContext = (String)contexts.getSelectedItem();
    FileType fileType = ourFtSearchVariant;
    options.setFileType(fileType);
    options.setDialect(ourDialect);
    options.setPatternContext(ourContext);

    options.setSearchPattern(searchCriteriaEdit.getDocument().getText());
    options.setCaseSensitiveMatch(caseSensitiveMatch.isSelected());
  }

  @Override
  protected String getDimensionServiceKey() {
    return "#com.intellij.structuralsearch.plugin.ui.SearchDialog";
  }

  @Override
  public void dispose() {
    Configuration.setActiveCreator(null);
    disposeEditorContent();

    myAlarm.cancelAllRequests();

    super.dispose();
    StructuralSearchPlugin.getInstance(getProject()).setDialogVisible(false);
  }

  protected void disposeEditorContent() {
    mySavedEditorText = searchCriteriaEdit.getDocument().getText();

    // this will remove from myExcludedSet
    final PsiFile file = PsiDocumentManager.getInstance(searchContext.getProject()).getPsiFile(searchCriteriaEdit.getDocument());
    if (file != null) {
      DaemonCodeAnalyzer.getInstance(searchContext.getProject()).setHighlightingEnabled(file, true);
    }

    EditorFactory.getInstance().releaseEditor(searchCriteriaEdit);
  }

  @Override
  protected String getHelpId() {
    return "find.structuredSearch";
  }

  public SearchContext getSearchContext() {
    return searchContext;
  }
}
