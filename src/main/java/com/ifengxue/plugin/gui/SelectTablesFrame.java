package com.ifengxue.plugin.gui;

import com.ifengxue.fastjdbc.FastJdbc;
import com.ifengxue.fastjdbc.SimpleFastJdbc;
import com.ifengxue.fastjdbc.Sql;
import com.ifengxue.fastjdbc.SqlBuilder;
import com.ifengxue.plugin.Holder;
import com.ifengxue.plugin.component.AutoGeneratorConfig;
import com.ifengxue.plugin.component.SelectTables;
import com.ifengxue.plugin.entity.Column;
import com.ifengxue.plugin.entity.ColumnSchema;
import com.ifengxue.plugin.entity.Table;
import com.ifengxue.plugin.generator.config.DriverConfig;
import com.ifengxue.plugin.generator.config.GeneratorConfig;
import com.ifengxue.plugin.generator.config.TablesConfig;
import com.ifengxue.plugin.generator.config.TablesConfig.LineSeparator;
import com.ifengxue.plugin.generator.config.TablesConfig.ORM;
import com.ifengxue.plugin.generator.config.Vendor;
import com.ifengxue.plugin.generator.source.EntitySourceParser;
import com.ifengxue.plugin.generator.source.JpaRepositorySourceParser;
import com.ifengxue.plugin.util.ColumnUtil;
import com.ifengxue.plugin.util.WindowUtil;
import com.intellij.ide.util.DirectoryUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.mysql.jdbc.Driver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import org.apache.velocity.app.VelocityEngine;

public class SelectTablesFrame {

  private Logger log = Logger.getInstance(getClass());
  private final JFrame frameHolder;

  private SelectTablesFrame(List<Table> tableList, AutoGeneratorConfig config) {
    frameHolder = new JFrame("智能选择数据库表");
    int rowCount = tableList.size();
    SelectTables selectTablesHolder = new SelectTables(tableList);
    JTable table = selectTablesHolder.getTblTableSchema();
    table.setModel(new AbstractTableModel() {
      private static final long serialVersionUID = 8974669315458199207L;
      String[] columns = {"选中", "序号", "表名", "类名", "注释"};

      @Override
      public int getRowCount() {
        return rowCount;
      }

      @Override
      public int getColumnCount() {
        return columns.length;
      }

      @Override
      public String getColumnName(int column) {
        return columns[column];
      }

      @Override
      public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0 || columnIndex == 3 || columnIndex == 4;
      }

      @Override
      public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
          case 0:
            return Boolean.class;
          case 1:
            return Integer.class;
          default:
            return String.class;
        }
      }

      @Override
      public Object getValueAt(int rowIndex, int columnIndex) {
        switch (columnIndex) {
          case 0:
            return tableList.get(rowIndex).isSelected();
          case 1:
            return rowIndex + 1;
          case 2:
            return tableList.get(rowIndex).getTableName();
          case 3:
            return tableList.get(rowIndex).getEntityName();
          case 4:
            return tableList.get(rowIndex).getTableComment();
          default:
            throw new IllegalStateException("无法识别的列索引:" + columnIndex);
        }
      }

      @Override
      public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        switch (columnIndex) {
          case 0:
            tableList.get(rowIndex).setSelected((Boolean) aValue);
            break;
          case 3:
            tableList.get(rowIndex).setEntityName(aValue.toString());
            break;
          case 4:
            tableList.get(rowIndex).setTableComment(aValue.toString());
            break;
          default:
            break;
        }
      }
    });
    table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JCheckBox()));
    table.getColumnModel().getColumn(0).setMaxWidth(60);
    table.getColumnModel().getColumn(1).setMaxWidth(40);
    frameHolder.setContentPane(selectTablesHolder.getRootComponent());
    frameHolder.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frameHolder.setLocationRelativeTo(WindowUtil.getParentWindow(Holder.getEvent().getProject()));
    frameHolder.pack();
    frameHolder.setVisible(true);

    selectTablesHolder.getBtnCancel().addActionListener(event -> frameHolder.dispose());
    // 选中所有行
    selectTablesHolder.getBtnSelectAll().addActionListener(event -> {
      for (Table t : tableList) {
        t.setSelected(true);
      }
      table.updateUI();
    });
    // 全不选
    selectTablesHolder.getBtnSelectNone().addActionListener(event -> {
      for (Table t : tableList) {
        t.setSelected(false);
      }
      table.updateUI();
    });
    // 反选
    selectTablesHolder.getBtnSelectOther().addActionListener(event -> {
      for (Table t : tableList) {
        t.setSelected(!t.isSelected());
      }
      table.updateUI();
    });
    // 开始生成
    selectTablesHolder.getBtnGenerate().addActionListener(event -> {
      if (tableList.stream().noneMatch(Table::isSelected)) {
        Messages.showWarningDialog(Holder.getEvent().getProject(), "没有选择任何待生成的表！", "提示");
        return;
      }
      // 开始生成
      ApplicationManager.getApplication().executeOnPooledThread(new GeneratorRunner(tableList, config));
    });
  }

  public static void show(List<Table> tableList, AutoGeneratorConfig config) {
    new SelectTablesFrame(tableList, config);
  }

  /**
   * 生成器
   */
  private class GeneratorRunner implements Runnable {

    private final List<Table> tableList;
    private final AutoGeneratorConfig config;

    public GeneratorRunner(List<Table> tableList, AutoGeneratorConfig config) {
      this.tableList = Collections
          .unmodifiableList(tableList.stream().filter(Table::isSelected).collect(Collectors.toList()));
      this.config = config;
    }

    @Override
    public void run() {
      AnActionEvent event = Holder.getEvent();
      Project project = event.getProject();

      // repository
      VelocityEngine velocityEngine = new VelocityEngine();
      String encoding = StandardCharsets.UTF_8.name();
      velocityEngine.addProperty("input.encoding", encoding);
      velocityEngine.addProperty("output.encoding", encoding);
      JpaRepositorySourceParser repositorySourceParser = new JpaRepositorySourceParser();
      repositorySourceParser.setVelocityEngine(velocityEngine, encoding);

      FastJdbc fastJdbc = new SimpleFastJdbc();
      EntitySourceParser sourceParser = new EntitySourceParser();

      // 生成数量
      AtomicInteger generatCount = new AtomicInteger(tableList.size());
      for (Table table : tableList) {
        Sql sql = SqlBuilder.newSelectBuilder(ColumnSchema.class)
            .select()
            .from()
            .where()
            .equal("tableSchema", table.getTableSchema())
            .and().equal("tableName", table.getTableName())
            .build();
        List<ColumnSchema> columnSchemaList;
        try {
          columnSchemaList = fastJdbc.find(sql.getSql(), ColumnSchema.class, sql.getArgs().toArray());
        } catch (SQLException se) {
          log.error("读取数据库错误", se);
          ApplicationManager.getApplication()
              .invokeLater(() -> Bus.notify(new Notification("JpaSupport", "Error",
                  se.getErrorCode() + "," + se.getSQLState() + "," + se.getLocalizedMessage(),
                  NotificationType.ERROR)));
          ApplicationManager.getApplication().invokeAndWait(frameHolder::requestFocus);
          return;
        }
        // 解析字段列表
        List<Column> columnList = new ArrayList<>(columnSchemaList.size());
        for (ColumnSchema columnSchema : columnSchemaList) {
          Column column = new Column();
          column.setColumnName(columnSchema.getColumnName());
          column.setSort(columnSchema.getOrdinalPosition());
          column.setDbDataType(columnSchema.getDataType());
          column.setPrimary("PRI".equalsIgnoreCase(columnSchema.getColumnKey()));
          column.setNullable("NO".equalsIgnoreCase(columnSchema.getIsNullable()));
          column.setAutoIncrement(columnSchema.getExtra().contains("auto_increment"));
          column.setColumnComment(columnSchema.getColumnComment());
          column.setDefaultValue(columnSchema.getColumnDefault());
          ColumnUtil.parseColumn(column, config.getRemoveFieldPrefix(), true);
          if (column.isPrimary()) {
            table.setPrimaryKeyClassType(column.getJavaDataType());
          }
          if (!config.getExcludeFields().contains(column.getFieldName())) {
            columnList.add(column);
          }
        }
        table.setPackageName(config.getEntityPackage());
        table.setColumns(columnList);

        // 配置源码生成信息
        GeneratorConfig generatorConfig = new GeneratorConfig();
        generatorConfig.setDriverConfig(new DriverConfig()
            .setVendor(Vendor.MYSQL)
            .setDriverClass(Driver.class));
        int lastIndex;
        String basePackageName = config.getEntityPackage();
        if ((lastIndex = config.getEntityPackage().lastIndexOf('.')) != -1) {
          basePackageName = config.getEntityPackage().substring(0, lastIndex);
        }
        generatorConfig.setTablesConfig(new TablesConfig()
            .setBasePackageName(basePackageName)
            .setEntityPackageName(config.getEntityPackage())
            .setEnumSubPackageName(basePackageName + ".enums")
            .setIndent("2space")
            .setLineSeparator(LineSeparator.UNIX.getLineSeparator())
            .setOrm(ORM.JPA)
            .setExtendsEntityName(config.getExtendBaseClass())
            .setRemoveTablePrefix(config.getRemoveTablePrefix())
            .setRemoveFieldPrefix(config.getRemoveFieldPrefix())
            .setRepositoryPackageName(config.getRepositoryPackage())
            .setSerializable(config.isImplementSerializable())
            .setUseClassComment(config.isGenerateClassComment())
            .setUseFieldComment(config.isGenerateFieldComment())
            .setUseMethodComment(config.isGenerateMethodComment())
            .setUseDefaultValue(true)
            .setUseWrapper(true)
            .setUseLombok(config.isUseLombok()));
        generatorConfig.setPluginConfigs(Collections.emptyList());

        // 生成源码
        String sourceCode = sourceParser.parse(generatorConfig, table);
        WriteCommandAction.runWriteCommandAction(project, () -> {
          String filename = table.getEntityName() + ".java";
          writeContent(project, filename, config.getEntityDirectory(), sourceCode);
          if (!config.isGenerateRepository()) {
            if (generatCount.decrementAndGet() <= 0) {
              ApplicationManager.getApplication().invokeAndWait(frameHolder::requestFocus);
            }
            return;
          }
          filename = table.getEntityName() + "Repository.java";
          String repositorySourceCode = repositorySourceParser.parse(generatorConfig, table);
          writeContent(project, filename, config.getRepositoryDirectory(), repositorySourceCode);
          if (generatCount.decrementAndGet() <= 0) {
            ApplicationManager.getApplication().invokeAndWait(frameHolder::requestFocus);
          }
        });
      }
    }

    private void writeContent(Project project, String filename, String directory, String sourceCode) {
      PsiDirectory psiDirectory = DirectoryUtil.mkdirs(PsiManager.getInstance(project), directory);
      PsiFile psiFile = psiDirectory.findFile(filename);
      if (psiFile != null) {
        // 切换UI线程
        ApplicationManager.getApplication().invokeLater(() -> {
          int selectButton = Messages
              .showOkCancelDialog("文件" + filename + "已存在，是否覆盖？", "提示", Messages.getQuestionIcon());
          // 不覆盖
          if (selectButton != Messages.OK) {
            return;
          }
          // 切换IO线程
          WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiFile pf = psiDirectory.findFile(filename);
            assert pf != null;
            VirtualFile vf = pf.getVirtualFile();
            writeContent(sourceCode, vf);
          });
        });
      } else {
        psiFile = psiDirectory.createFile(filename);
        VirtualFile vFile = psiFile.getVirtualFile();
        writeContent(sourceCode, vFile);
      }
    }

    private void writeContent(String sourceCode, VirtualFile vFile) {
      try {
        vFile.setWritable(true);
        vFile.setCharset(StandardCharsets.UTF_8);
        vFile.setBinaryContent(sourceCode.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        log.error("生成源码失败", e);
      }
    }
  }
}
