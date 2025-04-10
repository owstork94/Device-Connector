package httpconnector;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class HttpConnector_V4 extends JFrame {
    private JTextField ipField, portField, searchField;
    private JButton scanButton;
    private JTable table;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;

    // IP → MAC 매핑
    private Map<String, String> ipToMacMap = new HashMap<String, String>() {{
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
    }};

    public HttpConnector_V4() {
        setTitle("IP 대역 HTTPS 접속기");
        setSize(500, 300); // 창 크기 설정
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 입력 패널 (IP 대역 + 포트)
        JPanel inputPanel = new JPanel();
        inputPanel.add(new JLabel("IP 대역:"));
        ipField = new JTextField("192.168", 10);
        inputPanel.add(ipField);

        inputPanel.add(new JLabel("포트 (옵션):"));
        portField = new JTextField(5);
        inputPanel.add(portField);

        scanButton = new JButton("검색");
        inputPanel.add(scanButton);
        add(inputPanel, BorderLayout.NORTH);

        // 테이블 생성
        tableModel = new DefaultTableModel(new Object[]{"IP 주소", "상태", "접속"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return column == 2; // 접속 버튼만 클릭 가능
            }
        };
        table = new JTable(tableModel);
        table.getColumn("상태").setCellRenderer(new StatusRenderer());
        table.getColumn("접속").setCellRenderer(new ButtonRenderer());
        table.getColumn("접속").setCellEditor(new ButtonEditor(new JCheckBox()));

        JScrollPane scrollPane = new JScrollPane(table);

        // 검색창 + 테이블 묶기
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(new JLabel("검색:"), BorderLayout.WEST);
        searchField = new JTextField();
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(searchPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // 검색 기능
        rowSorter = new TableRowSorter<>(tableModel);
        rowSorter.setComparator(0, (ip1, ip2) -> ipToLong(ip1.toString()).compareTo(ipToLong(ip2.toString())));
        table.setRowSorter(rowSorter);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }

            private void filter() {
                String text = searchField.getText();
                if (text.trim().length() == 0) {
                    rowSorter.setRowFilter(null);
                } else {
                    rowSorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                }
            }
        });

        // 검색 버튼 누를 때
        scanButton.addActionListener(e -> scanNetwork());

        setVisible(true);

        // 시작하자마자 자동 검색
        scanNetwork();
    }

    // 네트워크 스캔
    private void scanNetwork() {
        tableModel.setRowCount(0); // 초기화
        String baseIP = ipField.getText().trim();

        ExecutorService executor = Executors.newFixedThreadPool(50);

        for (int i = 1; i <= 254; i++) {
            final String ip = baseIP + ".0." + i;
            executor.execute(() -> {
                boolean isCamera = isCamera(ip);
                if (isCamera) {
                    SwingUtilities.invokeLater(() -> {
                        String displayIp = ip;
                        if (ipToMacMap.containsKey(ip)) {
                            displayIp += " (" + ipToMacMap.get(ip) + ")";
                        }
                        tableModel.addRow(new Object[]{
                                displayIp,
                                "카메라",
                                "접속"
                        });
                    });
                }
            });
        }
        executor.shutdown();
    }

    // 핑 + HTTPS SSL 오류 체크
    private boolean isCamera(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (!address.isReachable(500)) {
                return false; // 핑 실패
            }
            URL url = new URL("https://" + ip);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.connect();
            conn.disconnect();
            return false; // HTTPS 정상 연결
        } catch (SSLHandshakeException sslEx) {
            return true; // SSL 인증서 문제 -> 카메라
        } catch (Exception e) {
            return false;
        }
    }

    // IP를 숫자로 변환 (정렬용)
    private Long ipToLong(String ipAddress) {
        try {
            String[] parts = ipAddress.split("\\.");
            long ip = 0;
            for (int i = 0; i < parts.length; i++) {
                ip = ip << 8;
                ip += Integer.parseInt(parts[i]);
            }
            return ip;
        } catch (Exception e) {
            return 0L;
        }
    }

    // 상태 컬러 렌더링
    class StatusRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel label = new JLabel(value.toString(), SwingConstants.CENTER);
            if ("카메라".equals(value.toString())) {
                label.setForeground(new Color(0, 153, 0)); // 녹색
            }
            return label;
        }
    }

    // 접속 버튼 렌더러
    class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setText("접속");
        }
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            return this;
        }
    }

    // 접속 버튼 핸들러
    class ButtonEditor extends DefaultCellEditor {
        private JButton button = new JButton("접속");
        private String ip;
        private boolean isPushed;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button.addActionListener(e -> fireEditingStopped());
        }

        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            ip = table.getValueAt(row, 0).toString();
            if (ip.contains("(")) {
                ip = ip.substring(0, ip.indexOf("(")).trim();
            }
            isPushed = true;
            return button;
        }

        public Object getCellEditorValue() {
            if (isPushed) {
                String portText = portField.getText().trim();
                int port = portText.isEmpty() ? 443 : Integer.parseInt(portText);
                String url = "https://" + ip + ":" + port;
                try {
                    Desktop.getDesktop().browse(new URI(url));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, "브라우저 열기 실패: " + ex.getMessage());
                }
            }
            isPushed = false;
            return "접속";
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(HttpConnector_V4::new);
    }
}
