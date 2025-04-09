package httpconnector;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.net.*;
import java.util.concurrent.*;

class HttpConnector_v2 extends JFrame {
    private JTextField ipField, portField;
    private JButton scanButton;
    private JTable table;
    private DefaultTableModel tableModel;

    public HttpConnector_v2() {
        setTitle("IP 대역 HTTPS 접속기");
        setSize(600, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 입력 패널
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

        // 테이블 구성
        tableModel = new DefaultTableModel(new Object[]{"IP 주소", "상태", "접속"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return column == 2; // 접속 버튼만 클릭 가능
            }
        };
        table = new JTable(tableModel);
        table.getColumn("상태").setCellRenderer(new StatusRenderer());
        table.getColumn("접속").setCellRenderer(new ButtonRenderer());
        table.getColumn("접속").setCellEditor(new ButtonEditor(new JCheckBox()));

        add(new JScrollPane(table), BorderLayout.CENTER);

        // 이벤트
        scanButton.addActionListener(e -> scanNetwork());

        setVisible(true);
    }
    //검색결과 내림 차순으로 정렬
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

    //접속 상태 확인(ping)해서 "접속", "불가" 반환
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

    private boolean isReachable(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isReachable(500); // 타임아웃 500ms
        } catch (Exception e) {
            return false;
        }
    }

    // 상태 셀 렌더러 (JLabel 색상)
    class StatusRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel label = new JLabel(value.toString(), SwingConstants.CENTER);
            if ("접속 가능".equals(value.toString())) {
                label.setForeground(new Color(0, 153, 0)); // 녹색
            } else {
                label.setForeground(Color.RED);
            }
            return label;
        }
    }

    // 접속 버튼 렌더링
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

    // 접속 버튼 클릭 핸들링
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
        SwingUtilities.invokeLater(HttpConnector_v2::new);
    }
}
