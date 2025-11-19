import * as admin from "firebase-admin";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

admin.initializeApp();

/**
 * friendRequests/{requestId} 문서가 생성될 때 실행됨
 * → FCM 발송
 * → Firestore /users/{receiverUid}/notifications 에 저장
 */
export const onFriendRequestCreated = onDocumentCreated(
  {
      region: "asia-east1",
      document: "friendRequests/{requestId}"
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const requestData = snap.data();
    const senderUid = requestData.senderUid;
    const receiverUid = requestData.receiverUid;

    console.log("🔥 Friend request generated:", senderUid, "→", receiverUid);

    // 1) 수신자 FCM Token 조회
    const receiverDoc = await admin
      .firestore()
      .collection("users")
      .doc(receiverUid)
      .get();

    const receiverToken = receiverDoc.get("fcmToken");

    if (!receiverToken) {
      console.log("❌ No FCM token for receiver:", receiverUid);
    } else {
      // 2) FCM 전송
      await admin.messaging().send({
        token: receiverToken,
        notification: {
          title: "새 친구 요청",
          body: "친구 요청이 도착했습니다!",
        },
        data: {
          senderUid,
          receiverUid,
          type: "friend_request",
        },
      });

      console.log("📨 FCM notification sent");
    }

    // 3) Firestore 알림 저장 (앱에서 읽는 경로!)
    await admin
      .firestore()
      .collection("users")
      .doc(receiverUid)
      .collection("notifications")
      .add({
        senderUid,
        receiverUid,
        title: "새 친구 요청",
        message: "친구 요청이 도착했습니다!",
        type: "friend_request",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    console.log(
      "📝 Firestore notification saved → /users/" +
        receiverUid +
        "/notifications"
    );
  }
);
