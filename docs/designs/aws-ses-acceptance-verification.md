# 서울 리전 SES 발송 수락 이벤트 검증

[이슈 #11](https://github.com/whoisyourbias/daily-brief-dev/issues/11) · [SES 수락 이벤트 설계](aws-ses-acceptance.md)

## 사전 점검 결과

2026-11-24에 서울 리전(`ap-northeast-2`)을 조회했다. SES의 `SendingEnabled`는 `true`, `ProductionAccessEnabled`는 `false`였다. configuration set 목록과 `SendingEnabled=true`인 발신자 목록은 모두 비어 있었다. 이후 테스트용 이메일 주소를 인증하고 같은 리전에서 발송과 이벤트 수집을 완료했다.

## 실측 결과

서울 리전의 전용 configuration set `daily-brief-issue11-1795483382`에서 `SEND` 이벤트를 EventBridge 기본 버스로 게시했다. 규칙에서 `aws.ses`의 `Email Sent` 이벤트를 임시 SQS Standard 큐로 전달하도록 설정했다. 메일박스 시뮬레이터에 발송 요청을 한 번 제출했고, 큐에서 받은 이벤트 메시지 ID가 발송 응답의 메시지 ID와 정확히 일치했다.

| 항목 | JSON 경로 | 관측된 형식 |
| --- | --- | --- |
| 발송 행 태그 | `detail.mail.tags.delivery_id` | 문자열 배열, 값 하나 `issue-11-seoul-smoke` |
| SES 메시지 ID | `detail.mail.messageId` | 문자열 |
| Configuration set | `detail.mail.tags["ses:configuration-set"]` | 문자열 배열, 값 하나 |

[필터·파서 테스트 fixture](../../src/test/resources/fixtures/ses/email-sent-seoul.json)는 실제 이벤트에서 검증에 필요한 필드를 골라 저장했다. 발신·수신 주소, 헤더, ARN, 호출자 정보와 IP를 제외하고, 계정 ID, 이벤트 ID, 메시지 ID와 configuration set 이름은 예시 값으로 치환했다. 테스트가 끝난 뒤 EventBridge 대상과 규칙, SQS 큐, SES configuration set을 정리했다. 서울 리전에서 규칙, 큐, configuration set을 각각 조회해 모두 존재하지 않는 것을 확인했다.

## 재현 절차

1. SES 콘솔에서 리전을 서울로 선택한다. 메일을 받을 수 있는 테스트 발신 주소를 identity로 등록하고, 인증 링크를 완료한 뒤 해당 identity가 발송 가능한 상태인지 확인한다. 테스트 수신자는 AWS SES 메일박스 시뮬레이터의 `success@simulator.amazonses.com`을 사용한다. [메일박스 시뮬레이터](https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html)
2. 이번 검증 전용 configuration set을 생성한다. 이벤트 대상으로 EventBridge 기본 이벤트 버스를 지정하고 `Send` 이벤트를 활성화한다. [SES EventBridge 대상 설정](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-add-event-destination-eventbridge.html)
3. 기본 이벤트 버스에 임시 규칙을 생성한다. 이벤트 패턴은 `source`가 `aws.ses`, `detail-type`이 `Email Sent`, `region`이 `ap-northeast-2`, `account`가 테스트 계정 ID인 이벤트로 제한한다. 같은 계정의 임시 SQS Standard 큐를 대상으로 연결하고 전체 이벤트를 입력으로 지정한다. 큐 정책에는 이 규칙의 ARN을 조건으로 `events.amazonaws.com`의 `sqs:SendMessage`를 허용한다. [EventBridge의 SQS 대상 권한](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-use-resource-based.html)
4. 아래 명령을 한 번 실행하고 응답에 포함된 SES 메시지 ID를 비교용으로 보관한다. `SES_TEST_SENDER`에는 인증한 주소를, `SES_TEST_CONFIG_SET`에는 전용 configuration set 이름을 넣는다. 응답이 유실되면 우선 큐에서 테스트 태그로 이벤트를 찾는다.

   ```sh
   AWS_MAX_ATTEMPTS=1 aws sesv2 send-email \
     --region ap-northeast-2 \
     --from-email-address "${SES_TEST_SENDER:?Set the verified sender}" \
     --destination 'ToAddresses=success@simulator.amazonses.com' \
     --configuration-set-name "${SES_TEST_CONFIG_SET:?Set the dedicated configuration set}" \
     --email-tags 'Name=delivery_id,Value=issue-11-seoul-smoke' \
     --content '{"Simple":{"Subject":{"Data":"Daily Brief SES event check"},"Body":{"Text":{"Data":"SES test event for issue 11"}}}}' \
     --query MessageId --output text
   ```

5. SQS 메시지의 `Body`를 JSON으로 읽고 테스트 태그와 configuration set으로 대상 이벤트를 식별한다. `delivery_id`, 메시지 ID, configuration set의 실제 JSON 경로와 값 타입을 기록한다. 이벤트의 메시지 ID를 발송 응답과 비교한다.
6. 실제 이벤트에서 발신·수신 주소, 계정 ID, 계정이 포함된 ARN과 메일 헤더를 검토하고 식별 정보를 제거하거나 예시 값으로 치환한다. 관측된 필드 경로와 타입을 보존해 필터·파서 테스트용 fixture를 저장한다. 검증이 끝나면 이번 실행에서 만든 임시 대상, 규칙, 큐, configuration set을 정리한다.
