# Daily Brief MVP 설계

작성일 2026-11-19 · 상태 APPROVED · 사이드 프로젝트

## 읽는 경험

하루의 주요 기술 뉴스와 내가 쓰는 오픈소스의 새 릴리스를 받은편지함에서 빠르게 확인한다. 개발자를 첫 독자로 삼고, 동일한 뉴스 메일은 PM과 IT 직군도 구독할 수 있게 한다.

- **Daily Brief · 오늘의 뉴스**는 Hacker News와 GeekNews에서 각각 최대 4개를 고른다. 각 항목에는 제목, 출처, 링크만 싣는다. Hacker News는 기사 원문으로, GeekNews는 GeekNews 토픽으로 연결한다.
- **Daily Brief · 내 기술 업데이트**는 수신자가 선택한 프로젝트의 신규 정식 GitHub 릴리스를 한 통에 모은다. 프로젝트명, 버전, 한국어 한두 문장의 변경 요약, 릴리스 링크를 싣는다. 맞는 릴리스가 없으면 이 메일을 생략한다.
- 뉴스 제목은 출처의 언어를 유지한다. 두 메일은 모바일에서 읽기 쉬운 단일 열 목록으로 구성하고 텍스트 버전도 제공한다.

## 구독

이메일 주소를 입력하고 두 메일의 수신 여부와 프로젝트를 선택한다. 초기 선택지는 React, Next.js, Spring Boot다. 새 구독은 메일에서 받은 확인 링크를 열고 확인 버튼을 눌러야 활성화된다. 비밀번호 없이 관리 링크로 설정을 변경하거나 해지한다. 개인별 발송 시간, 임의 GitHub 저장소 등록, 프리릴리스, 공개 아카이브는 이후 범위다.

확인·관리 토큰은 주소와 용도에 묶인 난수로 발급하고, 만료·사용 여부를 서버에서 검증한다. 링크를 여는 GET은 읽기 전용이다. 확인 POST는 주소가 현재 미확인이고 확인 요청 세대가 일치할 때만 토큰 소비와 설정 적용을 같은 트랜잭션에서 처리한다. 다른 토큰으로 이미 활성화했거나 이후 해지된 주소에는 기존 확인 링크로 설정을 바꿀 수 없다. 해지 후 다시 구독하려면 새 확인 요청이 필요하다. 관리 링크의 POST는 단기 세션을 연다. 이후 설정 변경과 해지는 해당 구독자 세션과 CSRF 검증을 거친다. 토큰은 성공한 POST에서 한 번만 소비된다. 기존 구독 주소를 다시 입력해도 현재 설정은 유지한다. 만료된 링크는 등록된 주소로 다시 요청할 수 있다. 확인·관리 메일 요청은 주소당 15분에 한 번, 요청 IP당 한 시간에 10번으로 제한하고 구독 여부와 관계없이 같은 접수 문구를 보여 준다.

## 구현 방향

**선택한 방식은 단일 Spring Boot 앱, Spring Batch, PostgreSQL이다.** 앱이 구독 화면과 배치 진입점을 제공한다. Spring Batch의 일일 Job이 소스 수집, 릴리스 요약, 메일 생성·발송을 Step으로 실행한다. 별도의 재시도 Job은 기존 발송 기록을 처리하며, 두 Job은 같은 앱에서 실행한다. PostgreSQL에 서비스 데이터와 Spring Batch JobRepository를 저장한다. [Spring Boot의 Spring Batch 지원](https://docs.spring.io/spring-boot/reference/io/spring-batch.html)

- **수집 및 정렬.** Hacker News 공식 API의 상위 목록에서 발송 구간 안에 등록됐고 외부 URL이 있는 글을 순위대로 고른다. GeekNews 공식 Atom 피드는 발송 구간 안의 최신 토픽 순으로 고른다. 뉴스는 확인된 원문 URL이 같으면 Hacker News를 남기고 GeekNews는 다음 후보로 채운다. GeekNews에 원문 URL이 없으면 토픽 URL을 식별에 사용하므로 두 출처에서 같은 기사가 나올 수 있다. GitHub Releases API는 `draft`와 `prerelease`가 모두 거짓인 공개 릴리스만 받는다. `published_at`으로 수집 구간을 판정한다. [Hacker News API](https://github.com/HackerNews/API) · [GeekNews 피드](https://news.hada.io/blog/geeknews_feed) · [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases)
- **공통 데이터.** 항목에는 유형, 출처, 출처 ID, 표시 제목, 링크, 출처 시각이 있다. 릴리스에만 프로젝트, 버전, 요약을 추가한다. 출처와 출처 ID의 조합으로 재수집을 중복 없이 처리한다. 구독자와 프로젝트 선택, 출처별 처리 기록, 발송 기록은 별도로 저장한다.
- **LLM 요약.** 릴리스 노트만 입력으로 사용하고, 동일 릴리스의 성공한 요약을 구독자 간 재사용한다. 버전과 URL은 GitHub 데이터에서 가져온다. 노트가 없거나 요약에 실패하면 그 릴리스에는 버전과 원문 링크를 싣는다. 제목·버전·요약은 일반 텍스트로 처리하고 HTML에서 이스케이프한다. 링크는 검증한 HTTP(S) URL만 쓴다.
- **발송 시각과 장애 처리.** 첫 배포는 매일 08:00 한국 시간에 실행한다. 일반 발송 구간은 전날 08:00 이상부터 당일 08:00 미만이다. 뉴스는 한 출처가 실패하면 조회된 출처로 발송하고, 보낼 항목이 없으면 생략한다. GitHub는 저장소별 마지막 완료 시각부터 이번 마감 시각까지 처리한다. 첫 시작점은 이전 정규 마감이다. 페이지 끝까지 조회한 결과와 완료 시각을 함께 저장한다. 오류나 중단이 생기면 완료 시각을 유지하므로 건너뛴 실행도 다음 번에 따라잡는다.
- **기술 메일에 넣을 릴리스.** 마감 전에 공개된 릴리스 중 현재 선택 이후에 나왔고, 해당 구독자의 다른 발송에 배정되지 않은 항목을 넣는다. 늦게 수집된 항목은 아직 발송을 준비하지 않은 가장 가까운 발송일에 포함한다. 구독자·릴리스 단위로 배정을 고유하게 기록하고 메일 서비스가 요청을 수락하면 발송된 것으로 처리한다.
- **중복 방지와 설정 반영.** 구독자·발송일·메일 종류마다 발송 기록을 하나만 만든다. 첫 제출 전에 DB에서 발송 권한과 요청 본문, 릴리스 배정, 요청 키를 함께 확정한다. 작업은 발송 행에 2분간 유효한 실행 권한을 원자적으로 획득한다. 외부 요청 직전에 권한을 확인하고, 현재 설정 검사와 제출 예정 시각 기록을 한 트랜잭션으로 처리한다. 기한이 지나면 다른 실행이 인수한다. 제출 예정 기록이 없으면 아직 미제출로, 기록이 있으면 외부에 도달했을 수 있는 요청으로 복구한다. 후자의 재시도에는 같은 본문과 키를 쓴다. 구독 해지, 메일 종류 끄기 또는 프로젝트 빼기가 제출 전에 확인되면 발송을 취소한다. 아직 선택된 릴리스는 다음 발송에 배정할 수 있다. 다시 추가한 프로젝트는 새 선택 시점부터 적용한다.
- **발송 결과가 불명확할 때.** 같은 키의 중복 방지를 보장하는 메일 서비스를 선택하고, 보장 기간 안에서 구독 설정에 여전히 맞는 요청만 재시도한다. 이미 보냈을 수 있는 요청의 본문이나 키는 바꾸지 않는다. 제공업체가 수락하지 않았다고 명시한 일시 거절은 같은 키의 보장 기간 안에서 재시도한다. 영구 거절 또는 재시도 기간이 끝난 확정 미발송은 발송 행을 종료하고 아직 해당하는 릴리스의 배정을 같은 트랜잭션에서 해제한다. 운영자가 제공업체 기록으로 미발송을 확인한 경우에도 같은 방식으로 처리한다. 수락을 확인하면 발송 완료로 기록한다. 확인할 수 없으면 재발송을 보류하고 기존 배정을 유지한다.

## 완료 기준

1. 프로젝트 선택이 서로 다른 두 테스트 구독자에게는 같은 뉴스 제목과 링크가 표시되고, 기술 메일에는 각자가 선택한 프로젝트만 들어간다.
2. 새 정식 릴리스가 없으면 기술 메일을 만들지 않는다. 정식 릴리스·초안·프리릴리스가 섞인 입력, 날짜 경계, 다음 페이지에서의 장애, 놓친 배치, 요약 실패, 발송 재시도와 직전 설정 변경을 고정 샘플로 검증한다.
3. 확인 및 관리 링크를 여는 것만으로 상태가 바뀌지 않는다. 유효한 확인 제출만 구독을 활성화한다. 만료·재사용 토큰을 거부하고, 관리 세션은 자기 설정만 수정한다. 만료된 관리 링크는 이메일로 다시 받을 수 있다.
4. 시범 구독자 두 명이 모바일 받은편지함에서 두 메일을 제목으로 구분하고, 30초 안에 해당하는 릴리스의 원문을 열 수 있다.

## 배포와 첫 검증

공개 구독 페이지 주소를 공유해 시범 구독을 받는다. CI에서 구독 흐름과 고정 샘플의 수집·메일 생성·발송 복구를 검증한 뒤 예약 실행을 켠다. 호스팅, 메일 및 LLM 제공업체는 구현 계획에서 정한다. 첫 시범에서는 본인과 동료 한 명이 서로 다른 프로젝트를 선택해 두 샘플 메일을 확인한다.

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
