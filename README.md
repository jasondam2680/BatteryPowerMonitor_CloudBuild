# Battery Power Monitor v3.3 (Skeuomorphism + Aqua & Catalog mở rộng)

## Có gì mới ở v3.3

1. **Phong cách thiết kế Skeuomorphism + Aqua (thay hẳn Liquid Glass)**:
   - Thanh công cụ Aqua kiểu macOS X: chrome sáng, dải gloss, segmented control gel, đèn LED trạng thái REC.
   - Panel nội dung graphite chải xước tĩnh (không còn orb chuyển động), viền bevel sáng trên / tối dưới, bóng đổ thật, ô số kiểu LCD lõm.
   - Điều hướng giữ nguyên 3 tab + cửa sổ song song trên tablet/xoay ngang, nhưng nút chuyển tab nay là segmented control Aqua; rãnh phân tách 2 cửa sổ là rãnh kim loại (groove) thay cho dải sáng.
   - Dấu ấn thiết kế của Bé: chrome Aqua sáng + panel graphite tối (như một máy đo để bàn: vỏ chrome, mặt máy tối), để chữ sáng sẵn có trong app vẫn đọc được.
2. **Icon app mới (file PNG giao riêng, chưa nằm trong commit này vì connector GitHub của Bé không push được file nhị phân)**:
   - Hình: củ sạc kim loại chải xước + nút gel Aqua + tia sét vàng + đèn LED xanh + cổng USB-C.
   - Cách gắn: copy toàn bộ thư mục `res/mipmap-*` và `res/values/ic_launcher_background.xml` từ file zip `bpm-v33-icon.zip` vào `app/src/main/res/`, rồi thêm vào `<application>` trong `AndroidManifest.xml`:
     `android:icon="@mipmap/ic_launcher"` và `android:roundIcon="@mipmap/ic_launcher_round"`, sau đó push — lúc đó adaptive icon (foreground 432px + background `#0B1E3A`) và icon legacy đủ mật độ mdpi–xxxhdpi mới có hiệu lực.
3. **Catalog củ sạc: từ 15 lên 30 mẫu**:
   - Mới: Anker (A121D 45W, A2688 100W, A2697 140W), Samsung (EP-T2510 25W, EP-T4511 45W, EP-T6010 60W), Cuktech (15 140W, 10 Ultra 120W, A15C 65W), Ugreen (Nexode Pro 100W, Nexode Pro 160W, Nexode Air 65W), CMF by Nothing Power 65W GaN, Nothing Power 45W, Baseus GaN2 Lite C+U 65W (mã theo yêu cầu: CGAN65US).
   - Quy tắc ghi chú trong code: IC chưa có teardown thì ghi "Chưa công bố teardown", PPS chưa có dải APDO thì ghi "Chưa xác minh", kích thước khác nhau theo vùng thì ghi rõ biến thể đã chọn.
   - Lưu ý phạm vi, đọc kỹ trước khi dùng số liệu: củ Nothing 45W ra mắt 2022 (ngoài khung 3 năm, giữ vì là củ Nothing duy nhất); CMF 65W là thương hiệu con của Nothing, ra mắt 26/09/2023; năm ra mắt Samsung EP-T4511 chưa xác minh chính thức; Baseus CGAN65US chưa xác minh công khai (alias đã xác minh: CCGAN2L-B01/B02, 2022); ảnh CMF lấy từ trang support chính thức CMF (không phải ảnh cắt nền sản phẩm), ảnh Nothing lấy từ trang chính thức nothing.tech; mã model của cả 2 mẫu này đều chưa xác minh (các shop ghi mâu thuẫn).
   - Bộ lọc hãng trong tab tra cứu đã cập nhật đủ 10 hãng + "Tất cả". Củ tham chiếu mặc định vẫn là Belkin WCA013 (index 8, không đổi vì mẫu mới chèn sau mẫu cũ).

## Chế độ vận hành (không đổi từ v3.2)

- Chế độ 1 Phổ thông (API Android), chế độ 2 ADB qua Shizuku, chế độ 3 Root (`su -c`).
- Foreground Service + ghi CSV streaming, chia sẻ/xóa CSV qua FileProvider.
- Công suất tách riêng P_bat (V_bat × I_bat × số cell 1S/2S do người dùng chọn) và P_vbus (VBUS × IBUS khi đọc được sysfs), nhãn kernel-verified vs suy luận riêng.
