# 웹 Lambda 관리 세션과 요청 IP

[Daily Brief AWS 전환 설계](daily-brief-aws.md)의 구독 관리 세션과 요청 IP 구현을 정리한다.

## 세션

관리 세션을 DSQL에 저장하고 브라우저에는 고엔트로피 세션 ID를 HTTPS 쿠키로 보낸다. DSQL에는 세션 ID의 해시, 구독자 ID, 관리 세대, 만료 시각과 세션 기반 CSRF 검증에 필요한 상태를 보관한다. 관리 링크를 소비한 뒤 새 세션을 발급하고 현재 브라우저의 이전 세션을 무효화한다. 성공한 관리 링크 소비와 세션 생성은 한 트랜잭션에서 처리한다.

서명된 JWT의 고정 만료 시각만으로는 15분 유휴 만료와 이전 세션의 즉시 무효화를 함께 적용할 수 없다. 두 동작에 필요한 상태를 DSQL의 세션 행에서 관리한다.

- 관리 요청마다 15분 유휴 만료를 확인한다. 관리 링크 사용에 성공하면 별도 DB 작업으로 만료된 세션을 최대 100개 정리한다. 활동 시각 갱신은 유효한 행에만 적용하며 이전 요청이 만료를 앞당기거나 무효화된 세션을 되살릴 수 없게 한다.
- 웹 Lambda는 `__Host-brief-management` 쿠키에 `Secure`, `HttpOnly`, `SameSite=Lax`를 적용한다. 설정 조회·저장과 해지에는 활성 구독과 관리 세대를 확인하고, 상태를 바꾸는 요청에는 세션에 연결된 CSRF 토큰을 검증한다. DSQL 조회에 실패하면 실패한 작업을 중단하고 HTTP `503`과 `Retry-After`를 반환한다.

## 요청 IP

초기 운영에서는 API Gateway HTTP API가 직접 공개 요청을 받는다. Lambda 어댑터는 페이로드 2.0의 `requestContext.http.sourceIp`만 요청 IP로 사용해 기존 IP 한도에 넘긴다. 클라이언트가 보낸 전달 헤더는 IP 판단에서 제외하고, 주소가 없거나 파싱할 수 없으면 메일 요청을 중단한다. IPv4와 IPv6는 동일한 주소가 항상 같은 한도 키로 변환되도록 정규화한다. Lambda는 의도한 API Gateway 통합을 호출 경로로 사용하며 공개 Function URL을 만들지 않는다. 프록시를 추가할 때에는 IP 신뢰 경계를 다시 검토한다. [API Gateway HTTP API Lambda 페이로드](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html)

구독 확인·관리 링크 메일은 수신 이메일 주소당 15분에 1회, 요청 IP당 1시간에 10회로 제한한다. 두 한도는 신규 구독과 링크 재요청에 함께 적용한다. 공유기나 회사 네트워크에서 동일한 공인 IPv4로 접속하면 10회 한도를 공유한다. 한도에 걸린 요청도 같은 접수 문구를 표시한다. 사이트 접속과 발급된 링크 사용, 구독 설정 조회·저장은 계속 가능하다. [구독 요청 한도 구현](../../src/main/java/com/dailybrief/subscription/SubscriptionService.java)

## 검증 결과

| 환경 | 확인 결과 |
| --- | --- |
| 서울 리전 임시 API Gateway·Lambda·Aurora DSQL | 한 Lambda에서 관리 링크를 소비하고 다른 Lambda에서 같은 쿠키로 설정을 조회·저장했다. 새 링크로 교체한 이전 세션과 CSRF 토큰이 거절됐고, 만료된 세션은 관리 링크 요청 화면으로 이동했다. 전달 헤더 값을 매번 바꿔도 같은 IP의 11번째 메일 요청이 제한됐다. |
| 서울 리전 임시 Aurora DSQL | V2~V5 초기화를 중단 후 재실행했다. 세션 교체와 이전 세션의 만료 갱신이 경합했을 때 이전 세션이 복구되지 않았고, 만료 행 정리 SQL도 통과했다. |
| 로컬 PostgreSQL·Lambda 어댑터 테스트 | 15분 유휴 만료 갱신, 관리 세대 불일치, CSRF 실패, DB 오류의 `503` 응답, HTTP API 2.0 쿠키 응답 형식을 확인했다. IPv4와 IPv6 이벤트에서 전달 헤더보다 Gateway가 기록한 주소가 사용됐고, 동일한 IPv6 주소의 서로 다른 표기는 같은 키로 집계됐다. |

통합 검증에 사용한 AWS 리소스는 삭제했다. 운영 인프라 구성은 AWS 전환의 배포 단계에서 적용한다. [구현 및 검증 PR](https://github.com/whoisyourbias/daily-brief-dev/pull/3)

## 웹 Lambda 실행 설정

```sh
./mvnw clean package dependency:copy-dependencies -DskipTests \
  -DincludeScope=runtime -DoutputDirectory=target/lambda-libs
docker build --provenance=false -f Dockerfile.web-lambda -t daily-brief-web-lambda .
```

API Gateway HTTP API의 Lambda 프록시 통합은 페이로드 버전 `2.0`으로 설정한다. Lambda 실행 역할에는 해당 Aurora DSQL 클러스터의 `dsql:DbConnect`를 부여하고, 대응하는 DB 역할에는 구독과 세션 테이블을 처리하는 데 필요한 권한을 부여한다. `DAILYBRIEF_DB_URL`에 `jdbc:aws-dsql:postgresql://<endpoint>/postgres?currentSchema=daily_brief`, `DAILYBRIEF_DB_USER`에 DB 역할, `DAILYBRIEF_PUBLIC_BASE_URL`에 사용자에게 공개할 HTTPS 주소를 지정한다. [Aurora DSQL JDBC 커넥터](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/SECTION_program-with-jdbc-connector.html)

웹 Lambda가 시작하기 전에 [DSQL 초기화 명령](../../README.md#aurora-dsql-스키마-초기화)으로 `daily_brief` 스키마를 적용한다. 초기화 명령은 V5 세션 테이블과 만료 인덱스를 포함한다.
