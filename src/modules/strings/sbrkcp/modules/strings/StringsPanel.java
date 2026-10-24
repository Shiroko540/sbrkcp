package sbrkcp.modules.strings;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import sbrkcp.api.ModuleContext;
import sbrkcp.core.text.StringsEntry;
import sbrkcp.core.text.StringsRunner;
import sbrkcp.core.text.StringsProbe;

/**
 * 「界面文案」面板：源文件 + 探测摘要 + 策略台账 + 文案表（结构化/裸扫分栏）+ 搜索 + 导出。
 * 提取与导出全部走 StringsRunner，与 CLI 同一实现。
 */
public final class StringsPanel extends JPanel {

    private final ModuleContext ctx;

    private final JTextField inField = new JTextField(30);
    private final JTextField outField = new JTextField(26);
    private final JTextField search = new JTextField(16);
    private final JComboBox<String> formatBox = new JComboBox<String>(
            new String[]{"csv", "txt", "json", "md", "csv,txt,json,md"});
    private final JComboBox<String> langBox = new JComboBox<String>(new String[]{"both", "cn", "en"});
    private final JButton browseIn = new JButton("浏览…");
    private final JButton browseOut = new JButton("浏览…");
    private final JButton startBtn = new JButton("开始提取");
    private final JButton exportBtn = new JButton("导出");
    private final JButton mergeBtn = new JButton("合并去重");
    private final JProgressBar bar = new JProgressBar(0, 100);

    private final DefaultTableModel stepModel = new DefaultTableModel(
            new Object[]{"策略", "状态", "命中", "耗时", "备注"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final DefaultTableModel structModel = model();
    private final DefaultTableModel bareModel = model();
    private final DefaultTableModel mergedModel = model();

    private final JTable stepTable = new JTable(stepModel);
    private final JTable structTable = new JTable(structModel);
    private final JTable bareTable = new JTable(bareModel);
    private final JTable mergedTable = new JTable(mergedModel);
    private final JTabbedPane tabs = new JTabbedPane();

    private final JLabel summary = new JLabel("就绪");

    private StringsProbe.Report last;
    private volatile boolean running;

    public StringsPanel(ModuleContext ctx) {
        super(new BorderLayout(8, 8));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        buildUi();
        wire();
    }

    private static DefaultTableModel model() {
        return new DefaultTableModel(new Object[]{"编号", "中文", "English", "偏移", "来源", "编码", "置信"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        form.add(new JLabel("输入："), c);
        c.gridx = 1; c.weightx = 1;
        form.add(inField, c);
        c.gridx = 2; c.weightx = 0;
        form.add(browseIn, c);

        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("输出："), c);
        c.gridx = 1; c.weightx = 1;
        form.add(outField, c);
        c.gridx = 2; c.weightx = 0;
        form.add(browseOut, c);

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.add(new JLabel("格式"));
        opts.add(formatBox);
        opts.add(new JLabel("语言"));
        opts.add(langBox);
        opts.add(startBtn);
        opts.add(exportBtn);
        opts.add(mergeBtn);
        opts.add(new JLabel("搜索"));
        opts.add(search);
        c.gridx = 1; c.gridy = 2; c.weightx = 1;
        form.add(opts, c);

        add(form, BorderLayout.NORTH);

        bar.setStringPainted(true);
        bar.setString("就绪");

        stepTable.setAutoCreateRowSorter(true);
        structTable.setAutoCreateRowSorter(true);
        bareTable.setAutoCreateRowSorter(true);
        mergedTable.setAutoCreateRowSorter(true);
        stepTable.setRowHeight(22);

        JScrollPane spSteps = new JScrollPane(stepTable);
        spSteps.setBorder(BorderFactory.createTitledBorder("策略台账（S1–S10 全跑，命中即产出）"));
        spSteps.setPreferredSize(new Dimension(900, 170));

        tabs.addTab("结构化（带编号）", new JScrollPane(structTable));
        tabs.addTab("裸扫（无编号）", new JScrollPane(bareTable));
        tabs.addTab("全部合并", new JScrollPane(mergedTable));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spSteps, tabs);
        split.setDividerLocation(180);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.add(bar, BorderLayout.NORTH);
        south.add(summary, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        exportBtn.setEnabled(false);
    }

    private void wire() {
        File def = ctx == null ? null : ctx.defaultOutputDir();
        outField.setText(new File(def == null ? new File("unpacked") : def, "strings").getAbsolutePath());

        browseIn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(ctx == null ? null : ctx.lastInputDir());
            fc.setDialogTitle("选择固件文件或目录");
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                inField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        browseOut.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择输出目录");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                outField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        startBtn.addActionListener(e -> start());
        exportBtn.addActionListener(e -> doExport());
        mergeBtn.addActionListener(e -> fillMerged());
        search.addActionListener(e -> applyFilter());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }
        });
    }

    private void start() {
        if (running) return;
        File in = new File(inField.getText().trim());
        if (!in.exists()) {
            log("输入不存在：" + in);
            return;
        }
        final File outDir = new File(outField.getText().trim());
        running = true;
        startBtn.setEnabled(false);
        exportBtn.setEnabled(false);
        stepModel.setRowCount(0);
        structModel.setRowCount(0);
        bareModel.setRowCount(0);
        mergedModel.setRowCount(0);
        bar.setIndeterminate(true);
        bar.setString("提取中…");
        summary.setText("提取中：" + in.getAbsolutePath());

        final StringsRunner.Options o = new StringsRunner.Options();
        o.input = in;
        o.outDir = outDir;
        o.formats = String.valueOf(formatBox.getSelectedItem());
        o.lang = String.valueOf(langBox.getSelectedItem());

        Thread t = new Thread(() -> {
            StringsProbe.Report r;
            try {
                r = StringsRunner.probe(o, s -> SwingUtilities.invokeLater(() -> log(s)));
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    log("提取失败：" + ex);
                    finish();
                });
                return;
            }
            final StringsProbe.Report rep = r;
            SwingUtilities.invokeLater(() -> {
                last = rep;
                show(rep);
                finish();
                exportBtn.setEnabled(true);
            });
        }, "strings-probe");
        t.setDaemon(true);
        t.start();
    }

    private void finish() {
        running = false;
        startBtn.setEnabled(true);
        bar.setIndeterminate(false);
        bar.setValue(100);
        bar.setString("就绪");
    }

    private void show(StringsProbe.Report r) {
        stepModel.setRowCount(0);
        for (StringsProbe.Step s : r.steps) {
            stepModel.addRow(new Object[]{s.id + " " + s.name, s.status, s.hits, s.ms + "ms", s.note});
        }
        fill(structModel, r.structured);
        fill(bareModel, r.bare);
        mergedModel.setRowCount(0);
        String base = "";
        for (StringsProbe.Step s : r.steps) {
            if ("S1".equals(s.id) && s.note != null && s.note.contains("base=")) {
                int i = s.note.indexOf("base=");
                int j = s.note.indexOf(' ', i);
                base = s.note.substring(i, j < 0 ? s.note.length() : j);
                break;
            }
        }
        summary.setText("文件：" + r.name + "  大小：" + r.size + " B  结构化：" + r.structured.size()
                + "  裸扫：" + r.bare.size() + "  合计：" + r.total()
                + (base.isEmpty() ? "" : "  " + base)
                + (r.diag == null ? "" : "  " + r.diag.summary));
        tabs.setTitleAt(0, "结构化（带编号） " + r.structured.size());
        tabs.setTitleAt(1, "裸扫（无编号） " + r.bare.size());
        tabs.setTitleAt(2, "全部合并");
        log("完成：结构化 " + r.structured.size() + " 条 / 裸扫 " + r.bare.size() + " 条");
        if (r.empty()) log("未提取到文案；10 条策略的结论见上表与 probe_report.txt");
        for (String n : r.notes) log("  · " + n);
    }

    private void fill(DefaultTableModel m, List<StringsEntry> list) {
        m.setRowCount(0);
        for (StringsEntry e : list) {
            m.addRow(new Object[]{
                e.id < 0 ? "" : String.valueOf(e.id),
                e.displayCn(),
                e.displayEn(),
                "0x" + Long.toHexString(e.offset),
                e.source + (e.mergedFrom.isEmpty() ? "" : "(" + e.mergedFrom + ")"),
                e.encoding,
                e.lowConfidence ? "低：" + e.noiseReason : "高"});
        }
    }

    /** 合并视图：结构化优先，裸扫去重后追加，保留来源标记。 */
    private void fillMerged() {
        if (last == null) return;
        StringsProbe.Report copy = new StringsProbe.Report();
        copy.name = last.name;
        copy.size = last.size;
        copy.diag = last.diag;
        copy.structured.addAll(last.structured);
        copy.bare.addAll(last.bare);
        StringsProbe.dedup(copy);
        fill(mergedModel, copy.all());
        tabs.setSelectedIndex(2);
        summary.setText("合并去重后 " + copy.total() + " 条（结构化 " + copy.structured.size()
                + " / 裸扫 " + copy.bare.size() + "），来源标记已保留");
        log("合并去重完成：" + copy.total() + " 条");
    }

    private void doExport() {
        if (last == null) {
            log("还没有可导出的结果");
            return;
        }
        final StringsRunner.Options o = new StringsRunner.Options();
        o.input = new File(inField.getText().trim());
        o.outDir = new File(outField.getText().trim());
        o.formats = String.valueOf(formatBox.getSelectedItem());
        o.lang = String.valueOf(langBox.getSelectedItem());
        try {
            List<File> files = StringsRunner.export(last, o, s -> SwingUtilities.invokeLater(() -> log(s)));
            for (File f : files) log("产出：" + f.getAbsolutePath());
            summary.setText("已导出 " + files.size() + " 个文件到 " + o.outDir.getAbsolutePath());
        } catch (Throwable t) {
            log("导出失败：" + t);
        }
    }

    private void applyFilter() {
        String q = search.getText();
        RowFilter<Object, Object> f = q == null || q.trim().isEmpty()
                ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q.trim()));
        for (JTable t : new JTable[]{structTable, bareTable, mergedTable}) {
            @SuppressWarnings("unchecked")
            TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) t.getRowSorter();
            if (sorter == null) {
                sorter = new TableRowSorter<DefaultTableModel>((DefaultTableModel) t.getModel());
                t.setRowSorter(sorter);
            }
            sorter.setRowFilter(f);
        }
    }

    private void log(String line) {
        if (ctx != null) ctx.log("[strings] " + line);
        else System.out.println(line);
    }

    /** 供 MainFrame 预置输入。 */
    public void setInputFile(File f) {
        if (f != null) inField.setText(f.getAbsolutePath());
    }

    /** 供冒烟测试触发。 */
    public boolean hasResult() {
        return last != null;
    }

    /** 无界面时的空实现占位，避免外部误用。 */
    public List<File> exported() {
        return new ArrayList<File>();
    }

    public JTextArea logAreaHint() {
        return null;
    }
}
