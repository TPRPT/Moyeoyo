# Moyeoyo (모여요): 공정한 모임 장소 찾기

"Moyeoyo"는 친구들과의 모임을 더 쉽고 공평하게 만들어주는 안드로이드 앱입니다.
각 구성원의 위치를 기반으로 가장 공정한 중간 지점을 계산하고, 주변 장소를 추천하며, 그룹 투표를 통해 약속을 신속하게 확정할 수 있도록 돕습니다.

## 🚀 핵심 기능 (Core Features)

-   **간편한 인증**: Google 계정을 이용한 빠르고 안전한 로그인.
-   **그룹 관리**: 약속별 그룹 생성 및 딥링크를 통한 간편한 멤버 초대.
-   **스마트한 장소 추천**: 모든 멤버의 위치를 고려한 공평한 중간 지점 계산.
-   **장소/시간 투표**: 중간 지점 주변의 장소(카페, 식당) 추천 및 필터링, 그룹원 투표로 장소 확정.
-   **실시간 알림**: 약속 확정, 변경, 리마인더(D-1, 1시간 전) 등 주요 이벤트 푸시 알림.
-   **캘린더 연동**: 확정된 약속을 캘린더에 손쉽게 추가.

## 🛠️ 기술 스택 (Tech Stack)

-   **Backend & DB**: Firebase (Authentication, Firestore, Storage, Dynamic Links, FCM)
-   **Location & Maps**: Google Maps SDK, Google Places API, FusedLocationProviderClient
-   **Asynchronous**: Kotlin Coroutines, WorkManager
-   **UI & Image**: Android Jetpack (RecyclerView), Material Design 3, Coil/Glide

---

## 👨‍💻 역할 분담 (Team Roles)

-   **[은아](https://github.com/github-tprpt) (Leader / Backend & Auth)**
    -   Firebase 백엔드 아키텍처 및 DB 설계
    -   Firebase Authentication (로그인, 회원가입)
    -   그룹 생성, 초대 링크 (Dynamic Links), 멤버 관리
    -   푸시 알림 (FCM) 및 일정 확정 로직

-   **[영진](https://github.com/github-youn9jin) (Core Logic & Location)**
    -   핵심 중간값 계산 알고리즘 개발
    -   위치 기반 서비스 (GPS, 주소 검색) 연동
    -   이동 수단별 옵션 및 예상 소요 시간 계산 (Distance Matrix API)

-   **[지혜](https://github.com/github-jihyes12) (Feature & Vote Logic)**
    -   장소 추천 (Places API) 및 필터링 기능
    -   실시간 장소/시간 투표 및 확정 UI/UX
    -   캘린더 연동 및 기타 UI 개발
