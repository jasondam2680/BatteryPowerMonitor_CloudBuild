# Ứng dụng Đo Công Suất Sạc Pin (Battery Power Monitor)

Dự án đã được cấu hình sẵn GitHub Actions để tự động biên dịch ra file .apk trên đám mây mà không cần cài đặt Android Studio.

## Cách lấy file .apk bằng GitHub Actions (0 MB dung lượng máy)
1. Tạo một Repository mới trên GitHub (https://github.com/new).
2. Giải nén toàn bộ thư mục này và đẩy (hoặc Upload files) lên Repository vừa tạo (nhớ bao gồm cả thư mục .github/workflows/build-apk.yml).
3. Chuyển sang tab Actions trên GitHub, bấm vào workflow Build Android APK -> chọn Run workflow.
4. Đợi khoảng 2-3 phút để máy chủ GitHub biên dịch xong, kéo xuống mục Artifacts và tải file BatteryPowerMonitor-debug-apk (chứa app-debug.apk) về cài trực tiếp lên điện thoại Android.
