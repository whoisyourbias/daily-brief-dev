# D8 SES 수락 이벤트 전달 경로

[Daily Brief AWS 전환 설계](daily-brief-aws.md)의 SES 수락 이벤트 전달 경로에 대한 제안이다.

## 제안

`EventBridge → SQS Standard → Java 21 Lambda`를 사용한다. 큐에 도착한 이벤트는 보존 기간 동안 DSQL 장애와 별개로 유지할 수 있고, Lambda 동시성을 제한해 복구 직후 DSQL에 쓰기가 몰리는 것을 줄일 수 있다. 직접 Lambda로 전달하는 구성은 리소스가 적고 Lambda 비동기 실패 대상을 설정할 수 있다. D8에서는 대기 중인 이벤트를 확인하고 처리 속도를 조절하며 실패 메시지를 선별 재처리할 수 있는 SQS를 우선한다. [Lambda의 SQS 연동](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html)

- 일일 브리프 발송 요청에 전용 SES configuration set과 기존 발송 행의 `delivery_id` 태그를 지정한다. EventBridge 규칙에서 예상 AWS 계정, 리전, 이벤트 유형과 configuration set을 확인한다. 실제 이벤트에서 태그와 SES 메시지 ID의 경로를 확인한 뒤 필터와 파서를 확정한다. [SES 발송 이벤트 설정](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-send-email.html)
- Java 핸들러는 기존 수락 상태 전이 규칙을 사용한다. 이미 같은 SES 메시지 ID로 수락을 기록했으면 성공으로 응답한다. 다른 메시지 ID나 연결할 수 없는 발송 행은 조사 대상으로 남긴다. DSQL 커밋 이후에만 처리 성공을 반환하고, 배치 안에서 실패한 레코드만 다시 처리한다. 핸들러의 IAM 역할에는 메일 발송 권한을 부여하지 않는다.
- EventBridge가 SQS에 전달하지 못한 이벤트와 Lambda가 SQS에서 반복 처리에 실패한 메시지는 각각 별도 DLQ로 보낸다. 두 DLQ에 경보를 걸고, 정상 큐에는 가장 오래된 메시지의 대기 시간 경보를 둔다. 운영자는 원인을 확인한 뒤 해당 이벤트를 수락 처리 경로로 다시 넣는다. [EventBridge 전달 재시도](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-rule-retry-policy.html)
- SES에서 EventBridge로의 `Email Sent` 전달은 best effort다. EventBridge에 도착하지 않은 이벤트는 두 DLQ에도 기록되지 않는다. 수락 증거를 확인하지 못한 발송은 기존 설계대로 보류하고 릴리스 배정을 유지한 채 운영자에게 알린다. [EventBridge의 SES 이벤트 전달 방식](https://docs.aws.amazon.com/eventbridge/latest/ref/events-ref-ses.html)

## 승인 전 검증

서울 리전에서 테스트 메일을 한 번만 제출하고, 발송 측이 응답을 저장하지 못한 상황에서 실제 `Email Sent` 이벤트가 같은 발송 행을 수락으로 전환하는지 확인한다. 수락 증거가 오지 않는 경우에는 발송이 보류되고 운영자에게 알림이 가는지, 자동 재제출이 발생하지 않는지 확인한다. 배치와 이벤트 처리기의 동시 기록, 중복 이벤트, DSQL 일시 오류, 두 DLQ 경로와 재처리를 확인한 뒤 예약 발송을 켠다.
