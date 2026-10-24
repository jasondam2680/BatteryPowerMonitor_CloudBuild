# Battery Power Monitor v3.2 (Liquid Glass UI & Tablet Parallel Split-Window)

1. **Phong cách thiết kế Liquid Glass (Kính lông mờ đa lớp & Phản chiếu ánh sáng)**:
   - Nền động `LiquidGlassAmbientBackground` với 3 luồng ánh sáng chuyển động mượt mà phía dưới các tấm kính mờ.
   - Khu vực Menu `LiquidGlassTopMenuOverlay` nằm ở lớp trên cùng (`zIndex = 10f`), phản chiếu ánh sáng động (Specular Shimmer & Caustic Highlight) qua lớp kính lông mờ xuống các thành phần đồ họa bên dưới khi cuộn.
2. **Giao diện Máy tính bảng / Xoay ngang (Parallel Split-Window)**:
   - Tự động kích hoạt bố cục 2 cửa sổ song song khi xoay ngang hoặc trên màn hình rộng (`screenWidthDp >= 600`), cho phép chọn độc lập nội dung của **Cửa sổ Trái** và **Cửa sổ Phải** ngay trên thanh Menu Liquid Glass.
