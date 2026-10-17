# Relay: Mac вне дома без Tailscale

Как устроено и почему так: [docs/relay.md](../docs/relay.md). Здесь две программы:

- `rmrelay`: сервер на VPS с белым IP. Ничего не хранит и не может прочитать трафик.
- `rmhelper`: на Mac рядом с Sill. Держит соединение с relay и передаёт подключения телефона в Sill.

Нужен Go 1.22+.

## 1. Сервер

Подойдёт любой VPS с Linux. Лучше ближе к вам: задержка картинки = пинг до VPS туда и обратно.

```bash
GOOS=linux GOARCH=amd64 go build -o rmrelay ./cmd/rmrelay
```

```bash
scp rmrelay deploy/rmrelay.service root@VPS:/tmp/
```

На сервере:

```bash
install -m 755 /tmp/rmrelay /usr/local/bin/ && install -m 644 /tmp/rmrelay.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now rmrelay
```

Откройте TCP 7456 в файрволе (например, `ufw allow 7456/tcp`). Логи: `journalctl -u rmrelay -f`.

## 2. Mac

В Sill: Settings → Remote Access → включить. «Allow connections from the internet» не нужен.

```bash
go build -o rmhelper ./cmd/rmhelper
```

```bash
./rmhelper install -relay VPS:7456
```

Helper запустится сейчас и при каждом входе в систему (LaunchAgent `dev.remotemac.helper`), а в терминале
появится QR-код. Ключи и настройки лежат в `~/Library/Application Support/RemoteMac Helper/`,
лог в `~/Library/Logs/RemoteMac Helper.log`.

QR-код содержит ключ доступа: не публикуйте его. Сеанс без сопряжённого телефона он не даёт, но открывает дверь
Sill для попыток.

Если порт remote door в Sill не 7455, добавьте `-sill-port N`.

## 3. Телефон

1. Сопрягите телефон с Mac как обычно (дома).
2. Отсканируйте QR от helper системной камерой или кнопкой «Scan Pairing Code» в приложении.
   Показать код снова: `./rmhelper link`, или `./rmhelper link -png qr.png`.
   Сопряжение через relay закрыто: сопрягайтесь дома.

В разделе «Away from home» у Mac появится «Remote · relay». Вне дома приложение сначала пробует адреса Sill
(VPN, LAN), потом relay.

## Потерянный телефон

1. Удалите его в Sill на Mac: его сеансы оборвутся, ключ больше не примут.
2. Смените ключ доступа: старый QR-код перестанет пускать к Mac.

```bash
./rmhelper new-access
```

Оставшиеся телефоны сканируют новый код.

Если Sill на Mac переустановили с новым ключом, прочитайте его заново: `./rmhelper link -refresh`.

## Удаление

```bash
./rmhelper uninstall
```

## Проверка

```bash
go test -race ./...
```

Проверка с настоящим Sill без телефона: тестовый SillHost с `--remote=17455`, `rmrelay -listen 127.0.0.1:17456`,
`rmhelper run -relay 127.0.0.1:17456 -sill-port 17455`, сопрягите консольный клиент (README в корне), затем

```bash
protocol/build/install/protocol/bin/protocol 0 0 --relay-link "$(rmhelper link -relay 127.0.0.1:17456 -sill-port 17455 | grep ^remotemac)"
```

Из эмулятора Android relay виден как `10.0.2.2:17456`: QR для него печатает `rmhelper link -relay 10.0.2.2:17456`.

Для такой проверки не используйте `rmhelper install`: он ставит LaunchAgent в вашу настоящую сессию.
Используйте `rmhelper run`.
