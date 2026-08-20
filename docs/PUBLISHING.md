# Maven Central 배포 절차 (공개 로드맵 6단계 · [B07](../BACKLOG.md))

> ⚠️ **Central 은 한 번 올린 버전을 지울 수 없다.** 그래서 이 문서의 절반이 리허설이다.
> 배포 자체는 마지막 두 줄이다.

## 무엇을 올리고, 무엇을 안 올리나

| 모듈 | 좌표 | 왜 |
|---|---|---|
| `ocpp-core` | `io.github.zannabi-lab:ocpp-core` | 프레임워크를 모르는 코덱·스키마·세션 계층. **남이 의존으로 적을 수 있는 것** |
| `swap-domain` | `io.github.zannabi-lab:swap-domain` | 의존성 0 의 도메인 모델 |

`csms` · `station-sim` · `sim-console` 은 애플리케이션이고 `java-compat` 은 시험 전용이라
좌표를 가질 이유가 없다. 설정은 루트 `build.gradle.kts` 의 `publishedModules` 한 곳에 있다.

## ⚠️ 먼저 풀어야 할 것 — 네임스페이스

**`io.github.<조직명>` 은 자동 등록되지 않는다.** Central Portal 이 자동으로 주는 것은
**가입에 쓴 GitHub 계정의 username** 기준 `io.github.<username>` 뿐이다
([공식 문서](https://central.sonatype.org/register/namespace/)):

> *"Currently, we only support the GitHub username that you used to sign up, so
> `io.github.<github organization name>` is not available as an automatically registered namespace."*

`ZANNABI-LAB` 은 조직이므로 셋 중 하나를 골라야 한다.

| 경로 | 절차 | 걸리는 시간 |
|---|---|---|
| **A. 조직 네임스페이스를 지원에 요청** | Central Support 에 메일. 조직 소유를 확인받는다 | 며칠 (응답 대기) |
| **B. 개인 username 으로** `io.github.<username>` | 자동. 검증 키와 같은 이름의 **public 저장소**를 만들면 끝 (뒤에 지워도 된다) | 30분 |
| **C. 보유 도메인으로** `space.deep-thought` | DNS **TXT 레코드**에 검증 키를 넣는다. 조직/개인 구분과 무관하다 | 1시간 (DNS 전파) |

**C 는 이미 가진 것으로 된다** — 블로그 도메인을 쓰고 있으므로 새로 살 것이 없다.
A 는 그룹 ID 를 안 바꿔도 되지만 남의 응답을 기다려야 한다.

> 그룹 ID 가 바뀌면 고칠 곳은 **`gradle.properties` 한 줄**이다. 경계 검사도 좌표를
> 하드코딩하지 않고 `rootProject.group` 을 참조하므로 함께 따라온다.

## 사전 준비

1. **Central Portal 계정** — <https://central.sonatype.com> 에서 GitHub 로 로그인
2. **네임스페이스 등록** — 위 A/B/C 중 하나
3. **GPG 키** — Central 은 모든 아티팩트의 서명을 요구한다

   ```bash
   gpg --gen-key                                   # 이름·메일 입력
   gpg --list-secret-keys --keyid-format=short     # 키 ID 확인
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # 공개키 배포 (필수)
   gpg --armor --export-secret-keys <KEY_ID>       # 아래 gradle.properties 에 넣을 값
   ```

4. **Portal 토큰** — Portal 화면에서 발급. 계정 비밀번호가 아니다
5. **`~/.gradle/gradle.properties`** (저장소가 아니라 홈 디렉토리다):

   ```properties
   mavenCentralUsername=<Portal 토큰 username>
   mavenCentralPassword=<Portal 토큰 password>
   signingInMemoryKey=<gpg --armor --export-secret-keys 출력에서 헤더/푸터를 뺀 본문>
   signingInMemoryKeyId=<KEY_ID>
   signingInMemoryKeyPassword=<키 암호>
   ```

## 리허설 — 여기서 다 잡는다

### 1. 로컬 저장소에 올린다

```bash
./gradlew :ocpp-core:publishToMavenLocal :swap-domain:publishToMavenLocal
```

서명 키가 없어도 통과한다 (`signMavenPublication SKIPPED`). 루트 빌드가 키 없는 환경에서
`signing.required = false` 로 두기 때문이고, **배포 경로는 이 완화를 타지 않는다** —
Central 이 서명 없는 번들을 거절하므로 키를 잊으면 업로드에서 잡힌다.

### 2. 산출물을 눈으로 확인한다

```bash
cd ~/.m2/repository/io/github/zannabi-lab/ocpp-core/0.1.0
unzip -l ocpp-core-0.1.0.jar | grep -c 'ocpp/schemas/.*\.json'   # → 181
unzip -l ocpp-core-0.1.0.jar | grep 'META-INF/NOTICE'            # → 있어야 한다
cat ocpp-core-0.1.0.pom
```

세 가지가 반드시 있어야 한다:

- **공식 스키마 181개** — 없으면 소비자 쪽에서 스키마 검증이 통째로 죽는다
- **`META-INF/LICENSE` · `META-INF/NOTICE`** — Apache-2.0 §4(d) 이고, 더 중요하게는
  jar 안에 **CC BY-ND 4.0 인 OCA 스키마가 들어 있다.** 좌표로만 받은 사람은 그 고지를
  jar 안에서만 볼 수 있다. *(첫 리허설에서 실제로 빠져 있었다.)*
- **POM 의 name · description · url · licenses · developers · scm** — 하나라도 없으면
  Central 이 거절한다

### 3. 소비자 입장에서 실제로 컴파일한다

**이것이 리허설의 핵심이다.** 별도 디렉토리에 최소 프로젝트를 만들고 `mavenLocal()` 에서 받는다.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }

// build.gradle.kts
plugins { kotlin("jvm") version "2.1.0"; application }
dependencies {
    implementation("io.github.zannabi-lab:ocpp-core:0.1.0")
    implementation("io.github.zannabi-lab:swap-domain:0.1.0")
}
```

확인할 것 셋:

1. **코덱 왕복** — `OcppFrameCodec().encode(...)` → `decode(...)` 가 `Decoded` 로 온다
2. **스키마 검증** — `OcppPayloadValidator().validateCall("BootNotification", payload)` 가
   `Valid` 를, 빈 페이로드가 `Invalid` 를 준다. **jar 안의 스키마가 실제로 로드된다는 증거다**
3. **Java 에서도 부른다** — `new OcppFrameCodec(new ObjectMapper())`.
   [LAYERS §4](LAYERS.md) 의 판정을 배포 아티팩트로 다시 확인하는 것이다

> **알아 둘 것**: 소비자 콘솔에 `SLF4J(W): No SLF4J providers were found` 가 뜬다.
> `json-schema-validator` 가 `slf4j-api` 를 쓰기 때문이고, 구현체를 하나 넣으면 사라진다.
> 동작에는 영향이 없다.

## 배포

```bash
./gradlew publishToMavenCentral            # 업로드만 — Portal 화면에서 사람이 릴리스한다
./gradlew publishAndReleaseToMavenCentral  # 업로드 + 자동 릴리스
```

**첫 배포는 반드시 앞의 것으로 한다.** Portal 화면에서 파일 목록과 POM 을 한 번 더 보고
누르는 절차가 남아 있는 편이 낫다 — 되돌릴 수 없기 때문이다.

버전은 `gradle.properties` 한 줄이다. 계획은 **`0.0.1` 로 한 번 올려 전 과정을 확인한 뒤
`0.1.0`** 이다. `0.0.1` 이 남는 것은 비용이 아니라 기록이다.

## 배포 후

- `README.md` 의 *"Not yet published to Maven Central"* 문장을 의존성 좌표로 바꾼다
  (한글판 `README.ko.md` 도 같이)
- [B07](../BACKLOG.md) 을 완료로 옮긴다
