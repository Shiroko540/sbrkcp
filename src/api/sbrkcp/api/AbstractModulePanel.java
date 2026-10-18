package sbrkcp.api;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 所有模块共用的面板：输入选择 + 输出目录 + 开始/取消 + 日志 + 进度条。
 * 模块 createPanel() 直接返回它即可，模块 jar 里不需要再写一套 UI。
 */
public abstract class AbstractModulePanel extends JPanel {

    protected final SbrkcpModule module;
    protected final ModuleContext ctx;

    protected final JTextField inField = new JTextField(28);
    protected final JTextField outField = new JTextField(28);
    protected final JTextArea logArea = new JTextArea(12, 60);
    protected final JProgressBar bar = new JProgressBar(0, 100);
    protected final JButton startBtn = new JButton("开始解包");
    protected final JButton cancelBtn = new JButton("取消");
    protected final JButton inBrowse = new JButton("浏览…");
    protected final JButton outBrowse = new JButton("浏览…");

    private volatile boolean cancelled;
    private volatile Thread worker;

    protected AbstractModulePanel(SbrkcpModule module, ModuleContext ctx) {
        super(new BorderLayout(8, 8));
        this.module = module;
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        buildUi();
        wire();
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        form.add(new JLabel("输入："), c);
        c.gridx = 1; c.weightx = 1;
        form.add(inField, c);
        c.gridx = 2; c.weightx = 0;
        form.add(inBrowse, c);

        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("输出："), c);
        c.gridx = 1; c.weightx = 1;
        form.add(outField, c);
        c.gridx = 2; c.weightx = 0;
        form.add(outBrowse, c);

        JPanel actions = new JPanel();
        actions.add(startBtn);
        actions.add(cancelBtn);
        c.gridx = 1; c.gridy = 2; c.weightx = 0;
        form.add(actions, c);

        add(form, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(false);
        bar.setStringPainted(true);
        bar.setString("就绪");

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(bar, BorderLayout.NORTH);
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        center.setPreferredSize(new Dimension(800, 380));
        add(center, BorderLayout.CENTER);

        File defOut = ctx == null ? new File("unpacked") : ctx.defaultOutputDir();
        outField.setText(defOut == null ? "unpacked" : defOut.getAbsolutePath());
        if (ctx != null && ctx.lastInputDir() != null) {
            inField.setText("");
            inField.setToolTipText("最近目录：" + ctx.lastInputDir());
        }
        cancelBtn.setEnabled(false);
    }

    private void wire() {
        inBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(ctx == null ? null : ctx.lastInputDir());
            fc.setDialogTitle("选择固件");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                inField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        outBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择输出目录");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                outField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        startBtn.addActionListener(e -> start());
        cancelBtn.addActionListener(e -> {
            cancelled = true;
            appendLog("用户请求取消…");
        });
    }

    /** 子类可覆盖以追加勾选项。 */
    protected UnpackOptions collectOptions() {
        return UnpackOptions.defaults();
    }

    private void start() {
        File in = new File(inField.getText().trim());
        if (!in.isFile()) {
            appendLog("输入文件不存在：" + in);
            return;
        }
        File out = new File(outField.getText().trim());
        cancelled = false;
        startBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        logArea.setText("");
        bar.setValue(0);

        ProgressListener pl = new ProgressListener() {
            @Override public void log(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }
            @Override public void progress(int percent, String stage) {
                SwingUtilities.invokeLater(() -> {
                    bar.setValue(Math.max(0, Math.min(100, percent)));
                    bar.setString(stage + "  " + percent + "%");
                });
            }
            @Override public boolean isCancelled() {
                return cancelled;
            }
        };

        UnpackOptions opt = collectOptions();
        worker = new Thread(() -> {
            try {
                UnpackResult r = module.unpack(in, out, pl, opt);
                SwingUtilities.invokeLater(() -> {
                    appendLog("完成：" + r.pngCount + " 张 PNG / " + r.fileCount + " 个文件");
                    for (String n : r.notes) appendLog("  · " + n);
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> appendLog("失败：" + t));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    startBtn.setEnabled(true);
                    cancelBtn.setEnabled(false);
                    bar.setString(cancelled ? "已取消" : "就绪");
                });
            }
        }, "unpack-" + module.info().id);
        worker.setDaemon(true);
        worker.start();
    }

    protected void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        if (ctx != null) ctx.log("[" + module.info().id + "] " + line);
    }

    public void setInputFile(File f) {
        if (f != null) inField.setText(f.getAbsolutePath());
    }
}
