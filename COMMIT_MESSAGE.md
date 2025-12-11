feat: 그룹 관리 및 사진 기능 개선, UI 통일

## 주요 변경사항

### 그룹 관리
- 그룹 관리 화면에 "전체 투표 초기화" 버튼 추가
- 모든 투표 결과 삭제 및 그룹 상태 초기화 기능 구현
- GroupRepository에 resetAllVotes 함수 추가

### 사진 기능 개선
- 사진 크게 보기를 오버레이 방식으로 변경 (Activity root view에 직접 추가)
- 다운로드 기능 개선: MediaStore API 사용하여 갤러리에 저장
- 사진 뷰어 레이아웃 개선: FrameLayout 기반으로 변경, 스크롤 지원
- 사진 삭제 기능 추가 (본인 업로드 사진만)

### 친구 카드 UI 통일
- 친구 목록 및 친구 선택 화면에서 이메일 표시
- FriendRepository에 getUserEmail 함수 추가
- 모든 친구 카드에서 일관된 UI 제공

### 코드 정리
- DeepLinkHandler 통합 (위젯 딥링크 처리 포함)
- App.kt 위치 변경 시도 (Hilt 요구사항으로 원래 위치 유지)
- Toast 메시지로 통일 (Snackbar 제거)

### 버그 수정
- 사진 크게 보기 레이아웃 오류 수정 (minHeight 문제)
- 다운로드 경로 명확화 (갤러리 저장 확인)

