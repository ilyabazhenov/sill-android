# Remote Mac: Android-клиент для Sill

Окна Mac и весь его рабочий стол на телефоне или планшете с Android. Управление касаниями, клавиатурой, мышью и стилусом.

Это неофициальный Android-клиент для [Sill](https://getsill.app) (открытый проект, Apache-2.0, [Saffsanity/sill](https://github.com/Saffsanity/sill)). На Mac работает стандартное приложение **Sill for Mac** без изменений. Это приложение говорит на его протоколе, поэтому iPhone/iPad с официальным Sill и Android-устройства подключаются к одному и тому же Mac.

## Что умеет

- **Поиск Mac в локальной сети** (Bonjour `_sill._tcp` через NSD).
- **Сопряжение** тремя способами:
  - касание «Not paired», затем Mac показывает код, его надо ввести;
  - сканирование QR-кода камерой (или через системную камеру, ссылка `sill://pair`);
  - адрес плюс код для сопряжения издалека.
- **Шифрование**: TLS 1.3, взаимный пиннинг ключей P-256. Ключ устройства хранится в Android Keystore.
- **Видео**: поток HEVC до 120 к/с, аппаратный декодер MediaCodec в режиме низкой задержки.
- **Окна**:
  - лента живых миниатюр;
  - долгое нажатие открывает «светофор»: закрыть, свернуть, полный экран;
  - кнопка «Рабочий стол»;
  - список всех приложений Mac с поиском и запуском.
- **Ввод напрямую**:
  - тап — клик, долгое нажатие — правый клик;
  - долгое нажатие и движение — перетаскивание;
  - прокрутка одним или двумя пальцами с инерцией;
  - мышь, тачпад и стилус работают как мышь Mac, включая колесо и наведение.
- **Режим трекпада** (телефон вертикально): картинка сверху, ряд клавиш, трекпад снизу.
  - тап — клик, тап двумя пальцами — правый клик;
  - прокрутка двумя пальцами;
  - долгое нажатие и движение — перетаскивание.
- **Жесты тремя пальцами**: Mission Control, App Exposé, переключение Spaces, Apps, Show Desktop.
- **Клавиатура**:
  - экранная: текст, Delete, Return;
  - ряд esc/tab/ctrl/opt/cmd/shift со стрелками, модификаторы фиксируются, чтобы делать сочетания вроде ⌘C;
  - аппаратная клавиатура передаёт каждую клавишу как есть (⌘ — клавиша Meta/Win).
- **Aa**: масштаб окна Mac под экран устройства (0.5×–1.5×).
- **Меню приложения Mac**: строка меню текущего приложения, подменю подгружаются при открытии, пункты нажимаются на Mac.
- **Указатель Mac**, когда мышью двигают на самом Mac, с формой курсора.
- **Буфер обмена в обе стороны**:
  - скопированное на Mac попадает в буфер Android;
  - «Paste on Mac» отправляет текст с телефона.
- **Настройки Mac с телефона**: качество (Low…Extreme), Retina/Standard, 60/120 к/с, приоритет скорости кодирования, виртуальный дисплей.
- **Удалённый доступ**:
  - через свой relay на VPS, без Tailscale: на Mac рядом с Sill работает маленький helper ([relay/README.md](relay/README.md));
  - через VPN (Tailscale и т.п.) или проброшенный порт;
  - адреса Mac приходят в подписанном сообщении; приложение пробует их параллельно, с шагом в 1 с.
- **Автопереподключение**: после обрыва, выхода Sill или ухода приложения в фон.

## Чего нет (только для Apple)

- Сопряжения по USB-кабелю: Mac распознаёт только кабельный интерфейс iPhone/iPad.
- Direct Wireless (AWDL): по Wi-Fi нужна общая сеть или VPN.

## Как пользоваться

1. Установите **Sill for Mac** с [getsill.app](https://getsill.app/download) и откройте его. Выдайте разрешения Screen Recording и Accessibility.
2. Установите APK на Android, подключитесь к той же Wi-Fi сети и откройте приложение.
3. Нажмите на свой Mac. В первый раз Mac покажет QR-код и 12-значный код. Отсканируйте QR или введите цифры.
4. Вне дома: включите Remote Access в настройках Sill на Mac. Дальше одно из двух: Tailscale на обоих устройствах или свой relay ([relay/README.md](relay/README.md)). Mac появится в разделе «Away from home».

## Сборка

Нужны JDK 17+ и Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release-сборка (`./gradlew :app:assembleRelease`) подписана debug-ключом, только для проверки. Для публикации подпишите своим ключом.

## Устройство проекта

- `protocol/`: модуль на чистом Kotlin/JVM, без Android.
  - формат сообщений Sill (`Wire.kt`, `Messages.kt`);
  - криптография сопряжения, сверенная байт в байт с эталонными векторами из Swift-кода Sill (`Pairing.kt`, тесты в `GoldenVectorsTest`);
  - самоподписанный сертификат устройства (`Identity.kt`);
  - TLS 1.3 с ALPN и пиннингом (`Transport.kt`);
  - клиенты сопряжения и сессии (`PairingClient.kt`, `Session.kt`);
  - парсер HEVC SPS.
- `protocol/.../cli/TestClient.kt`: консольный клиент. Говорит с настоящим хостом Sill так же, как приложение.
- `app/`: Android-приложение (Jetpack Compose).
  - `Controller`: подключение, сопряжение, сессия, переподключение, каталог;
  - `VideoDecoder`: MediaCodec, перевод в Annex-B;
  - `Discovery`: NSD;
  - `InputLayer`: касания, трекпад, жесты;
  - `HidKeys`: клавиши в USB HID;
  - экраны в `ui/`.
- `relay/`: relay на Go (`rmrelay` для VPS, `rmhelper` для Mac), устройство в `docs/relay.md`.
- `docs/sill-protocol-notes.md`: заметки о протоколе по коду iOS-клиента и хоста.
- `tools/vectors/main.swift`: генератор эталонных векторов из Swift-кода Sill.
- `reference/sill`: клон Sill для справки, в git не входит.

## Проверка без телефона

Хост Sill умеет тестовый режим: тестовая картинка вместо экрана, ввод никуда не отправляется.

```bash
git clone https://github.com/Saffsanity/sill reference/sill
```

```bash
(cd reference/sill && swift build -c release --product SillHost)
```

```bash
SILL_TEST_LOOPBACK=1 SILL_TEST_SOFTWARE_ENCODER=1 SILL_TEST_NO_ROUTER=1 SILL_TEST_REMOTE_DIR=/tmp/sillid reference/sill/.build/release/SillHost --synthetic --pairing
```

Хост напечатает код и ссылку `sill://pair…`. Порт можно узнать через `lsof -nP -iTCP -sTCP:LISTEN`.

```bash
./gradlew :protocol:installDist
```

```bash
protocol/build/install/protocol/bin/protocol 127.0.0.1 PORT --link 'sill://pair?…'
```

Из эмулятора Android хост доступен по адресу `10.0.2.2:PORT`: «Add a Mac…» → адрес → код.

## Лицензия

[GPL-3.0](LICENSE). Sill — отдельный проект своих авторов под Apache-2.0; это приложение с ними не связано.
