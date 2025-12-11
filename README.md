# Moyeoyo (모여요): 공정한 모임 장소 찾기

"Moyeoyo"는 친구들과의 모임을 더 쉽고 공평하게 만들어주는 안드로이드 앱입니다.
각 구성원의 위치를 기반으로 가장 공정한 중간 지점을 계산하고, 주변 장소를 추천하며, 그룹 투표를 통해 약속을 신속하게 확정할 수 있도록 돕습니다.

---

## 🚀 핵심 기능 (Core Features)

1. **Midpoint Calculation** - 모두에게 공평한 중간 지점 계산
2. **Location Recommendations & Filtering** - 중간 지점 주변 장소 추천 및 필터링
3. **Time & Location Voting** - 실시간 시간/장소 투표 시스템
4. **Notifications & Calendar Sync** - 약속 확정 알림 및 캘린더 연동
5. **Invite & Share Links** - 딥링크를 통한 간편한 그룹 초대 및 약속 공유

---

## 🛠️ 기술 스택 (Tech Stack)

### Android / Frontend

| Category | Technology Used |
|----------|----------------|
| **Language** | Kotlin |
| **UI** | ViewBinding, Material Design Components |
| **Architecture** | MVVM (ViewModel, LiveData) |
| **Structure** | Single Activity + Multiple Fragments (Navigation Component) |
| **Dependency Injection** | Hilt (Dagger Hilt) |
| **Navigation** | Navigation Component (Safe Args) |
| **List** | RecyclerView, DiffUtil, ListAdapter |
| **Map** | Google Maps SDK |
| **Location** | Places API, Geocoding API (Android Geocoder), Distance Matrix API, FusedLocationProviderClient |
| **Image** | Glide |
| **Asynchronous** | Kotlin Coroutines |
| **Local Database** | Room Database |
| **Network** | OkHttp |
| **JSON Parsing** | Moshi |
| **Widget** | AppWidget |

### Firebase / Backend

| Area | Technology Used |
|------|----------------|
| **Authentication** | Firebase Authentication (Google Login) |
| **Database** | Firestore (Users / Friends / Groups / Subcollections) |
| **File Storage** | Firebase Storage (Profile Image) |
| **Notifications** | Firebase Cloud Messaging (FCM) |
| **Server Logic** | Cloud Functions (Node.js) |
| **Link Sharing** | Dynamic Links |

---

## 👨‍💻 역할 분담 (Team Roles)

### [은아](https://github.com/github-tprpt) - Backend & Data Lead & Push Notifications

- Firebase 백엔드 아키텍처 및 DB 설계
- Firebase Authentication (로그인, 회원가입)
- Firestore/Realtime DB (데이터 모델, 그룹 동기화, 투표)
- 그룹 생성, 초대 링크 (Dynamic Links), 멤버 관리
- 푸시 알림 (FCM) 및 일정 확정 로직

**Key Tech:** Firebase Auth, Firestore/Realtime DB

---

### [영진](https://github.com/github-youn9jin) - Core Logic & Maps Lead & Voting

- 핵심 중간값 계산 알고리즘 개발
- GPS/Maps 연동 및 지도 표시
- Place/Distance API 로직 구현
- 이동 수단별 옵션 및 예상 소요 시간 계산 (Distance Matrix API)
- 위치 기반 서비스 (GPS, 주소 검색) 연동
- 투표 로직 개발
- 장소 추천 (Places API) 및 필터링 기능

**Key Tech:** GPS/Maps, Place/Distance API

---

### [지혜](https://github.com/github-jihyes12) - UI/UX & Scheduling Lead

- UI/UX 디자인 및 구현
- 투표 인터페이스 개발
- 실시간 장소/시간 투표 및 확정 UI/UX
- App Widget 개발
- 캘린더 연동 및 기타 UI 개발
- Device I/O 처리

**Key Tech:** UI/UX, App Widget, Device I/O

---

## 📱 주요 화면

- **시간 투표**: 주간 캘린더 기반 시간대 선택 및 투표
- **장소 추천**: 중간 지점 주변 카페/식당 추천 및 필터링
- **중간 지점 계산**: 멤버별 이동 소요 시간을 고려한 공평한 중간 지점 계산
- **투표 확정**: 실시간 투표 결과 확인 및 약속 확정
- **약속 공유**: 확정된 약속 정보 공유 및 캘린더 연동

---

## 📄 라이선스

이 프로젝트는 팀 프로젝트입니다.
