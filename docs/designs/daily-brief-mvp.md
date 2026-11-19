# Daily Brief MVP 설계

작성일 2026-11-19 · 상태 APPROVED · 사이드 프로젝트

## 읽는 경험

하루의 주요 기술 뉴스와 내가 쓰는 오픈소스의 새 릴리스를 받은편지함에서 빠르게 확인한다. 개발자를 첫 독자로 삼고, 동일한 뉴스 메일은 PM과 IT 직군도 구독할 수 있게 한다.

- **Daily Brief · 오늘의 뉴스**는 Hacker News와 GeekNews에서 각각 최대 4개를 고른다. 각 항목에는 제목, 출처, 링크만 싣는다. Hacker News는 기사 원문으로, GeekNews는 GeekNews 토픽으로 연결한다.
- **Daily Brief · 내 기술 업데이트**는 수신자가 선택한 프로젝트의 신규 정식 GitHub 릴리스를 한 통에 모은다. 프로젝트명, 버전, 릴리스 링크를 싣는다. 요약을 만들면 한국어 한두 문장을 더한다. 릴리스 노트가 없거나 요약에 실패해도 버전과 릴리스 링크를 제공한다. 맞는 릴리스가 없으면 이 메일을 생략한다.
- 뉴스 제목은 출처의 언어를 유지한다. 두 메일은 모바일에서 읽기 쉬운 단일 열 목록으로 구성하고 텍스트 버전도 제공한다. 웹과 HTML 메일의 링크에는 공통으로 짙은 청록색과 밑줄을 적용한다. 메일 앱이 색을 바꾸는 경우에도 밑줄로 링크를 구분할 수 있게 한다.

## 구독

이메일 주소를 입력하고 두 메일의 수신 여부와 프로젝트를 선택한다. 초기 선택지는 React, Next.js, Spring Boot다. 구독 화면은 서비스명 다음에 설명을 붙인 두 메일 선택 항목, 프로젝트 선택, 이메일 입력, 확인 메일 요청 순으로 보여 준다. 새 구독과 설정 변경에서 기술 메일을 켜면 프로젝트를 하나 이상 선택해야 한다. 선택이 비어 있으면 요청을 제출할 수 없고, 입력과 선택을 유지한 채 프로젝트 목록 옆에 안내한다. 새 구독은 두 메일 중 하나 이상을 선택해야 한다. 두 메일을 모두 끄면 확인 메일을 보내지 않고, 입력과 선택을 유지한 채 메일 선택 항목 옆에 안내한다. 새 구독은 메일에서 받은 확인 링크를 열고 확인 버튼을 눌러야 활성화된다. 확인에 성공하면 완료 문구 바로 아래에 실제로 수신을 켠 메일의 제목을 표시한다. 비밀번호 없이 관리 링크로 설정을 변경하거나 해지한다. 구독과 관리 폼은 화면 크기와 관계없이 한 열로 배치한다. 좁은 화면에서는 가용 너비에 맞추고, 넓은 화면에서는 폼의 최대 너비를 제한한다. 개인별 발송 시간, 임의 GitHub 저장소 등록, 프리릴리스, 공개 아카이브는 이후 범위다.

확인·관리 토큰은 주소와 용도에 묶인 난수로 발급하고, 만료·사용 여부를 서버에서 검증한다. 링크를 여는 GET은 읽기 전용이다. 확인 POST는 주소가 현재 미확인이고 확인 요청 세대가 일치할 때만 토큰 소비와 설정 적용을 같은 트랜잭션에서 처리한다. 다른 토큰으로 이미 활성화했거나 이후 해지된 주소에는 기존 확인 링크로 설정을 바꿀 수 없다. 해지 후 다시 구독하려면 새 확인 요청이 필요하다. 관리 링크의 POST는 단기 세션을 연다. 이후 설정 변경과 해지는 해당 구독자 세션과 CSRF 검증을 거친다. 토큰은 성공한 POST에서 한 번만 소비된다. 기존 구독 주소를 다시 입력해도 현재 설정은 유지한다. 만료된 링크는 등록된 주소로 다시 요청할 수 있다. 만료된 확인·관리 링크를 열면 안내 아래에 이메일 입력란과 재요청 버튼을 제공한다. 사용자가 이메일을 직접 입력하며, 입력 형식 오류와 재요청 접수 결과는 같은 화면에서 표시한다. 확인·관리 메일 요청은 주소당 15분에 한 번, 요청 IP당 한 시간에 10번으로 제한하고 구독 여부와 관계없이 같은 접수 문구를 보여 준다.

## 구현 방향

**선택한 방식은 단일 Spring Boot 앱, Spring Batch, PostgreSQL이다.** 앱이 구독 화면과 배치 진입점을 제공한다. Spring Batch의 일일 Job이 소스 수집, 릴리스 요약, 메일 생성·발송을 Step으로 실행한다. 별도의 재시도 Job은 기존 발송 기록을 처리하며, 두 Job은 같은 앱에서 실행한다. PostgreSQL에 서비스 데이터와 Spring Batch JobRepository를 저장한다. 두 Job과 Step은 하나의 배치 설정 클래스에서 정의하고, 요약·발송 Step은 담당 서비스를 직접 호출한다. [Spring Boot의 Spring Batch 지원](https://docs.spring.io/spring-boot/reference/io/spring-batch.html)

- **수집 및 정렬.** Hacker News 공식 API의 상위 목록에서 발송 구간 안에 등록됐고 외부 URL이 있는 글을 순위대로 고른다. GeekNews 공식 Atom 피드는 발송 구간 안의 최신 토픽 순으로 고른다. 뉴스는 확인된 원문 URL이 같으면 Hacker News를 남기고 GeekNews는 다음 후보로 채운다. GeekNews에 원문 URL이 없으면 토픽 URL을 식별에 사용하므로 두 출처에서 같은 기사가 나올 수 있다. GitHub Releases API는 `draft`와 `prerelease`가 모두 거짓인 공개 릴리스만 받는다. `published_at`으로 수집 구간을 판정한다. [Hacker News API](https://github.com/HackerNews/API) · [GeekNews 피드](https://news.hada.io/blog/geeknews_feed) · [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases)
- **공통 데이터.** 항목에는 유형, 출처, 출처 ID, 표시 제목, 링크, 출처 시각이 있다. 릴리스에만 프로젝트, 버전, 요약을 추가한다. 출처와 출처 ID의 조합으로 재수집을 중복 없이 처리한다. 구독자와 프로젝트 선택, 출처별 처리 기록, 발송 기록은 별도로 저장한다.
- **LLM 요약.** 릴리스 노트만 입력으로 사용하고, 동일 릴리스의 성공한 요약을 구독자 간 재사용한다. 버전과 URL은 GitHub 데이터에서 가져온다. 노트가 없거나 요약에 실패하면 그 릴리스에는 버전과 원문 링크를 싣는다. 제목·버전·요약은 일반 텍스트로 처리하고 HTML에서 이스케이프한다. 링크는 검증한 HTTP(S) URL만 쓴다.
- **발송 시각과 장애 처리.** 첫 배포는 매일 08:00 한국 시간에 실행한다. 일반 발송 구간은 전날 08:00 이상부터 당일 08:00 미만이다. 운영에서 예약 발송을 활성화한 뒤 가장 최근 08:00 KST 마감의 일일 Job이 시작되지 않았다면 다음 08:00 전 앱 기동 시 그 마감만 실행한다. 정규 트리거와 기동 복구는 같은 마감 식별자를 사용한다. 뉴스는 한 출처가 실패하면 조회된 출처로 발송하고, 보낼 항목이 없으면 생략한다. GitHub는 저장소별 최초 실행의 직전 정규 마감을 고정 수집 기준으로 저장한다. 매 실행은 이 기준 이상부터 이번 마감 미만까지 공개된 정식 릴리스의 모든 페이지를 조회하고, 기존 출처·릴리스 ID로 재수집 중복을 막는다. 페이지 조회를 끝까지 완료한 결과와 마지막 완료 시각을 함께 저장한다. 오류나 중단이 생기면 완료 시각을 유지한다. 다음 실행은 같은 고정 기준부터 확인하므로 건너뛴 실행이나 조회 도중 페이지가 이동해 빠진 릴리스를 다시 발견하면 수집할 수 있다.
- **기술 메일에 넣을 릴리스.** 마감 전에 공개된 릴리스 중 현재 선택 이후에 나왔고, 해당 구독자의 다른 발송에 배정되지 않은 항목을 넣는다. 늦게 수집된 항목은 아직 발송을 준비하지 않은 가장 가까운 발송일에 포함한다. 구독자·릴리스 단위로 배정을 고유하게 기록하고 메일 서비스가 요청을 수락하면 발송된 것으로 처리한다.
- **중복 방지와 설정 반영.** 구독자·발송일·메일 종류마다 발송 기록을 하나만 만든다. 첫 제출 전에 DB에서 발송 권한과 요청 본문, 릴리스 배정, 요청 키를 함께 확정한다. 작업은 발송 행에 2분간 유효한 실행 권한을 원자적으로 획득한다. 외부 요청 직전에 권한을 확인하고, 현재 설정 검사와 제출 예정 시각 기록을 한 트랜잭션으로 처리한다. 기한이 지나면 다른 실행이 인수한다. 제출 예정 기록이 없으면 아직 미제출로, 기록이 있으면 외부에 도달했을 수 있는 요청으로 복구한다. 후자의 재시도에는 같은 본문과 키를 쓴다. 구독 해지, 메일 종류 끄기 또는 프로젝트 빼기가 제출 전에 확인되면 발송을 취소한다. 아직 선택된 릴리스는 다음 발송에 배정할 수 있다. 다시 추가한 프로젝트는 새 선택 시점부터 적용한다. 작업이 실행 권한을 인수할 때마다 발송 행의 소유 번호를 증가시킨다. 제출 예정 시각·발송 결과·릴리스 배정 해제는 작업의 소유 번호와 발송 행의 현재 값이 일치할 때만 기록하며, 상태 갱신과 배정 해제는 같은 트랜잭션에서 처리한다.
- **발송 결과가 불명확할 때.** 같은 키의 중복 방지를 보장하는 메일 서비스를 선택하고, 보장 기간 안에서 구독 설정에 여전히 맞는 요청만 재시도한다. 이미 보냈을 수 있는 요청의 본문이나 키는 바꾸지 않는다. 제공업체가 수락하지 않았다고 명시한 일시 거절은 같은 키의 보장 기간 안에서 재시도한다. 영구 거절 또는 재시도 기간이 끝난 확정 미발송은 발송 행을 종료하고 아직 해당하는 릴리스의 배정을 같은 트랜잭션에서 해제한다. 운영자가 제공업체 기록으로 미발송을 확인한 경우에도 같은 방식으로 처리한다. 수락을 확인하면 발송 완료로 기록한다. 첫 제출 전에 개인정보를 포함하지 않은 발송 식별자를 확정하고, 업체의 성공 응답을 잃은 경우에도 이 식별자로 수락 증거를 원래 발송 행에 연결할 수 있는 업체를 선택한다. 확인할 수 없으면 재발송을 보류하고 기존 배정을 유지한다.

## 완료 기준

1. 프로젝트 선택이 서로 다른 두 테스트 구독자에게는 같은 뉴스 제목과 링크가 표시되고, 기술 메일에는 각자가 선택한 프로젝트만 들어간다.
2. 새 정식 릴리스가 없으면 기술 메일을 만들지 않는다. 정식 릴리스·초안·프리릴리스가 섞인 입력, 날짜 경계, 다음 페이지에서의 장애, 놓친 배치, 요약 실패, 발송 재시도와 직전 설정 변경을 고정 샘플로 검증한다. 고정 시계로 08:00에 서비스가 멈춘 상태와 09:00 기동을 재현하고, 추가 재기동 후에도 해당 마감의 일일 Job이 한 번만 시작되는지 검증한다. GitHub 고정 응답은 페이지 이동으로 첫 수집에서 빠진 릴리스가 다음 수집에서 한 번만 저장되고 최초 수집 기준 이전 항목은 제외되는지 검증한다. PostgreSQL 통합 테스트에서 인수한 작업이 성공을 기록한 뒤 이전 작업의 타임아웃이 완료 상태와 릴리스 배정을 바꾸지 못함을 검증한다.
3. 확인 및 관리 링크를 여는 것만으로 상태가 바뀌지 않는다. 유효한 확인 제출만 구독을 활성화한다. 만료·재사용 토큰을 거부하고, 관리 세션은 자기 설정만 수정한다. 만료된 관리 링크는 이메일로 다시 받을 수 있다. 서버 요청에서는 구독·관리의 선택 조건을 화면 검증 없이도 확인한다. 만료된 확인·관리 링크를 같은 화면에서 다시 요청하는 경로와, 구독 확인을 완료했을 때 활성화된 선택의 메일 제목만 표시하는 경로를 검증한다.
4. 시범 구독자 두 명이 모바일 받은편지함에서 두 메일을 제목으로 구분하고, 30초 안에 해당하는 릴리스의 원문을 열 수 있다.

## 배포와 첫 검증

공개 구독 페이지 주소를 공유해 시범 구독을 받는다. CI에서 구독 흐름과 고정 샘플의 수집·메일 생성·발송 복구를 검증한 뒤 예약 실행을 켠다. 호스팅, 메일 및 LLM 제공업체는 구현 계획에서 정한다. 예약 발송 활성화 전에 실제 메일 업체의 테스트 발송에서 성공 응답을 받지 않은 상태로, 새 제출 없이 업체 기록의 수락 증거와 기존 발송 식별자가 일치하는지 확인한다. 첫 시범에서는 본인과 동료 한 명이 서로 다른 프로젝트를 선택해 두 샘플 메일을 확인한다.

## 공개 사례에서 가져온 판단

Digest는 뉴스 링크를 정해진 일정에 모아 보내고, NewReleases는 프로젝트별 릴리스를 모아 보낸다. Daily Brief는 “한눈에 확인”할 수 있도록 두 읽기 목적을 메일 제목부터 구분한다. [Digest](https://usedigest.com/hacker-news-newsletter/) · [NewReleases](https://newreleases.io/about)

<!-- gstack:office-hours:concerns:start -->
## Reviewer Concerns

Disposition: CONCERNS_RECORDED

Stop: CONVERGENCE

### R2-1 — completeness (blocking)

**Problem**

> The unique send record and frozen request are durable, but the design does not specify how an approved attempt is claimed or how a later batch recovers an abandoned claim. The rule that only one approved job submits does not say what makes that true when a restarted batch overlaps an earlier worker.

**Remedy**

> Define an atomic attempt claim on the durable send record, when and how a later batch can recover it, and how a recovered claim distinguishes work that was never submitted from a submit that may have reached the provider. Route uncertain retries through the existing immutable-key and provider-window rules.

### R2-2 — completeness (blocking)

**Problem**

> The revised rules release assignments after a pre-submit cancellation or an operator resolves an uncertain request as unsent. They leave technically eligible releases assigned when the provider returns a definite rejection and the same digest cannot be retried successfully. The next digest excludes all already-assigned releases, so this path can suppress an update indefinitely.

**Remedy**

> Define retry and terminal handling for definitively rejected requests. When a send ends as definitively unsent, close its record and release still-eligible release assignments atomically, including when the provider reports the rejection directly.

### R2-3 — consistency (blocking)

**Problem**

> Per-token expiry and single use allow two independently valid confirmation requests before activation. The unconditional rule to apply a token's settings on a confirmation POST can then overwrite an existing subscription if the second token is redeemed after the first activation or after a management change. The stated protection on entering an already registered address does not address separately issued tokens that are already outstanding.

**Remedy**

> Check eligibility to activate at confirmation time as part of the same transaction that consumes the token. Define a no-change outcome for outstanding confirmation tokens after another token has activated the address, and prevent such a token from restoring a later unsubscription.

### R2-4 — clarity (minor)

**Problem**

> The Reading Experience still lists a Korean summary as part of every technical update, although a release without notes or a successful summary gets only its version and link.

**Remedy**

> Mention the fallback in the technical email description.

### Prior finding evidence

**R1-7 → R2-1 (persisting)**

> The deduplication section adds a unique send row and atomically freezes assignments, content and key, but its approved-job rule supplies neither the ownership transition nor abandoned-claim recovery needed for concurrent or interrupted batches.

**R1-12 → R2-4 (persisting)**

> The Reading Experience still promises a Korean summary in its item contents and only the LLM summary paragraph describes the fallback.

### Unresolved at the cap

1. R2-1 — completeness (blocking): The unique send record and frozen request are durable, but the design does not specify how an approved attempt is claimed or how a later batch recovers an abandoned claim. The rule that only one approved job submits does not say what makes that true when a restarted batch overlaps an earlier worker.
2. R2-2 — completeness (blocking): The revised rules release assignments after a pre-submit cancellation or an operator resolves an uncertain request as unsent. They leave technically eligible releases assigned when the provider returns a definite rejection and the same digest cannot be retried successfully. The next digest excludes all already-assigned releases, so this path can suppress an update indefinitely.
3. R2-3 — consistency (blocking): Per-token expiry and single use allow two independently valid confirmation requests before activation. The unconditional rule to apply a token's settings on a confirmation POST can then overwrite an existing subscription if the second token is redeemed after the first activation or after a management change. The stated protection on entering an already registered address does not address separately issued tokens that are already outstanding.

issues verified: 3 of 3

Verification is deterministic: a fix counts when its `applied_text` is in the current design and absent from the round-2 snapshot. It confirms applied edits, never a review verdict; no reviewer was launched past the cap.
<!-- gstack:office-hours:concerns:end -->

## 엔지니어링 리뷰 기록

검토 대상: `docs/designs/daily-brief-mvp.md` (main, 기준 커밋 `f652b9c`)

### Scope Challenge

feature answers: 신규 범위 변경 제안 없음. 설계 본문의 범위 유지.
structure: B) Smaller arrangement, D3에 대한 사용자 응답 `B`.
accepted scope: 단일 Spring Boot 애플리케이션, PostgreSQL, Spring Batch의 일일 발송 Job과 별도의 재시도 Job, 기존 구독 및 해지 흐름, 수집·요약·발송 기능과 완료 기준을 유지한다. 두 Job과 각 Step은 한 설정 클래스에 정의한다. 수집 서비스가 출처별 결과를 조합하고 요약·발송 Step은 담당 서비스를 직접 호출한다. 배치 구성안은 25개 파일, 운영 클래스 13개 추정이다.
pending remedies: R1 발송 권한 만료와 인수, R2 GitHub 페이지 조회 중 목록 변경, R3 메일 사업자 수락 여부 확인, R4 예약 시각에 앱이 내려갔을 때의 복구.

Scope Challenge result: scope accepted as-is. 기능을 줄이지 않았다. 구조 결정은 별도 장애 처리 변경을 승인하지 않는다.

## Decision ledger

### R1: 인수 이후 발송 결과의 기록 권한

Finding: 1, CRITICAL, confidence 8/10, `docs/designs/daily-brief-mvp.md:28-29`, Codex Architecture review. 28행의 “외부 요청 직전에 권한을 확인하고”와 29행의 “수락을 확인하면 발송 완료로 기록한다” 사이에 결과 기록 시점의 소유권 조건이 없다.
Plan baseline: 원안은 2분 실행 권한과 제출 직전 설정·권한 검사, 불명확한 제출의 동일 본문·키 재시도를 명시한다. D3/B는 Job과 클래스 구성만 결정했다.
Runtime evidence: 기준 커밋 `f652b9c`에 앱과 테스트가 없다. 런타임 재현은 해당 없음. PostgreSQL은 행 업데이트 조건을 적용할 수 있다. [PostgreSQL Read Committed](https://www.postgresql.org/docs/current/transaction-iso.html)

Comparison grid:

| 항목 | 현재 | A) 현재 소유자만 기록 | B) 현 설계 유지 | 근거 |
|---|---|---|---|---|
| R1 발송 행 소유권 | 2분 실행 권한. 인수 전 작업의 늦은 결과가 DB에 기록될 때 적용할 조건은 미정 | 인수 때 소유 번호 증가. 같은 소유 번호일 때에만 제출 예정·결과·배정 해제 기록. 상태와 배정은 동일 트랜잭션으로 갱신. PostgreSQL 통합 테스트에서 늦은 결과가 상태와 배정을 바꾸지 못함을 검증 | 제출 직전 권한 확인을 유지. 결과 기록에 새 소유 번호 조건이나 해당 통합 테스트를 추가하지 않음 | 설계 28–29행, 본 결정 대기 |
| 권한 길이·외부 처리 | 2분. 현재 설정 검사 및 동일 키·본문과 사업자 보장 기간을 적용 | 원안 유지 | 원안 유지 | 설계 28–29행 |
| 구조 | 단일 앱, 두 Job과 Step 설정 클래스 하나 | 승인 구조 유지 | 승인 구조 유지 | D3/B |
| 다른 불확실성 | R2 GitHub 페이지 조회, R3 사업자 수락 확인, R4 예약 복구 미정 | 미정 유지 | 미정 유지 | Scope Challenge |

Question D4:
D4 — 만료된 발송 작업의 결과 처리
Project/branch/task: daily-brief-dev의 main에서 Daily Brief MVP의 발송 복구를 검토합니다.
ELI10: 발송 작업 A가 메일 업체의 답을 기다리다가 2분 권한을 잃으면 작업 B가 같은 발송을 이어받습니다. B가 발송 성공을 기록한 다음 A의 요청이 늦게 타임아웃되면, A가 같은 기록을 재시도 상태로 되돌릴 수 있습니다. 설계 28행에는 제출 직전 권한 확인이 있고, 29행의 결과 기록에는 인수된 작업을 구분하는 조건이 명시돼 있지 않습니다.
Stakes if we pick wrong: 발송 완료 기록이 뒤집히면 운영자가 잘못된 상태를 보고, 릴리스가 이후 메일에 다시 배정될 수 있습니다.
Recommendation: A. 현재 권한을 가진 작업만 결과를 확정하도록 DB에서 확인하면 늦은 응답이 상태를 되돌리지 못합니다.
Completeness: A=10/10, B=4/10
Pros / cons:
Net: 이 결정은 기존 2분 권한과 외부 요청 규칙을 유지하면서, 인수 이후 누가 DB의 발송 결과를 기록할지만 정합니다.
Header: 발송 결과 기록
Options:
A) 현재 소유자만 기록 (recommended)
✅ 권한을 인수할 때마다 발송 행의 소유 번호를 올리고, 제출 예정·결과·배정 해제는 그 번호가 현재 값과 일치할 때만 기록합니다. 상태 갱신과 배정 해제는 한 트랜잭션으로 처리합니다.
✅ PostgreSQL 통합 테스트에서 B가 성공을 기록한 뒤 도착한 A의 타임아웃이 발송 상태와 릴리스 배정을 바꾸지 못하는지 확인합니다.
❌ 쓰기 경로마다 소유 번호를 조건에 포함해야 합니다. 사람 약 2시간, 에이전트 약 25분의 구현·테스트 작업을 예상합니다.
B) 현 설계 유지
✅ 설계에 명시한 제출 직전 권한 확인을 사용하므로 발송 결과 경로에 별도의 소유 번호를 전달할 필요가 없습니다.
✅ 권한 인수 구조와 기존 검증 범위를 그대로 유지하므로 이번 결정으로 추가되는 구현·유지보수 작업이 없습니다.
❌ 인수 전에 시작한 작업의 늦은 결과가 새 작업의 발송 상태나 릴리스 배정을 바꿀 가능성이 설계에 남습니다.

State: approved
Actual answer: A) 현재 소유자만 기록, D4에 대한 사용자 응답 `A`.
Accepted scope: 실행 권한을 인수할 때마다 발송 행의 소유 번호를 증가시킨다. 제출 예정 시각, 결과 및 릴리스 배정 해제는 현재 소유 번호에 한해 기록한다. 상태 갱신과 배정 해제를 같은 PostgreSQL 트랜잭션에서 처리한다. 두 작업이 인수 전후로 결과를 제출하는 PostgreSQL 통합 테스트에서 이전 작업의 늦은 타임아웃이 완료 상태와 배정을 바꾸지 못함을 검증한다.
History: 최초 D4 제안은 답변 대기였으며 승인된 범위가 없었다.

### R2: 페이지 이동으로 한 번 빠진 정식 릴리스를 후속 스캔에서 처리할지

Finding: 2, CRITICAL, confidence 8/10, `docs/designs/daily-brief-mvp.md:23,26-27`, Codex Architecture review. 26행의 “저장소별 마지막 완료 시각부터 이번 마감 시각까지 처리한다”와 “페이지 끝까지 조회한 결과와 완료 시각을 함께 저장한다”가 목록 변경에 따른 누락을 다음 스캔에서도 제외할 수 있다.
Plan baseline: 마지막 성공 마감을 저장소별 하한으로 사용한다. 장애 시 완료 시각을 유지하고 늦게 수집된 릴리스는 준비 전인 발송일에 배정한다. D4/A 발송 행 소유 번호는 적용됐다.
Runtime evidence: 앱 코드가 없다. GitHub 공식 REST 문서는 릴리스 목록에 `per_page` 및 `page`를 제공하며 여러 요청에 걸친 스냅숏을 보장한다고 명시하지 않는다. 페이지 이동과 영구 누락은 이 조건에서 가능한 설계 경로다. [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases) · [GitHub 페이지 조회](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)

Comparison grid:

| 항목 | 현재 | A) 이전에 놓친 릴리스 회수 | B) 현 수집 구간 유지 | 근거 |
|---|---|---|---|---|
| R2 수집 자격 하한 | 저장소별 마지막 완료 시각 | 저장소별 최초 직전 정규 마감 고정. 최초 마감 이상, 이번 마감 미만의 정식 릴리스를 매번 확인. 기존 ID로 중복 방지. 이동 때문에 첫 실행에서 빠진 릴리스가 다음 번에 한 번만 저장되고 하한 이전 항목은 계속 제외되는지 고정 응답으로 검증 | 마지막 완료 시각을 하한으로 유지. 새 회수 규칙과 검증을 추가하지 않음 | 설계 23–26행, 본 결정 대기 |
| 페이지 요청과 완료 기록 | 끝까지 조회. 실패하면 완료 시각 유지 | 동일한 페이지 순회. 완료 시각은 진행·장애 기록으로 사용. 실패하면 완료 시각 유지 | 동일한 페이지 순회. 완료 시각은 진행 기록이자 다음 수집 하한 | 설계 26행 |
| 중복·구독자 배정 | 출처·출처 ID, 선택 이후 공개, 이미 배정된 항목 제외 | 원안 유지 | 원안 유지 | 설계 24, 27행 |
| 파일 구성과 발송 소유권 | 설정 클래스 하나, 인수 후 현재 소유 번호만 DB 갱신 | 승인 범위 유지 | 승인 범위 유지 | D3/B, D4/A |
| 다른 불확실성 | R3 사업자 수락 확인, R4 예약 복구 미정 | 미정 유지 | 미정 유지 | Scope Challenge |

Question D5:
D5 — GitHub 페이지 이동으로 누락된 릴리스 회수
Project/branch/task: daily-brief-dev의 main에서 Daily Brief MVP의 릴리스 수집을 검토합니다.
ELI10: 첫 페이지를 받은 뒤 앞쪽 릴리스가 삭제되면, 아직 읽지 않은 릴리스가 이미 읽은 페이지로 밀릴 수 있습니다. 다음 페이지까지 모두 요청해도 그 릴리스는 결과에 없습니다. 26행대로 완료 시각을 다음 수집 구간의 시작으로 쓰면, 다음 실행이 해당 릴리스를 보더라도 이전 날짜라는 이유로 버릴 수 있습니다.
Stakes if we pick wrong: 수집이 성공으로 기록된 날의 새 릴리스가 독자의 기술 메일에서 계속 빠질 수 있습니다.
Recommendation: A. 이미 모든 페이지를 가져오므로 기존 완료 시각을 장애 추적에 쓰고, 늦게 발견한 릴리스는 기존 ID 중복 방지 규칙으로 처리할 수 있습니다.
Completeness: A=10/10, B=4/10
Pros / cons:
Net: 다음 조회에서 이전에 놓친 정식 릴리스가 보일 때 이를 받아들일지 정합니다. 독자에게 넣는 기준과 수집 실패 처리는 기존 설계를 따릅니다.
Header: 릴리스 수집 구간
Options:
A) 이전에 놓친 릴리스 회수 (recommended)
✅ 저장소별 최초 직전 정규 마감을 고정하고, 매 실행에 그 시각 이상 이번 마감 미만인 정식 릴리스를 검토합니다. 마지막 완료 시각은 처리 기록으로 유지합니다.
✅ 기존 출처·릴리스 ID로 중복을 막습니다. 페이지 이동으로 한 번 빠진 항목이 다음 실행에서 한 번만 저장되고, 최초 마감 이전 항목은 제외되는지 고정 응답으로 확인합니다.
❌ GitHub 페이지 요청 수는 원안과 같지만, 출시 이후 구간이 길어질수록 다시 판정하는 항목이 늘어납니다. 사람 약 1시간 30분, 에이전트 약 20분의 구현·테스트를 예상합니다.
B) 현 수집 구간 유지
✅ 마지막 완료 시각부터 이번 마감까지만 저장하므로 이미 지난 구간의 릴리스를 다시 판정하지 않습니다.
✅ 기존 완료 시각을 필터에 그대로 쓰므로 이번 결정으로 추가되는 구현·유지보수 작업이 없습니다.
❌ 모든 페이지가 성공해도 목록이 조회 도중 바뀌어 건너뛴 릴리스는 다음 실행에서 기간 필터 때문에 다시 제외될 수 있습니다.

State: approved
Actual answer: A) 이전에 놓친 릴리스 회수, D5에 대한 사용자 응답 `1` (첫 번째 선택지).
Accepted scope: 저장소별 최초 직전 정규 마감을 고정한다. 매 실행은 최초 마감 이상 이번 마감 미만의 정식 릴리스를 확인하고 기존 출처·릴리스 ID로 중복을 막는다. 마지막 완료 시각은 처리 기록으로 유지하며 실패 시 갱신하지 않는다. 첫 스캔에 페이지 이동으로 누락된 항목이 다음에 한 번만 저장되고 최초 마감 이전 항목은 제외되는지 고정 응답으로 검증한다.
History: 최초 D5 제안은 답변 대기였으며 승인된 범위가 없었다.

### R3: 최초 응답 없이 수락 증거를 찾는 사업자 계약을 요구할지

Finding: 3, INFORMATIONAL, confidence 8/10, `docs/designs/daily-brief-mvp.md:29,40`, Codex Architecture review. 29행은 “같은 키의 중복 방지를 보장하는 메일 서비스를 선택”하고 “수락을 확인하면 발송 완료로 기록한다”고 하며, 40행은 사업자 선택을 구현 계획에 둔다. 최초 응답과 사업자 메시지 ID 없이 수락을 확인할 연결 규칙이 빠져 있다.
Plan baseline: 응답이 모호하면 같은 본문과 키를 유지한다. 설정이 바뀌면 재제출하지 않는다. 수락을 확인하면 완료 처리하고 충분한 증거가 없으면 기존 배정을 보존한다. D4/A 소유 번호와 D5/1 고정 수집 기준이 승인됐다.
Runtime evidence: 실제 업체는 미선정이고 계정 실험도 없다. Resend 공식 자료는 키 중복 방지와 `email.sent` 이벤트의 태그 예를 각각 문서화한다. 한 업체에서 필요한 조회 경로를 시범으로 확인할 근거이며 실제 연동 증명은 아니다. [Resend 키 정책](https://resend.com/changelog/idempotency-keys) · [Resend 수락 이벤트](https://resend.com/changelog/message-id-for-sent-emails)

Comparison grid:

| 항목 | 현재 | A) 수락 조회 가능 업체 | B) 키 보장만 요구 | 근거 |
|---|---|---|---|---|
| R3 업체에 필요한 수락 증거 | 키 중복 방지 요구. 응답 유실 때 메시지를 찾아낼 식별자 계약 없음 | 제출 전 확정한 개인정보 없는 발송 식별자로 수락 증거를 원래 행에 연결하고, 예약을 켜기 전 실제 업체 테스트에서 성공 응답 없이 일치와 새 메일 제출 없이 확인할 수 있는지 검증 | 키 보장만 필수. 현재 가능한 경우에 기존 복구를 쓰고 별도 식별자 추적 요구와 검증 없음 | 설계 29, 40행, 본 결정 대기 |
| 불명확·재제출·배정 | 증거가 불충분하면 보류. 설정 불일치 시 재요청 금지. 확정 미발송에 한해 배정 해제 | 원안 유지 | 원안 유지 | 설계 28–29행 |
| 기존 승인과 업체 선택 | 설정 클래스 하나, 소유 번호, 고정 GitHub 수집 기준. 업체 미선정 | 기존 승인 유지. 특정 업체·API·운영 UI 선택은 미정 | 기존 승인 유지. 특정 업체·API·운영 UI 선택은 미정 | D3/B, D4/A, D5/1, 설계 40행 |
| 다른 불확실성 | R4 예약 복구 미정 | 미정 유지 | 미정 유지 | Scope Challenge |

Question D6:
D6 — 응답이 유실된 발송의 수락 여부 확인
Project/branch/task: daily-brief-dev의 main에서 Daily Brief MVP의 메일 사업자 요구사항을 검토합니다.
ELI10: 메일 업체가 요청을 수락했지만 앱에 응답이 도착하지 않으면, 앱에는 업체가 부여한 메시지 ID가 없습니다. 그사이 구독자가 해지하면 같은 키로도 다시 제출하지 않는 것이 기존 규칙입니다. 29행대로 사업자 기록을 확인하려면 앱이 제출 전에 만든 식별자로 수락 증거를 찾을 수 있어야 합니다.
Stakes if we pick wrong: 독자에게 보낸 메일도 앱에서는 계속 보류로 남아 운영자가 실제 발송 건을 확인하기 어려울 수 있습니다.
Recommendation: A. 응답이 없어도 보낸 요청을 식별할 수 있는 업체를 고르고, 테스트 발송으로 확인하면 재발송 없이 수락을 기록할 수 있습니다.
Completeness: A=10/10, B=4/10
Pros / cons:
Net: 업체를 고를 때 요구할 수락 확인 기능을 정합니다. 미발송이 확정되지 않은 건은 기존대로 보류합니다.
Header: 수락 증거
Options:
A) 수락 조회 가능 업체 (recommended)
✅ 첫 제출 전에 확정한 개인정보 없는 발송 식별자로 사업자의 수락 증거를 찾을 수 있어야 합니다. 응답이 유실된 경우에도 원래 발송 행을 정확히 확인할 수 있습니다.
✅ 예약을 켜기 전에 사업자의 실제 테스트 발송에서 성공 응답 없이 식별자와 수락 증거가 일치하는지 확인합니다. 상태 확인에 새 메일 제출이 필요하지 않습니다.
❌ 맞는 업체와 확인 경로를 검증해야 합니다. 사업자 계약 검증은 사람 약 2시간, 에이전트 약 30분으로 추정하며 테스트 계정과 발송 도메인 준비 시간은 별도입니다.
B) 키 보장만 요구
✅ 중복 방지 키 보장만 필수로 두고 수락을 확인할 수 있는 건에 기존 복구 규칙을 적용합니다. 사업자 선택지가 넓습니다.
✅ 식별자로 추적할 수 있는지 별도로 확인하지 않으므로 사업자 도입 시 이 항목의 작업이나 유지보수가 추가되지 않습니다.
❌ 응답이 끊긴 건을 개별 발송 행과 연결할 방법이 없으면 실제 수락된 메일도 보류에 남을 수 있습니다.

State: approved
Actual answer: A) 수락 조회 가능 업체, D6에 대한 사용자 응답 `A`.
Accepted scope: 첫 제출 전에 확정한 개인정보 없는 발송 식별자로 업체의 수락 증거를 원래 발송 행에 연결할 수 있는 사업자를 고른다. 예약 발송 활성화 전에 기존 요청의 성공 응답을 받지 못한 상황에서 새로 제출하지 않고 실제 업체 기록을 원래 발송 행과 연결하는지 테스트한다. 증거 불충분 건은 기존 보류 정책을 따른다. 업체와 조회 방식은 구현 계획에서 선택한다.
History: 최초 D6 제안은 답변 대기였으며 승인된 범위가 없었다.

### R4: 정규 시각에 시작하지 못한 일일 Job을 같은 발송일에 실행할지

Finding: 4, INFORMATIONAL, confidence 8/10, `docs/designs/daily-brief-mvp.md:21,26,40`, Codex Architecture review. 26행의 “첫 배포는 매일 08:00 한국 시간에 실행한다”와 “일반 발송 구간은 전날 08:00 이상부터 당일 08:00 미만이다”에는 정규 트리거를 놓친 날의 뉴스 복구 규칙이 없다.
Plan baseline: 일일 Job은 정규 마감으로 구간을 나눈다. 예약을 언제 어디서 실행할지는 호스팅 선택에 달려 있다. 승인한 고정 GitHub 기준은 후속 수집에 적용된다.
Runtime evidence: 앱과 호스팅이 없다. Spring Framework는 시간대가 있는 cron을 지원하고 Spring Batch는 식별 파라미터로 Job 인스턴스를 구분한다. 재기동 시 과거 마감을 찾아 실행할지는 설계가 정한다. [Spring 예약 실행](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) · [Spring Batch JobInstance](https://docs.spring.io/spring-batch/reference/domain.html)

Comparison grid:

| 항목 | 현재 | A) 기동 시 최근 마감 복구 | B) 정규 발송만 실행 | 근거 |
|---|---|---|---|---|
| R4 시작하지 못한 일일 Job | 08:00 KST 정규 실행. 놓친 뉴스 시작 규칙 미정 | 활성화 뒤 놓친 가장 최근 08:00 KST 마감만 다음 08:00 전 기동 시 실행. 정규 실행과 같은 마감 식별자 사용. 고정 시계 08:00 정지, 09:00 기동 및 다시 기동에서 한 번 실행 검증 | 정규 트리거만 사용. 놓친 뉴스 구간을 건너뜀. 기동 발송 검사 추가 없음 | 설계 26, 40행, 본 결정 대기 |
| 이미 시작한 Job과 기존 발송 행 | Job 중간 실패와 발송 행의 기존 복구 정책 | 원안 유지 | 원안 유지 | 설계 26–29행 |
| 기존 승인 | 설정 클래스 하나, DB 발송 소유 번호, GitHub 고정 수집 기준, 사업자 수락 증거 | 승인 범위 유지 | 승인 범위 유지 | D3/B, D4/A, D5/1, D6/A |
| 도구 선택 | 호스팅 미정 | 호스팅 선택은 미정 | 호스팅 선택은 미정 | 설계 40행 |

Question D7:
D7 — 예정된 오전 8시 실행을 놓쳤을 때
Project/branch/task: daily-brief-dev의 main에서 Daily Brief MVP의 정규 발송을 검토합니다.
ELI10: 오전 8시에 앱이 내려가 있어서 일일 Job이 시작조차 못 하면, 설계에는 앱이 다시 켜졌을 때 놓친 발송을 시작하는 규칙이 없습니다. GitHub 릴리스는 다음 수집에서 회수할 수 있지만, 뉴스는 다음 날에 새 24시간 구간을 사용하므로 놓친 구간은 그대로 지나갑니다.
Stakes if we pick wrong: 잠깐의 점검이나 재배포로 당일 뉴스 메일이 빠질 수 있고, 복구 발송을 허용하면 당일 늦은 시간에 도착할 수 있습니다.
Recommendation: A. 재기동 시 가장 최근 마감만 확인하면 짧은 중단에서 회복하면서 여러 날 치 뉴스가 한꺼번에 나가는 것을 피할 수 있습니다.
Completeness: A=10/10, B=7/10
Pros / cons:
Net: 시작하지 못한 정규 발송을 같은 발송일 안에서 늦게 실행할지 정합니다. 이미 시작했다가 실패한 Job과 기존 발송 행은 각자의 복구 규칙을 따릅니다.
Header: 정규 예약 복구
Options:
A) 기동 시 최근 마감 복구 (recommended)
✅ 예약 발송을 활성화한 뒤 놓친 가장 최근 08:00 KST 마감만 다음 08:00 전까지 앱 기동 때 실행합니다. 정규 실행과 기동 실행은 같은 마감 식별자를 사용합니다.
✅ 고정 시계로 08:00 정지·09:00 기동과 다음 기동을 재현해 같은 마감의 일일 Job이 한 번만 시작되는지 검증합니다.
❌ 메일이 평소보다 늦을 수 있고 앱 기동 경로에서 DB의 실행 기록을 확인해야 합니다. 사람 약 2시간, 에이전트 약 30분의 구현·테스트를 예상합니다.
B) 정규 발송만 실행
✅ 정규 트리거로만 일일 Job을 시작해 뒤늦은 뉴스 메일이 도착하지 않습니다. 기존 GitHub 수집 규칙으로 후속 정규 실행에서 릴리스를 회수할 수 있습니다.
✅ 앱 기동 시 발송일 확인과 중복 실행 테스트를 추가하는 작업이 없습니다.
❌ 오전 8시에 앱이 내려가 있으면 그 구간의 뉴스 메일은 건너뜁니다.

State: approved
Actual answer: A) 기동 시 최근 마감 복구, D7에 대한 사용자 응답 `A`.
Accepted scope: 운영에서 예약 발송 활성화 이후 시작하지 못한 가장 최근 08:00 KST 마감만 다음 08:00 전 앱 기동 시 실행한다. 정상 경로와 기동 경로는 같은 마감 식별자를 사용한다. 고정 시계로 08:00 정지와 09:00 기동을 재현하고 다시 기동해도 해당 마감의 일일 Job이 한 번만 시작되는지 확인한다. 이미 시작한 Job과 발송 행은 기존 복구 규칙을 따른다.
History: 최초 D7 제안은 답변 대기였으며 승인된 범위가 없었다.

Approval readiness: PASS. 구조 D3/B, R1 D4/A, R2 D5/1(첫 번째 선택지), R3 D6/A, R4 D7/A. 아래 표시 설명 수정은 25행의 기존 동작과 맞춘다. 사용 통계 선택은 구현 승인에 포함하지 않는다.

## Engineering review

### Step 0 — Scope Challenge

두 메일, 구독 관리, 수집·요약·발송·복구 및 시범 검증 범위를 유지한다. 25개 파일과 운영 클래스 13개는 D3 구조의 설계 추정치다. 로컬 기준 커밋은 문서만 추적한다. `git fetch --dry-run --no-tags origin main`은 원격에 해당 ref가 없다고 응답했고 `git ls-remote --heads origin`에는 원격 브랜치가 없었다. `f652b9c`에서 이전 구현 계획을 제거했다. 별도 `TODOS.md`는 발견되지 않았다. 이전 Office Hours 검토 기록은 이 문서의 역사이며, 현재 본문의 결론은 17·28·29행과 이번 결정 기록에 있다.

| 전제 | 근거 | 판정 |
|---|---|---|
| Spring Batch 메타데이터와 일일·재시도 Job을 단일 앱에서 운용한다 | 설계 21행과 Spring Boot 공식 문서 | 프레임워크 지원 확인. 실제 앱은 설계 단계 |
| PostgreSQL에서 실행 권한과 상태를 조건부로 갱신할 수 있다 | 설계 28행과 PostgreSQL 행 잠금·격리 문서 | 기능 확인. 실제 경합 테스트는 구현 작업 |
| 선택한 업체의 동일 키 재시도 보장 기간이 운영 요구에 맞는다 | 설계 29행. Resend 문서의 정책은 후보 근거 | 미검증 |
| 성공 응답 없이 선택한 업체에서 수락을 확인할 수 있다 | 설계 29·40행. 공식 이벤트 문서는 후보 근거 | 미검증 |
| GitHub가 페이지 요청 전체의 동일한 스냅숏을 제공한다 | 설계 23·26행. 공식 REST 문서는 페이지 번호를 명시 | 미검증. D5는 다음에 다시 보이는 항목을 회수 |
| 선택한 호스트가 예약 실행과 장애 후 기동을 제공한다 | 설계 26·40행 | 미검증. D7을 실제 배포 구조에서 검증 |

REALITY: premise-table finding unverified=4; provider retry window and reconciliation, GitHub cross-page snapshot, and chosen host availability require evidence docs/designs/daily-brief-mvp.md:21-40
REALITY: already-done pass local tree has only this design; remote advertised no heads and the earlier implementation plan was removed f652b9c
REALITY: surface-check pass official source and Spring Batch interfaces were checked; HTTP routes remain unnamed docs/designs/daily-brief-mvp.md:17-23
REALITY: binding-decisions pass no prior branch decisions were returned; D3 through D7 are recorded by their actual answers docs/designs/daily-brief-mvp.md:117-288
REALITY: numbers pass design targets are requirements; file and class counts are estimates docs/designs/daily-brief-mvp.md:9-40
REALITY: deferred-asks pass no linked issue; custom schedules and arbitrary repos are deferred explicitly docs/designs/daily-brief-mvp.md:15
REALITY: pr-count pass one MVP PR assumed by default; the plan specifies no separate PR schedule docs/designs/daily-brief-mvp.md:38-40
REALITY: prior-art pass use Spring Batch metadata and PostgreSQL transaction primitives; no vendored patches docs/designs/daily-brief-mvp.md:21-28
REALITY: transaction-semantics finding row ownership and external submission use separate boundaries; D4 adds ownership on result writes docs/designs/daily-brief-mvp.md:28-29
REALITY: contract-assumptions finding provider proof is due before scheduling and GitHub documents page offsets; D5 handles an item found in a later scan docs/designs/daily-brief-mvp.md:26-29
REALITY: dependency-fallback pass partial news, retained GitHub completion marker, summary fallback, and send hold are specified docs/designs/daily-brief-mvp.md:25-29
REALITY: obligation-tiers pass MVP includes CI and accepted recovery; custom schedules and arbitrary repos are deferred docs/designs/daily-brief-mvp.md:15-40

### 1. Architecture

1. [CRITICAL] (confidence: 8/10) `docs/designs/daily-brief-mvp.md:28-29` — 원문의 “외부 요청 직전에 권한을 확인하고”와 “수락을 확인하면 발송 완료로 기록한다” 사이에서 인수 전 작업의 늦은 응답이 새 작업의 상태를 덮어쓸 수 있었다. D4가 DB의 조건부 기록과 통합 테스트를 승인했다.
2. [CRITICAL] (confidence: 8/10) `docs/designs/daily-brief-mvp.md:26` — 원문의 “저장소별 마지막 완료 시각부터 이번 마감 시각까지 처리한다”는 페이지 이동으로 한 번 누락된 릴리스를 다음 조회에서도 제외할 수 있었다. D5가 고정 초기 컷오프와 ID 중복 방지를 승인했다. [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases)
3. [INFORMATIONAL] (confidence: 8/10) `docs/designs/daily-brief-mvp.md:29,40` — “수락을 확인하면 발송 완료로 기록한다”를 실행하려면 응답 유실 시 참조할 수 있는 식별자가 필요하다. D6가 사업자 선정과 실제 사전 검증에 이 조건을 추가했다. [Resend 수락 이벤트](https://resend.com/changelog/message-id-for-sent-emails)
4. [INFORMATIONAL] (confidence: 8/10) `docs/designs/daily-brief-mvp.md:26` — “매일 08:00 한국 시간에 실행한다”의 정규 실행이 시작되지 않으면 그 뉴스 구간을 둘러볼 기회가 사라질 수 있었다. D7이 최근 마감의 기동 복구를 승인했다. [Spring Batch JobInstance](https://docs.spring.io/spring-batch/reference/domain.html)

Dispositions: R1 accepted D4/A, R2 accepted D5/1, R3 accepted D6/A, R4 accepted D7/A.

**두 가지 구조.** 선택안은 두 Spring Batch Job이 소스 수집과 메일 발송 서비스를 직접 호출하고 발송 서비스가 발송 행과 배정의 트랜잭션을 소유한다. 스케줄러는 같은 마감 식별자를 전달한다. 비교한 구조는 두 Job이 PostgreSQL 명령 행을 기록하면 별도 내부 실행기가 명령을 읽어 같은 작업을 처리한다.

```text
DailyJob.run(Cutoff) -> CommandQueue.enqueue -> not implemented
RetryJob.run() -> CommandQueue.enqueue -> not implemented
CommandLoop.poll() -> MailCommandHandler.handle -> not implemented
MailCommandHandler.handle(Command) -> SendLedger.apply -> not implemented
SendLedger.apply(Outcome) -> PostgreSQL -> not implemented
```

직접 호출안은 하나의 발송 서비스 표면 뒤에 권한·설정 확인과 배정·재시도 정책을 둔다. 명령 행, 명령 실행기의 수명, 배치 메타데이터 외의 실행 상태를 추가하는 이유가 시범 규모에서 확인되지 않았다. [Spring Batch JobRepository](https://docs.spring.io/spring-batch/reference/api/org/springframework/batch/core/repository/JobRepository.html) · [PostgreSQL 행 잠금](https://www.postgresql.org/docs/current/explicit-locking.html)

| 구조 점검 | 결과 |
|---|---|
| 얕은 모듈과 단순 전달 | D3대로 요약·발송 Step이 서비스를 직접 호출한다. |
| 정보 누출 | 수집 클라이언트가 외부 응답을 공통 항목으로 변환한다. 업체 응답 분류는 발송 어댑터 안에 둔다. |
| 실행 순서 기준 분해 | Step은 순서를 표현한다. 구독 세대·수집 자격·메일 배정은 각 데이터를 소유하는 서비스에서 결정한다. |
| 소유권 분산 | 일일 Job과 재시도 Job은 같은 발송 행의 소유 번호 조건을 적용한다. |
| 같은 일을 하는 경로 중복 | 정규와 기동이 같은 일일 Job과 마감 식별자를 사용한다. 재시도는 기존 발송 행을 사용한다. |
| 내부 기능의 공개 | 나중에 구현할 서비스 경계에서 DB·업체 타입을 제한한다. 현재 공개 코드가 없다. |
| 수동 동기화 목록 | 프로젝트 허용 목록은 구독 폼·서버 검증·수집에서 하나의 정의를 사용하면 된다. 현재 유지해야 할 소스 목록이 없다. |

**배포 경계.** 한 애플리케이션과 PostgreSQL에 서비스 스키마와 배치 스키마가 필요하다. 구독 화면은 HTTPS로 접근하고 업체와 모델 키는 서버 설정으로 관리한다. 스키마 적용과 CI의 고정 입력 검증, 실제 메일 업체의 수락 증거 확인을 거쳐 예약을 켠다. 호스트·OS 및 아키텍처·JDK와 스키마 배포 명령은 40행의 구현 계획에서 정한다. 공개 다운로드, 다중 배포, 별도 배치 서버는 설계에 없다.

### 2. Code Quality

5. [INFORMATIONAL] (confidence: 10/10) `docs/designs/daily-brief-mvp.md:10,25` — 원래 독자 설명의 “한국어 한두 문장의 변경 요약”은 모든 릴리스에 요약을 약속하는 것으로 읽힌다. 25행은 “노트가 없거나 요약에 실패하면 그 릴리스에는 버전과 원문 링크를 싣는다”고 정한다. 10행을 기존 동작과 맞췄다.

Dispositions: 표시 설명의 사실 불일치 수정. 원래 동작을 유지한다.

현재 첫 번째 당사자 소스나 호출자가 없어서 공통 헬퍼 추출 후보를 확인할 수 없었다. 두 배치가 공유할 발송 규칙의 근거는 28–29행의 공통 저장·복구 계약이다. 출처별 필터링과 원문·토픽 링크가 다르므로 출처별 응답을 분리한 채 공통 항목만 넘긴다. 구독 오류의 경계는 17행의 토큰 용도·세대·만료·사용 여부·구독자 세션과 CSRF다. 이 경계를 Spring Security의 표준 요청 검사와 PostgreSQL 트랜잭션으로 검증할 수 있다. 화면 라우트와 토큰 형식은 정해지지 않았다. 원래 문서에는 흐름도를 찾을 수 없었다. [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

### 3. Tests

`AGENTS.md`에 테스트 명령이나 LLM 변경 패턴이 없고, 저장소에 빌드 매니페스트·테스트 설정·테스트가 없다. 프레임워크와 실제 파일명은 구현 계획에서 정한다. 아래 명칭은 테스트 책임을 가리키는 제안이다. 기존 실행 경로가 없는 신규 기능이므로 실행한 프로젝트 테스트 또는 기존 테스트 폐기 항목은 없다.

```text
PROPOSED CODE PATHS                                     USER / OPERATOR FLOWS
[GAP G01] Request -> parse options/address -> reject    [GAP G13] [->E2E] Subscribe -> confirm -> two tailored emails
          or give same acknowledgment; limit by IP/key  [GAP G14] [->E2E] Link preview GET -> no activation or session
[GAP G02] Confirm GET -> view; POST -> token-purpose/   [GAP G15] [->E2E] Expired link -> request fresh link -> manage
          expiry/check generation -> atomic consume    [GAP G16] [->E2E] Change or cancel before submit -> no stale mail
          -> activate or reject / no change             [GAP G17] [->E2E] 08:00 app down -> 09:00 start -> no duplicate
[GAP G03] Manage GET -> view; POST -> consume -> session [GAP G18] [->E2E] Provider accepted, reply lost -> resolve
          -> owner + CSRF -> update or unsubscribe
[GAP G04] Daily trigger / boot -> KST cutoff -> gate
          -> same JobInstance started, already started,
          or latest cutoff unavailable
[GAP G05] HN ordered ids -> details -> time + URL filter
          GN Atom -> dates + link -> cross-source dedup
          -> four each, one-source fallback, or none
[GAP G06] GitHub repos -> pages -> ID/date/eligibility
          -> complete result + timestamp or unchanged
          -> moved item visible on replay -> one write
[GAP G07] Notes -> cached, model call or missing
          -> Korean summary or version/link fallback
[GAP G08] Mail renderer -> opt-ins/eligible selection
          -> filtered links and escaped HTML/text
          -> create one per type or skip empty
[GAP G09] Send claim -> valid or another owner -> freeze
          -> settings match: mark submission; changed:
          cancel and release valid assignments
[GAP G10] Provider result -> accepted, transient,
          permanent, or uncertain -> record if owner;
          stale owner -> no change to row or assignments
[GAP G11] Retry Job -> same key/body within window and
          current settings, expiry or hold; only release
          after definitive non-acceptance
[GAP G12] Reconcile -> matched proof / confirmed unsent /
          insufficient evidence -> complete / release / hold
```

LLM integration: G07 [→EVAL]. 후보 모델의 실제 응답으로 제공된 릴리스 노트에 근거한 한국어 한두 문장인지 확인한다. 고정 응답은 타임아웃·빈 노트·캐시 재사용의 동작 증명이다. 실제 모델의 품질 기준과 성공·실패 사례를 같은 릴리스 노트로 남긴다. 지금 실행한 유료 평가나 비교 모델은 없다.

**Coverage:** 0/18 경로 검증됨. 코드 0/12, 사용자·운영 흐름 0/6. ★★★ 0, ★★ 0, ★ 0. 공백 18개, E2E 대상 사용자·운영 경로 6개, LLM 평가 경로 1개. 경로 수는 실행 분기 묶음 수다.

| 예정 테스트 책임 | 유형과 검증 |
|---|---|
| SubscriptionFlowTest, G01–G03 / G13–G16 | 웹·PostgreSQL 통합 및 E2E. 두 독자의 메일, 토큰 동시 소비, 만료·재사용·오용, CSRF 및 세션 격리, 주소와 IP의 경계에서 실제 효과를 확인 |
| DailyLaunchTest, G04 / G17 | 고정 시계와 PostgreSQL 배치 메타데이터로 정상·기동 경로가 같은 마감을 사용하고 중복 Job을 시작하지 않는지 확인 |
| NewsCollectionTest 및 ReleaseCollectionTest, G05–G06 | 고정 HTTP 응답. HN/GN 시간·우선순위·중복과 부분 실패, GitHub 페이지 장애·이동·초기 기준·반복 저장 검증 |
| ReleaseSummaryTest 및 MailRenderingTest, G07–G08 | 단위·고정 응답·별도 실제 모델 평가. 요약 실패, 불신 HTML, 링크 스킴, 동일 요약 재사용, 구독자별 자격과 기술 메일 없음 검증 |
| DeliveryRecoveryTest, G09–G12 / G16 | 실제 PostgreSQL의 경쟁 상태로 소유 번호와 설정 변경 순서를 검증. 업체 고정 응답으로 재시도 기간 및 배정 유지·해제를 확인 |
| ProviderContractCheck, G12 / G18 | 실제 업체 테스트에서 응답 유실과 수락 증거를 확인. 테스트가 통과해도 독자 수신함 배치 여부는 별도 시범 확인 |

Dispositions: 설계 17, 23–29, 33–36, 40행과 D4–D7의 동작을 검증하는 예정 테스트다. 실제 코드와 시험은 구현 이후에 수행한다. 테스트 계획: `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/SSAFY-main-eng-review-test-plan-20261119-145148.md`.

### 4. Performance

6. [INFORMATIONAL] (confidence: 8/10) `docs/designs/daily-brief-mvp.md:23,26` — “상위 목록”의 한 항목을 판정할 때 상세 요청이 필요하고 “한 출처가 실패하면 조회된 출처로 발송”하려면 멈춘 요청이 실패로 끝나야 한다. HN 상위 목록은 공식 상한 500개다. 순서대로 유효한 4개를 채우면 상세 조회를 마치고, 외부 요청은 제한된 시간 안에 실패가 반환돼야 한다. 정확한 제한 시간은 측정하지 않았다. [Hacker News API](https://github.com/HackerNews/API#new-top-and-best-stories)

GitHub는 초기 3개 저장소이며 페이지당 공식 상한은 100개다. D5는 기존 전체 페이지 요청 수를 유지하지만 시간이 지날수록 확인할 후보가 늘어난다. 페이지 단위 파싱과 ID 중복 검사를 DB에서 수행할 수 있다. GeekNews 공개 주소의 2026-11-19 단일 GET 응답에서 Atom 항목 20개와 `published` 및 토픽 링크를 확인했다. 시간대나 보존 범위가 고정된다는 추정에는 사용하지 않았다. 전체 배치 실행 시간, 구독자당 조회 수, 메일 본문·백로그 바이트, 처리량은 코드와 환경이 없어 미측정이다. 구현 시 구독자별 쿼리 반복을 확인하고, 발송 만료 후보 및 고유 키에 걸리는 쿼리를 PostgreSQL에서 확인한다. 릴리스 요약 재사용은 25행의 기존 캐싱 계약이다. [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases) · [GeekNews 공식 피드](https://news.hada.io/blog/geeknews_feed)

Dispositions: 뉴스 조기 종료와 HTTP 타임아웃 검증을 기존 상위 4개 및 출처 오류 처리의 구현 작업으로 기록했다. 새 수치 제한, 별도 캐시, 페이지 조기 종료, 성능 목표 완화는 결정하지 않았다.

### Failure modes

| 경계 | 현실적 실패 | 처리 및 검증 | 사용자 영향 |
|---|---|---|---|
| 공개 구독과 링크 | 링크 미리 읽기 또는 두 POST의 경쟁 | GET 읽기 전용, 세대·만료와 원자적 소비. G01–G03·G13–G15 | 유효한 확인만 활성화. 만료 시 다시 요청 |
| 뉴스 공급자 | Atom 피드 또는 HN 상세 요청 타임아웃 | 정상 출처 사용, 없으면 메일 생략. G05 | 항목이 적거나 뉴스 메일이 없음 |
| GitHub | 페이지 조회 중 오류나 결과 이동 | 완료 시각 보존 또는 고정 하한 재조회로 회수. G06 | 다시 발견한 릴리스가 다음 적격 메일에 포함 |
| 요약·렌더링 | 모델 타임아웃이나 노트 없음 | 버전·원문 링크 유지, HTML 이스케이프. G07–G08 | 해당 항목에 변경 요약이 없음 |
| 정규 실행 | 08:00에 앱이 정지 | D7의 기동 복구. G04·G17 | 같은 발송일 안에 늦게 도착할 수 있음 |
| 발송 작업 | 권한이 인수된 뒤 이전 작업이 종료 | D4의 소유 번호 조건. G09–G11·G16 | 이전 작업이 새 발송 상태나 배정을 변경하지 않음 |
| 외부 발송 업체 | 업체 수락 후 응답 유실 | 확정된 본문·키, 동일 키 보장 기간, D6 증거 확인. G11–G12·G18 | 기존 요청을 대조할 때까지 상태가 보류될 수 있음 |

### What already exists

- [Layer 1] Spring Batch의 Job·Step·JobRepository와 식별 파라미터, PostgreSQL의 고유 제약·트랜잭션, Spring Security의 CSRF 보호를 사용할 수 있다. 이 저장소에는 재사용 가능한 첫 번째 당사자 실행 코드가 없다. [Spring Batch 구조](https://docs.spring.io/spring-batch/reference/domain.html) · [PostgreSQL 격리](https://www.postgresql.org/docs/current/transaction-iso.html) · [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Layer 1] GitHub REST 페이지 연결, HN의 상위 목록과 개별 항목 API, GeekNews 공식 Atom 피드를 기존 출처 경계로 사용한다. [GitHub 페이지 조회](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api) · [Hacker News API](https://github.com/HackerNews/API) · [GeekNews](https://news.hada.io/blog/geeknews_feed)

### NOT in scope

- 개인별 발송 시각, 임의 GitHub 저장소 등록, 프리릴리스, 공개 아카이브는 설계 15행의 명시적 이후 범위다.
- 여러 날 치 뉴스 발송은 D7의 최근 마감 정책 범위 밖이다. GitHub 릴리스의 후속 회수는 유지한다.
- 호스트·업체·JDK 및 테스트 프레임워크 선정, 실제 앱·테스트 작성과 배포는 구현 계획과 구현 작업이다.
- 별도 큐 실행기와 운영 UI는 선택한 구조에 필요하지 않다. 테스트에 쓰일 업체 확인 경로는 40행에서 정한다.

### Implementation sequence

| 작업 | 모듈 | 선행 작업 |
|---|---|---|
| 공통 기반 | 앱 설정, 공통 스키마 및 경계 | 없음 |
| A 구독 웹 흐름 | 구독 화면, 토큰·세션, 선택 DB | 공통 기반 |
| B 뉴스·릴리스 및 요약 | 공급자 어댑터, 수집, 요약 | 공통 기반 |
| C 배치, 메일 발송, 실제 업체 계약 | 스케줄, 발송 행·배정, 업체, CI | A와 B의 인터페이스 및 스키마 |
| D 출시 전 검증 | E2E, 실제 메일 업체 및 테스트 받은편지함 | A, B, C |

공통 기반을 정한 뒤 A와 B를 별도 작업 공간에서 병행할 수 있다. 두 작업을 병합한 뒤 C와 D를 순차 실행한다. 공통 스키마 경계와 첫 CI 작업의 편집은 분담 전에 합의한다. 향후 발송 권한, 설정 확인, 외부 요청 이후 상태 기록은 발송 서비스에 흐름을 남긴다.

## Implementation Tasks

- [ ] **T1 (P1, human: ~2h / CC: ~25min)** — 발송 — 현재 소유 번호로 상태 쓰기와 배정 해제를 제한한다. 근거 R1/D4. 파일은 발송 서비스·저장소와 PostgreSQL 통합 테스트. 인수 후 이전 작업 결과의 영향이 없음을 검증한다.
- [ ] **T2 (P1, human: ~1h 30min / CC: ~20min)** — 수집 — 고정 최초 마감과 릴리스 ID로 페이지 이동 누락을 회수한다. 근거 R2/D5. 파일은 GitHub 수집과 응답 고정 샘플. 첫 조회 누락과 다음 한 번 저장을 검증한다.
- [ ] **T3 (P2, human: ~2h / CC: ~30min)** — 메일 업체 — 초기 응답 없이 기존 요청의 수락 증거와 발송 행을 대조한다. 근거 R3/D6. 파일은 업체 어댑터와 업체 계약 검증. 실제 업체에서 새 제출 없이 확인한다.
- [ ] **T4 (P2, human: ~2h / CC: ~30min)** — 배치 — 최근 KST 마감만 기동 복구한다. 근거 R4/D7. 파일은 진입점과 고정 시계·저장소 테스트. 08:00 정지, 09:00 기동, 추가 기동에서 한 번 실행을 확인한다.
- [ ] **T5 (P1, human: ~1 day / CC: ~2h)** — 검증 — 구독·수집·요약·발송 계약을 검증한다. 근거 G01–G18. 파일은 E2E, 고정 샘플, PostgreSQL 통합 테스트. 프레임워크 선정 후 CI에서 실행한다.
- [ ] **T6 (P2, human: ~1h / CC: ~15min)** — 수집 성능 — HN 후보 조회를 조기 종료하고 소스 타임아웃 뒤 정상 출처의 발송을 검증한다. 근거 Performance. 파일은 뉴스 수집과 HTTP 고정 응답 테스트.

_No new tasks from Code Quality. 독자 설명은 이번 문서 변경에서 정리했다._

작업 목록 JSONL: `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/tasks-eng-review-main-20261119-055211.jsonl`. 작업량은 설정과 테스트 기반을 사용할 수 있다는 가정의 추정치다. 실제 업체 테스트 계정과 도메인 준비 시간은 별도다.

### Completion summary

- Scope Challenge: scope accepted as-is. D3에서 두 Job의 설정을 한 클래스에 모으는 안을 승인했다.
- Reality rows: 12/12, 미검증 전제 4개. 아래 검증은 표 항목의 존재와 근거 경로를 확인한다.
- Architecture: 발견 4개. Code Quality: 발견 1개, 설명 수정. Tests: 경로 묶음 18개 공백, 계획 저장. Performance: 발견 1개.
- NOT in scope 및 What already exists를 기록했다. 신규 TODO 제안 0개.
- 실패 경로 검토에서 처리·검증·표시가 모두 빠진 추가 치명적 공백 0개. 구현·테스트 전에는 승인 동작이 실제로 실행됐다고 볼 수 없다.
- 이 리뷰의 미답변 결정 0개. 문서가 위임한 도구·업체 선택은 구현 계획에서 한다.
- Outside voice: Claude Code unavailable. 설치된 CLI와 읽기 전용 Plan 검토자가 없어 실행하지 않았다.
- 병렬화: 공통 기반 이후 2개 병렬 작업(A·B), 공통 기반과 통합·최종 검증 3개 순차 단계.
- Lake Score: 4/4. D4–D7은 모두 이슈별 10/10 선택지를 택했다.
- 내역 복원은 현재 설계와 해시가 일치하는 사본을 확인했다. gstack Brain 요약은 설치에 필요한 모듈이 빠져 조회하지 못했다.

### Suppressed findings

- 없음. 호스팅과 외부 업체 검증 전제는 Scope Challenge에 별도로 표시했다.

## 디자인 리뷰 기록

검토 대상: `docs/designs/daily-brief-mvp.md`의 구독·확인·관리·해지와 뉴스 및 기술 메일. 디자인 D1의 사용자 응답 `A`로 전체 흐름을 검토한다.

### Pass 1 — 정보 구조

- UX1, D2/1A 승인. 사용자 응답은 `A`였다. 구독 첫 화면에 서비스명, 설명을 붙인 두 메일 선택 항목, 프로젝트 선택, 이메일 입력, 확인 메일 요청 순서를 적용한다.
- 정보 구조 5/10 → 7/10. 구독 첫 행동과 순서를 정했다. 확인·관리 화면에서 복구 상태와 다음 행동의 구조는 이후 단계에서 검토한다.

### Pass 2 — 상호작용 상태

- UX2, D3/2A 승인. 사용자 응답은 `A`였다. 만료된 확인·관리 링크 화면에서 사용자가 직접 이메일 주소를 입력해 재요청한다. 입력 형식 오류 및 접수 결과를 같은 화면에서 보여 주고 구독 여부에 따라 접수 문구를 바꾸지 않는다.
- 상태 안내 5/10 → 6/10. 만료와 재요청의 다음 행동을 정했다. 구독 선택이 비어 있을 때 처리하는 규칙을 검토한다.
- UX3, D4/3A 승인. 사용자 응답은 `A`였다. 새 구독과 설정 변경에서 기술 메일이 켜져 있으면 프로젝트를 하나 이상 요구한다. 프로젝트가 없으면 제출을 차단하고 기존 입력을 유지하며 목록 옆에 안내한다.
- 상태 안내 6/10 → 7/10. 기술 메일과 프로젝트 선택의 관계를 정했다. 새 구독에서 두 메일이 모두 꺼져 있는 경우는 별도로 검토한다.

- UX4, D5/4A 승인. 사용자 응답은 `A`였다. 새 구독에서 두 메일을 모두 끄면 확인 메일을 보내지 않고, 기존 입력과 선택을 유지하며 메일 선택 항목 옆에 안내한다. 새 구독에 적용하는 조건이다.
- 상태 안내 7/10 → 8/10. 만료 복구, 기술 메일의 프로젝트 누락, 새 구독의 메일 선택 누락에서 다음 행동을 정했다.

### Pass 3 — 사용자 여정

- UX5, D6/5A 승인. 사용자 응답은 `A`였다. 구독 확인 성공 문구 아래에 실제로 수신을 켠 메일의 제목을 표시한다.
- 사용자 여정 7/10 → 8/10. 구독 화면에서 고른 메일과 확인 화면, 실제 받은편지함의 이름을 연결했다.

| 단계 | 사용자의 행동 | 의도한 경험과 설계 근거 |
|---|---|---|
| 1 | 구독 화면에서 메일과 프로젝트를 고른다 | 주소 입력 전에 무엇을 받을지 이해한다. D2/1A와 D4·D5의 입력 안내 |
| 2 | 이메일 주소를 입력하고 확인 메일을 요청한다 | 받은편지함으로 이동해야 함을 안다. 확인 요청과 실제 확인을 분리 |
| 3 | 링크에서 확인 버튼을 누른다 | 구독 활성화와 받기로 한 메일을 확인한다. D6/5A |
| 4 | 받은편지함에서 제목으로 뉴스와 기술 메일을 구분한다 | 기사나 릴리스 원문을 빠르게 연다. 모바일 단일 열과 30초 시범 기준 |
| 5 | 필요하면 관리 링크를 열어 설정을 바꾸거나 해지한다 | 메일을 계속 받을지 스스로 결정한다. 만료 링크는 그 화면에서 복구. D3/2A |

### Pass 4 — 화면의 목적과 표현

- 7/10 → 7/10. 웹의 구독·관리는 신청과 설정을 수행하는 화면(Operate)이고, 두 메일은 기사와 릴리스를 읽는 화면(Read)이다. 문서에서 장식 카드 격자, 카루셀, 움직이는 배경을 찾지 못했다. 렌더링된 화면이 없어 시각적 효과는 확인할 수 없다.
- 문서 기준 점검: 첫 화면에서 서비스 식별 가능(예, D2), 시각적 초점 확인(아니요, 화면 미구현), 제목만 훑어 이해 가능(메일 예, 제목이 명시됨), 섹션별 역할(예, D2 및 D6), 장식용 카드 필요(아니요), 움직임이 필요한 흐름(아니요), 그림자 없이도 읽고 조작 가능(문서상 예, 시각 검증 미실시).

### Pass 5 — 공통 시각 기준

- UX6, D7/6A 승인. 사용자 응답은 `A`였다. 웹과 HTML 메일에 같은 짙은 청록색과 밑줄을 적용한다. 메일 앱이 색을 덮어써도 밑줄이 링크임을 나타낸다.
- 4/10 → 7/10. `DESIGN.md`나 기존 UI가 없지만, 링크에 적용할 공통 규칙을 정했다. 실제 명암비와 메일 앱의 색상·밑줄 표시는 구현에서 검증한다.

### Pass 6 — 반응형과 접근성

- UX7, D8/7A 승인. 사용자 응답은 `A`였다. 구독과 관리 폼을 어느 화면에서도 한 열로 배치하고 넓은 화면에는 폼의 최대 너비를 적용한다.
- 5/10 → 8/10. 동일한 정보 순서를 유지하는 배치와 좁은 화면·키보드 검증을 정했다. 실제 브라우저와 메일 앱에서 렌더링한 결과를 확인하는 단계가 남았다.
- 검증: 320 CSS px 너비에서 긴 제목, 선택 항목과 오류가 잘리거나 가로 스크롤을 만들지 않는다. 넓은 화면에서도 한 열로 읽고 조작할 수 있다. Tab과 Shift+Tab은 폼의 논리 순서를 따르고 현재 초점이 보인다. 메일·프로젝트·이메일 필드에는 계속 보이는 이름이 있으며 보조 기술이 각 오류와 해당 항목을 연결해 읽을 수 있다. 브라우저 검증에서는 선택 행과 주요 버튼을 터치할 수 있는지 점검한다. [내용 재배치](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) · [초점 순서](https://www.w3.org/WAI/WCAG22/Understanding/focus-order.html)

### Pass 7 — 미결정 사항

| 결정 | 사용자 승인 | 구현 영향 |
|---|---|---|
| UX1 구독 첫 화면의 정보 순서 | D2/1A | 선택 그룹 뒤에 이메일과 확인 메일 요청을 둔다. |
| UX2 만료 링크의 복구 위치 | D3/2A | 만료 화면에서 입력, 형식 오류 및 중립적인 접수 내용을 표시한다. |
| UX3 기술 메일과 프로젝트 조건 | D4/3A | 신규 및 관리 폼에서 빈 프로젝트 선택을 해당 항목에서 안내한다. |
| UX4 새 구독에서 받는 메일의 최소 수 | D5/4A | 모두 껐으면 신규 확인 메일을 보내지 않는다. |
| UX5 구독 완료 표시 | D6/5A | 활성화 직후 선택한 메일의 제목을 표시한다. |
| UX6 링크 표시 | D7/6A | 웹과 HTML 메일에 짙은 청록 링크와 밑줄을 사용한다. |
| UX7 반응형 폼 | D8/7A | 구독과 관리에 한 열과 최대 너비를 적용한다. |

확인 GET은 읽기 전용이고 관리 변경·해지는 검증된 세션과 CSRF를 사용하는 POST다. 실패 후 다음 행동과 링크가 보이는 조건은 위 합의대로 구현한다. 미답변 UX 선택 0개. 수신 앱에서의 대비·줄바꿈, 실제 폼 너비, 키보드·보조 기술은 구현 후 확인할 항목이다.

### 화면과 상태

```text
구독 화면 -> 메일 종류 -> 프로젝트 -> 이메일 -> 확인 메일 요청
   -> 받은편지함 -> 확인 링크 GET -> 확인 POST -> 완료 + 선택한 메일 제목
   -> 받은편지함 -> 뉴스 / 기술 업데이트 -> 기사 / GeekNews 토픽 / 릴리스
관리 링크 GET -> 관리 POST -> 관리 폼 -> 저장 또는 해지
확인·관리 링크 만료 -> 같은 화면 이메일 입력 -> 재요청 접수
```

D2에서 정한 첫 화면 순서와 D3·D6의 복구·완료 지점을 합치면 정보 구조는 5/10 → 8/10이다.

| 화면·동작 | 요청 중 | 비어 있거나 미충족 | 오류·부분 상태 | 성공 |
|---|---|---|---|---|
| 새 구독 | 확인 메일 요청 제출 | 메일 없음은 메일 그룹, 기술 메일을 켰는데 프로젝트 없음은 프로젝트 그룹에서 안내 | 승인한 선택 오류에서 입력과 선택을 보존 | 구독 여부와 관계없이 같은 접수 문구 |
| 확인 링크 | 링크를 열어 확인 POST 수행 | 만료 링크는 같은 화면에서 새 링크 요청 | 재사용되거나 자격이 없는 토큰은 구독 상태를 변경하지 않음 | 확인 완료와 켠 메일 제목 |
| 관리·해지 | 관리 POST에서 세션을 연 뒤 변경 또는 해지 | 유효한 관리 링크가 만료되면 같은 화면에서 재요청 | 잘못된 세션·CSRF는 저장하지 않음. 기술 메일을 켠 경우 빈 프로젝트에 입력 안내 | 요청한 설정을 저장하거나 해지 |
| 뉴스·기술 메일 | 받은편지함에서 메일 열기 | 보낼 뉴스·릴리스가 없으면 해당 메일 생략 | 한 뉴스 소스 실패 시 받은 소스만 발송. 요약 실패 시 버전과 링크 제공 | 제목으로 두 메일을 구분하고 각 원문을 엶 |

표의 요청 중 열은 기존 요청 경계를 보여 준다. 별도 로딩 연출과 세부 오류 문구는 구현 계획에서 확인한다.

### What already exists

- 두 개의 일관된 메일 제목, 모바일 단일 열, 평문 버전, 고정 확인 및 만료 복구 규칙은 MVP 설계에 이미 있었다.
- 사용 가능한 프로젝트 UI 컴포넌트나 `DESIGN.md`는 없다. 텍스트로 검토했고, 앱 화면과 메일 렌더링은 구현 단계의 시각 검증 대상이다.

### NOT in scope

- 개인별 발송 시각, 임의 저장소, 프리릴리스, 공개 아카이브는 MVP 외 범위다.
- 독립적인 브랜드 시스템과 이미지 시안은 이 문서의 텍스트 검토로 검증되지 않았다. MVP 검증 기준은 승인된 구조, 상호작용, 링크 표시에 적용한다.
- 호스트·메일 업체·LLM 업체 선택과 앱 소스 구현은 구현 계획의 범위다.

## Implementation Tasks

### Design Review

- [ ] **T1 (P2, human: ~1h / CC: ~15min)** — 구독 첫 화면을 승인된 신청 순서로 구성한다. UX1. 공개 구독 폼에서 서비스명, 두 메일, 프로젝트, 이메일, 확인 메일 요청이 순서대로 나타나는지 확인한다.
- [ ] **T2 (P1, human: ~2h / CC: ~30min)** — 만료된 확인·관리 링크를 그 화면에서 재요청하게 한다. UX2. 두 링크의 만료 고정 입력과 형식 오류, 동일한 접수 문구, 주소 수동 입력을 E2E에서 확인한다.
- [ ] **T3 (P1, human: ~1h / CC: ~20min)** — 기술 메일이 켜진 구독·관리 폼에서 적어도 한 프로젝트를 요구한다. UX3. 프로젝트 그룹에 오류가 보이고 기존 입력·설정이 보존되는지 확인한다.
- [ ] **T4 (P1, human: ~1h / CC: ~15min)** — 새 구독에서 적어도 한 종류의 메일을 요구한다. UX4. 두 메일을 끈 경우 메일 그룹에 안내하고 확인 메일 발송 요청을 생성하지 않는지 확인한다.
- [ ] **T5 (P2, human: ~1h / CC: ~15min)** — 확인 성공 화면에 실제로 켠 메일 제목을 표시한다. UX5. 두 양식 조합으로 선택하지 않은 메일은 표시되지 않는지 확인한다.
- [ ] **T6 (P2, human: ~1h / CC: ~15min)** — 웹과 HTML 메일에 승인한 링크 스타일을 적용한다. UX6. 브라우저와 시범 메일 앱에서 링크가 색과 밑줄로 구분되며, 색이 변경돼도 밑줄로 찾을 수 있는지 확인한다.
- [ ] **T7 (P1, human: ~2h / CC: ~30min)** — 구독과 관리 폼을 한 열로 제한한다. UX7. 320 CSS px, 넓은 화면, 키보드 이동과 오류의 보조 기술 연결을 확인한다.

_No new tasks from Pass 4 and Pass 7._ 작업량은 웹 폼·템플릿과 테스트 기반을 사용할 수 있다는 가정의 추정치다. 작업 목록 JSONL: `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/tasks-design-review-main-20261119-062827.jsonl`.

## Design Review Completion Summary

| 단계 | 결과 |
|---|---|
| 시스템 점검 | `DESIGN.md`와 기존 화면 구현이 없다. 범위는 구독·확인·관리·해지 및 뉴스·기술 메일이다. |
| Step 0 | 초기 인상 5/10. 전체 흐름은 D1/A. |
| Pass 1 | 정보 구조 5/10 → 8/10. 구독 첫 화면과 상태 후 다음 행동을 정했다. |
| Pass 2 | 상호작용 상태 5/10 → 8/10. 만료 복구와 누락 선택을 정했다. |
| Pass 3 | 사용자 여정 7/10 → 8/10. 성공 화면과 받은편지함의 제목을 연결했다. |
| Pass 4 | 화면의 목적과 표현 7/10 → 7/10. 문서에서 구분한 목적을 렌더링으로 점검해야 한다. |
| Pass 5 | 공통 시각 기준 4/10 → 7/10. 링크 규칙을 정했다. 서체·간격의 구체화와 실제 앱의 표시 형식은 확인 전이다. |
| Pass 6 | 반응형·접근성 5/10 → 8/10. 한 열, 320 CSS px 및 키보드 검증을 정했다. |
| Pass 7 | 승인 7개, 미답변 0개, 명시적 보류 0개. |
| NOT in scope / What already exists | 기록했다. |
| TODOS.md | 별도 항목 제안 0개. 승인한 검증은 위 구현 작업에 있다. |
| 승인 시안 | 0개 생성, 0개 승인. gstack 디자이너 실행 파일이 없어 화면 순서도와 문서로 검토했다. |
| 독립 의견 | 디자인 단계 미실행. 기존 엔지니어링 단계의 외부 의견은 unavailable로 기록돼 있다. |
| 결정 | 7개를 설계에 반영했다. |
| 전체 점수 | 4/10 → 7/10. 각 시점의 최저 Pass 점수다. |

**미결정 사항:** 이 리뷰에서 답변을 기다리는 선택은 없다. 앱과 메일 시각 검증은 예정 작업이다. 새 폼 규칙과 새 확인·복구 화면은 후속 엔지니어링 검토의 테스트 항목이다.

## 엔지니어링 재검토 — 구독 UX 변경

### Step 0 — Scope Challenge

디자인 D9/A의 범위로 구독·관리의 선택 조건, 만료 링크 재요청, 확인 성공 화면을 확인했다. 기준은 본문 15·17·35행과 디자인 D3/2A, D4/3A, D5/4A, D6/5A다. 기존 구독 서비스·웹 처리·화면·테스트 틀에서 처리하는 6–7개 예정 파일의 변경으로 추정한다. 신규 클래스·서비스는 0개로 본다. 제품 및 구현 범위는 scope accepted as-is. 이전 리뷰의 배치·발송 업무를 다시 판정하지 않았다.

| 전제 | 근거 | 판정 |
|---|---|---|
| 기술 메일 조건은 신규와 관리, 메일 최소 선택은 신규에 적용한다 | 본문 15행과 디자인 D4–D5 | 정확한 사용자 승인 |
| 재요청은 같은 화면에서 시작하지만 GET은 읽기 전용이며 주소와 IP 한도를 그대로 적용한다 | 본문 17행과 디자인 D3/2A | 정확한 사용자 승인과 기존 제약 |
| 성공 화면은 구독 요청 때 작성한 입력이 아니라 확인 POST가 적용한 결과를 사용한다 | 본문 15·17행과 디자인 D6/5A | 기존 트랜잭션 경계와 승인 내용에서 도출. 실제 앱 경합은 미검증 |

REALITY: premise-table finding unverified=1; approved scope and prior QA are inspectable; application transaction has no runtime evidence docs/designs/daily-brief-mvp.md:15-17
REALITY: already-done pass design approvals are present; tracked tree and upstream heads contain no application implementation f652b9c
REALITY: surface-check pass review uses named subscription, confirmation, renewal and management actions; HTTP routes are not yet assigned docs/designs/daily-brief-mvp.md:15-17
REALITY: binding-decisions pass design D3 through D6 are exact approvals; subscribe and management validation have different scopes docs/designs/daily-brief-mvp.md:501-511
REALITY: numbers pass counts are taken from the design or estimated explicitly docs/designs/daily-brief-mvp.md:15-17
REALITY: deferred-asks pass no linked issue; previously excluded personal schedules and arbitrary GitHub repos remain out of scope docs/designs/daily-brief-mvp.md:15
REALITY: pr-count pass focused updates carry the existing implementation tasks without a new PR docs/designs/daily-brief-mvp.md:586-594
REALITY: transaction-semantics pass token eligibility, consumption and settings activation share one transaction; integration proof is planned docs/designs/daily-brief-mvp.md:17

### 1. Architecture

No issues found. 새 외부 서비스나 웹 인증 경계가 생기지 않는다. 구독·관리 저장과 토큰 처리는 기존 경계를 사용하고, 성공 화면은 확인 트랜잭션이 커밋된 결과를 읽는다. 두 구조를 비교해야 할 신규 모듈·서비스·공개 경계가 없다.

- **새 구독과 설정 변경.** 허용된 프로젝트와 메일 종류를 확인한다. 기술 메일이 켜져 있으면 구독 요청과 인증된 관리 저장 둘 다 서버에서 프로젝트를 하나 이상 요구한다. 메일 선택을 하나 이상 요구하는 검사는 새 구독 요청에 한한다. 입력에 문제가 있으면 구독 활성화나 설정 변경, 확인 토큰 생성보다 먼저 거부한다. 구독 화면과 관리 화면에는 기존 입력을 유지하면서 해당 그룹 옆 오류를 표시한다.
- **만료 링크와 재요청.** GET은 만료 안내와 빈 이메일 입력을 보여 주는 읽기 전용 경로다. 재요청 POST는 목적에 맞는 기존 확인·관리 메일 요청 규칙과 주소·IP 제한을 거친다. 만료된 확인 요청을 갱신할 수 있는 경우 새 세대는 기존 미확인 선택을 이어받는다. 관리 재요청은 관리 링크를 요청하며 세션·설정은 이후 유효한 관리 POST와 저장 절차에서 바뀐다. 구독 상태에 관계없이 같은 접수 문구를 유지한다.
- **확인 성공.** 현재 미확인이고 요청 세대와 용도가 맞는 토큰을 원자적으로 소비하고 설정을 적용한다. 그 트랜잭션이 성공한 경우에만 활성화된 선택의 메일 제목을 표시한다. 만료·재사용·다른 용도·이전 세대 또는 롤백에서는 성공 화면과 선택을 노출하지 않는다.
- 스키마·CI·메일 업체 선택의 배포 경계는 이전 계획을 따른다. 새 배포 아티팩트는 없다.

Dispositions: 새 아키텍처 변경 제안 없음. 기존 구독·토큰 계약과 설계 D3–D6의 이행 조건을 정리했다.

### 2. Code Quality

[INFORMATIONAL] (confidence: 10/10) `docs/designs/daily-brief-mvp.md:15` — “선택이 비어 있으면 요청을 제출할 수 없고”라는 문장은 폼에서 보여 줄 행동을 정하지만 서버에서 검증한다는 완료 기준은 없었다. 직접 POST와 정상 폼 경로를 함께 검증하도록 35행 및 QA 계획에 명시했다.

구독용 최소 메일 조건을 관리 저장에서 함께 검사하면 이미 활성화한 독자가 두 메일을 끌 수 없다. 두 흐름은 공통 기술 메일 조건을 사용하고 신규에만 추가 조건을 적용한다. 인증, 세대 검사, 확정된 선택의 출처를 화면 템플릿이나 클라이언트 파라미터에 의존시키지 않는다. 현재 소스나 테스트가 없어서 실제 공통 코드 추출 후보와 비용은 확인할 수 없다. 기존 Spring Security CSRF와 PostgreSQL 트랜잭션을 사용할 수 있다. [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) · [PostgreSQL 트랜잭션 격리](https://www.postgresql.org/docs/current/transaction-iso.html)

Dispositions: 설계 D4/3A와 D5/4A의 적용 범위를 서버에서 검증하는 완료 기준을 반영했다. 추가 구현 정책 결정은 없다.

### 3. Tests

빌드 매니페스트·테스트 코드·프레임워크가 없는 문서 단계다. 이전 QA 계획은 구독·토큰의 기존 동작을 유지했고, 다음 세 설계 인수 조건을 구체화하지 않았다.

- **QA1 [INFORMATIONAL] (confidence: 10/10)** `docs/designs/daily-brief-mvp.md:15` — “새 구독은 두 메일 중 하나 이상을 선택해야 한다”와 “기술 메일을 켜면 프로젝트를 하나 이상 선택해야 한다.” 이전 QA는 허용 프로젝트와 링크·한도를 확인했다. 직접 POST에서 새 구독 조건, 관리 조건, 거부 시 무변경, 뉴스 단독 허용을 검증한다.
- **QA2 [INFORMATIONAL] (confidence: 10/10)** `docs/designs/daily-brief-mvp.md:17` — “만료된 확인·관리 링크를 열면 안내 아래에 이메일 입력란과 재요청 버튼을 제공한다.” 이전 QA에는 만료 관리 링크의 재요청만 명시됐다. 확인·관리 각각의 읽기 전용 GET, 같은 화면에서 오류·접수, 확인 선택 보존, 한도와 토큰 세대·용도를 검증한다.
- **QA3 [INFORMATIONAL] (confidence: 10/10)** `docs/designs/daily-brief-mvp.md:15` — “확인에 성공하면 완료 문구 바로 아래에 실제로 수신을 켠 메일의 제목을 표시한다.” 이전 QA는 구독 활성화까지 확인했다. 회수된 확인 링크를 포함해 각 선택 조합의 제목과 실패·롤백에서 성공 화면이 표시되지 않는지 검증한다.

```text
PROPOSED CODE PATHS                                  USER FLOWS
[GAP] Signup POST -> invalid: no token or mail       [GAP QA1] [->E2E] Change selection -> inline error
                   -> valid: existing draft flow                 -> repair -> deliberate submission
[GAP] Manage POST -> authenticate + CSRF             [GAP QA2] [->E2E] Expired link -> same-page form
                   -> reject or save scoped choices              -> new link -> recover prior intent
[GAP] Expired GET -> no state writes                 [GAP QA3] [->E2E] Confirm -> exact enabled titles
[GAP] Renewal POST -> bad syntax or neutral receipt              -> retry/old link -> show no preferences
                    -> limit + purpose -> mail or none
[GAP] Confirm POST -> stale/ineligible or rollback
                    -> commit -> enabled mail titles
```

0/8 실행 경로에 현재 테스트가 있다. 코드 0/5, 사용자 흐름 0/3. QA1–QA3은 계획 수준에서 보강한 세 묶음이다. 실제 컨트롤러 요청 및 PostgreSQL 통합 테스트로 서버 효과를 확인하고, 구독·재요청·확인 화면은 동일 흐름 테스트로 묶는다. 신규 LLM 변경, 회귀 위험에 놓인 기존 앱 코드, 폐기할 테스트는 없다.

Dispositions: 세 검증을 기존 테스트 작업 T5의 인수 범위로 반영했다. 새 QA 계획 `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/SSAFY-main-eng-review-test-plan-20261119-153507.md`에서 사용자 흐름과 각 경우의 검증 가치를 확인할 수 있다.

### 4. Performance

No issues found. 세 후보 프로젝트에 대한 선택 검증은 요청별 유한한 작업이다. 수신 내역 전체를 읽지 않고 해당 주소·토큰 또는 요청 제한 상태만 조회하는 현재 설계 경계를 사용한다. 확인 결과에 필요한 제목은 커밋한 구독의 두 메일 선택에서 나온다. 반복·전체 스캔이나 새로운 캐시·차단 호출을 요구하지 않는다. 실제 DB 조회 수, 대기 시간, 트래픽은 미측정이다.

Dispositions: 성능 정책이나 배포 조정 없음.

### Failure modes

| 실패 | 처리 | 예정 검증 |
|---|---|---|
| 브라우저의 폼 검증이 우회됨 | 서버가 쓰기 전에 필드 그룹 조건을 검사한다. 오류에서 기존 설정과 입력을 보존한다. | QA1 직접 POST와 폼 검증 |
| 만료 URL 미리 읽기 또는 재요청 경쟁 | GET은 읽기 전용이고 재요청 POST는 기존 주소·IP 한도와 세대 규칙을 적용한다. | QA2 |
| 활성화가 완료되기 전에 요청이 실패하거나 이전 토큰을 재사용 | 비활성화·실패 경로에서 선택과 성공 화면을 출력하지 않는다. 새 유효 확인은 실제 커밋 이후 제목을 표시한다. | QA3의 통합 및 사용자 흐름 |

세 경로 모두 오류 또는 대체 동작과 예정 검증이 정의돼 있다. 새 치명적 공백 0개.

### What already exists

- [Layer 1] 승인된 구독 설정, 토큰 용도·사용 여부·세대, 관리 세션과 CSRF, 주소·IP 한도 및 동일 접수 문구. 외부 패키지나 신규 서비스 없이 기존 설계 경계를 사용한다.
- 기존 QA와 구독·메일 E2E, 배치와 발송 작업은 유지한다.

### NOT in scope

- 최초 엔지니어링 리뷰 전체 재실행, 업체 선정, 신규 발송·수집 정책, 앱 소스 구현, 시각 QA 실행.
- 새 TODO는 제안하지 않았다. 후속 작업은 기존 T5에 연결한다.

### Implementation sequence

Sequential implementation, no parallelization opportunity. 구독 서버의 검증·만료·확인 결과 계약을 확정한 뒤 구독 UI와 통합 검증에 연결한다. 이미 분리된 수집·요약·배치 작업의 순서는 기존 계획을 따른다.

## Implementation Tasks

- 기존 엔지니어링 작업 T1–T6을 모두 이월했다. **T5 (P1)**의 구독 서버와 E2E 수용 기준에 QA1–QA3을 보강했다. 기존 작업의 다른 조건과 일정 추정은 그대로 적용된다.
- 최신 작업 JSONL: `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/tasks-eng-review-main-20261119-063544.jsonl`. 새 QA 계획: `C:/Users/SSAFY/.gstack/projects/whoisyourbias-daily-brief-dev/SSAFY-main-eng-review-test-plan-20261119-153507.md`.
- _No new tasks from Architecture or Performance._

### Approval readiness

Approval readiness: PASS. QA1은 디자인 D4/3A와 D5/4A, QA2는 디자인 D3/2A, QA3은 디자인 D6/5A를 근거로 한다. 디자인 D9/A는 검토 범위만 선택했다. 확인 요청과 설정 쓰기의 현재 설계 경계를 유지했다. 이번 검토의 미답변 결정 0개.

### Completion summary

- Scope Challenge: scope accepted as-is. Reality rows 8/8, 런타임 미검증 전제 1개.
- Architecture 0개, Code Quality 1개, Tests 3개, Performance 0개. 테스트 계획과 완료 기준을 저장했다.
- 치명적 공백 0개, 미답변 결정 0개, 신규 TODO 0개.
- Outside voice: Claude Code unavailable. 설치된 CLI와 읽기 전용 Plan 검토자가 없어 실행하지 않았다.
- 승인된 시각 방향과 실제 접근성 검증은 디자인 계획을 따른다. 애플리케이션이나 테스트 코드는 아직 없다.
- 병렬화: 집중 변경은 구독 경계에서 순차 적용. Lake Score N/A. 새 정책 선택은 없었다.

### Suppressed findings

- 없음.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | 완료 기록 없음 |
| Outside Review | Claude Code / `/plan-eng-review` | Independent 2nd opinion | 2 | unavailable | 엔지니어링 두 단계 독립 검토 미완료. 디자인 단계 미실행. |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | ISSUES OPEN | 집중 재검토 4개, 치명적 공백 0개. 최초 리뷰 24개는 기존 기록 참조 |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | ISSUES OPEN | score: 4/10 → 7/10, 7 decisions |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | 완료 기록 없음 |

**OUTSIDE COVERAGE:** Claude Code, plan-review, unavailable. Design phase skipped.

**VERDICT:** ISSUES OPEN — eng review required.

NO UNRESOLVED DECISIONS
