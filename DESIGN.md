---
# gstack: design-md-format=spec
name: Daily Brief
description: 발행일과 기사 제목을 한눈에 훑는 개발자 뉴스레터
colors:
  background: "#F6F8F6"
  surface: "#FFFFFF"
  text: "#152E2D"
  text-muted: "#425B59"
  primary: "#075E59"
  on-primary: "#FFFFFF"
  divider: "#D7E2DF"
  border: "#758A88"
  focus: "#925D00"
  error: "#A21D28"
typography:
  date:
    fontFamily: Pretendard Variable
    fontSize: 2.5rem
    fontWeight: 700
    lineHeight: 1.1
  headline:
    fontFamily: Pretendard Variable
    fontSize: 1.25rem
    fontWeight: 650
    lineHeight: 1.45
  body:
    fontFamily: Pretendard Variable
    fontSize: 1rem
    fontWeight: 400
    lineHeight: 1.6
  metadata:
    fontFamily: Pretendard Variable
    fontSize: 0.875rem
    fontWeight: 500
    lineHeight: 1.5
rounded:
  control: 6px
spacing:
  title-source: 4px
  row-padding: 12px
  gutter: 20px
  section: 24px
  reading-width: 640px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.control}"
---

# Daily Brief

## 개요

개발자가 최근 이슈를 한눈에 확인하고 오늘 읽을 글을 바로 고를 수 있게 한다.
구독 웹 화면은 받을 내용을 보여주고, 메일은 기사 원문과 릴리스 노트로 안내한다.

## 레이아웃

발행일을 왼쪽에 크게 두고 Daily Brief 이름을 오른쪽에 둔다. 좁은 화면에서
둘이 겹치면 이름을 다음 줄에 배치한다. 헤더 아래에는 청록색 선을 긋는다.
본문은 왼쪽 정렬 단일 열로 배치한다.

- 구독 화면은 기사 제목 세 개와 출처를 보여준 뒤 구독 폼을 배치한다.
  실제 발행본 연결 전에는 목록에 ‘오늘의 뉴스 · 예시’를 표시한다.
- 뉴스 메일은 발행일 다음에 기사 목록을 배치한다. 제목 크기를 통일하고
  제목 아래에 출처를 둔다. 기사 사이에는 얇은 구분선을 사용한다.
- 기술 업데이트 메일은 프로젝트명과 버전을 먼저 보여준다. 버전 숫자를
  강조하고, 변경 요약 뒤에 ‘릴리스 노트 읽기’ 링크를 둔다.

## 색과 서체

밝은 회녹색과 흰색을 바탕으로 사용한다. 청록은 헤더 구분선과 링크,
구독 버튼에 사용한다. 기사 링크는 밑줄로 구분한다.
입력란에는 명확한 테두리를, 키보드 포커스에는 별도의 윤곽선을 표시한다.

웹은 Pretendard Variable 1.3.9의 공식 가변 서브셋을 사용한다.
메일은 기기 기본 한글 글꼴에서도 크기와 굵기로 같은 읽기 순서를 유지한다.
긴 한글 제목은 끝까지 표시하며 버전 문자열은 가능한 한 한 덩어리로 표시한다.

## 구성요소와 동작

구독 폼의 레이블은 항상 표시하고 오류는 해당 입력란 가까이에서 안내한다.
기사 목록과 메일은 정적으로 배치한다. 구독 옵션에 따른 설정 표시에는
150ms 전환을 사용할 수 있다. 동작 줄이기 설정에서는 즉시 표시한다.

## 확인 기준

- 390px 화면에서 구독 폼보다 앞에 기사 제목 세 개와 출처가 보인다.
- 긴 한글 제목이 줄바꿈돼도 다음 기사와 구분된다.
- 키보드로 이동할 때 현재 위치가 보인다.
- 메일에서 웹폰트가 빠져도 기사 제목, 출처, 릴리스 버전이 구분된다.
