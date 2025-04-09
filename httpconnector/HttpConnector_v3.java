package httpconnector;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpConnector_v3 extends JFrame {
    private JTextField ipField, portField, searchField;
    private JButton scanButton;
    private JTable table;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;

    public HttpConnector_v3() {
        setTitle("IP 대역 HTTPS 접속기");
        setSize(700, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 상단 입력 패널
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

        // 테이블 모델
        tableModel = new DefaultTableModel(new Object[]{"IP 주소", "상태", "접속"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return column == 2; // 접속 버튼만 클릭 가능
            }
        };

        // 테이블 + 스크롤
        table = new JTable(tableModel);
        table.getColumn("상태").setCellRenderer(new StatusRenderer());
        table.getColumn("접속").setCellRenderer(new ButtonRenderer());
        table.getColumn("접속").setCellEditor(new ButtonEditor(new JCheckBox()));

        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        // 검색 패널
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(new JLabel("검색:"), BorderLayout.WEST);

        searchField = new JTextField();
        searchPanel.add(searchField, BorderLayout.CENTER);

        add(searchPanel, BorderLayout.SOUTH);

        // 검색 기능 + 정렬 기능
        rowSorter = new TableRowSorter<>(tableModel);

        // ⭐ IP 주소를 숫자 크기로 정렬하는 Comparator 추가 ⭐
        rowSorter.setComparator(0, (ip1, ip2) -> {
            return ipToLong(ip1.toString()).compareTo(ipToLong(ip2.toString()));
        });

        table.setRowSorter(rowSorter);

        // 검색창 입력시 필터링
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

        // 검색 버튼 클릭 이벤트
        scanButton.addActionListener(e -> scanNetwork());

        setVisible(true);
    }

    // 네트워크 스캔
    private void scanNetwork() {
        tableModel.setRowCount(0); // 초기화
        String baseIP = ipField.getText().trim();
        String portText = portField.getText().trim();
        int port = portText.isEmpty() ? 443 : Integer.parseInt(portText);

        ExecutorService executor = Executors.newFixedThreadPool(50);

        for (int i = 1; i <= 254; i++) {
            final String ip = baseIP + ".0." + i;
            executor.execute(() -> {
                boolean reachable = isReachable(ip);
                SwingUtilities.invokeLater(() -> {
                    tableModel.addRow(new Object[]{
                            ip,
                            reachable ? "접속 가능" : "불가",
                            "접속"
                    });
                });
            });
        }

        executor.shutdown();
    }

    // 핑 체크
    private boolean isReachable(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isReachable(500); // 타임아웃 500ms
        } catch (Exception e) {
            return false;
        }
    }

    // IP를 숫자로 변환
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

    // 상태 표시 렌더러
    class StatusRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel label = new JLabel(value.toString(), SwingConstants.CENTER);
            if ("접속 가능".equals(value.toString())) {
                label.setForeground(new Color(0, 153, 0)); // 녹색
            } else {
                label.setForeground(Color.RED); // 빨강색
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

    // 접속 버튼 에디터 (클릭 이벤트)
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
        SwingUtilities.invokeLater(HttpConnector_v3::new);
    }
}
