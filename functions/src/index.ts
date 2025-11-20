import * as admin from "firebase-admin";
import {
  onDocumentCreated,
  onDocumentUpdated
} from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";

/* ------------------------------------------------------
  Admin SDK 초기화 (Node 22 + Functions v2 필수)
-------------------------------------------------------*/
if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.applicationDefault()
  });
}

const db = admin.firestore();

/* ------------------------------------------------------
  공통: 멤버 FCM 토큰 가져오기
-------------------------------------------------------*/
async function getMembersFcmTokens(memberUids: string[]): Promise<string[]> {
  const tokens: string[] = [];
  for (const uid of memberUids) {
    const doc = await db.collection("users").doc(uid).get();
    const token = doc.get("fcmToken");
    if (token) tokens.push(token);
  }
  return tokens;
}

/* ------------------------------------------------------
  공통: 알림 문서 생성
-------------------------------------------------------*/
async function createNotification(uid: string, data: any) {
  return db
    .collection("users")
    .doc(uid)
    .collection("notifications")
    .add({
      ...data,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      read: false,
      handled: false,
    });
}

/* ------------------------------------------------------
  공통: 푸시 + 알림 문서 생성 (FCM v1 안정 API)
-------------------------------------------------------*/
async function sendPushToMembers(
  memberUids: string[],
  title: string,
  message: string,
  data: any = {}
) {
  const tokens = await getMembersFcmTokens(memberUids);

  if (tokens.length > 0) {
    await admin.messaging().sendEachForMulticast({
      tokens,
      notification: { title, body: message },
      data
    });
  }

  // Firestore 알림 저장 시 전달된 data를 그대로 포함
  for (const uid of memberUids) {
    await createNotification(uid, {
      title,
      message,
      ...data
    });
  }
}


/* ------------------------------------------------------
  1) 친구 요청 알림
-------------------------------------------------------*/
export const onFriendRequestCreated = onDocumentCreated(
  {
    region: "asia-east1",
    document: "friendRequests/{requestId}"
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const data = snap.data();
    if (!data) return;

    const senderUid = data.senderUid;
    const receiverUid = data.receiverUid;

    const senderDoc = await db.collection("users").doc(senderUid).get();
    const senderName = senderDoc.get("nickname") ?? "누군가";

    const receiverDoc = await db.collection("users").doc(receiverUid).get();
    const receiverToken = receiverDoc.get("fcmToken");

    // FCM 푸시
    if (receiverToken) {
      await admin.messaging().sendEachForMulticast({
        tokens: [receiverToken],
        notification: {
          title: "🤝 새 친구 요청",
          body: `${senderName}님이 친구 요청을 보냈습니다!`
        },
        data: { type: "friend_request" }
      });
    }

    // Firestore 저장
    await createNotification(receiverUid, {
      title: "새 친구 요청",
      message: `${senderName}님이 친구 요청을 보냈습니다!`,
      type: "friend_request",
      senderUid: senderUid
    });
  }
);

/* ------------------------------------------------------
  2) 그룹 상태 변경 알림
-------------------------------------------------------*/
export const onGroupStatusChanged = onDocumentUpdated(
  {
    region: "asia-east1",
    document: "groups/{groupId}"
  },
  async (event) => {
    const before = event?.data?.before?.data();
    const after = event?.data?.after?.data();

    if (!before || !after) return;

    const previousStatus = before.status;
    const newStatus = after.status;

    if (!newStatus || previousStatus === newStatus) return;

    const memberUids = after.memberUids ?? [];
    const groupName = after.groupName ?? "모임";

    switch (newStatus) {
      case "TIME_VOTE_REQUIRED":
        await sendPushToMembers(
          memberUids,
          "⏰ 시간 투표 요청",
          `${groupName} 모임이 만들어졌습니다! 가능한 시간을 투표해주세요.`,
          { type: "time_vote" }
        );
        break;

      case "TIME_FINALIZING":
        await sendPushToMembers(
          memberUids,
          "🕒 최종 시간 투표",
          "최종 약속 일정을 투표해주세요!",
          { type: "location_input" }
        );
        break;

      case "LOCATION_INPUT_REQUIRED":
        await sendPushToMembers(
          memberUids,
          "📍 시간 투표 완료!",
          `${groupName}의 약속 일정이 확정되었습니다. 이제 출발 위치를 입력해주세요.`,
          { type: "location_input" }
        );
        break;

      case "PLACE_RANKING":
        await sendPushToMembers(
          memberUids,
          "✨ 장소 순위 투표 시작",
          "중간 지점 계산 완료! 주변 장소를 보고 순위를 투표해주세요.",
          { type: "ranking" }
        );
        break;

      case "FINAL_PLACE_VOTE":
        await sendPushToMembers(
          memberUids,
          "🔥 최종 장소 투표",
          "마지막으로 최종 약속 장소를 투표해주세요!",
          { type: "final_vote" }
        );
        break;

      case "FINALIZED":
        await sendPushToMembers(
          memberUids,
          "🎉 약속 장소 확정!",
          `${groupName}의 약속 장소가 확정되었습니다.`,
          { type: "finalized" }
        );
        break;
    }
  }
);

/* ------------------------------------------------------
  3) 약속 전날 알림
-------------------------------------------------------*/
export const appointmentReminder = onSchedule("0 9 * * *", async () => {
  const today = new Date();
  const tomorrow = new Date(today);
  tomorrow.setDate(today.getDate() + 1);

  const start = new Date(tomorrow.setHours(0, 0, 0));
  const end = new Date(tomorrow.setHours(23, 59, 59));

  const snap = await db
    .collection("groups")
    .where("confirmedTime", ">=", start)
    .where("confirmedTime", "<=", end)
    .get();

  for (const doc of snap.docs) {
    const data = doc.data();
    const memberUids = data.memberUids ?? [];

    const groupName = data.groupName ?? "모임";

    // 약속 시간 포맷팅
    const confirmedTime = data.confirmedTime?.toDate?.() ?? null;
    let timeText = "";
    if (confirmedTime) {
      const hours = confirmedTime.getHours().toString().padStart(2, "0");
      const minutes = confirmedTime.getMinutes().toString().padStart(2, "0");
      timeText = `${hours}:${minutes}`;
    }

    // 장소 정보
    const placeName =
          typeof data.confirmedPlace === "string"
            ? data.confirmedPlace
            : data.confirmedPlace?.name ?? "장소 미정";

    // 알림 메시지 생성
    const reminderMessage =
          `내일 '${groupName}' 일정이 있어요!\n` +
          `${placeName}` + (timeText ? ` / ${timeText}` : "");

    await sendPushToMembers(
          memberUids,
          "🔔 내일 약속이 있어요!",
          reminderMessage,
          {
            type: "reminder",
            groupId: doc.id
          }
    );
  }
});
