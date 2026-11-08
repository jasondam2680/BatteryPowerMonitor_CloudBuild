# Battery Power Monitor v3.6 (Shizuku ẩn, bố cục V2)

## Có gì mới ở v3.6 (sửa lỗi "Shizuku chưa chạy" khi Shizuku đang ẩn, theo báo cáo ngày 09/11)

**Vấn đề:** anh Jason đã hide Shizuku (tính năng Hide đổi tên package, vd. `moe.shizuku.privileged.api` → `...api.x7k9p`, để app ngân hàng không phát hiện) và không muốn un-hide. App vẫn báo "Shizuku chưa chạy" và đòi mở app Shizuku — mà icon launcher đã mất nên không mở được.

**Nguyên nhân:** `Shizuku.pingBinder()` chỉ trả về true/false, không phân biệt được "chưa cài" với "đã cài nhưng server chưa chạy". Mọi cách kiểm tra dựa trên tên package gốc đều mù khi package đã đổi tên.

**Cách sửa (dùng đoạn mã anh Jason cung cấp):** tra cứu quyền `moe.shizuku.manager.permission.API_V23` trong PackageManager (`getPermissionInfo`) để tìm package Shizuku hiện tại — bảng đăng ký quyền vẫn biết package nào khai báo quyền này dù tên đã đổi. Từ đó app phân biệt 4 trạng thái: chưa cài / đã cài nhưng server chưa chạy / chưa cấp quyền / sẵn sàng.
- **Nút "Mở Shizuku" mới:** hiện khi đã cài nhưng server chưa chạy — mở app Shizuku bằng explicit intent theo đúng package hiện tại (không cần icon launcher, không cần un-hide); nếu không có launch intent thì fallback sang trang App info trong Cài đặt.
- Khi server đã chết thì shell thường trực của v3.5 cũng được hủy hẳn để lần sau mở shell mới sạch.
- Report lỗi lúc Shizuku chưa sẵn sàng giờ mang `isPrivilegeGranted = false` (trước đây v3.5 để true) nên trạng thái hiện màu vàng và đúng nút hành động, thay vì xanh như đang đo tốt.

**Giới hạn cần biết:** đoạn tra cứu chỉ giúp *tìm* Shizuku đang ẩn và *mở* nó để khởi động lại server — server vẫn phải chạy thì app mới đo được VBUS. Nếu bấm "Mở Shizuku" mà không mở được gì (package bị disable ở mức hệ thống, khác với kiểu hide đổi tên), app sẽ mở trang tải Shizuku; lúc đó anh báo lại giúp Bé xem anh hide bằng cách nào.

## Có gì mới ở v3.5 (sửa lỗi ngắt Shizuku liên tục, theo ảnh anh Jason gửi 08/11)

**Triệu chứng:** cùng 1 củ sạc, cùng 1 phút, lúc đọc được VBUS 4,61V × 0,50A (= 2,31W, "XÁC THỰC QUA ADB"), lúc lại "(Không nhận được phản hồi từ tiến trình shell)" rồi rơi về "ƯỚC TÍNH TỪ P_BAT"; mục thông số truy xuất còn hiện "Đã tắt ở chế độ Phổ thông" dù header vẫn ghi chế độ 2.

**Nguyên nhân gốc (trong code v3.4):**
- Mỗi lần đo (1 giây/lần) app spawn 1 tiến trình shell MỚI qua Shizuku để chạy `dumpsys battery` — mỗi lần là 1 giao dịch binder + 1 phiên shell adbd mới qua Wi-Fi, chỉ cần 1 lần hiccup là lần đó chết, dù binder Shizuku vẫn sống ("Đã kết nối Shizuku: ADB Shell (UID 2000)").
- `readText()` chờ tới khi process đóng stdout — KHÔNG có timeout thật; `waitFor(1500ms)` đặt SAU `readText()` nên vô tác dụng. Shell kẹt thì cả vòng đo đứng im; stderr không ai xả nên còn có thể deadlock. Process kẹt không bị destroy nên xác shell tồn đọng, lần sau càng dễ fail.
- Một lần đọc lỗi là toàn bộ VBUS về null, chẩn đoán lật từ "XÁC THỰC QUA ADB" sang "ƯỚC TÍNH TỪ P_BAT"; report lỗi dùng nhầm giá trị mặc định của chế độ Phổ thông nên nhìn như app tự nhảy chế độ.

**Cách sửa:**
- **Shell thường trực:** mở 1 phiên `sh` qua Shizuku và tái dùng cho mọi lần đo (gửi lệnh qua stdin, đọc tới marker có nonce, timeout thật 3s). Shell kẹt/chết thì hủy hẳn và mở lại, có backoff 2s → 4s → 8s. Nếu mở shell thường trực không được, dự phòng spawn 1 lần nhưng có timeout thật + xả stderr + destroy khi quá hạn.
- **Giữ số đo cuối:** một lần đọc lỗi không xóa VBUS — giữ report tốt cuối cùng tối đa 60s, ghi rõ lý do lỗi + số lần lỗi liên tiếp + số giây từ lần đo cuối.
- **Probe VBUS 2s/lần** (P_bat vẫn 1s/lần): giá trị đàm phán thay đổi chậm, `dumpsys` nặng, không cần 1Hz.
- **Nhãn trung thực:** IBUS lấy từ dumpsys "Max charging current" là GIỚI HẠN đàm phán, không phải dòng thực tế tức thời — UI giờ ghi "giới hạn đàm phán". (Bằng chứng: P_bat 2,61W từng lớn hơn P_vbus 2,31W, điều không thể xảy ra nếu cả hai đều là công suất thực.)

**Anh cần chỉnh thêm trong app Shizuku (ảnh 7):** các mục "Bắt đầu khi khởi động", "Chó canh gác", "Chế độ TCP" của anh đã bật đúng — chỉ cần **TẮT "Auto-disable USB debugging"**: đang bật thì mỗi lần Shizuku dừng, USB debugging bị tắt theo, Shizuku không tự khởi động lại được nữa. Lưu ý: sau khi khởi động lại máy, Wireless Debugging mất pairing nên phải mở Shizuku bấm Start lại 1 lần — đó là giới hạn của Android, app giờ chỉ còn giữ số đo cuối trong lúc chờ anh bấm lại.

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
