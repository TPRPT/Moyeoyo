import * as functions from "firebase-functions/v1";
import * as logger from "firebase-functions/logger";

import * as admin from "firebase-admin";
import {DocumentSnapshot} from "firebase-functions/v1/firestore";

admin.initializeApp();
const db = admin.firestore();


/**
 * 요청을 보낸 사용자(senderUid)의 닉네임을 Firestore에서 가져옵니다.
 * @param {string} senderUid 요청을 보낸 사용자 UID
 */
async function getSenderNickname(
  senderUid: string,
): Promise<string> {
  try {
    const senderDoc = await db.collection("users").doc(senderUid).get();
    return senderDoc.data()?.nickname || "알 수 없는 사용자";
  } catch (error) {
    logger.error("Error fetching sender nickname:", error);
    return "알 수 없는 사용자";
  }
}

/**
 * Firestore의 'notifications' 컬렉션에 친구 요청 문서가 생성되면 푸시 알림을 발송합니다.
 */
export const sendNotificationOnFriendRequest = functions
  .runWith({maxInstances: 5})
  .firestore
  .document("notifications/{notificationId}")
  .onCreate(async (snapshot: DocumentSnapshot) => {
    const data = snapshot.data();

    if (!data) {
      logger.warn("Snapshot data is null or undefined. Exiting.");
      return null;
    }

    const receiverUid = data.receiverUid;
    const type = data.type;

    if (type !== "friend_request") {
      logger.info("Not a friend request notification. Exiting.", {type: type});
      return null;
    }

    const userDoc = await db.collection("users").doc(receiverUid).get();
    const fcmToken = userDoc.data()?.fcmToken;
    const senderNickname = await getSenderNickname(data.senderUid);

    if (!fcmToken) {
      logger.warn(
        `FCM token not found for user: ${receiverUid}. ` +
                "Cannot send push notification."
      );
      return null;
    }

    const payload: admin.messaging.MessagingPayload = {
      notification: {
        title: "새로운 친구 요청 도착 🎉",
        body: `${senderNickname} 님이 친구 요청을 보냈습니다.`,
        sound: "default",
      },
      data: {
        targetScreen: "NotificationActivity",
        notificationType: type,
        senderUid: data.senderUid,
      },
    };

    try {
      const response = await admin.messaging()
        .sendToDevice(fcmToken, payload);

      logger.info(
        "Successfully sent message:",
        {
          successCount: response.successCount,
          failureCount: response.failureCount,
        }
      );

      await snapshot.ref.update({
        isSent: true,
        messageId: response.results[0].messageId,
      });

      return null;
    } catch (error) {
      const errorMessage = (error as Error).toString();
      logger.error("Error sending message:", errorMessage);

      await snapshot.ref.update({isSent: false, error: errorMessage});
      return null;
    }
  });
