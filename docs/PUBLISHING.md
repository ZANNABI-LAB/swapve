# Maven Central 배포 절차

> ⚠️ **Central 은 한 번 올린 버전을 지울 수 없다.** 그래서 이 문서의 절반이 리허설이다.
> 배포 자체는 마지막 두 줄이다.

## 무엇을 올리고, 무엇을 안 올리나

| 모듈 | 좌표 | 왜 |
|---|---|---|
| `ocpp-core` | `io.github.zannabi-lab:ocpp-core` | 프레임워크를 모르는 코덱·스키마·세션 계층. **남이 의존으로 적을 수 있는 것** |
| `swap-domain` | `io.github.zannabi-lab:swap-domain` | 의존성 0 의 도메인 모델 |

`csms` · `station-sim` · `sim-console` 은 애플리케이션이고 `java-compat` 은 시험 전용이라
좌표를 가질 이유가 없다. 설정은 루트 `build.gradle.kts` 의 `publishedModules` 한 곳에 있다.

## 먼저 풀어야 할 것 — 네임스페이스 (진행 중)

**`io.github.zannabi-lab` 을 그대로 쓰기로 했다** (2026-08-20 결정). 그룹 ID 가 저장소 URL 과
한 줄로 이어지는 값이 응답을 기다리는 비용보다 크다고 봤다.

⚠️ **다만 조직 네임스페이스는 자동 등록되지 않는다.** Portal 이 자동으로 주는 것은 가입에 쓴
GitHub **username** 기준 `io.github.<username>` 뿐이다
([공식 문서](https://central.sonatype.org/register/namespace/)):

> *"Currently, we only support the GitHub username that you used to sign up, so
> `io.github.<github organization name>` is not available as an automatically registered namespace."*

### 절차

**1단계 — 먼저 Portal 에서 직접 시도한다.** <https://central.sonatype.com> → *Namespaces* →
*Add Namespace* 에 `io.github.zannabi-lab` 을 넣는다. 검증 키가 나오면 **`ZANNABI-LAB` 조직에
그 키와 같은 이름의 public 저장소**를 만들고 *Verify* 를 누른다. 이걸로 통과하면 메일은 필요 없다.
(검증이 끝나면 그 빈 저장소는 지워도 된다.)

**2단계 — 막히면 Central Support 에 요청한다.** <central-support@sonatype.com>.
조직 소유를 확인받는 절차이므로 아래 정보를 처음부터 담는다:

```
Subject: Namespace request for io.github.zannabi-lab (GitHub organization)

Hello,

I would like to register the namespace io.github.zannabi-lab.

- GitHub organization: https://github.com/ZANNABI-LAB
- My GitHub account (organization owner): https://github.com/<username>
- Portal account email: <가입 메일>
- Project to publish: https://github.com/ZANNABI-LAB/swapve
  (Apache-2.0, an OCPP 2.1 Battery Swap library for the JVM)

The Add Namespace flow does not accept an organization name automatically,
so I am requesting manual verification. I can prove ownership of the
organization in whatever way you prefer — for example by creating a public
repository with a verification key under the organization.

Thank you.
```

**응답을 기다리는 동안 배포 외의 것은 전부 준비해 둘 수 있다** — 아래 GPG 와 리허설이 그것이다.

> 그룹 ID 를 나중에 바꿔야 해도 고칠 곳은 **`gradle.properties` 한 줄**이다. 경계 검사도
> 좌표를 하드코딩하지 않고 `rootProject.group` 을 참조하므로 함께 따라온다.
> 대안이었던 것 둘: 개인 username 기준 `io.github.<username>`(자동, 30분) ·
> 보유 도메인 기준 `space.deep-thought`(DNS TXT, 1시간).

## 사전 준비

1. **Central Portal 계정** — <https://central.sonatype.com> 에서 GitHub 로 로그인
2. **네임스페이스 등록** — 위 절차 (조직 승인 대기 중)
3. **GPG 키** — Central 은 모든 아티팩트의 서명을 요구한다

   ```bash
   gpg --gen-key                                   # 이름·메일 입력
   gpg --list-secret-keys --keyid-format=short     # 키 ID 확인
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>   # 공개키 배포 (필수)
   gpg --armor --export-secret-keys <KEY_ID>       # 아래 gradle.properties 에 넣을 값
   ```

   > `/dev/tty` 가 없는 환경(에이전트·CI·일부 원격 셸)에서는 `gpg --gen-key` 가
   > *"cannot open '/dev/tty'"* 로 죽는다. 그때는 배치 모드를 쓴다 —
   > `gpg --batch --pinentry-mode loopback --gen-key <파라미터파일>` 이고,
   > 파라미터 파일에 `Key-Type`·`Name-Real`·`Name-Email`·`Passphrase` 를 적는다.
   > **파라미터 파일에 암호가 평문으로 들어가므로 쓰고 나서 지운다.**

4. **Portal 토큰** — Portal 화면에서 발급. 계정 비밀번호가 아니다
5. **`~/.gradle/gradle.properties`** (저장소가 아니라 홈 디렉토리다):

   ```properties
   mavenCentralUsername=<Portal 토큰 username>
   mavenCentralPassword=<Portal 토큰 password>
   signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nlQdG...\n-----END PGP PRIVATE KEY BLOCK-----
   signingInMemoryKeyId=<KEY_ID>
   signingInMemoryKeyPassword=<키 암호>
   ```

   ⚠️ **`signingInMemoryKey` 는 ASCII-armor 전문을 한 줄로 넣는다.** 줄바꿈은 `\n` 으로
   바꾼다 — `.properties` 가 그것을 줄바꿈으로 되돌린다. **헤더·푸터를 떼면 안 된다.**
   떼면 `signMavenPublication` 이 *"Could not read PGP secret key"* 로 죽는다
   (2026-08-21 리허설에서 실제로 겪었다. 이 문서가 그렇게 시켰던 것을 고친 것이다).

   ```bash
   # 넣을 값을 그대로 만들어 주는 한 줄
   gpg --armor --export-secret-keys <KEY_ID> | python3 -c \
     "import sys;print(sys.stdin.read().strip().replace(chr(10), chr(92)+'n'))"
   ```

   파일 권한은 `chmod 600` 으로 좁힌다.

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

