# Battery Power Monitor v3.0 (Kiến trúc chuẩn kỹ thuật)

1. **Tách bạch bản chất vật lý của công suất**:
   - Hiển thị rõ **Công suất ước tính phía Cell Pin (`P_bat = V_bat * I_bat * Số_Cell_Nối_Tiếp`)**, hỗ trợ chuyển đổi cấu hình Pin đơn `1S (~4.4V)` và Pin kép nối tiếp `2S (~8.8V)`.
   - Quét đa đường dẫn `/sys/class/power_supply/*` để tìm `VBUS` và `IBUS`. Nếu SELinux trên máy không Root chặn quyền đọc, ứng dụng báo cáo minh bạch từng đường dẫn bị chặn và giải thích rõ sự khác biệt giữa công suất sau mạch Charge Pump và công suất đầu vào VBUS.
2. **Loại bỏ hoàn toàn lỗi suy luận vòng tròn (Circular Reasoning)**:
   - Không tự động chọn củ sạc theo công suất rồi dùng ngược lại củ sạc đó để kết luận chuẩn sạc (tránh việc tự gán nhãn PD 3.2 cho củ Belkin WCA013 khi chưa đọc được thương lượng PD từ Kernel).
   - Phân tách rõ nhãn **[✓ XÁC THỰC TỪ KERNEL]** và **[⚠️ SUY LUẬN TỪ P_BAT (SELINUX CHẶN SYSFS)]**.
3. **Thư viện Củ sạc đối chiếu thủ công có dẫn nguồn Teardown / Official FAQ**:
   - Ghi rõ trạng thái kiểm chứng từng mẫu (*Đã kiểm chứng Teardown ChargerLAB* vs *Thông số công bố của hãng*), kèm nút mở trực tiếp link bài mổ xẻ phần cứng.
4. **Foreground Service + Streaming CSV + Sống qua xoay màn hình + FileProvider**:
   - Chạy `ChargingMonitorService` hiển thị công suất trên thanh thông báo, giữ phiên ghi sống ngay cả khi tắt màn hình hoặc thoát ra nền.
   - Ghi nối tiếp (Append) từng mẫu trực tiếp xuống file `.csv` trên đĩa, giới hạn bộ đệm hiển thị ở 120 mẫu gần nhất.
   - Xóa lịch sử sẽ xóa sạch cả các file `.csv` vật lý trên đĩa; hỗ trợ nút **Chia sẻ / Mở CSV** qua `FileProvider`.
