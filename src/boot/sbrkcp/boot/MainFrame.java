package sbrkcp.boot;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import sbrkcp.api.AbstractModulePanel;
import sbrkcp.api.ModuleContext;
import sbrkcp.api.SbrkcpModule;

/** 主窗口：模块列表 + CardLayout 面板 + 日志 + 状态栏 + 拖放。 */
public final class MainFrame extends JFrame {

    private final List<ModuleLoader.Entry> entries;
    private final File baseDir;
    private final LogBus bus;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final Map<String, SbrkcpModule> byId = new LinkedHashMap<>();
    private final Map<String, javax.swing.JComponent> panels = new LinkedHashMap<>();
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> moduleList = new JList<>(listModel);
    private final JTextArea logArea = new JTextArea(8, 80);
    private final JLabel status = new JLabel("就绪");
    private final Preferences prefs = Preferences.userNodeForPackage(MainFrame.class);

    public MainFrame(List<ModuleLoader.Entry> entries, File baseDir, LogBus bus) {
        super(Version.title() + "  —  RK 固件解包器");
        this.entries = entries;
        this.baseDir = baseDir;
        this.bus = bus;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1100, 720);
        setMinimumSize(new Dimension(900, 560));
        buildUi();
        buildModules();
        restorePrefs();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePrefs();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                // 关窗后进程完全退出：模块面板线程均为守护线程，无需额外处理
            }
        });
    }

    private void buildUi() {
        ModuleContext ctx = new ModuleContext() {
            @Override
            public File defaultOutputDir() {
                return new File(baseDir, "unpacked");
            }

            @Override
            public File lastInputDir() {
                String p = prefs.get("lastInDir", null);
                return p == null ? null : new File(p);
            }

            @Override
            public void log(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }

            @Override
            public String hostName() {
                return System.getProperty("os.name") + " / Java " + System.getProperty("java.version");
            }
        };
        this.ctx = ctx;

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JButton openBtn = new JButton("打开固件");
        JButton aboutBtn = new JButton("关于");
        top.add(openBtn);
        top.add(aboutBtn);
        top.add(new JLabel("  " + Version.title() + "  "));
        add(top, BorderLayout.NORTH);

        moduleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        moduleList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int i = moduleList.getSelectedIndex();
            if (i < 0) return;
            String key = keyAt(i);
            if (key != null && panels.containsKey(key)) {
                cards.show(cardHost, key);
                status.setText("当前模块：" + moduleList.getSelectedValue());
            } else {
                ModuleLoader.Entry en = entryAt(i);
                if (en != null && en.error != null) appendLog("[" + en.jar.getName() + "] " + en.error);
            }
        });

        JScrollPane listScroll = new JScrollPane(moduleList);
        listScroll.setBorder(BorderFactory.createTitledBorder("模块"));
        listScroll.setPreferredSize(new Dimension(220, 400));

        cardHost.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, cardHost);
        split.setDividerLocation(230);
        add(split, BorderLayout.CENTER);

        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));
        logScroll.setPreferredSize(new Dimension(900, 150));

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(status, BorderLayout.WEST);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        JPanel south = new JPanel(new BorderLayout());
        south.add(logScroll, BorderLayout.CENTER);
        south.add(statusBar, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        openBtn.addActionListener(e -> chooseAndOpen());
        aboutBtn.addActionListener(e -> JOptionPane.showMessageDialog(this,
                Version.title() + "\nRK 固件解包器（模块化 GUI）\nJava " + System.getProperty("java.version")
                        + "\nAPI v" + ModuleLoader.API_VERSION,
                "关于", JOptionPane.INFORMATION_MESSAGE));

        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files != null && !files.isEmpty()) {
                        openFile(files.get(0));
                        return true;
                    }
                } catch (Exception ex) {
                    appendLog("拖放失败：" + ex);
                }
                return false;
            }
        });
    }

    private ModuleContext ctx;

    private void buildModules() {
        int row = 0;
        for (ModuleLoader.Entry e : entries) {
            row++;
            if (e.ok()) {
                SbrkcpModule m = e.module;
                byId.put(m.info().id, m);
                listModel.addElement("● " + m.info().name);
                javax.swing.JComponent p;
                try {
                    p = m.createPanel(ctx);
                } catch (Throwable t) {
                    appendLog("面板创建失败 " + m.info().id + "：" + t);
                    p = new JLabel("面板创建失败：" + t);
                }
                panels.put(m.info().id, p);
                cardHost.add(p, m.info().id);
            } else {
                listModel.addElement("○ " + e.jar.getName() + "（失败）");
            }
        }
        status.setText(Version.title() + "   ·   " + ModuleLoader.summary(entries));
        if (listModel.getSize() > 0) moduleList.setSelectedIndex(0);
        for (ModuleLoader.Entry e : entries) {
            if (!e.ok() && e.error != null) appendLog("[跳过] " + e.jar.getName() + "：" + e.error);
        }
    }

    private String keyAt(int row) {
        int seen = 0;
        for (ModuleLoader.Entry e : entries) {
            if (!e.ok()) continue;
            if (seen == row) return e.module.info().id;
            seen++;
        }
        return null;
    }

    private ModuleLoader.Entry entryAt(int row) {
        int seen = 0;
        for (ModuleLoader.Entry e : entries) {
            if (row == seen) return e;
            seen++;
        }
        return null;
    }

    private void chooseAndOpen() {
        JFileChooser fc = new JFileChooser(prefs.get("lastInDir", null));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(fc.getSelectedFile());
        }
    }

    /** 按扩展名切到匹配模块；无匹配时提示支持列表。 */
    public void openFile(File f) {
        prefs.put("lastInDir", f.getParent() == null ? "" : f.getParent());
        String ext = ext(f.getName());
        List<SbrkcpModule> hits = new ArrayList<>();
        for (SbrkcpModule m : byId.values()) {
            if (m.info().supports(ext)) hits.add(m);
        }
        if (hits.isEmpty()) {
            StringBuilder sb = new StringBuilder("没有模块支持 ." + ext + "\n已加载模块支持：\n");
            for (SbrkcpModule m : byId.values()) {
                sb.append("  · ").append(m.info().id).append(" -> ")
                        .append(String.join(",", m.info().extensions)).append("\n");
            }
            appendLog(sb.toString().replace('\n', ' '));
            JOptionPane.showMessageDialog(this, sb.toString(), "无匹配模块", JOptionPane.WARNING_MESSAGE);
            return;
        }
        SbrkcpModule target;
        if (hits.size() == 1) {
            target = hits.get(0);
        } else {
            Object[] opts = hits.stream().map(m -> m.info().name).toArray();
            int pick = JOptionPane.showOptionDialog(this, "选择解包模块", "多模块支持",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            target = hits.get(Math.max(0, pick));
        }
        selectModule(target.info().id);
        javax.swing.JComponent p = panels.get(target.info().id);
        if (p instanceof AbstractModulePanel) ((AbstractModulePanel) p).setInputFile(f);
        appendLog("已选择 " + f.getAbsolutePath() + " -> " + target.info().name);
    }

    private void selectModule(String id) {
        int row = 0;
        for (ModuleLoader.Entry e : entries) {
            if (e.ok()) {
                if (e.module.info().id.equals(id)) {
                    moduleList.setSelectedIndex(row);
                    return;
                }
                row++;
            }
        }
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void restorePrefs() {
        int w = prefs.getInt("winW", 1100);
        int h = prefs.getInt("winH", 720);
        setSize(w, h);
    }

    private void savePrefs() {
        prefs.putInt("winW", getWidth());
        prefs.putInt("winH", getHeight());
    }

    /** GUI 冒烟：切换模块 switchTimes 次；给出样本时模拟拖入并触发一次"开始 → 取消"。 */
    public void smokeAndDispose(final int switchTimes, final int delayMs, final String samplePath) {
        new javax.swing.Timer(delayMs, ev -> {
            ((javax.swing.Timer) ev.getSource()).stop();
            try {
                int n = moduleList.getModel().getSize();
                for (int k = 0; k < switchTimes && n > 0; k++) {
                    moduleList.setSelectedIndex(k % n);
                }
            } catch (Throwable t) {
                appendLog("smoke 切换异常：" + t);
            }
            if (samplePath == null || samplePath.isEmpty()) {
                dispose();
                return;
            }
            try {
                File f = new File(samplePath);
                if (f.isFile()) openFile(f);
            } catch (Throwable t) {
                appendLog("smoke 拖入异常：" + t);
            }
            JButton start = findButton(currentPanel(), "开始解包");
            if (start != null && start.isEnabled()) {
                start.doClick();
                appendLog("smoke 已触发解包");
            }
            new javax.swing.Timer(400, ev2 -> {
                ((javax.swing.Timer) ev2.getSource()).stop();
                JButton cancel = findButton(currentPanel(), "取消");
                if (cancel != null && cancel.isEnabled()) {
                    cancel.doClick();
                    appendLog("smoke 已触发取消");
                }
                new javax.swing.Timer(800, ev3 -> {
                    ((javax.swing.Timer) ev3.getSource()).stop();
                    dispose();
                }).start();
            }).start();
        }).start();
    }

    private javax.swing.JComponent currentPanel() {
        int row = moduleList.getSelectedIndex();
        int i = 0;
        for (ModuleLoader.Entry e : entries) {
            if (!e.ok()) continue;
            if (i == row) return panels.get(e.module.info().id);
            i++;
        }
        return null;
    }

    private static JButton findButton(java.awt.Component root, String text) {
        if (root == null) return null;
        if (root instanceof JButton) {
            JButton b = (JButton) root;
            if (b.getText() != null && b.getText().contains(text)) return b;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                JButton r = findButton(c, text);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static void applyLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
