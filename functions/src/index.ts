import * as admin from "firebase-admin";
import {
  onDocumentCreated,
  onDocumentUpdated
} from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";

admin.initializeApp();
const db = admin.firestore();

/* ------------------------------------------------------
  공통: 멤버 FCM 토큰 가져오기
-------------------------------------------------------*/
async function getMembersFcmTokens(memberUids: string[]): Promise<string[]> {
  const tokens: string[] = [];
  for (const uid of memberUids) {
    const userDoc = await db.collection("users").doc(uid).get();
    const token = userDoc.get("fcmToken");
    if (token) tokens.push(token);
  }
  return tokens;
}

/* ------------------------------------------------------
  공통: Firestore notifications 저장
-------------------------------------------------------*/
async function createNotification(uid: string, data: any) {
  await db
    .collection("users")
    .doc(uid)
    .collection("notifications")
    .add({
      ...data,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
}

/* ------------------------------------------------------
  공통: 푸시 발송 + Firestore 알림 저장
-------------------------------------------------------*/
async function sendPushToMembers(
  memberUids: string[],
  title: string,
  message: string,
  data: any = {}
) {
  const tokens = await getMembersFcmTokens(memberUids);
  if (tokens.length > 0) {
    await admin.messaging().sendToDevice(tokens, {
      notification: { title, body: message },
      data
    });
  }

  // Firestore 알림 문서 생성
  for (const uid of memberUids) {
    await createNotification(uid, {
      title,
      message,
      type: data.type ?? "group_event"
    });
  }
}

/* ------------------------------------------------------
  1) 기존 친구 요청 알림 ( 그대로 유지 )
-------------------------------------------------------*/
export const onFriendRequestCreated = onDocumentCreated(
  {
    region: "asia-east1",
    document: "friendRequests/{requestId}"
  },
  async (event) => {
    const data = event.data?.data();
    if (!data) return;

    const receiverUid = data.receiverUid;
    const senderUid = data.senderUid;

    const senderDoc = await db.collection("users").doc(senderUid).get();
    const senderName = senderDoc.get("nickname") ?? "누군가";

    const receiverDoc = await db.collection("users").doc(receiverUid).get();
    const token = receiverDoc.get("fcmToken");
    if (!token) return;

    await admin.messaging().sendToDevice(token, {
      notification: {
        title: "새 친구 요청",
        body: `${senderName}님이 친구 요청을 보냈습니다.`
      },
      data: { type: "friend_request" }
    });

    await createNotification(receiverUid, {
      title: "새 친구 요청",
      message: `${senderName}님이 친구 요청을 보냈습니다.`,
      type: "friend_request"
    });
  }
);

/* ------------------------------------------------------
  2) 그룹 상태 변경 → 푸시 발송
  (GROUP_CREATED 는 제외)
-------------------------------------------------------*/
export const onGroupStatusChanged = onDocumentUpdated(
  {
    region: "asia-east1",
    document: "groups/{groupId}"
  },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();

    if (!before || !after) return;
    if (before.status === after.status) return;

    const status = after.status;
    const memberUids = after.memberUids ?? [];
    const groupName = after.groupName ?? "모임";

    switch (status) {
      // ⭐ 방장이 투표 시작 눌렀을 때만 시간 투표 요청
      case "TIME_VOTE_REQUIRED":
        await sendPushToMembers(
          memberUids,
          "시간 투표 요청",
          `${groupName} 모임의 가능한 시간을 투표해주세요.`,
          { type: "time_vote" }
        );
        break;

      case "TIME_FINALIZING":
        await sendPushToMembers(
          memberUids,
          "시간 투표 완료!",
          "시간 투표가 완료되었습니다. 이제 출발 위치를 입력해주세요.",
          { type: "location_input" }
        );
        break;

      case "LOCATION_INPUT_REQUIRED":
        await sendPushToMembers(
          memberUids,
          "출발 위치 입력 요청",
          "중간 위치 계산을 위해 출발 위치를 입력해주세요.",
          { type: "location_input" }
        );
        break;

      case "PLACE_RANKING":
        await sendPushToMembers(
          memberUids,
          "장소 순위 투표 시작",
          "중간 위치 주변 장소를 보고 순위 투표를 해주세요!",
          { type: "ranking" }
        );
        break;

      case "FINAL_PLACE_VOTE":
        await sendPushToMembers(
          memberUids,
          "최종 장소 투표",
          "마지막으로 최종 장소 투표를 해주세요!",
          { type: "final_vote" }
        );
        break;

      case "FINALIZED":
        await sendPushToMembers(
          memberUids,
          "약속 장소 확정!",
          `${groupName}의 최종 약속 장소가 확정되었습니다.`,
          { type: "finalized" }
        );
        break;
    }
  }
);

/* ------------------------------------------------------
  3) 약속 전날 알림 (Scheduler)
-------------------------------------------------------*/
export const appointmentReminder = onSchedule("0 9 * * *", async () => {
  const now = new Date();
  const tomorrow = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1
  );

  const snap = await db
    .collection("groups")
    .where("confirmedTime", ">=", new Date(tomorrow.setHours(0, 0, 0)))
    .where("confirmedTime", "<=", new Date(tomorrow.setHours(23, 59, 59)))
    .get();

  for (const doc of snap.docs) {
    const group = doc.data();
    const memberUids = group.memberUids ?? [];

    await sendPushToMembers(
      memberUids,
      "내일 약속이 있어요!",
      "내일 모임이 예정되어 있습니다. 잊지 말고 준비해 주세요!",
      { type: "reminder" }
    );
  }
});
