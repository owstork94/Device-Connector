package httpconnector;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.net.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI/UX 업그레이드 포인트
 * - 상단 툴바 스타일: 정렬/여백/아이콘/키보드 단축키(Enter=검색, Esc=중지, Ctrl+F=검색창 포커스)
 * - 검색창 플레이스홀더, 즉시 필터
 * - 테이블: 고정 높이, 헤더 굵게, 지브라(줄무늬) 배경, 상태 pill(라운드 배경), IP monospace, 열 너비/정렬 최적화
 * - 하단 상태바: 총 대상/검출 수, 진행률 표시
 * - 컨테이너 여백과 컬러 톤(라이트/다크 모두 무난)
 * - 코드/로직은 기존과 동일하게 유지, 시각 요소만 개선
 */
public class HttpConnector_V4 extends JFrame {
    // ===== Constants =====
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int TCP_CONNECT_TIMEOUT_MS = 500;    // 포트 열림 감지용
    private static final int HTTPS_CONNECT_TIMEOUT_MS = 1200; // 핸드셰이크 시도
    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);

    // ===== UI Fields =====
    private JTextField ipField, portField, searchField;
    private JButton scanButton;
    private JTable table;
    private ScanTableModel tableModel;
    private TableRowSorter<ScanTableModel> rowSorter;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    // ===== State =====
    private volatile ScanWorker currentWorker;

    // (선택) IP → MAC 매핑
    private final Map<String, String> ipToMacMap = new HashMap<String, String>() {{
        put("192.168.0.7", "6C-1C-71-0C-4A-06");
        put("192.168.0.9", "14-A7-8B-A9-03-02");
        put("192.168.0.11", "00-18-9A-27-A7-E0");
        put("192.168.0.12", "00-18-9A-27-A7-D6");
        put("192.168.0.15", "00-25-C2-86-92-B1");
        put("192.168.0.17", "A0-BD-1D-F2-30-2A");
        put("192.168.0.18", "6C-1C-71-0A-AB-FF");
        put("192.168.0.19", "B4-4C-3B-FA-12-4C");
        put("192.168.0.20", "6C-1C-71-0C-4A-86");
        put("192.168.0.21", "A0-BD-1D-F2-36-37");
        put("192.168.0.22", "6C-1C-71-0C-4A-7F");
        put("192.168.0.23", "6C-1C-71-0C-4A-27");
        put("192.168.0.148", "14-A7-8B-A8-E1-47");
        put("192.168.0.8", "A0-BD-1D-F2-30-22");
        //
    }};

    public HttpConnector_V4() {
        applyModernUI();
        setTitle("IP 대역 HTTPS 접속기");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // ===== Top: 툴바(입력) =====
        JToolBar toolbar = buildToolbar();
        add(toolbar, BorderLayout.NORTH);

        // ===== Center: 검색 + 테이블 =====
        tableModel = new ScanTableModel();
        table = new JTable(tableModel) {
            // 줄무늬 배경
            @Override public Component prepareRenderer(TableCellRenderer r, int row, int col) {
                Component c = super.prepareRenderer(r, row, col);
                if (!isRowSelected(row)) {
                    Color base = getBackground();
                    Color zebra = new Color(base.getRed(), base.getGreen(), base.getBlue(), 10);
                    c.setBackground((row % 2 == 0) ? base : zebra);
                }
                return c;
            }
        };
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setAutoCreateRowSorter(false);

        // 헤더 스타일
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 32));
        header.setFont(header.getFont().deriveFont(Font.BOLD));

        // 렌더러/에디터
        table.getColumnModel().getColumn(0).setCellRenderer(new IpRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusPillRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(new JCheckBox()));

        // 정렬 + 필터
        rowSorter = new TableRowSorter<>(tableModel);
        rowSorter.setComparator(0, Comparator.comparingLong(Util::ipToLong));
        table.setRowSorter(rowSorter);

        // 열 너비
        TableColumnModel cols = table.getColumnModel();
        cols.getColumn(0).setPreferredWidth(340);
        cols.getColumn(1).setPreferredWidth(120);
        cols.getColumn(2).setPreferredWidth(80);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(new EmptyBorder(0, 12, 12, 12));

        // 상단 검색바
        JPanel searchPanel = new JPanel(new BorderLayout(8, 8));
        searchPanel.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel searchLabel = new JLabel("검색");
        searchField = new JTextField();
        installPlaceholder(searchField, "IP/MAC/상태 필터…  (Ctrl+F)");
        searchPanel.add(searchLabel, BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(searchPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // 하단 상태바
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(6, 12, 8, 12));
        statusLabel = new JLabel("대기 중");
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(progressBar, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        // 검색 필터 동작
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() {
                String text = searchField.getText();
                if (text == null || text.trim().isEmpty()) {
                    rowSorter.setRowFilter(null);
                } else {
                    String regex = Pattern.quote(text.trim());
                    rowSorter.setRowFilter(RowFilter.regexFilter("(?i)" + regex));
                }
                updateStatus();
            }
        });

        // 버튼 핸들러
        scanButton.addActionListener(e -> onScanButton());

        // 단축키
        InputMap im = centerPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = centerPanel.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "scan");
        am.put("scan", new AbstractAction() { public void actionPerformed(ActionEvent e) { onScanButton(); }});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        am.put("cancel", new AbstractAction() { public void actionPerformed(ActionEvent e) { cancelScanIfRunning(); }});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusSearch");
        am.put("focusSearch", new AbstractAction() { public void actionPerformed(ActionEvent e) { searchField.requestFocusInWindow(); }});

        // 우클릭 메뉴(복사/열기)
        JPopupMenu popup = new JPopupMenu();
        JMenuItem copyIp = new JMenuItem("IP 복사");
        copyIp.addActionListener(e -> copySelectedIp());
        JMenuItem open = new JMenuItem("브라우저로 열기");
        open.addActionListener(e -> openSelectedIp());
        popup.add(copyIp); popup.add(open);
        table.setComponentPopupMenu(popup);

        // 안전 종료: 스캔 중이면 취소
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cancelScanIfRunning(); }
        });

        setVisible(true);

        // 시작 시 자동 검색
        onScanButton();
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(new EmptyBorder(10, 12, 6, 12));
        bar.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 0));

        JLabel title = new JLabel("IP 대역 HTTPS 접속기");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 2f));
        title.setBorder(new EmptyBorder(0, 0, 0, 8));

        bar.add(title);
        bar.add(separator());

        bar.add(new JLabel("IP 대역"));
        ipField = sizedField("192.168.0", 14);
        bar.add(ipField);

        bar.add(new JLabel("포트"));
        portField = sizedField("", 5);
        bar.add(portField);

        scanButton = new JButton("검색");
        scanButton.putClientProperty("JButton.buttonType", "roundRect");
        bar.add(scanButton);

        return bar;
    }

    private Component separator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(6, 28));
        return sep;
    }

    private JTextField sizedField(String text, int columns) {
        JTextField tf = new JTextField(text, columns);
        tf.putClientProperty("JComponent.sizeVariant", "regular");
        return tf;
    }

    private void installPlaceholder(JTextField field, String placeholder) {
        field.putClientProperty("JTextField.placeholderText", placeholder); // 일부 LAF 지원
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { field.repaint(); }
            @Override public void focusLost(FocusEvent e) { field.repaint(); }
        });
        field.addPropertyChangeListener(evt -> field.repaint());
    }

    private void cancelScanIfRunning() {
        if (currentWorker != null && !currentWorker.isDone()) currentWorker.cancel(true);
    }

    private void copySelectedIp() {
        int view = table.getSelectedRow();
        if (view < 0) return;
        int modelRow = table.convertRowIndexToModel(view);
        String ip = tableModel.ipAt(modelRow);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ip), null);
        statusLabel.setText("복사됨: " + ip);
    }

    private void openSelectedIp() {
        int view = table.getSelectedRow();
        if (view < 0) return;
        int modelRow = table.convertRowIndexToModel(view);
        String ip = tableModel.ipAt(modelRow);
        openInBrowser(ip);
    }

    private void openInBrowser(String ip) {
        int port = parsePort(portField.getText().trim());
        String url = "https://" + ip + ":" + port;
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "브라우저 열기 실패: " + ex.getMessage());
        }
    }

    private void updateStatus() {
        int total = tableModel.getRowCount();
        statusLabel.setText("표시: " + total + "건");
    }

    private void onScanButton() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
            return;
        }

        String input = ipField.getText().trim();
        int port = parsePort(portField.getText().trim());

        List<String> targets;
        try {
            targets = RangeParser.parseTargets(input);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, "IP 대역 입력 형식 오류: " + ex.getMessage(), "입력 오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 초기화
        tableModel.clear();
        progressBar.setVisible(true);
        progressBar.setMinimum(0);
        progressBar.setMaximum(targets.size());
        progressBar.setValue(0);
        scanButton.setText("중지");
        statusLabel.setText("검색 중… 총 " + targets.size() + "개 대상");

        currentWorker = new ScanWorker(targets, port);
        currentWorker.execute();
    }

    // ===== SwingWorker: 백그라운드 스캔 =====
    private class ScanWorker extends SwingWorker<Void, ScanResult> {
        private final List<String> targets;
        private final int port;
        private final long startTime = System.currentTimeMillis();

        ScanWorker(List<String> targets, int port) {
            this.targets = targets;
            this.port = port;
        }

        @Override
        protected Void doInBackground() {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
                Thread t = new Thread(r, "scan-worker");
                t.setDaemon(true);
                return t;
            });

            try {
                final AtomicInteger done = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (String ip : targets) {
                    if (isCancelled()) break;
                    futures.add(pool.submit(() -> {
                        if (isCancelled()) return; // 빠른 취소
                        ScanResult res = scanOne(ip, port);
                        if (res != null && res.isCamera) publish(res);
                        int v = done.incrementAndGet();
                        setProgress((int) ((v * 100.0) / targets.size()));
                        SwingUtilities.invokeLater(() -> progressBar.setValue(v));
                    }));
                }
                for (Future<?> f : futures) {
                    if (isCancelled()) break;
                    try { f.get(); } catch (CancellationException ignore) { /* ignore */ }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ee) {
                System.err.println("Execution error: " + ee.getMessage());
            } finally {
                pool.shutdownNow();
            }
            return null;
        }

        @Override
        protected void process(List<ScanResult> chunks) {
            for (ScanResult r : chunks) tableModel.addRow(r, ipToMacMap.get(r.ip));
            updateStatus();
        }

        @Override
        protected void done() {
            progressBar.setVisible(false);
            scanButton.setText("검색");
            long ms = System.currentTimeMillis() - startTime;
            statusLabel.setText("완료 · " + tableModel.getRowCount() + "건 감지 · " + ms + "ms");
        }
    }

    // 단일 IP 스캔 → 카메라 추정 여부
    private static ScanResult scanOne(String ip, int port) {
        if (!tcpOpen(ip, port, TCP_CONNECT_TIMEOUT_MS)) return null;
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://" + ip + ":" + port + "/");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTPS_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HTTPS_CONNECT_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            return new ScanResult(ip, false);
        } catch (SSLHandshakeException ssl) {
            return new ScanResult(ip, true);
        } catch (Exception ignore) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean tcpOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int parsePort(String portText) {
        if (portText == null || portText.isBlank()) return DEFAULT_HTTPS_PORT;
        try {
            int p = Integer.parseInt(portText.trim());
            if (p <= 0 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "포트 번호가 올바르지 않습니다. 기본값(443) 사용", "경고", JOptionPane.WARNING_MESSAGE);
            return DEFAULT_HTTPS_PORT;
        }
    }

    // ===== Table Model =====
    static class ScanTableModel extends AbstractTableModel {
        private final String[] cols = {"IP 주소", "상태", "접속"};
        private final List<Row> rows = new ArrayList<>();

        static class Row {
            final String ip;      // 순수 IP
            final String display; // IP (MAC)
            final boolean isCamera;
            Row(String ip, String display, boolean isCamera) { this.ip = ip; this.display = display; this.isCamera = isCamera; }
        }

        void clear() { rows.clear(); fireTableDataChanged(); }
        void addRow(ScanResult r, String mac) {
            String display = mac == null ? r.ip : r.ip + " (" + mac + ")";
            rows.add(new Row(r.ip, display, r.isCamera));
            int idx = rows.size() - 1; fireTableRowsInserted(idx, idx);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int column) { return cols[column]; }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return columnIndex == 2; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.display;
                case 1: return r.isCamera ? "카메라" : "-";
                case 2: return "접속";
            }
            return null;
        }
        public String ipAt(int modelRow) { return rows.get(modelRow).ip; }
    }

    // ===== Renderer/Editor =====
    static class IpRenderer extends DefaultTableCellRenderer {
        private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setFont(mono);
            label.setBorder(new EmptyBorder(0, 8, 0, 8));
            return label;
        }
    }

    static class StatusPillRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String v = value == null ? "" : value.toString();
            JLabel pill = new JLabel(v, SwingConstants.CENTER) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = getWidth(); int h = getHeight();
                    Color bg;
                    if ("카메라".equals(getText())) bg = new Color(32, 158, 80);
                    else bg = new Color(120, 120, 120);
                    if (isSelected) bg = bg.darker();
                    g2.setColor(bg);
                    g2.fillRoundRect(6, 6, w - 12, h - 12, h, h);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            pill.setForeground(Color.WHITE);
            pill.setOpaque(false);
            pill.setBorder(new EmptyBorder(0, 0, 0, 0));
            return pill;
        }
    }

    static class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() { setText("접속"); setFocusPainted(false); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) { return this; }
    }

    class ButtonEditor extends DefaultCellEditor {
        private final JButton button = new JButton("접속");
        private String ip;
        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button.setFocusPainted(false);
            button.addActionListener(e -> fireEditingStopped());
        }
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            this.ip = tableModel.ipAt(modelRow);
            return button;
        }
        @Override
        public Object getCellEditorValue() {
            openInBrowser(ip);
            return "접속";
        }
    }

    // ===== Data & Utils =====
    static class ScanResult { final String ip; boolean isCamera; ScanResult(String ip, boolean isCamera) { this.ip = ip; this.isCamera = isCamera; } }

    static class Util {
        static long ipToLong(String ipOrDisplay) {
            String ip = ipOrDisplay;
            int idx = ip.indexOf(' ');
            if (idx > 0) ip = ip.substring(0, idx);
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return 0L;
            long v = 0;
            try { for (String p : parts) { v = (v << 8) + Integer.parseInt(p); } }
            catch (NumberFormatException e) { return 0L; }
            return v;
        }
    }

    static class RangeParser {
        private static final Pattern EXACT = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
        private static final Pattern BASE = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
        private static final Pattern STAR = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.\\*$");
        private static final Pattern RANGE = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})-(\\d{1,3})$");
        private static final Pattern CIDR24 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}/24$");

        static List<String> parseTargets(String input) {
            input = input.trim();
            Matcher m;
            m = EXACT.matcher(input);
            if (m.matches()) { String ip = four(m); validateOctets(ip); return Collections.singletonList(ip); }
            m = BASE.matcher(input);
            if (m.matches()) { String prefix = three(m); validateThree(prefix); return range(prefix, 1, 254); }
            m = STAR.matcher(input);
            if (m.matches()) { String prefix = three(m); validateThree(prefix); return range(prefix, 1, 254); }
            m = RANGE.matcher(input);
            if (m.matches()) {
                String prefix = m.group(1) + "." + m.group(2) + "." + m.group(3);
                int start = Integer.parseInt(m.group(4));
                int end = Integer.parseInt(m.group(5));
                validateThree(prefix);
                if (start < 0 || start > 255 || end < 0 || end > 255 || start > end)
                    throw new IllegalArgumentException("마지막 옥텟 범위가 올바르지 않습니다.");
                return range(prefix, start, end);
            }
            m = CIDR24.matcher(input);
            if (m.matches()) { String prefix = three(m); validateThree(prefix); return range(prefix, 1, 254); }
            throw new IllegalArgumentException("지원 형식: 'a.b.c', 'a.b.c.*', 'a.b.c.d', 'a.b.c.d-e', 'a.b.c.x/24'");
        }
        private static String four(Matcher m) { return m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4); }
        private static String three(Matcher m) { return m.group(1) + "." + m.group(2) + "." + m.group(3); }
        private static void validateThree(String prefix) { String[] p = prefix.split("\\."); if (p.length != 3) throw new IllegalArgumentException("입력 오류"); for (String s : p) validateOctet(s); }
        private static void validateOctets(String ip) { String[] parts = ip.split("\\."); if (parts.length != 4) throw new IllegalArgumentException("입력 오류"); for (String s : parts) validateOctet(s); }
        private static void validateOctet(String s) { int v = Integer.parseInt(s); if (v < 0 || v > 255) throw new IllegalArgumentException("옥텟 범위(0~255) 위반: " + v); }
        private static List<String> range(String prefix, int start, int end) { List<String> list = new ArrayList<>(Math.max(0, end - start + 1)); for (int i = start; i <= end; i++) list.add(prefix + "." + i); return list; }
    }

    private void applyModernUI() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}
        // 폰트 톤 조정(조심스럽게)
        Font base = UIManager.getFont("Label.font");
        if (base != null) {
            Font ui = base.deriveFont(base.getSize2D());
            UIManager.put("Label.font", ui);
            UIManager.put("Button.font", ui);
            UIManager.put("TextField.font", ui);
            UIManager.put("Table.font", ui);
            UIManager.put("TableHeader.font", ui.deriveFont(Font.BOLD));
        }
    }

    public static void main(String[] args) { SwingUtilities.invokeLater(HttpConnector_V4::new); }
}
