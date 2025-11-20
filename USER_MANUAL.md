# Moyeoyo 앱 사용설명서

## 목차
1. [앱 시작 및 로그인](#1-앱-시작-및-로그인)
2. [프로필 설정](#2-프로필-설정)
3. [친구 추가](#3-친구-추가)
4. [그룹 생성](#4-그룹-생성)
5. [시간 투표](#5-시간-투표)
6. [장소 투표](#6-장소-투표)
7. [최종 확정](#7-최종-확정)
8. [푸시 알림](#8-푸시-알림)

---

## 1. 앱 시작 및 로그인

### 1.1 앱 실행
- **파일**: `MainActivity.kt`
- **설명**: 앱이 시작되면 자동으로 로그인 상태를 확인합니다.
- **기능**:
  - 로그인되어 있으면 → `MainFragment` 표시
  - 로그인되어 있지 않으면 → `LoginActivity`로 이동

### 1.2 Google 로그인
- **파일**: `ui/auth/LoginActivity.kt`, `data/repository/AuthRepository.kt`
- **기능**:
  - Google Sign-In을 통한 로그인
  - Firebase Authentication 연동
  - 신규 유저/기존 유저 자동 판별
- **흐름**:
  1. 사용자가 "Google로 로그인" 버튼 클릭
  2. Google 계정 선택 화면 표시
  3. 계정 선택 후 Firebase 인증 진행
  4. **신규 유저** → `ProfileSetupActivity`로 이동
  5. **기존 유저** → `MainActivity`로 이동

### 1.3 FCM 토큰 저장
- **파일**: `MainActivity.kt` (onCreate)
- **기능**: 로그인 시 FCM 토큰을 Firestore에 저장하여 푸시 알림 수신 준비

---

## 2. 프로필 설정

### 2.1 프로필 설정 화면
- **파일**: `ui/auth/ProfileSetupActivity.kt`
- **기능**:
  - 닉네임 입력
  - 프로필 이미지 설정 (카메라/갤러리)
  - 집 주소 설정 (필수)
  - 직장 주소 설정 (선택)
- **위치 설정 방법**:
  1. **주소 검색**: Google Places API를 사용한 자동완성 검색
  2. **현재 위치 사용**: GPS를 사용하여 현재 위치를 집/직장으로 설정
  3. **지도에서 확인**: `ConfirmLocationActivity`에서 지도로 위치 확인 및 조정

### 2.2 프로필 저장
- **저장 위치**: Firestore `users/{uid}` 컬렉션
- **저장 데이터**:
  - `nickname`: 닉네임
  - `photoUrl`: 프로필 이미지 URL (Firebase Storage)
  - `homeLocation`: 집 위치 정보 (GeoPoint, 주소, placeId)
  - `workLocation`: 직장 위치 정보 (선택)

### 2.3 프로필 수정
- **파일**: `ui/main/MainFragment.kt`
- **기능**: 프로필 카드 클릭 시 `ProfileSetupActivity`로 이동하여 프로필 수정 가능

---

## 3. 친구 추가

### 3.1 친구 검색
- **파일**: `ui/friends/AddFriendActivity.kt`, `data/repository/FriendRepository.kt`
- **기능**:
  - 이메일로 친구 검색
  - 검색 결과 표시 (닉네임, 이메일)
  - 친구 요청 전송
- **검색 결과 상태**:
  - 사용자 찾음 → 친구 요청 버튼 표시
  - 이미 친구 → "이미 친구입니다" 메시지
  - 본인 → "본인은 친구로 추가할 수 없습니다" 메시지
  - 사용자 없음 → "사용자를 찾을 수 없습니다" 메시지

### 3.2 친구 초대 링크
- **기능**: 친구 초대 링크 생성 및 공유
- **링크 형식**: `https://moyeoyo-57ac0.web.app/friend?uid={내UID}`
- **딥링크 처리**: `core/DeeplinkHandler.kt`에서 친구 요청 자동 처리

### 3.3 친구 목록 관리
- **기능**:
  - 친구 목록 표시
  - 친구 삭제 기능
- **저장 위치**: Firestore `users/{uid}/friends` 배열

### 3.4 친구 요청 알림
- **파일**: `data/repository/NotificationRepository.kt`, `services/FirebaseMessagingService.kt`
- **기능**: 친구 요청 시 푸시 알림 전송 (Cloud Functions 사용)
- **알림 확인**: `ui/notification/NotificationActivity.kt`에서 확인 및 수락/거절

---

## 4. 그룹 생성

### 4.1 그룹 생성 화면
- **파일**: `ui/groups/CreateGroupActivity.kt`
- **기능**:
  - 그룹 이름 입력
  - 친구 선택 화면으로 이동

### 4.2 친구 선택
- **파일**: `ui/groups/SelectFriendsActivity.kt`
- **기능**:
  - 친구 목록에서 그룹 멤버 선택 (다중 선택 가능)
  - 선택된 친구 목록 반환

### 4.3 그룹 생성 완료
- **파일**: `data/repository/GroupRepository.kt`
- **저장 위치**: Firestore `groups/{groupId}` 컬렉션
- **저장 데이터**:
  - `groupName`: 그룹 이름
  - `hostUid`: 그룹 생성자 UID
  - `memberUids`: 멤버 UID 배열
  - `status`: 그룹 상태 ("CREATED", "TIME_VOTING", "TIME_FINALIZING", "LOCATION_INPUT_REQUIRED", "PLACE_RANKING", "FINAL_PLACE_VOTE", "FINALIZED")
  - `createdAt`: 생성 시간

### 4.4 그룹 초대
- **파일**: `ui/groups/GroupInviteActivity.kt`
- **기능**:
  - 그룹 초대 링크 생성 및 공유
  - 링크 형식: `https://moyeoyo-57ac0.web.app/join?groupId={groupId}`
  - 딥링크 처리: `core/DeeplinkHandler.kt`에서 그룹 가입 자동 처리

---

## 5. 시간 투표

### 5.1 시간 투표 화면
- **파일**: `ui/time/TimeVoteActivity.kt`, `data/repository/TimeVoteRepository.kt`
- **기능**:
  - 주간 달력 표시 (월요일~일요일)
  - 시간대 선택 (드래그로 여러 시간 선택 가능)
  - 선택한 시간을 Firestore에 저장
- **저장 위치**: Firestore `groups/{groupId}/timeVotes/{date}/{uid}` 문서
- **저장 데이터**:
  - `selectedTimes`: 선택한 시간 배열 (예: ["09:00", "10:00", "11:00"])

### 5.2 모든 멤버 투표 완료 확인
- **기능**: 모든 멤버가 시간 투표를 완료하면 자동으로 최종 시간 투표 화면으로 이동
- **확인 방법**: `TimeVoteRepository.checkAllMembersVoted()` 함수 사용

### 5.3 최종 시간 투표
- **파일**: `ui/time/FinalTimeVoteActivity.kt`
- **기능**:
  - 모든 멤버가 겹치는 시간 후보 표시
  - 각 시간별 투표 수 표시
  - 최종 시간 선택 및 투표
- **저장 위치**: Firestore `groups/{groupId}/finalTimeVotes/{date}/{uid}` 문서

### 5.4 최종 시간 확정
- **기능**: 모든 멤버가 최종 시간 투표를 완료하면 자동으로 시간 확정
- **확정 로직**:
  1. 모든 멤버의 최종 투표 수집
  2. 가장 많은 투표를 받은 시간 선택
  3. `GroupRepository.setFinalTime()` 호출하여 그룹에 확정 시간 저장
  4. 그룹 상태를 "LOCATION_INPUT_REQUIRED"로 변경
- **확정 후**: `GroupDetailActivity`로 이동하여 장소 투표 진행

---

## 6. 장소 투표

### 6.1 위치 입력
- **파일**: `ui/location/LocationInputActivity.kt`
- **기능**:
  - 각 멤버가 자신의 출발 위치 입력
  - 이동 수단 선택 (도보/대중교통/자동차)
  - 위치 저장
- **저장 위치**: Firestore `groups/{groupId}/inputLocations/{uid}` 문서
- **저장 데이터**:
  - `latLng`: 위치 좌표 (GeoPoint)
  - `address`: 주소
  - `transportMode`: 이동 수단 ("WALK", "TRANSIT", "DRIVE")

### 6.2 중간 지점 계산
- **파일**: `map/MidpointActivity.kt`, `map/MapViewModel.kt`, `data/repository/MapRepository.kt`
- **기능**:
  - 모든 멤버의 위치를 기반으로 중간 지점 계산 (가중 평균)
  - 중간 지점 주변 장소 검색 (Google Places API)
  - 장소 목록 표시

### 6.3 장소 순위 지정
- **파일**: `ui/place/RecommendedPlaceActivity.kt`, `ui/place/RecommendedPlaceViewModel.kt`
- **기능**:
  - 추천 장소 목록 표시
  - 각 장소의 대중교통 소요시간 표시
  - 상위 3개 장소 선택 및 순위 지정 (1순위, 2순위, 3순위)
- **저장 위치**: Firestore `groups/{groupId}/userRankings/{uid}` 문서
- **저장 데이터**:
  - `rankedPlaces`: 순위가 매겨진 장소 배열 (각 장소는 placeId, rank, score 포함)

### 6.4 모든 멤버 순위 지정 완료 확인
- **기능**: 모든 멤버가 순위 지정을 완료하면 자동으로 최종 투표 화면으로 이동
- **확인 방법**: Firestore `groups/{groupId}/vote` 문서의 `rankedUsers` 배열 확인

### 6.5 최종 장소 투표
- **파일**: `ui/place/FinalVoteActivity.kt`, `ui/place/FinalVoteViewModel.kt`
- **기능**:
  - 모든 멤버의 순위 점수를 합산하여 상위 3개 장소 후보 생성
  - 각 후보의 대중교통 소요시간 표시
  - 최종 장소 선택 및 투표
- **점수 계산**:
  - 1순위: 3점
  - 2순위: 2점
  - 3순위: 1점
- **저장 위치**: Firestore `groups/{groupId}/placeCandidates` 컬렉션

### 6.6 최종 장소 확정
- **기능**: 모든 멤버가 최종 투표를 완료하면 자동으로 장소 확정
- **확정 로직**:
  1. 모든 멤버의 최종 투표 수집
  2. 가장 많은 투표를 받은 장소 선택
  3. `GroupRepository.confirmGroupSchedule()` 호출하여 그룹에 확정 장소 저장
  4. 그룹 상태를 "FINALIZED"로 변경
- **확정 후**: `GroupDetailActivity`로 이동하여 최종 확정 정보 표시

---

## 7. 최종 확정

### 7.1 그룹 상세 화면
- **파일**: `ui/groups/GroupDetailActivity.kt`
- **기능**:
  - 그룹 정보 표시 (이름, 멤버 수)
  - 확정된 일정 표시
  - 투표 탭 / 멤버 탭 전환

### 7.2 최종 확정 정보 표시
- **파일**: `activity_group_detail.xml`
- **표시 내용**:
  - **다음 모임 카드**: 확정된 일시와 장소를 파란색 카드로 표시
  - **캘린더 연동 버튼**: 캘린더에 일정 추가
  - **최종 확정된 장소 지도**: 
    - 빨간색 마커: 최종 확정된 장소
    - 주황색 마커: 각 멤버의 출발 위치
    - 확대/축소 버튼 활성화
  - **멤버별 소요시간**: 각 멤버의 최종 장소까지 이동 수단별 소요시간 표시

### 7.3 캘린더 연동
- **파일**: `ui/vote/ConfirmActivity.kt`
- **기능**: 확정된 일정을 기기 캘린더에 추가

### 7.4 위젯
- **파일**: `ui/widget/NextMeetingWidgetProvider.kt`
- **기능**: 홈 화면 위젯으로 다음 모임 정보 표시

---

## 8. 푸시 알림

### 8.1 FCM 서비스
- **파일**: `services/FirebaseMessagingService.kt`
- **기능**:
  - FCM 토큰 갱신 시 Firestore에 저장
  - 푸시 알림 수신 및 표시
- **토큰 저장**: Firestore `users/{uid}/fcmToken` 필드

### 8.2 알림 종류
- **파일**: Cloud Functions (`functions/src/index.ts`)
- **알림 종류**:
  1. **친구 요청 알림**: 친구 요청 수신 시
  2. **그룹 초대 알림**: 그룹 초대 수신 시
  3. **투표 진행 알림**: 그룹에서 투표가 진행될 때
  4. **일정 확정 알림**: 일정이 확정되었을 때

### 8.3 알림 확인
- **파일**: `ui/notification/NotificationActivity.kt`, `data/repository/NotificationRepository.kt`
- **기능**:
  - 알림 목록 표시
  - 알림 읽음 처리
  - 알림 클릭 시 해당 화면으로 이동

### 8.4 알림 배지
- **파일**: `ui/main/MainFragment.kt`
- **기능**: 읽지 않은 친구 요청이 있으면 알림 배지 표시

---

## 주요 데이터 구조

### Firestore 컬렉션 구조

```
users/
  {uid}/
    - nickname: String
    - email: String
    - photoUrl: String
    - homeLocation: Map
    - workLocation: Map
    - friends: Array<String>
    - fcmToken: String
    - groups: Array<String>

groups/
  {groupId}/
    - groupName: String
    - hostUid: String
    - memberUids: Array<String>
    - status: String
    - confirmedTime: Timestamp
    - confirmedPlace: Map
    - createdAt: Timestamp
    - timeVotes/
      {date}/
        {uid}/
          - selectedTimes: Array<String>
    - finalTimeVotes/
      {date}/
        {uid}/
          - selectedTime: String
    - inputLocations/
      {uid}/
        - latLng: GeoPoint
        - address: String
        - transportMode: String
    - userRankings/
      {uid}/
        - rankedPlaces: Array<Map>
    - placeCandidates/
      {candidateId}/
        - place: Map
        - totalScore: Number
        - voterUids: Array<String>
    - vote/
      - status: String
      - rankedUsers: Array<String>
      - finalVotedUsers: Array<String>

notifications/
  {notificationId}/
    - uid: String
    - title: String
    - message: String
    - type: String
    - read: Boolean
    - createdAt: Timestamp
```

---

## 주요 Repository 클래스

### AuthRepository
- **파일**: `data/repository/AuthRepository.kt`
- **기능**: Google 로그인, Firebase 인증, 신규 유저 판별

### FriendRepository
- **파일**: `data/repository/FriendRepository.kt`
- **기능**: 친구 검색, 친구 요청 전송/수락/거절, 친구 목록 관리

### GroupRepository
- **파일**: `data/repository/GroupRepository.kt`
- **기능**: 그룹 생성, 그룹 정보 조회, 그룹 상태 업데이트, 일정 확정

### TimeVoteRepository
- **파일**: `data/repository/TimeVoteRepository.kt`
- **기능**: 시간 투표 저장/조회, 겹치는 시간 계산, 최종 시간 투표

### MapRepository
- **파일**: `data/repository/MapRepository.kt`
- **기능**: 위치 저장/조회, 중간 지점 계산, 장소 검색, 거리/소요시간 계산

### VoteRepository
- **파일**: `data/repository/VoteRepository.kt`
- **기능**: 장소 순위 저장, 최종 투표, 투표 상태 관리

### NotificationRepository
- **파일**: `data/repository/NotificationRepository.kt`
- **기능**: FCM 토큰 저장, 알림 조회, Cloud Functions 호출

---

## 주요 화면 흐름

### 전체 흐름도
```
1. 앱 시작 (MainActivity)
   ↓
2. 로그인 확인
   ├─ 로그인 안됨 → LoginActivity
   └─ 로그인 됨 → MainFragment
   
3. LoginActivity
   ├─ Google 로그인
   ├─ 신규 유저 → ProfileSetupActivity
   └─ 기존 유저 → MainActivity
   
4. ProfileSetupActivity
   ├─ 프로필 설정
   └─ MainActivity로 이동
   
5. MainFragment
   ├─ 그룹 목록 표시
   ├─ 그룹 생성 → CreateGroupActivity
   ├─ 친구 추가 → AddFriendActivity
   └─ 그룹 클릭 → GroupDetailActivity
   
6. CreateGroupActivity
   ├─ 그룹 이름 입력
   ├─ 친구 선택 → SelectFriendsActivity
   └─ 그룹 생성 완료 → GroupInviteActivity
   
7. GroupDetailActivity
   ├─ 투표 탭
   │  ├─ 시간 버튼 → TimeVoteActivity
   │  │  └─ 모든 멤버 투표 완료 → FinalTimeVoteActivity
   │  │     └─ 시간 확정 → GroupDetailActivity
   │  └─ 위치 버튼 → LocationInputActivity
   │     └─ RecommendedPlaceActivity
   │        └─ 모든 멤버 순위 지정 완료 → FinalVoteActivity
   │           └─ 장소 확정 → GroupDetailActivity
   └─ 멤버 탭
      └─ 멤버 목록 표시
```

---

## 권한 요청

### 필요한 권한
1. **인터넷**: 네트워크 통신
2. **위치**: 현재 위치 조회, 지도 표시
3. **카메라**: 프로필 사진 촬영
4. **알림**: 푸시 알림 수신

### 권한 요청 시점
- **위치 권한**: 프로필 설정 시, 위치 입력 시
- **카메라 권한**: 프로필 사진 촬영 시
- **알림 권한**: MainActivity 시작 시

---

## 딥링크 처리

### 딥링크 종류
1. **그룹 초대**: `https://moyeoyo-57ac0.web.app/join?groupId={groupId}`
2. **친구 초대**: `https://moyeoyo-57ac0.web.app/friend?uid={uid}`

### 처리 파일
- **파일**: `core/DeeplinkHandler.kt`
- **기능**: 딥링크 파싱 및 해당 화면으로 자동 이동

---

## 주요 기술 스택

### 백엔드
- **Firebase Authentication**: 사용자 인증
- **Firestore**: 데이터베이스
- **Firebase Storage**: 이미지 저장
- **Firebase Cloud Messaging**: 푸시 알림
- **Firebase Cloud Functions**: 서버 로직 (푸시 알림 전송)

### 외부 API
- **Google Sign-In**: 소셜 로그인
- **Google Maps API**: 지도 표시, 장소 검색
- **Google Places API**: 장소 자동완성, 장소 검색
- **Google Distance Matrix API**: 거리/소요시간 계산

### 라이브러리
- **Hilt**: 의존성 주입
- **Coroutines**: 비동기 처리
- **Glide**: 이미지 로딩
- **Material Design Components**: UI 컴포넌트

---

## 주의사항

1. **Google API 키**: `google-services.json`과 `AndroidManifest.xml`에 올바르게 설정되어 있어야 합니다.
2. **Firebase 프로젝트**: Firebase 프로젝트가 올바르게 설정되어 있어야 합니다.
3. **Cloud Functions**: 푸시 알림을 위해서는 Cloud Functions가 배포되어 있어야 합니다.
4. **권한**: 위치, 카메라, 알림 권한이 허용되어 있어야 해당 기능이 작동합니다.

---

## 버전 정보
- **앱 이름**: Moyeoyo
- **최종 업데이트**: 2025년 11월
- **플랫폼**: Android

