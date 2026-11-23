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

## Aurora DSQL 스키마 초기화

AWS CLI의 DSQL 관리자 연결 권한과 Python 3, `psql`을 준비합니다. 시스템 신뢰 저장소를 쓰려면 libpq 17 이상이 필요합니다. 이전 버전은 `--ca /path/to/root-ca.pem`으로 신뢰할 루트 인증서를 지정합니다. 새 Daily Brief 전용 스키마에 대해 한 번에 한 프로세스만 실행합니다.

```bash
python3 -B scripts/dsql_schema.py "$DSQL_CLUSTER" --region ap-northeast-2 --schema daily_brief
```

V2~V5의 테이블을 각각 커밋합니다. 구독자 자동 ID에는 `CACHE 65536`을 적용합니다. 세션 만료 인덱스를 포함한 보조 인덱스는 비동기로 생성하며 작업 성공과 카탈로그의 유효 상태를 확인한 뒤 종료합니다. V1 Spring Batch 메타데이터는 PostgreSQL의 Flyway 경로에서 관리합니다. DSQL 접속 세션에서는 초기화한 스키마를 `search_path`에 지정합니다.

실행 중 오류나 연결 끊김이 발생하면 원인을 해결하고 같은 클러스터와 스키마로 명령을 다시 실행합니다. 이미 커밋된 테이블과 유효한 인덱스를 재사용하고, 진행 중인 인덱스 작업은 이어서 기다립니다. 비어 있는 전용 스키마나 이 명령으로 이미 초기화를 시작한 스키마에서 실행합니다.

실패한 인덱스를 복구하려면 `pg_index`에서 유효 상태를 확인하고 `sys.jobs`에서 최근 실패 상세를 조회합니다. 유효하지 않고 진행 중인 작업도 없는 인덱스만 이름을 스키마로 한정해 삭제하고 다시 실행합니다. 인덱스가 여전히 생성 중이면 명령을 다시 실행해 완료를 기다립니다.

```sql
SELECT indisvalid
FROM pg_catalog.pg_index
WHERE indexrelid = 'daily_brief.brief_release_published_idx'::regclass;

SELECT job_id, status, details
FROM sys.jobs
WHERE object_name = 'daily_brief.brief_release_published_idx'
ORDER BY start_time DESC;

-- 실패한 인덱스를 삭제하는 예
DROP INDEX daily_brief.brief_release_published_idx;
```

임시 클러스터에서 비동기 인덱스 제출 직후 연결이 끊긴 상황을 재현한 뒤, 재실행과 자동 ID, 외래 키, 보조 인덱스를 검증합니다. 검증 스크립트는 별도 스키마를 만들고 가상 데이터를 기록합니다.

```bash
python3 -B scripts/check-dsql-compatibility.py "$DSQL_TEST_CLUSTER" --region ap-northeast-2
```

## 불확정 발송 확인

Resend 요청에는 `delivery_id` 태그를 붙입니다. 업체 기록에서 발송 ID와 수락 증거가 일치하는지 확인한 운영자가 같은 PostgreSQL을 사용하는 환경에서 대조를 실행할 수 있습니다. 이 명령은 웹 서버와 배치를 시작하지 않습니다.

```powershell
java -jar target/daily-brief-0.1.0.jar "--dailybrief.reconcile.id=$deliveryId" --dailybrief.reconcile.outcome=accepted "--dailybrief.reconcile.provider-id=$providerMessageId"
```

`$deliveryId`와 `$providerMessageId`에는 확인한 발송 UUID와 Resend 메시지 ID를 지정합니다. 업체가 미발송을 확정한 건은 `--dailybrief.reconcile.outcome=unsent`로 처리합니다. 수락 여부를 확인할 수 없는 발송은 보류합니다.
