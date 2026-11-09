# Meowney

App Android cá nhân: tiền (6 chiếc lọ), thói quen, lịch, sổ tay — với một con mèo pixel.

## Build & cài

```
gradlew.bat testDebugUnitTest assembleDebug
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Art pipeline (`tools/`, chạy bằng `python -I`)

`assets/` là kho art gốc (1,1 GB, không đưa vào git). Các script chép phần đã chọn vào app:

| Script | Làm gì |
|---|---|
| `tools/patch_font.py` | Pixeloid + 32 chữ Việt còn thiếu → `res/font/meowney_pixel_*.ttf` |
| `tools/pack_assets.py` | Sprite chọn lọc → `app/src/main/assets/sprites/` (atlas + `sprites.json`) |
| `tools/build_room.py` | Góc mèo trong vòng tròn → `app/src/main/assets/room/corner.json` (`--preview x.png`) |
| `tools/make_icon.py` | Icon mèo ôm xu (`--sheet x.png` xem phương án, `--pick N` chọn; đang dùng 5) |

Chạy lại `pack_assets.py` trước `build_room.py` / `make_icon.py` (hai script sau đọc atlas).
