# SSH 리모트 (안드로이드)

휴대폰에서 맥북(또는 SSH 가 켜진 컴퓨터)에 **버튼 한 번으로 명령을 보내는** 가벼운 앱입니다.
터미널 화면은 없고, 다음 세 가지만 합니다.

1. 호스트 등록: IP · 포트 · 계정 · 비밀번호(또는 개인키)
2. 원클릭 실행: "맥북 잠자기", "맥북 끄기" 같은 명령 버튼
3. 결과 확인: stdout / stderr / 종료 코드 / 걸린 시간

Kotlin + Jetpack Compose, MVVM + Repository, Room, SSHJ, Android Keystore.

## 폴더 구조

```
app/src/main/java/com/example/sshremote/
├── data/
│   ├── local/
│   │   ├── HostEntity.kt          # 호스트 (이름, IP, 포트, 계정, 암호화된 비밀번호/키, 서버 지문) + DAO
│   │   ├── SnippetEntity.kt       # 명령 버튼 (이름, 셸 명령, 위험 여부, sudo 자동 입력) + DAO
│   │   ├── AppDatabase.kt         # Room 데이터베이스
│   │   └── CryptoManager.kt       # Android Keystore AES-256-GCM 암호화
│   ├── ssh/
│   │   └── SshCommandRunner.kt    # SSHJ 접속, 서버 지문 확인, 명령 실행, 타임아웃
│   └── repository/
│       └── RemoteControlRepository.kt
├── ui/
│   ├── screens/
│   │   ├── HostListScreen.kt      # 호스트 목록 · 추가 · 수정 · 삭제
│   │   ├── SnippetScreen.kt       # 명령 버튼 · 실행 결과
│   │   └── Dialogs.kt             # 호스트/명령 편집, 위험 명령 확인, 서버 지문 확인 창
│   ├── theme/Theme.kt
│   └── viewmodel/MainViewModel.kt
├── SshRemoteApp.kt                # BouncyCastle 등록, 저장소 생성
└── MainActivity.kt                # 화면 이동 (호스트 목록 → 명령 화면)
```

## 맥북 준비 (한 번만)

1. **원격 로그인 켜기**: 시스템 설정 › 일반 › 공유 › 원격 로그인 (켬)
2. **IP 확인**: 시스템 설정 › Wi-Fi › 연결된 네트워크의 "세부사항" (예: `192.168.0.10`)
   - 공유기에서 맥북 IP 를 고정(DHCP 예약)해 두면 IP 가 바뀌지 않습니다.
3. **계정 이름 확인**: 터미널에서 `whoami`
4. **서버 지문 확인** (앱에서 처음 접속할 때 비교용):
   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```

### 끄기 · 재시작 명령의 sudo 비밀번호

`sudo shutdown -h now` 는 관리자 비밀번호가 필요합니다. 둘 중 하나를 고르세요.

- **방법 1 (기본)**: 명령 편집에서 "sudo 비밀번호 자동 입력"을 켜면, 호스트 비밀번호를 `sudo -S` 로 넘깁니다.
  (맥 로그인 비밀번호와 관리자 비밀번호가 같을 때. 비밀번호 인증 호스트에서만 가능)
- **방법 2 (개인키 인증일 때)**: 맥에서 끄기 · 재시작만 비밀번호 없이 허용합니다.
  ```bash
  sudo visudo -f /etc/sudoers.d/ssh-remote
  ```
  아래 한 줄을 넣고 저장 (`내계정` 은 `whoami` 결과로 바꿈):
  ```
  내계정 ALL=(root) NOPASSWD: /sbin/shutdown
  ```

`pmset sleepnow`(잠자기), `pmset displaysleepnow`(화면 끄기)는 sudo 없이 됩니다.

## 앱 사용

1. "호스트 추가" → 이름 · IP · 포트(22) · 계정 · 비밀번호 입력
2. 호스트를 누르고 오른쪽 위 연결 테스트 버튼 → 처음 접속이면 **서버 지문 확인 창**이 뜸 → 맥에서 확인한 값과 같으면 승인
3. "맥북 기본 명령 추가" → 상태 확인 · 화면 끄기 · 잠자기 · 재시작 · 끄기 5개가 생김
4. 버튼의 "실행" → 아래 "실행 결과"에 출력과 종료 코드가 표시됨
   - 끄기 · 재시작처럼 빨간 버튼은 한 번 더 확인합니다.
   - 끄기 · 재시작은 실행 중 연결이 끊기는 것이 정상이며 "연결 끊김"으로 표시됩니다.

## 보안 설계

| 항목 | 방식 |
|---|---|
| 비밀번호 · 개인키 저장 | Android Keystore 의 AES-256 키로 AES/GCM 암호화, `v1:IV:암호문` 형태로 Room 에 저장. 평문 저장 안 함 |
| 복호화 시점 | 명령 실행 직전에만 메모리에서 복호화, 실행 후 참조 버림 |
| 서버 지문 | 처음 접속 때 SHA-256 지문을 보여주고 승인하면 저장. 이후 지문이 다르면 접속을 막고 경고 (모든 키 허용 `PromiscuousVerifier` 는 쓰지 않음) |
| 주소 변경 | 호스트의 IP/포트를 바꾸면 저장된 지문을 지우고 다시 확인 |
| 백업 | 앱 데이터 백업 · 기기 이전 제외 (Keystore 키는 옮겨지지 않으므로) |
| 타임아웃 | 연결 10초, 명령 30초. 시간 초과면 연결을 끊어 앱이 멈추지 않음 |

## 설치 (APK)

이 저장소에 푸시하면 GitHub Actions 가 APK 를 만듭니다.

- **main 브랜치**: 저장소 Releases 의 `android-latest` 에서 `ssh-remote.apk` 내려받기
- **다른 브랜치**: Actions › 해당 실행 › Artifacts 의 `ssh-remote-apk`

휴대폰에서 처음 한 번 "출처를 알 수 없는 앱 설치"를 허용해야 합니다.

## 로컬 개발 (내 컴퓨터)

```bash
git clone https://github.com/<내계정>/<저장소이름>.git
```

1. Android Studio 에서 이 폴더 열기 (Gradle 동기화가 자동으로 진행됨)
2. 휴대폰 USB 디버깅 켜고 연결 → ▶ 실행

명령줄로 빌드할 때 (Android SDK 필요):

```bash
./gradlew assembleDebug
```

결과물: `app/build/outputs/apk/debug/app-debug.apk`

## 다음 단계 (로드맵)

- [x] 1단계: SSH 명령 실행기 (stdout / stderr / 종료 코드, 타임아웃, 서버 지문 확인)
- [x] 2단계: 암호화 저장소 + 호스트/명령 CRUD + 맥북 기본 명령
- [x] 3단계: Compose 화면, 위험 명령 확인 창, 실행 결과 표시
- [ ] 실행 기록을 DB 에 저장 (지금은 앱을 끄면 사라짐)
- [ ] 홈 화면 위젯 / 빠른 설정 타일로 "맥북 잠자기" 바로 실행
- [ ] Wake-on-LAN (꺼진 맥 깨우기)
- [ ] 생체 인증 후 위험 명령 실행
