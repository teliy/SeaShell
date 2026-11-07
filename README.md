<div align="center">

<img src=".github/icon.png" width="128" alt="SeaShell icon"/>

# 🐚 SeaShell

**PuTTY-подобный SSH-клиент для Android**

Kotlin · minSdk 24 (Android 7.0) · JSch + xterm.js

</div>

---

## Возможности

- 🖥 **Интерактивный терминал** — полноценная эмуляция xterm-256color (цвета, курсор, скроллбек 5000 строк) на базе xterm.js
- 💾 **Список серверов** — сохранение подключений (хост, порт, пользователь, способ входа), редактирование, дублирование, удаление
- 🔑 **Аутентификация** — по паролю или приватным ключом (OpenSSH / PEM), с passphrase; поддержка keyboard-interactive (PAM)
- 🛡 **Проверка ключей хостов** — как в PuTTY: диалог подтверждения при первом подключении и запоминание ключа (known hosts)
- 🎨 **6 тем оформления** — Океан, Графит, Закат, Лес, Аметист, Роза (светлые и тёмные варианты)
- 🌍 **6 языков** — русский, English, français, 中文, العربية (RTL), español; переключение внутри приложения
- ✍️ **Удобства терминала** — вставка из буфера обмена, изменение размера шрифта, экран не гаснет во время сессии
- 🤖 **Поддержка Android 7.0+** (API 24), адаптивная иконка и monochrome-слой для Android 13+

## Установка

Скачайте готовый `SeaShell-1.0.0.apk` из раздела [Releases](https://github.com/teliy/SeaShell/releases) и установите его
(при необходимости разрешите установку из неизвестных источников).

## Сборка из исходников

```bash
git clone https://github.com/teliy/SeaShell.git
cd SeaShell
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Требуется JDK 17+ и Android SDK (platform 34). Также работает кнопка **Code → Actions → build** —
GitHub Actions соберёт APK автоматически.

## Сборка терминала

Терминал — это WebView + [xterm.js](https://xtermjs.org/) (файлы уже лежат в `app/src/main/assets/term/`),
обмен данными идёт с JSch (`com.github.mwiede:jsch`) через `JavascriptInterface`.
Реализовано: PTY, изменение размера окна (window-change), UTF-8, keep-alive каждые 30 секунд.

## Примечания по безопасности

- Приватные ключи не копируются в хранилище приложения — читаются напрямую из выбранного файла (SAF).
- Пароли и known hosts хранятся в приватном хранилище приложения (SharedPreferences, доступно только SeaShell).
  В планах — миграция на EncryptedSharedPreferences.
- Поддерживаются ключи OpenSSH и PEM; PuTTY `.ppk` пока не поддерживается.

## Структура

```
app/src/main/java/com/teliy/seashell/
├── MainActivity.kt            # список серверов
├── SessionEditorActivity.kt   # редактор подключения
├── TerminalActivity.kt        # SSH-терминал (JSch + xterm.js)
├── SettingsActivity.kt        # темы, язык, known hosts
├── KnownHostsRepository.kt    # хранилище ключей хостов
├── ShellUserInfo.kt           # диалоги (host key, keyboard-interactive)
├── SessionStore.kt            # сохранение подключений
├── ThemeManager.kt            # 6 тем оформления
└── ...
```

## Лицензия

[MIT](LICENSE) © 2026 teliy

---

<div align="center">

### English summary

SeaShell is an open-source PuTTY-style SSH client for Android 7.0+: saved server list,
interactive xterm-256color terminal (xterm.js + JSch), password/key auth, host key verification,
6 color themes and 6 languages (ru/en/fr/zh/ar/es with RTL), font size control and clipboard paste.

</div>
