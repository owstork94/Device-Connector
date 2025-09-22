package httpconnector;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리팩토링 포인트
 * - EDT 블로킹 제거: SwingWorker로 백그라운드 스캔 + publish/process 로 안전한 UI 업데이트
 * - 구조화: ScanResult/Model/Renderer/Editor 분리, 상수/유틸 정리
 * - 입력 유연화: "192.168.0", "192.168.0.*", "192.168.0.10-100", "192.168.0.0/24", "192.168.0.15" 지원
 * - 정렬 안정화: 실제 IP(Long) 기준 정렬
 * - 취소/진행률: 검색 버튼 토글(검색↔중지), ProgressBar 추가
 * - 네트워크 체크 개선: 빠른 TCP 포트 열림 체크 → HTTPS 연결 시도 → SSLHandshakeException 이면 "카메라"로 판별
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
    }};

    public HttpConnector_V4() {
        setTitle("IP 대역 HTTPS 접속기 (리팩토링)");
        setSize(700, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        // ===== Top: 입력 =====
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputPanel.add(new JLabel("IP 대역:"));
        ipField = new JTextField("192.168.0", 14);
        inputPanel.add(ipField);

        inputPanel.add(new JLabel("포트(옵션):"));
        portField = new JTextField(5);
        inputPanel.add(portField);

        scanButton = new JButton("검색");
        inputPanel.add(scanButton);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(180, 18));
        inputPanel.add(progressBar);

        add(inputPanel, BorderLayout.NORTH);

        // ===== Center: 검색 + 테이블 =====
        tableModel = new ScanTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(24);

        // 렌더러/에디터
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(new JCheckBox()));

        // 정렬 + 필터
        rowSorter = new TableRowSorter<>(tableModel);
        rowSorter.setComparator(0, Comparator.comparingLong(Util::ipToLong));
        table.setRowSorter(rowSorter);

        JScrollPane scrollPane = new JScrollPane(table);

        JPanel searchPanel = new JPanel(new BorderLayout(6, 6));
        searchPanel.add(new JLabel("검색:"), BorderLayout.WEST);
        searchField = new JTextField();
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.add(searchPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

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
            }
        });

        // 버튼 핸들러
        scanButton.addActionListener(e -> onScanButton());

        // 안전 종료: 스캔 중이면 취소
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (currentWorker != null && !currentWorker.isDone()) {
                    currentWorker.cancel(true);
                }
            }
        });

        setVisible(true);

        // 시작 시 자동 검색
        onScanButton();
    }

    private void onScanButton() {
        if (currentWorker != null && !currentWorker.isDone()) {
            // 진행 중이면 취소 처리
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

        currentWorker = new ScanWorker(targets, port);
        currentWorker.execute();
    }

    // ===== SwingWorker: 백그라운드 스캔 =====
    private class ScanWorker extends SwingWorker<Void, ScanResult> {
        private final List<String> targets;
        private final int port;

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
                        if (res != null && res.isCamera) {
                            publish(res);
                        }
                        int v = done.incrementAndGet();
                        setProgress((int) ((v * 100.0) / targets.size()));
                        SwingUtilities.invokeLater(() -> progressBar.setValue(v));
                    }));
                }
                // 모두 대기 (취소 시도 시 인터럽트)
                for (Future<?> f : futures) {
                    if (isCancelled()) break;
                    try { f.get(); } catch (CancellationException ignore) { /* ignore */ }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ee) {
                // 개별 예외는 scanOne 내부에서 처리하므로 여기서는 로깅만
                System.err.println("Execution error: " + ee.getMessage());
            } finally {
                pool.shutdownNow();
            }
            return null;
        }

        @Override
        protected void process(List<ScanResult> chunks) {
            for (ScanResult r : chunks) tableModel.addRow(r, ipToMacMap.get(r.ip));
        }

        @Override
        protected void done() {
            progressBar.setVisible(false);
            scanButton.setText("검색");
        }
    }

    // 단일 IP 스캔 → 카메라 추정 여부
    private static ScanResult scanOne(String ip, int port) {
        // 1) 빠른 TCP 포트 열림 체크
        if (!tcpOpen(ip, port, TCP_CONNECT_TIMEOUT_MS)) return null;

        // 2) HTTPS 연결 시도 → SSLHandshakeException 이면 카메라로 표기
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://" + ip + ":" + port + "/");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTPS_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HTTPS_CONNECT_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            // 정상 핸드셰이크/연결이면 "카메라"로 단정하지 않음 (false)
            return new ScanResult(ip, false);
        } catch (SSLHandshakeException ssl) {
            // 자체서명/허약한 암호군 등으로 실패 → 임베디드 장비(카메라)일 확률 높음
            return new ScanResult(ip, true);
        } catch (Exception ignore) {
            // 기타 예외는 불명확 → 표시하지 않음
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

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        void addRow(ScanResult r, String mac) {
            String display = mac == null ? r.ip : r.ip + " (" + mac + ")";
            rows.add(new Row(r.ip, display, r.isCamera));
            int idx = rows.size() - 1;
            fireTableRowsInserted(idx, idx);
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

        public String ipAt(int viewRow) {
            Row r = rows.get(viewRow);
            return r.ip;
        }
    }

    // ===== Renderer/Editor =====
    static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String v = value == null ? "" : value.toString();
            label.setHorizontalAlignment(SwingConstants.CENTER);
            if ("카메라".equals(v)) label.setForeground(new Color(0, 153, 0));
            else label.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            return label;
        }
    }

    static class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() { setText("접속"); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) { return this; }
    }

    class ButtonEditor extends DefaultCellEditor {
        private final JButton button = new JButton("접속");
        private String ip;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
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
            String portText = portField.getText().trim();
            int port = parsePort(portText);
            String url = "https://" + ip + ":" + port;
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "브라우저 열기 실패: " + ex.getMessage());
            }
            return "접속";
        }
    }

    // ===== Data & Utils =====
    static class ScanResult {
        final String ip; boolean isCamera;
        ScanResult(String ip, boolean isCamera) { this.ip = ip; this.isCamera = isCamera; }
    }

    static class Util {
        static long ipToLong(String ipOrDisplay) {
            // "192.168.0.10 (MAC)" → 순수 IP만 추출
            String ip = ipOrDisplay;
            int idx = ip.indexOf(' ');
            if (idx > 0) ip = ip.substring(0, idx);
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return 0L;
            long v = 0;
            try {
                for (String p : parts) {
                    v = (v << 8) + Integer.parseInt(p);
                }
            } catch (NumberFormatException e) {
                return 0L;
            }
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

            // 1) 정확한 IP 하나
            m = EXACT.matcher(input);
            if (m.matches()) {
                String ip = four(m);
                validateOctets(ip);
                return Collections.singletonList(ip);
            }

            // 2) 3옥텟까지: a.b.c → a.b.c.1~254
            m = BASE.matcher(input);
            if (m.matches()) {
                String prefix = three(m);
                validateThree(prefix);
                return range(prefix, 1, 254);
            }

            // 3) a.b.c.*
            m = STAR.matcher(input);
            if (m.matches()) {
                String prefix = three(m);
                validateThree(prefix);
                return range(prefix, 1, 254);
            }

            // 4) a.b.c.d-e
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

            // 5) a.b.c.x/24 → a.b.c.1~254
            m = CIDR24.matcher(input);
            if (m.matches()) {
                String prefix = three(m);
                validateThree(prefix);
                return range(prefix, 1, 254);
            }

            throw new IllegalArgumentException("지원 형식: 'a.b.c', 'a.b.c.*', 'a.b.c.d', 'a.b.c.d-e', 'a.b.c.x/24'");
        }

        private static String four(Matcher m) { return m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4); }
        private static String three(Matcher m) { return m.group(1) + "." + m.group(2) + "." + m.group(3); }

        private static void validateThree(String prefix) {
            String[] p = prefix.split("\\.");
            if (p.length != 3) throw new IllegalArgumentException("입력 오류");
            for (String s : p) validateOctet(s);
        }
        private static void validateOctets(String ip) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("입력 오류");
            for (String s : parts) validateOctet(s);
        }
        private static void validateOctet(String s) {
            int v = Integer.parseInt(s);
            if (v < 0 || v > 255) throw new IllegalArgumentException("옥텟 범위(0~255) 위반: " + v);
        }
        private static List<String> range(String prefix, int start, int end) {
            List<String> list = new ArrayList<>(Math.max(0, end - start + 1));
            for (int i = start; i <= end; i++) list.add(prefix + "." + i);
            return list;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(HttpConnector_V4::new);
    }
}
