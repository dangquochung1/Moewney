# Merge nhánh 1.1 vào main (đã có PR #2)

## Bối cảnh
- `main` (origin) = PR #2 `51016862` "Giao diện mềm, widget phòng mèo, thông báo từ mèo, tab Phân tích".
- `feature/1.1-meo-hanh-tinh` = bản 1.1 của Hùng: 1.1 Mốc 1–7 (mèo lười + tiếng gừ gừ, mèo trên hành tinh thay phòng tròn, giao diện kiểu app thường, trang chủ mới + thanh điều hướng 6 mục, lịch Ngày/Tuần/Tháng, Quà còn 4 tab + Cửa hàng gộp Tủ đồ, ghi công + README 1.1.0), `run_emulator.bat`, commit "assets".
- Hai nhánh tách từ `3f1c2452` và **cả hai đều làm lại giao diện**. Thử `git merge` ra 18 file conflict:
  README.md, core/data/SettingsStore.kt, core/ui/{Theme,PixelKit,PixelForms,PixelPickers}.kt, feature/calendar/{CalendarScreen,CalendarViews}.kt, feature/game/{GiftScreen,TrophyTabs}.kt, feature/habit/{HabitScreens,PomodoroScreen}.kt, feature/money/{MoneyScreens,ReportScreens}.kt, feature/notes/DiaryScreens.kt, feature/room/HomeScreen.kt, widget/WidgetArt.kt, tools/build_room.py (1.1 xoá, PR #2 sửa).
- Ngoài ra **các file tự merge được vẫn sẽ không biên dịch**: code PR #2 gọi `RoomAssets`, ảnh `assets/room/*.png` (1.1 đã xoá cả phòng tròn: RoomAssets.kt, CatStage.kt, StageGeometry.kt, walls_floor/interior/doors_windows.png, build_room.py); dùng `Pal.Well/GoldDeep/GreenTint/...`, `ButtonLook.Primary/Plain`, `PixelPanel(sprite=…)` trong khi màn 1.1 dùng `Pal.Primary/Selected/Mint/...`, `ButtonLook.Orange/Light`, `PixelPanel(tone=CardTone…)`.

## Hùng đã quyết
1. **Giữ con mèo + hành tinh và các màn hình/luồng của 1.1**: PlanetStage, ParallaxSky/SkyLibrary, Cat.kt (mèo lười, ngủ theo giờ), Purr, BottomBar 6 mục, trang chủ mới, lịch Ngày/Tuần/Tháng, Quà 4 tab, Cửa hàng gộp Tủ đồ, DB v7 (`week_goal`).
2. **Lấy màu + component của PR #2**: bảng màu Canvas/Gold/Green/Sky 3 sắc độ, `Soft` shapes, `softSurface`, nút vàng, chip, `SegmentedTabs`, `RoundIconButton`, `IconChip`, phím xoá dạng icon, nút quay lại tròn. (Bảng 6 màu pastel của 1.1 bỏ.)
3. **Giữ tính năng PR #2**: tab Phân tích, thẻ tổng tài sản + lọc Chi/Thu/Chuyển + tìm ở Tiền, CatNotifier/NudgeEngine + bật/tắt từng loại + Gửi thử, nút thêm widget trong Cài đặt, README viết lại.
4. **Bỏ "Hôm nay còn được tiêu"** như PR #2 (phần engine đã tự merge).
5. **Widget: giữ logic PR #2** (CatScene, không hiện tiền, đồ trong Tủ đồ) **nhưng vẽ cảnh bầu trời + hành tinh của 1.1**, không khôi phục phòng.

## Các bước
Làm trên một nhánh tạm: `git switch main && git pull && git switch -c merge/1.1 && git merge origin/feature/1.1-meo-hanh-tinh`.
Trong mô tả dưới: **"PR #2"** = phía main, **"1.1"** = phía nhánh feature (không dùng ours/theirs cho khỏi lẫn).

### 1. Theme / component — PR #2 làm gốc, thêm API 1.1 còn được gọi
- `core/ui/Theme.kt`: lấy `object Pal` của PR #2 (gồm `Series`, `Night`, `Shadow`…). Thêm tên vai trò 1.1 làm alias để màn 1.1 tự lên màu mới: `Primary = Gold`, `Selected = Gold`, `Mint = Green`, `Accent = Gold`, `AccentText = GoldDeep`, `Track = Line`. Xoá `LemonCream/CoralReef/OceanMist/MorningSky/GoldenDune/SunsetClay` và sửa chỗ gọi sang vai trò. Phần dưới `Pal` (PixelMetrics, PixelType, AppFont Nunito, PixelFont, MeowneyTheme) lấy 1.1 — không conflict. `Pal.Jars` giữ.
- `core/ui/PixelKit.kt`: lấy PR #2. Bổ sung thứ chỉ 1.1 có mà còn được gọi:
  - `enum class CardTone` (Plain→Card, Faded→Well, Sky→SkyTint, Lemon→GoldTint, Selected→GoldTint + viền Gold) và tham số `tone: CardTone = CardTone.Plain` cho `PixelPanel` (giữ cả `sprite` để code PR #2 chạy; `"disabled" in sprite` ≙ Faded).
  - `TickBox(checked, size)` vẽ cùng kiểu `PixelCheckbox` PR #2.
  - tham số `shape` của `PixelButton`; `CardRadius` = 20.dp (bằng `Soft.Card`).
  - Đổi caller 1.1: `ButtonLook.Orange` → `Primary`, `ButtonLook.Light` → `Plain` (grep toàn `app/src`).
- `core/ui/PixelForms.kt`: lấy PR #2. Giữ `PixelTabs<T>` và `BackButton` của 1.1 nhưng viết lại bên trong bằng `SegmentedTabs` / `RoundIconButton`.
- `core/ui/PixelPickers.kt`: lấy PR #2, giữ `DayDot` của 1.1 (màu Gold / GoldTint).
- `core/ui/BottomBar.kt` (1.1, không conflict): đổi màu sang palette PR #2 nếu đụng tên đã xoá.

### 2. Màn hình conflict — bố cục/luồng 1.1, component + màu PR #2, bỏ mọi thứ dính allowance
- `feature/room/HomeScreen.kt`: cấu trúc 1.1 (PlanetStage, thẻ trang chủ mới, BottomBar). Lời chào không còn "hôm nay còn" (theo PR #2).
- `feature/calendar/CalendarScreen.kt`, `CalendarViews.kt`: 1.1 (Ngày/Tuần/Tháng, `settings.calendarView`); bỏ dấu ngày vượt hạn mức và bước ngày lương như PR #2.
- `feature/game/GiftScreen.kt`, `TrophyTabs.kt`: 1.1 (4 tab, `GiftTab.of` map tab cũ, Cửa hàng gộp Tủ đồ) + chip/tab PR #2; bỏ thử thách "Giữ trong hạn mức".
- `feature/money/MoneyScreens.kt`, `ReportScreens.kt`: PR #2 (thẻ tổng tài sản, chip, lọc, tìm; tab Phân tích) + BottomBar/điều hướng 1.1.
- `feature/habit/HabitScreens.kt`, `PomodoroScreen.kt`, `feature/notes/DiaryScreens.kt`: hợp hai bên — Pomodoro dùng PlanetStage (1.1); lưới thói quen và lịch nhật ký bỏ kiểu gỗ (PR #2).
- `core/data/SettingsStore.kt`: hợp key hai bên (1.1: `calendarView`…; PR #2: các toggle thông báo, `lastOpenDay`…); bỏ key chỉ phục vụ allowance nếu PR #2 đã bỏ.
- `README.md`: lấy bản viết lại PR #2, thêm phần 1.1 (mèo trên hành tinh, tiếng gừ gừ, lịch 3 chế độ, Quà 4 tab, ghi công tài nguyên + Nunito OFL), version 1.1.0. Ảnh `docs/screenshots` chụp lại sau khi xong.

### 3. Widget vẽ cảnh hành tinh
- `widget/WidgetArt.kt`: giữ `paint`, `text`, `night`, `pose(CatScene)`, `bang`, `zzz`, `button` của PR #2. Thay `sceneBitmap/roomLayer` bằng một hàm vẽ cảnh hành tinh:
  - nền: các lớp `SkyLibrary.sky(HOME_DAY | HOME_NIGHT).layers` (đứng yên, không drift);
  - hành tinh `SkyLibrary.planet("earth" | "moon")`, vị trí/scale theo `PlanetScene.of(w, h)` (`cx, cy, feetX, feetY, scale`) — xem `feature/room/PlanetStage.kt`;
  - bóng oval + sprite mèo `cat/<color>/<pose>` tại `feetX/feetY`; dấu "!" / "zzz" theo CatScene; `SeasonOverlay.behind/front` và mũ (`look.hat`) nếu gọn.
  - chữ ký mới: `scene(atlas, skies: SkyLibrary, s, w, h, hour, radius, plus)`.
- `widget/MeowneyWidgets.kt`: truyền `c.skies` thay `c.room` / `c.roomWide`.
- `MeowneyApp.kt`: xoá `room`, `roomWide` (RoomAssets).
- Xoá `app/src/main/assets/room/widget.json`; `git rm tools/build_room.py`.
- `res/drawable-nodpi/widget_preview_small.png`, `widget_preview_wide.png`: chụp lại từ widget mới.
- `TodaySnapshot.look: RoomLook` giữ — `RoomLook` của 1.1 vẫn có `hat`, `season`.

### 4. Biên dịch sạch
Hết dấu conflict (`git grep -nE '^(<<<<<<<|>>>>>>>)'` rỗng) rồi build, sửa lỗi còn lại ở file tự merge (AnalyticsScreen, SettingsScreens, CatNotifier, OnboardingScreen, EntryScreen, GoalScreens…) — chủ yếu tên màu, `ButtonLook`, `sprite=`, `RoomAssets`.

## Kiểm tra
1. `gradlew :app:assembleDebug` xanh.
2. `gradlew :app:testDebugUnitTest` pass hết (test 1.1: CatBrainTest, PlanetSceneTest, CalendarViewsTest, MigrationTest v7…; test PR #2: AnalyticsEngineTest, NudgeEngineTest, CatSceneTest — sửa CatSceneTest nếu nó dựa vào phòng).
3. `run_emulator.bat` rồi xem: Trang chủ (hành tinh + màu mới), Tiền (lọc/tìm), Tổng kết › Phân tích, Lịch Ngày/Tuần/Tháng, Quà 4 tab, Pomodoro, Nhật ký, Cài đặt › Thông báo (Gửi thử), widget 2×2 và 4×2 ban ngày/đêm.
4. Gửi ảnh cho Hùng duyệt rồi mới mở PR `merge/1.1` → `main`.
