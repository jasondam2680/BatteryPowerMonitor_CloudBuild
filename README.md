# Battery Power Monitor v3.4 (Bố cục V2 + Chủ đề sáng)

## Có gì mới ở v3.4 (theo yêu cầu ngày 24/10, sau v3.3.1)

- **Bố cục quay về V2** (khôi phục từ commit v2.0 72486bd): Scaffold với header trên cùng chứa tiêu đề "Battery Power & Charger Analyzer", pill REC/STANDBY và TabRow 3 tab; nội dung chỉ 1 cột duy nhất trên mọi màn hình.
- **Bỏ hẳn**: cửa sổ song song tablet/landscape (split-window) của v3.2, thanh công cụ Aqua overlay nổi của v3.3, nền graphite tối.
- **Chủ đề sáng**: nền xám nhạt `#F1F5F9`, card trắng viền xám nhạt + bóng mờ nhẹ + dải accent 3px ở mép trên, chữ tối (tiêu đề `#0F172A`, chữ phụ slate), xanh lá/xanh dương đậm hơn để đọc được trên nền sáng.
- **Chức năng giữ nguyên v3.3.1**: 3 chế độ quyền (Normal/Shizuku/Root), catalog 29 mẫu, tách P_bat/P_vbus, Foreground Service + CSV streaming, chia sẻ/xóa CSV. Không đổi logic đo, chỉ đổi bố cục + màu sắc.
- Lưu ý: tên hàm `AquaSkeuoCard` / `AquaSegmentButton` vẫn giữ nguyên trong code để khỏi đổi ~30 nơi gọi, nhưng ruột đã là card sáng.



## Có gì mới ở v3.3.1 (sửa theo yêu cầu ngày 24/10, sau commit v3.3)

- **Catalog: bỏ CMF Power 65W GaN** theo yêu cầu (không còn trong dữ liệu, bộ lọc hãng cũng bỏ "CMF by Nothing").
- **Nothing Power 45W:** ảnh đổi sang URL do anh Jason cung cấp: `https://cdn.shopify.com/s/files/1/0586/3270/0077/files/0000s_0002_power-45w-in.png`.
- **Baseus GaN2 Lite:** mã đính chính thành **CCGAN65US** (trước đó ghi nhầm CGAN65US).
- **Icon mới:** nền trắng, củ sạc cách điệu thành nhân vật QC (kiểm tra chất lượng) giúp nhận biết củ sạc tốt — file PNG trong `bpm-v33-icon-white-qc.zip`, vẫn cần copy tay vào repo (xem mục 2).

## Có gì mới ở v3.3

1. **Phong cách thiết kế Skeuomorphism + Aqua (thay hẳn Liquid Glass)**:
   - Thanh công cụ Aqua kiểu macOS X: chrome sáng, dải gloss, segmented control gel, đèn LED trạng thái REC.
   - Panel nội dung graphite chải xước tĩnh (không còn orb chuyển động), viền bevel sáng trên / tối dưới, bóng đổ thật, ô số kiểu LCD lõm.
   - Điều hướng giữ nguyên 3 tab + cửa sổ song song trên tablet/xoay ngang, nhưng nút chuyển tab nay là segmented control Aqua; rãnh phân tách 2 cửa sổ là rãnh kim loại (groove) thay cho dải sáng.
   - Dấu ấn thiết kế của Bé: chrome Aqua sáng + panel graphite tối (như một máy đo để bàn: vỏ chrome, mặt máy tối), để chữ sáng sẵn có trong app vẫn đọc được.
2. **Icon app**: anh Jason đã tự thêm icon QC nền trắng vào repo ngày 24/10 (commit 51e5fe6, "Add iCon App" + manifest icon/roundIcon), nên v3.4 không động tới icon nữa.
3. **Catalog củ sạc: từ 15 lên 29 mẫu** (sau khi bỏ CMF ở v3.3.1):
   - Mới: Anker (A121D 45W, A2688 100W, A2697 140W), Samsung (EP-T2510 25W, EP-T4511 45W, EP-T6010 60W), Cuktech (15 140W, 10 Ultra 120W, A15C 65W), Ugreen (Nexode Pro 100W, Nexode Pro 160W, Nexode Air 65W), Nothing Power 45W, Baseus GaN2 Lite C+U 65W (mã đính chính: CCGAN65US).
   - Quy tắc ghi chú trong code: IC chưa có teardown thì ghi "Chưa công bố teardown", PPS chưa có dải APDO thì ghi "Chưa xác minh", kích thước khác nhau theo vùng thì ghi rõ biến thể đã chọn.
   - Lưu ý phạm vi, đọc kỹ trước khi dùng số liệu: củ Nothing 45W ra mắt 2022 (ngoài khung 3 năm, giữ vì là củ Nothing duy nhất); CMF 65W là thương hiệu con của Nothing, ra mắt 26/09/2023; năm ra mắt Samsung EP-T4511 chưa xác minh chính thức; Baseus CGAN65US chưa xác minh công khai (alias đã xác minh: CCGAN2L-B01/B02, 2022); ảnh CMF lấy từ trang support chính thức CMF (không phải ảnh cắt nền sản phẩm), ảnh Nothing lấy từ trang chính thức nothing.tech; mã model của cả 2 mẫu này đều chưa xác minh (các shop ghi mâu thuẫn).
   - Bộ lọc hãng trong tab tra cứu đã cập nhật đủ 9 hãng + "Tất cả" (đã bỏ CMF). Củ tham chiếu mặc định vẫn là Belkin WCA013 (index 8, không đổi vì mẫu mới chèn sau mẫu cũ).

## Chế độ vận hành (không đổi từ v3.2)

- Chế độ 1 Phổ thông (API Android), chế độ 2 ADB qua Shizuku, chế độ 3 Root (`su -c`).
- Foreground Service + ghi CSV streaming, chia sẻ/xóa CSV qua FileProvider.
- Công suất tách riêng P_bat (V_bat × I_bat × số cell 1S/2S do người dùng chọn) và P_vbus (VBUS × IBUS khi đọc được sysfs), nhãn kernel-verified vs suy luận riêng.
