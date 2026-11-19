# Daily Brief MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 독자가 공통 기술 뉴스와 자신이 구독한 정식 GitHub 릴리스를 별도 메일로 읽을 수 있게 한다.

**Architecture:** 단일 Spring Boot 앱이 구독 웹과 Spring Batch의 일일 작업·재시도 작업을 실행한다. PostgreSQL에 공통 항목, 구독 설정, 저장소별 수집 마감, 발송 상태와 Spring Batch JobRepository 메타데이터를 저장한다. Batch 실행 이력과 제공업체 발송 이력을 각각 유지하며, 동일 날짜 재실행 시 발송 중복은 애플리케이션의 고유 키와 메일 제공업체의 요청 키로 막는다.

**Tech Stack:** Java 21, Maven Wrapper, Spring Boot 4.1.2, Spring Batch 6.0.6(Boot BOM 관리), Spring MVC, Thymeleaf, Spring Security, PostgreSQL 17, Spring JDBC, Flyway, JUnit Jupiter, Testcontainers, Docker Compose. 외부 연동은 GitHub Releases, OpenAI Responses API, Resend를 사용한다. [Spring Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html) · [관리 의존성](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)

**Spec:** [승인된 Daily Brief MVP 설계](../../designs/daily-brief-mvp.md).

## Global Constraints

- 앱과 배치는 Spring Boot와 Spring Batch로 구현하고 같은 애플리케이션으로 배포한다.
- 일일 마감은 08:00 `Asia/Seoul`이다. 보통 구간은 `[전날 08:00, 당일 08:00)`이다. DB 시각은 시간대를 포함해 저장한다.
- 공통 뉴스는 출처당 최대 4개, 제목·출처·링크만 제공한다. Hacker News는 원문, GeekNews는 공식 토픽으로 연결한다.
- 개인 기술 메일은 React, Next.js, Spring Boot 중 독자가 고른 프로젝트의 새 정식 릴리스만 포함한다. 변경 요약은 한국어 한두 문장으로 쓰고 실패하면 버전과 릴리스 링크를 싣는다. 항목이 없으면 기술 메일을 생략한다.
- 확인·관리 링크의 GET은 읽기 전용이다. 토큰 이메일은 주소당 15분에 한 번, 요청 IP당 한 시간에 10번까지 발급한다.
- 발송권은 2분간 유효하다. 거절과 결과 불명을 구분한다. 결과 불명은 저장된 동일 요청으로 제공업체의 키 보장 기간 안에서만 재시도한다.
- 모든 테스트는 Maven과 JUnit으로 실행한다. DB 제약과 경합은 실제 PostgreSQL 컨테이너에서 검증하고 외부 응답은 가짜 서버 또는 고정 샘플을 사용한다.

## Review Focus

1. 메일 클라이언트의 링크 미리 열기, 만료·중복 확인 토큰과 오래된 관리 세션은 구독 설정을 바꾸지 않는다. Task 2.
2. 두 출처의 선정은 동일 입력에서 일관되고, GeekNews 토픽에 원문을 추정해 붙이지 않는다. Task 3.
3. 저장소의 중간 페이지 실패나 예약 실행 누락은 릴리스 마감을 앞당기지 않는다. Task 4.
4. 발송 응답을 받기 전 중단, 구독 변경, 영구 거절에 대해 각각 기존 키 재사용, 재시도 보류, 릴리스 배정 해제를 검증한다. Task 6.
5. 예약 지연과 프로세스 비정상 종료 후 같은 날짜의 Batch Job을 다시 실행해도 발송은 중복되지 않는다. Tasks 1, 7.

---

## 파일 구조

Java 패키지는 `dev.dailybrief`다. 아래 작업의 `src/main/java/dev/dailybrief/`와 `src/test/java/dev/dailybrief/`를 각각 프로덕션과 테스트의 소스 루트로 사용한다. SQL은 `src/main/resources/db/migration/`, Thymeleaf 템플릿은 `src/main/resources/templates/`에 둔다. Java 파일은 `domain`(시간·데이터), `subscription`(구독), `source`(수집), `edition`(메일 구성), `delivery`(발송), `batch`(Job·Step·스케줄)로 나눈다.

Windows에서는 각 `./mvnw` 명령을 `.\mvnw.cmd`로 실행한다. 테스트 실행에는 Docker와 Java 21이 필요하다.

### Task 1. 애플리케이션, DB와 발송 구간

**Files:** `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__spring_batch.sql`, `src/main/resources/db/migration/V2__brief_items.sql`. Java 소스 루트 기준 `DailyBriefApplication.java`, `domain/DigestWindow.java`, `domain/Project.java`, `domain/BriefItem.java`, `domain/BriefItemRepository.java`. 테스트 소스 루트 기준 `domain/CoreSchemaTest.java`.

**Interfaces:** `DigestWindow.forDay(LocalDate day)`, `DigestWindow.latestDay(Instant now)`. `record DigestWindow(Instant startAt, Instant endAt)`. `record Project(long id, String slug, String displayName, String repository)`. `BriefItem`의 필드는 `id, kind, source, sourceId, title, url, occurredAt, projectId, version, summary, releaseNotes`다. `BriefItemRepository.upsert(List<BriefItem> items)`는 저장된 행을 반환한다.

- [ ] **Step 1. Spring Boot 기반을 초기화한다.** Java 21과 Boot 부모 POM 4.1.2를 고정한다. MVC, Thymeleaf, Security, JDBC Batch, Flyway 스타터와 PostgreSQL JDBC 드라이버, `org.flywaydb:flyway-database-postgresql`을 추가한다. 테스트는 Boot 테스트와 Testcontainers PostgreSQL/JUnit 통합을 사용한다. Boot의 기본 Job 자동 실행과 Batch DDL 초기화를 끈다. `spring.batch.job.enabled=false`, `spring.batch.jdbc.initialize-schema=never`.
- [ ] **Step 2. 실패 테스트를 작성한다.** PostgreSQL 17 Testcontainer를 Spring 테스트에 연결한다.

```java
@Test void seoulCutoffAndDatabaseSeed() {
    var day = LocalDate.parse("2026-11-19");
    var window = DigestWindow.forDay(day);
    assertThat(window.startAt()).isEqualTo(Instant.parse("2026-11-17T23:00:00Z"));
    assertThat(window.endAt()).isEqualTo(Instant.parse("2026-11-18T23:00:00Z"));
    assertThat(DigestWindow.latestDay(window.endAt().minusSeconds(1))).isEqualTo(day.minusDays(1));
    assertThat(DigestWindow.latestDay(window.endAt())).isEqualTo(day);
    assertThat(jdbc.queryForList("select repository from project", String.class))
        .containsExactlyInAnyOrder("react/react", "vercel/next.js", "spring-projects/spring-boot");
    assertThat(jdbc.queryForObject("select count(*) from batch_job_instance", Long.class)).isZero();
}
```

- [ ] **Step 3.** `./mvnw -Dtest=CoreSchemaTest test`를 실행한다. 도메인 타입 또는 마이그레이션 부재로 실패해야 한다.
- [ ] **Step 4.** Boot BOM의 Batch core JAR에 있는 PostgreSQL JobRepository 스키마를 `V1__spring_batch.sql`에 고정한다. V2는 `project`, `brief_item`, `release_cursor`를 만든다. `(source, source_id)`와 프로젝트 slug·리포지터리, 저장소별 커서를 고유하게 제한한다. 초기 slug는 `react`, `nextjs`, `spring-boot`다. 시간 계산과 저장은 `Instant`를 사용한다.
- [ ] **Step 5.** 같은 테스트가 통과하면 Task 1 파일을 스테이징하고 `git commit -m "feat: add Spring Boot storage and digest clock"`를 실행한다.

### Task 2. 이메일 확인과 구독 관리

**Files:** `src/main/resources/db/migration/V3__subscriptions.sql`, `src/main/resources/templates/subscription/{subscribe,link,settings,result}.html`. Java `subscription/{SubscriptionController,SubscriptionService,SubscriptionRepository,SubscriptionSecurity,Preferences,VerificationMailer}.java`. 테스트 `subscription/SubscriptionFlowTest.java`.

**Interfaces:** `record Preferences(boolean newsEnabled, boolean releasesEnabled, Set<String> projectSlugs)`. `SubscriptionService.requestLink(String email, String ip, LinkPurpose purpose, Preferences preferences, Instant now)`, `redeemConfirmation(String token, Instant now)` → `boolean`, `redeemManagement(String token, Instant now)` → `Optional<ManagementSession>`, `savePreferences(ManagementSession session, Preferences preferences, Instant now)`, `issueManagementToken(long subscriberId, Instant now)` → `String`. `VerificationMailer.sendLink(String address, LinkPurpose purpose, URI link, String requestKey)`. 구독 상태에는 `subscriberId, email, active, generation`, 메일 종류별 설정과 프로젝트별 `followedAt`이 있다.

- [ ] **Step 1. 실패 테스트를 작성한다.** `MockMvc`, CSRF 지원과 테스트 메일 어댑터로 실제 컨트롤러를 호출한다. 두 유효 확인 링크를 생성할 때는 고정 시계를 16분 이상 전진시킨다.

```java
@Test void scannerCannotActivateAndOldConfirmationCannotResubscribe() throws Exception {
    var first = requestSignup("reader@example.test", "react");
    var second = requestSignupAfter(Duration.ofMinutes(16), "reader@example.test", "nextjs");
    mvc.perform(get(first)).andExpect(status().isOk());
    assertThat(active("reader@example.test")).isFalse();
    mvc.perform(post(first).with(csrf())).andExpect(status().is3xxRedirection());
    unsubscribeThroughManagement("reader@example.test");
    mvc.perform(post(second).with(csrf())).andExpect(status().isBadRequest());
    assertThat(active("reader@example.test")).isFalse();
}

@Test void expiredLinkCanBeReissuedWithoutRevealingMembership() throws Exception {
    mvc.perform(post(expiredManagementLink()).with(csrf())).andExpect(status().isBadRequest());
    assertThat(requestManage("reader@example.test")).isEqualTo(requestManage("absent@example.test"));
    assertThat(requestManage("reader@example.test")).isEqualTo(genericAcceptedResponse());
    assertThat(sentManagementMailCount()).isEqualTo(1);
}

@Test void settingsAreBoundToTheSession() throws Exception {
    var session = openManagementSession("reader@example.test");
    mvc.perform(post("/settings").session(session).param("projects", "nextjs"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/settings").session(session).with(csrf())
        .param("subscriberId", otherSubscriberId()).param("projects", "nextjs").param("releasesEnabled", "true"))
        .andExpect(status().is3xxRedirection());
    assertThat(projectsFor("reader@example.test")).containsExactly("nextjs");
    assertThat(projectsFor("other@example.test")).containsExactly("spring-boot");
}
```

- [ ] **Step 2.** `./mvnw -Dtest=SubscriptionFlowTest test`를 실행한다. 라우팅이나 저장 기능이 없어서 실패해야 한다.
- [ ] **Step 3.** `GET/POST /subscribe`, `POST /manage/request`, `GET/POST /confirm/{token}`, `GET/POST /manage/{token}`, `GET/POST /settings`, `POST /unsubscribe`를 구현한다. 32바이트 난수 토큰의 해시와 용도를 저장한다. 확인 TTL은 24시간, 관리 링크는 30일, 관리 세션은 30분이다. 설정 변경에는 세션의 구독자 ID·세대와 CSRF를 사용한다. 활성화·해지는 세대 변경과 확인 토큰 소비를 DB에서 원자적으로 처리한다. 이미 활성인 주소의 새 요청은 설정을 유지한다. 해지 후 명시적으로 다시 신청할 때만 새 확인 토큰을 만든다.
- [ ] **Step 4.** 주소별 15분과 IP별 1시간 한도 검사를 발급 기록과 함께 트랜잭션에 둔다. 경합 시에도 한도를 지키고 동일한 접수 문구로 `202`를 반환한다. 기존 선택을 유지하면 `followedAt`을 보존한다. 프로젝트를 제거한 뒤 다시 선택하면 새 `followedAt`을 부여한다. 테스트에는 한 IP의 11번째 요청과 관리 링크의 재사용 거절을 추가한다.
- [ ] **Step 5.** 같은 테스트가 통과하면 Task 2 파일을 스테이징하고 `git commit -m "feat: add verified email subscriptions"`를 실행한다.

### Task 3. Hacker News와 GeekNews 수집

**Files:** Java `source/{NewsSource,HackerNewsSource,GeekNewsSource,NewsCandidate,NewsSelector,SourceUnavailableException}.java`. 테스트 `source/NewsSourcesTest.java`, `src/test/resources/fixtures/geeknews.atom.xml`.

**Interfaces:** `NewsSource.fetch(DigestWindow window) -> List<NewsCandidate>`. `NewsCandidate(source, sourceId, title, url, originalUrl, occurredAt, rank)`. `NewsSelector.select(List<NewsCandidate> hn, List<NewsCandidate> geekNews) -> List<NewsCandidate>`. 전체 요청 실패는 `SourceUnavailableException`이다.

- [ ] **Step 1. 실패 테스트를 작성한다.** Atom 샘플에는 토픽 링크만 있는 항목과 마감 밖 항목을 넣는다. HN 샘플에는 경계, 삭제 항목과 비웹 링크를 넣는다.

```java
@Test void atomKeepsOfficialTopicWhenOriginalIsAbsent() {
    assertThat(geekNews.fetch(window)).singleElement().satisfies(item -> {
        assertThat(item.url()).isEqualTo(URI.create("https://news.hada.io/topic?id=42"));
        assertThat(item.originalUrl()).isNull();
    });
}

@Test void eligibleStoriesPreserveSourceOrderAndBackfillAfterDeduplication() {
    assertThat(hn.fetch(window)).extracting(NewsCandidate::sourceId).containsExactly("11", "15");
    assertThat(selector.select(hnWithSharedOriginal(), geekNewsWithFiveCandidates()))
        .extracting(NewsCandidate::sourceId).containsExactly("hn-1", "gn-2", "gn-3", "gn-4", "gn-5");
}
```

- [ ] **Step 2.** `./mvnw -Dtest=NewsSourcesTest test`가 어댑터 부재로 실패하는지 확인한다.
- [ ] **Step 3.** Spring `RestClient`로 HN 상위 목록과 항목을 조회한다. 삭제·비공개 상태와 HTTP(S) 외 링크를 제외하며 현재 구간의 순위를 보존한다. GeekNews는 DTD와 외부 엔티티를 끈 Java XML 파서로 공식 Atom과 `published`를 읽는다. 메일용 링크는 공식 토픽 ID를 사용한다. 확인한 원문이 같으면 HN을 남기고 다음 GeekNews 후보를 고른다. 원문을 모르면 토픽 URL로 항목을 식별한다. 반복이나 비교에 사용하는 URI는 프래그먼트와 추적 파라미터를 제외하고 다른 쿼리·경로를 보존한다.
- [ ] **Step 4.** 지정 테스트가 통과하면 Task 3 파일을 스테이징하고 `git commit -m "feat: ingest Hacker News and GeekNews links"`를 실행한다.

### Task 4. GitHub 정식 릴리스와 요약

**Files:** Java `source/{GitHubReleaseSource,ReleaseCollector,ReleaseCursorRepository,ReleaseSummaryClient,OpenAiReleaseSummaryClient,ReleaseSummaryService}.java`. 테스트 `source/{ReleaseCollectorTest,ReleaseSummaryServiceTest}.java`.

**Interfaces:** `GitHubReleaseSource.fetch(Project project, DigestWindow window) -> List<BriefItem>`, `ReleaseCollector.collect(Project project, DigestWindow normalWindow) -> List<BriefItem>`. `ReleaseSummaryClient.summarize(String releaseNotes) -> Optional<String>`, `ReleaseSummaryService.summarizePending(Instant cutoff) -> int`.

- [ ] **Step 1. 실패 테스트를 작성한다.** GitHub 샘플에 초안·프리릴리스·정식과 정확히 마감에 발표된 릴리스를 포함한다.

```java
@Test void partialPaginationDoesNotAdvanceCursorAndNextDayCatchesUp() {
    var before = cursor.completedThrough(project);
    githubFailsOnSecondPage();
    assertThatThrownBy(() -> collector.collect(project, window)).isInstanceOf(SourceUnavailableException.class);
    assertThat(cursor.completedThrough(project)).isEqualTo(before);
    assertThat(storedVersions()).isEmpty();
    githubReturnsBothPages();
    collector.collect(project, nextWindow);
    assertThat(storedVersions()).containsExactlyInAnyOrder("v2.0.0", "v2.1.0");
}

@Test void summariesAreCachedAndFailuresRetainTheReleaseLink() {
    assertThat(summaryService.summarizePending(cutoff)).isEqualTo(1);
    assertThat(summaryService.summarizePending(cutoff)).isZero();
    assertThat(saved("v2.0.0").summary()).isEqualTo("빌드 시간이 줄었습니다.");
    assertThat(saved("v3.0.0").summary()).isNull();
    assertThat(saved("v3.0.0").url()).isEqualTo(URI.create("https://github.com/example/repo/releases/tag/v3"));
}
```

- [ ] **Step 2.** `./mvnw -Dtest=ReleaseCollectorTest,ReleaseSummaryServiceTest test`를 실행해 미구현 상태에서 실패를 확인한다.
- [ ] **Step 3.** 인증된 GitHub `GET /repos/{owner}/{repo}/releases?per_page=100` 요청의 `Link: rel=next`를 API 호스트에서 끝까지 따라간다. `draft=false`, `prerelease=false`, 저장소별 `[completedThrough, cutoff)`의 `published_at`을 적용한다. 최초 시작점은 전날 마감이다. 일부 페이지라도 실패하면 결과를 폐기한다. 성공한 결과 upsert와 커서 변경은 같은 트랜잭션에서 처리하고 이전 커서를 비교해 되돌림을 막는다.
- [ ] **Step 4.** 요약 API에는 길이가 제한된 릴리스 노트만 보낸다. 모델 기본값은 `gpt-4.1-mini-2025-04-14`, 구조화 필드는 `summary` 하나다. 최대 12,000자 입력과 240자 출력을 적용한다. 빈 노트나 API 오류는 요약 없이 통과한다. 성공한 요약은 DB에 유지하고 다시 호출하지 않는다. [모델 문서](https://developers.openai.com/api/docs/models/gpt-4.1-mini) · [Structured Outputs](https://platform.openai.com/docs/guides/structured-outputs)
- [ ] **Step 5.** 지정 테스트가 모두 통과하면 Task 4 파일을 스테이징하고 `git commit -m "feat: collect and summarize stable releases"`를 실행한다.

### Task 5. 공통 뉴스와 개인 기술 메일

**Files:** Java `edition/{MailKind,EmailContent,EditionService}.java`. `src/main/resources/templates/email/{news,releases}.{html,txt}`. 테스트 `edition/EditionServiceTest.java`.

**Interfaces:** `enum MailKind { NEWS, RELEASES }`. `record EmailContent(String subject, String html, String text)`. `EditionService.eligibleReleases(long subscriberId, Instant cutoff) -> List<BriefItem>`. `EditionService.render(MailKind kind, List<BriefItem> items, URI manageUrl) -> Optional<EmailContent>`. 기존 발송 배정 제외는 Task 6, 날짜별 공통 뉴스 고정은 Task 7에서 처리한다.

- [ ] **Step 1. 실패 테스트를 작성한다.** 두 독자의 관심 기술과 정식 릴리스 날짜를 다르게 하고 안전하지 않은 URL과 HTML을 고정 샘플에 포함한다.

```java
@Test void selectionRespectsFollowTimeAndSubscriberPreferences() {
    assertThat(editions.eligibleReleases(reactReader, cutoff)).extracting(BriefItem::version).containsExactly("v2.0.0");
    assertThat(editions.eligibleReleases(springReader, cutoff)).extracting(BriefItem::version).containsExactly("v3.0.0");
    assertThat(editions.render(RELEASES, List.of(), manageUrl)).isEmpty();
}

@Test void renderEscapesSourceTextAndPreservesValidLinks() {
    var news = editions.render(NEWS, List.of(htmlTitleStory(), invalidUrlStory(), geekNewsStory()), manageUrl).orElseThrow();
    assertThat(news.subject()).isEqualTo("Daily Brief · 오늘의 뉴스");
    assertThat(news.html()).doesNotContain("<script>", "javascript:");
    assertThat(news.text()).contains("https://news.hada.io/topic?id=42");
    var releases = editions.render(RELEASES, List.of(releaseWithoutSummary()), manageUrl).orElseThrow();
    assertThat(releases.subject()).isEqualTo("Daily Brief · 내 기술 업데이트");
    assertThat(releases.text()).contains("v2.0.0", releaseWithoutSummary().url().toString());
}
```

- [ ] **Step 2.** `./mvnw -Dtest=EditionServiceTest test`를 실행해 미구현 상태에서 실패를 확인한다.
- [ ] **Step 3.** 활성 구독에서 현재 선택 시각 이상·마감 미만 릴리스만 최신순으로 반환한다. HTML은 Thymeleaf의 이스케이프 출력을 사용하고 검증된 HTTP(S) URL만 링크한다. 뉴스 순서는 입력 순서다. 기술 메일에 요약이 없으면 버전과 릴리스 링크가 표시된다. 양쪽 메일은 모바일 단일 열, 텍스트 버전, 구독 관리 링크를 제공한다.
- [ ] **Step 4.** 지정 테스트가 모두 통과하면 Task 5 파일을 스테이징하고 `git commit -m "feat: render two daily email editions"`를 실행한다.

### Task 6. 중복 발송 방지와 복구

**Files:** `src/main/resources/db/migration/V4__deliveries.sql`. Java `delivery/{DeliveryService,DeliveryRepository,MailTransport,MailPayload,SendResult,SendState,ResendMailTransport}.java`. 테스트 `delivery/{DeliveryServiceTest,ResendMailTransportTest}.java`.

**Interfaces:** `DeliveryService.prepare(long subscriberId, LocalDate day, MailKind kind, List<BriefItem> news, Instant now) -> Optional<Long>`, `dispatch(long deliveryId, Instant now) -> SendState`, `retryDue(Instant now) -> int`, `reconcile(long deliveryId, Resolution outcome, String evidence, Instant now)`. `MailTransport.send(MailPayload payload, String idempotencyKey) -> SendResult`. `SendResult`는 수락(provider ID), 확정 일시 거절, 확정 영구 거절, 결과 불명을 구분한다.

- [ ] **Step 1. 실패 테스트를 작성한다.** 작업자 경합은 PostgreSQL의 서로 다른 연결로 실행하고 실제 메일 대신 요청 키를 기록하는 테스트 전송기를 사용한다.

```java
@Test void pendingNetworkRequestUsesTheSamePayloadAfterLeaseExpiry() {
    simulateProviderAcceptThenCrash(deliveryId);
    service.dispatch(deliveryId, now.plusSeconds(121));
    assertThat(mail.payloads()).containsExactly(originalPayload, originalPayload);
    assertThat(mail.idempotencyKeys()).containsOnly(originalKey);
    assertThat(mail.acceptedCount()).isEqualTo(1);
}

@Test void removingAFollowPreventsRetryWhenDeliveryIsUnknown() {
    markUnknown(deliveryId);
    disableReleaseSubscription();
    assertThat(service.dispatch(deliveryId, now)).isEqualTo(SendState.HELD);
    assertThat(mail.payloads()).isEmpty();
}

@Test void permanentRejectionMakesStillEligibleReleaseAssignableAgain() {
    mail.rejectPermanently();
    assertThat(service.dispatch(deliveryId, now)).isEqualTo(SendState.UNSENT);
    assertThat(assignments(deliveryId)).isEmpty();
    assertThat(nextDigestAssignments()).containsExactly(releaseId);
}
```

- [ ] **Step 2.** `./mvnw -Dtest=DeliveryServiceTest,ResendMailTransportTest test`가 미구현 상태에서 실패하는지 확인한다.
- [ ] **Step 3.** DB에 `(subscriber_id, digest_day, kind)`와 `(subscriber_id, release_id)` 고유 제약을 둔다. 발송 전 구독자와 릴리스 배정을 잠그고 동일하게 재전송할 수신자·보낸 사람·제목·본문·요청 키를 고정한다. 적합한 항목이 없으면 발송 행을 만들지 않는다. 뉴스 공통 목록은 건드리지 않는다.
- [ ] **Step 4.** DB에서 2분 소유권을 획득한다. 현재 설정과 권한을 검증하고 요청이 제공업체에 도달할 수 있다는 표시를 커밋한 다음 외부 API를 호출한다. I/O에 DB 트랜잭션을 열어 두지 않는다. 최대 15초 타임아웃을 두고, 응답은 같은 소유권 토큰을 지닌 실행만 반영한다. 미제출 상태의 설정 변경과 확정 미발송은 배정을 해제한다. 이미 제출됐을 수 있고 현재 설정과 맞지 않으면 `HELD`로 둔다.
- [ ] **Step 5.** Resend `POST /emails`에 `Idempotency-Key`를 추가한다. 첫 제출 시각부터 23시간 55분까지 같은 본문과 키로만 재시도한다. 영구 거절, 확정 일시 거절의 재시도 기간 만료, 운영자가 확인한 미발송은 배정을 원자적으로 해제한다. 불명확한 요청은 수락 여부를 확인할 때까지 기존 배정을 보존한다. 운영 조정은 제공업체 증거 참조값을 저장한다. [Resend의 24시간 키 보장](https://resend.com/docs/dashboard/emails/idempotency-keys)
- [ ] **Step 6.** 동시 선점 1건, 재시도 기간 경계, 설정 변경 전·후, 제공업체 응답 분류를 추가로 확인한다. 지정 테스트가 통과하면 Task 6 파일을 스테이징하고 `git commit -m "feat: persist idempotent mail delivery"`를 실행한다.

### Task 7. Spring Batch Job과 운영 흐름

**Files:** Java `batch/{DailyBriefJobConfig,BatchRuns,BriefSchedule,BriefOperator,NewsEditionService}.java`. `src/main/resources/db/migration/V5__news_editions.sql`, `src/main/resources/application.yml` 수정. 테스트 `batch/DailyBriefBatchTest.java`. `.env.example`, `Dockerfile`, `compose.yaml`, `deploy/Caddyfile`, `.github/workflows/ci.yml`, `README.md`.

**Interfaces:** `BatchRuns.runDaily(LocalDate digestDate, UUID launchId) -> JobExecution`, `BatchRuns.retry(Instant now, UUID launchId) -> JobExecution`. Job 이름은 `dailyBriefJob`과 `deliveryRetryJob`이다. 운영 CLI는 `--spring.profiles.active=ops --spring.main.web-application-type=none --brief.ops.action=run-daily --brief.ops.date=YYYY-MM-DD` 또는 `--brief.ops.action=retry`를 받는다. 조정은 `--brief.ops.action=reconcile --brief.ops.delivery-id=N --brief.ops.outcome=accepted|unsent --brief.ops.evidence=REFERENCE`를 사용한다.

- [ ] **Step 1. 실제 JobRepository를 사용하는 실패 테스트를 작성한다.** 자동 스케줄은 끄고 고정 시각·소스 응답·메일을 주입한다.

```java
@Test void rerunningSameDayPersistsBatchHistoryWithoutDuplicateMail() throws Exception {
    assertThat(batch.runDaily(day, UUID.randomUUID()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(batch.runDaily(day, UUID.randomUUID()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(mail.acceptedCount()).isEqualTo(5);
    assertThat(newsFor(reactReader)).isEqualTo(newsFor(springReader));
    assertThat(releasesFor(reactReader)).containsExactly("react-v2");
    assertThat(releasesFor(springReader)).containsExactly("spring-v3");
    assertThat(releasesFor(unmatchedReader)).isEmpty();
}

@Test void aProcessCrashDoesNotBlockADifferentLaunchIdForTheSameDate() throws Exception {
    createAbandonedExecutionAndAcceptedDelivery(day);
    assertThat(batch.runDaily(day, UUID.randomUUID()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(mail.acceptedKeys()).doesNotContain(previouslyAcceptedKey());
}
```

- [ ] **Step 2.** `./mvnw -Dtest=DailyBriefBatchTest test`에서 Job 정의나 최종 동작 부재로 실패하는지 확인한다.
- [ ] **Step 3.** `dailyBriefJob`을 `collectNews` → `collectReleases` → `summarizeReleases` → `prepareDeliveries` → `dispatchDeliveries` 순서의 Tasklet Step으로 만든다. `deliveryRetryJob`은 기존 발송 행만 처리한다. 뉴스는 날짜별로 한 번 고정한다. 한 출처만 실패하면 다른 출처로 구성하고 두 출처가 모두 실패하면 그날의 공통 뉴스는 미확정으로 둔다. GitHub 실패는 저장소 단위로 기록하고 가능한 나머지 작업을 이어간다. 외부 호출을 하는 Tasklet에는 `ResourcelessTransactionManager`를 사용하고, 실제 데이터 변경은 Spring JDBC 트랜잭션으로 짧게 커밋한다. JobRepository의 트랜잭션 관리자는 PostgreSQL을 사용한다. [Spring Batch Tasklet](https://docs.spring.io/spring-batch/reference/step/tasklet.html) · [Spring Boot Batch 설정](https://docs.spring.io/spring-boot/reference/io/spring-batch.html)
- [ ] **Step 4.** 일일 Job의 식별 파라미터는 `digestDate`와 `launchId`다. 기존 인스턴스가 강제 종료로 `STARTED`에 남아도 새 launch ID로 날짜를 재실행한다. 일일 Job은 전용 연결에서 PostgreSQL 날짜별 advisory lock을 잡고, 동기 실행을 마친 뒤 `finally`에서 락을 해제한다. 실제 중복 발송 보호와 복구는 Task 6의 DB 상태를 사용한다. 일일 예약은 `@Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")`로, 재시도 예약은 15분 간격으로 설정한다. `brief.scheduling.enabled` 기본값은 false다. 활성 상태에서 앱이 시작되면 가장 최근 마감일을 실행 대상으로 삼으며, 그보다 이전 뉴스는 소급 발송하지 않는다. 스케줄러 스레드는 2개다.
- [ ] **Step 5.** 운영에서는 동일 Boot 애플리케이션 JAR, PostgreSQL, Caddy를 Compose로 실행한다. `ops` 프로필에서 스케줄과 기본 Job 실행을 끈다. 발송 자격 증명은 환경 변수에 두고 개발·CI는 테스트 전송기를 사용한다. 구독 확인과 관리 메일도 Resend 전송기를 재사용한다. README에는 DB 백업, 스케줄 중지, 결과 불명 확인 및 조정 명령을 적는다.
- [ ] **Step 6.** `./mvnw clean verify`와 `docker compose config --quiet`를 실행한다. 두 뉴스 출처 중 하나가 실패할 때 나머지 출처와 릴리스 메일이 진행되는 통합 테스트도 포함한다. Task 7 파일을 스테이징하고 `git commit -m "feat: run Daily Brief with Spring Batch"`를 실행한다.
- [ ] **Step 7.** 제공업체 설정이 끝난 시범 환경에서 두 사람이 서로 다른 프로젝트를 골라 확인 링크를 제출한다. 두 메일을 제목으로 구분하고 30초 안에 릴리스 원문을 열 수 있는지 확인한다. 같은 발송일을 재실행해 새 발송 수가 0인지 확인한 뒤 예약 발송을 켠다.

## 작업 순서

Task 1 이후 Tasks 2–4로 구독과 원천 데이터를 준비한다. Task 5가 메일 내용을 생성하고 Task 6이 발송 상태를 저장한다. Task 7이 이를 Spring Batch Job으로 연결하고 운영 시범으로 검증한다. 앞선 작업별 테스트는 시범용 메일 주소나 외부 제공업체 키 없이 실행한다.
