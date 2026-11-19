# Daily Brief

Hacker News와 GeekNews의 주요 글, React·Next.js·Spring Boot의 정식 릴리스를 구독자의 선택에 따라 두 종류의 메일로 보냅니다. Java 21, Spring Boot, Spring Batch, PostgreSQL로 실행합니다.

AWS 전환 진행 상황은 [AWS 전환 설계](docs/designs/daily-brief-aws.md)에 기록합니다.

## 로컬 실행

Docker Desktop과 Java 21을 준비한 뒤 PowerShell에서 실행합니다. PostgreSQL 스키마와 Spring Batch 메타데이터는 애플리케이션이 Flyway로 적용합니다.

```powershell
docker compose up -d --wait postgres
.\mvnw.cmd spring-boot:run
```

macOS와 Linux에서는 Maven 명령을 `bash ./mvnw spring-boot:run`으로 실행합니다. 브라우저에서 [구독 화면](http://localhost:8080/)을 엽니다.

| 환경 변수 | 용도 |
|---|---|
| `DAILYBRIEF_DB_URL`, `DAILYBRIEF_DB_USER`, `DAILYBRIEF_DB_PASSWORD` | PostgreSQL 접속 정보. 기본값은 로컬 Compose에 맞춥니다. |
| `DAILYBRIEF_PUBLIC_BASE_URL` | 확인·관리 메일에 넣는 서비스 주소 |
| `DAILYBRIEF_SECURE_COOKIES` | HTTPS 배포에서 `true`로 설정 |
| `RESEND_API_KEY`, `RESEND_FROM` | 확인·관리 메일과 정규 메일에 사용할 Resend 인증 키 및 인증된 발신자 |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | 릴리스 노트 요약. 키가 비어 있으면 릴리스 버전과 링크를 제공합니다. |
| `DAILYBRIEF_SCHEDULING_ENABLED` | 예약 실행을 활성화합니다. 기본값은 `false`입니다. |
| `DAILYBRIEF_SCHEDULING_ACTIVATION_AT` | 예약을 활성화한 시각을 UTC ISO-8601 형식으로 지정합니다. |

메일 설정이 비어 있어도 구독 화면과 DB를 확인할 수 있습니다. 링크 메일 수신까지 확인하려면 실제 Resend 설정이 필요합니다. 운영 주소와 발신자를 설정하고, 시범 발송의 수락 증거를 확인한 뒤 예약을 활성화합니다. 운영에서 연결하는 프록시는 실제 요청 IP를 신뢰 가능한 경로로 전달하도록 구성합니다.

## 검증

```powershell
.\mvnw.cmd clean verify
```

Docker가 실행 중이면 PostgreSQL 통합 테스트도 실행됩니다. 테스트에서는 고정 HTTP 응답과 가짜 메일 게이트웨이를 사용합니다. CI는 Java 21에서 같은 Maven 검증을 실행합니다.

## 불확정 발송 확인

Resend 요청에는 `delivery_id` 태그를 붙입니다. 업체 기록에서 발송 ID와 수락 증거가 일치하는지 확인한 운영자가 같은 PostgreSQL을 사용하는 환경에서 대조를 실행할 수 있습니다. 이 명령은 웹 서버와 배치를 시작하지 않습니다.

```powershell
java -jar target/daily-brief-0.1.0.jar "--dailybrief.reconcile.id=$deliveryId" --dailybrief.reconcile.outcome=accepted "--dailybrief.reconcile.provider-id=$providerMessageId"
```

`$deliveryId`와 `$providerMessageId`에는 확인한 발송 UUID와 Resend 메시지 ID를 지정합니다. 업체가 미발송을 확정한 건은 `--dailybrief.reconcile.outcome=unsent`로 처리합니다. 수락 여부를 확인할 수 없는 발송은 보류합니다.
