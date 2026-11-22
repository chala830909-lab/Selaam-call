const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const CHARLIE_UID = "charlie_uid_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("directory").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("usernames").doc("chala").get());
  await assertFails(unauthDb.collection("phone_claims").doc("251983156394").get());
  await assertFails(unauthDb.collection("calls").where("participantUids", "array-contains", ALICE_UID).get());
});

test("User profile isolation and dual identity claims", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(5000);

  // Claim username and phone
  await assertSucceeds(
    aliceDb.collection("usernames").doc("chala").set({
      uid: ALICE_UID,
      createdAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("phone_claims").doc("251911223344").set({
      uid: ALICE_UID,
      createdAt: ts,
    })
  );

  // Bob cannot overwrite or delete Alice's claims
  await assertFails(
    bobDb.collection("usernames").doc("chala").set({
      uid: BOB_UID,
      createdAt: ts,
    })
  );
  await assertFails(bobDb.collection("usernames").doc("chala").delete());

  // Create private user profile
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      email: "chala@example.com",
      username: "chala",
      countryCode: "+251",
      localPhone: "911223344",
      phoneNumber: "+251911223344",
      displayName: "Chala M",
      onlineStatus: true,
      discoverable: true,
      lastSeen: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob cannot read or list Alice's private /users doc
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());

  // Reject ghost field (Shadow Update Test)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(1000),
    })
  );

  // Reject createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
});

test("Authenticated directory search enforces discoverable filter and owner-only writes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("directory").doc(ALICE_UID).set({
      uid: ALICE_UID,
      username: "chala",
      phoneNumber: "+251911223344",
      displayName: "Chala M",
      onlineStatus: true,
      discoverable: true,
      lastSeen: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Unfiltered list must fail
  await assertFails(bobDb.collection("directory").get());

  // Filtered discoverable list succeeds
  await assertSucceeds(
    bobDb.collection("directory").where("discoverable", "==", true).get()
  );

  // Bob cannot modify Alice's directory entry
  await assertFails(
    bobDb.collection("directory").doc(ALICE_UID).update({
      onlineStatus: false,
      updatedAt: pastDate(1000),
    })
  );

  // Alice can update her presence
  await assertSucceeds(
    aliceDb.collection("directory").doc(ALICE_UID).update({
      onlineStatus: false,
      lastSeen: pastDate(500),
      updatedAt: pastDate(500),
    })
  );
});

test("WebRTC call signaling lifecycle, participant isolation, and ICE candidates", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = pastDate(10000);

  // Seed Bob in directory
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("directory").doc(BOB_UID).set({
      uid: BOB_UID,
      username: "abebe",
      phoneNumber: "+251922334455",
      displayName: "Abebe B",
      onlineStatus: true,
      discoverable: true,
      lastSeen: ts,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Alice creates a ringing video call to Bob
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      callerId: ALICE_UID,
      callerUsername: "chala",
      callerDisplayName: "Chala M",
      callerPhone: "+251911223344",
      calleeId: BOB_UID,
      calleeUsername: "abebe",
      calleeDisplayName: "Abebe B",
      calleePhone: "+251922334455",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "video",
      status: "ringing",
      callerVideoEnabled: true,
      calleeVideoEnabled: true,
      offerSdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n",
      durationSeconds: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Charlie cannot read, list, or modify Alice-Bob call
  await assertFails(charlieDb.collection("calls").doc("call_1").get());
  await assertFails(charlieDb.collection("calls").get());
  await assertFails(
    charlieDb.collection("calls").doc("call_1").update({
      status: "ended",
      updatedAt: pastDate(1000),
    })
  );

  // Participants can query their call history
  await assertSucceeds(
    bobDb.collection("calls").where("participantUids", "array-contains", BOB_UID).get()
  );

  // Caller cannot force status to "accepted"
  await assertFails(
    aliceDb.collection("calls").doc("call_1").update({
      status: "accepted",
      updatedAt: pastDate(2000),
    })
  );

  // Callee accepts call with answerSdp
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      status: "accepted",
      answerSdp: "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n",
      updatedAt: pastDate(2000),
    })
  );

  // ICE candidates: Alice writes to callerCandidates, Bob reads; Charlie denied
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").collection("callerCandidates").doc("cand_1").set({
      senderId: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122252543 192.168.1.2 54321 typ host",
      createdAt: pastDate(1500),
    })
  );
  await assertFails(
    bobDb.collection("calls").doc("call_1").collection("callerCandidates").doc("cand_2").set({
      senderId: BOB_UID,
      participantUids: [ALICE_UID, BOB_UID],
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122252543 192.168.1.3 54321 typ host",
      createdAt: pastDate(1500),
    })
  );
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").collection("callerCandidates")
      .where("participantUids", "array-contains", BOB_UID).get()
  );
  await assertFails(
    charlieDb.collection("calls").doc("call_1").collection("callerCandidates").doc("cand_1").get()
  );
  await assertFails(
    charlieDb.collection("calls").doc("call_1").collection("callerCandidates").get()
  );
  await assertFails(
    charlieDb.collection("calls").doc("call_1").collection("callerCandidates")
      .where("participantUids", "array-contains", ALICE_UID).get()
  );

  // Alice ends call with duration
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").update({
      status: "ended",
      durationSeconds: 42,
      endedAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );

  // Terminal state lock: no updates allowed after "ended"
  await assertFails(
    bobDb.collection("calls").doc("call_1").update({
      status: "accepted",
      updatedAt: pastDate(100),
    })
  );
});
