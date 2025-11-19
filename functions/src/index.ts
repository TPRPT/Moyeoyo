import * as admin from "firebase-admin";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

admin.initializeApp();

/**
 * friendRequests/{requestId} 문서가 생성될 때 실행됨
 */
export const onFriendRequestCreated = onDocumentCreated(
  "friendRequests/{requestId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const requestData = snap.data();
    const senderUid = requestData.senderUid;
    const receiverUid = requestData.receiverUid;

    console.log("🔥 Friend request generated:", senderUid, "→", receiverUid);

    // receiver UID로 FCM token 조회
    const receiverDoc = await admin
      .firestore()
      .collection("users")
      .doc(receiverUid)
      .get();

    const receiverToken = receiverDoc.get("fcmToken");

    if (!receiverToken) {
      console.log("❌ No FCM token for receiver:", receiverUid);
      return;
    }

    // FCM 발송
    await admin.messaging().send({
      notification: {
        title: "새 친구 요청",
        body: "친구 요청이 도착했습니다!"
      },
      token: receiverToken
    });

    console.log("📨 FCM notification sent");

    // Firestore notifications 컬렉션에 저장
    await admin.firestore().collection("notifications").add({
      senderUid: senderUid,
      receiverUid: receiverUid,
      title: "새 친구 요청",
      message: "친구 요청이 도착했습니다!",
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    console.log("📝 Firestore notification saved");
  }
);
