# 30EBSE — 작업 전에 읽을 것

29EBWO 에서 구글 OAuth 를 걷어 낸 **공개 배포판**. 파일은 SAF 선택창으로 들인다.
설계와 까닭은 `README.md` 에 있다. 여기는 **다른 맥(회사 맥 포함)에서 이어 작업하기 위한
준비**만 적는다.

## 저장소에 없는 것 세 가지 — 구글 드라이브에서 가져온다

릴리스 서명 키, 그 암호, 글꼴은 git 에 넣지 않는다(`.gitignore`). 원본은 사용자의
구글 드라이브 `My Drive/_keys/30EBSE/` 에 있다.

```
_keys/30EBSE/
  keystore/30ebse.jks      릴리스 키 (PKCS12, RSA 4096, alias 30ebse)
  keystore.properties      storeFile·storePassword·keyAlias·keyPassword
  fonts/                   a2z_regular.ttf · geist_mono.ttf · geist_mono_italic.ttf
```

맥에 Google Drive 데스크톱 앱이 로그인돼 있으면(계정 폴더 이름은 맥마다 다를 수 있다):

```bash
GD="$(ls -d "$HOME/Library/CloudStorage/GoogleDrive-"*/"My Drive/_keys/30EBSE" | head -1)"
cp -R "$GD/keystore" ~/MyGit/30EBSE/
cp "$GD/keystore.properties" ~/MyGit/30EBSE/
cp "$GD/fonts/"*.ttf ~/MyGit/30EBSE/app/src/main/res/font/
chmod 600 ~/MyGit/30EBSE/keystore.properties
```

드라이브 앱이 없으면 사용자에게 drive.google.com 에서 그 폴더를 받아 달라고 한다.
**키 파일·암호를 저장소, 이슈, 커밋 메시지, 채팅 어디에도 옮기지 않는다.**

가져온 뒤 **서명이 맞는지 반드시 확인한다.** 다른 키로 서명한 APK 는 이미 깔린 앱 위에
덮어 설치되지 않고, 사용자가 지우고 다시 깔면 가져온 글과 읽던 자리가 사라진다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
"$(ls -d ~/Library/Android/sdk/build-tools/* | tail -1)/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep SHA-1
# 반드시: e82bbb4f1298b617fa120d4a5c5b988e938f6e1c
```

- 릴리스 키 SHA-1   `E8:2B:BB:4F:12:98:B6:17:FA:12:0D:4A:5C:5B:98:8E:93:8F:6E:1C`
- 릴리스 키 SHA-256 `85:53:7F:B5:DD:B6:2C:80:CD:58:79:86:B4:42:19:DB:01:89:0E:D5:C9:D3:86:37:0B:D3:EF:A8:42:87:C5:92`

`keystore.properties` 가 없으면 빌드는 되지만 **서명 없는 APK** 가 나온다 — 릴리스에
올리지 않는다. 글꼴이 없어도 빌드는 되지만 기기 기본 글꼴로 나온다 — 역시 올리지 않는다.

## 29EBWO 와 헷갈리지 않기

|  | 29EBWO | 30EBSE |
|---|---|---|
| 대상 | 본인과 소수, 비공개 | 일반 배포 |
| 패키지 | `com.artbrain.ebwo` | `com.artbrain.ebse` |
| 글 가져오기 | 드라이브 REST API + OAuth | SAF 선택창 (구글 연동 없음) |
| 서명 | `~/.android/debug.keystore` (디버그 키) | 전용 릴리스 키 (위) |
| 새로고침 단추 | `↩` | `*` (파일 고르기) |

두 앱은 한 기기에 같이 깔린다. 30EBSE 에서 잘 된 것(파일 들이기)은 나중에 29EBWO 로도 옮길 예정이다.

## 환경

- JDK 는 Android Studio 에 든 JBR (`JAVA_HOME` 위). `local.properties` 에 `sdk.dir`.
- 시험 기기: Boox Poke4 Lite(Android 11, e-ink), Nothing Phone (2a).
- Boox 는 새 앱을 자동 동결한다 → 설치 뒤 `adb shell pm enable com.artbrain.ebse`.
- Boox 의 `screencap -p` 는 PNG 앞에 `capture from screenshot!` 을 찍는다 — PNG 머리부터 자른다.
- Boox 의 파일 선택창(DocumentsUI)은 `adb shell input tap` 으로 폴더가 열리지 않았다.
  변환은 단위 시험(`testDebugUnitTest`)과 기기 시험(PdfTest)으로 본다 — README 의 "시험".

## 약속

- **버전 번호는 사용자에게 묻고 정한다.** 한 번 릴리스한 번호는 다시 쓰지 않는다.
- 사용자와는 한국어로. UI 문구는 한국어, 단추 이름은 영어(`Close` `Undo` `Install`).
- 커밋 메시지는 한국어로, 29EBWO 와 같은 꼴(`0.1.1 — 요약`).
