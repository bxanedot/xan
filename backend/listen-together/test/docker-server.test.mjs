import test from "node:test";
import assert from "node:assert/strict";
import WebSocket from "ws";
import {
  ListenTogetherService,
  applyPlayback,
  initialRoom,
  roomState,
  startServer,
  syncState,
} from "../server.mjs";

class FakeSocket {
  readyState = 1;
  messages = [];
  listenTogether;
  send(data) { this.messages.push(JSON.parse(data)); }
  close() { this.readyState = 3; }
  take(type) { return [...this.messages].reverse().find((entry) => entry.type === type); }
}

function frame(type, payload = {}) { return JSON.stringify({ type, payload }); }

function waitForMessage(socket, wantedType, timeoutMs = 3_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${wantedType}`)), timeoutMs);
    const onMessage = (data) => {
      const message = JSON.parse(data.toString());
      if (message.type !== wantedType) return;
      clearTimeout(timeout);
      socket.off("message", onMessage);
      resolve(message);
    };
    socket.on("message", onMessage);
  });
}

function createService() {
  let code = 0;
  let time = 1_000;
  let token = 0;
  const service = new ListenTogetherService({
    now: () => time,
    codeFactory: () => `ROOM${++code}`,
    tokenFactory: () => `token-${++token}`,
  });
  return { service, advance: (ms) => { time += ms; } };
}

test("room allocation and state match the Android snake_case protocol", () => {
  const { service } = createService();
  const code = service.allocateRoom();
  const room = service.rooms.get(code);
  const host = new FakeSocket();
  service.attach(room, host);
  return service.handleMessage(host, frame("create_room", { username: "Host" })).then(() => {
    const state = host.take("room_created").payload.state;
    assert.equal(code, "ROOM1");
    assert.equal(state.room_code, code);
    assert.equal(state.users[0].username, "Host");
    assert.equal(state.users[0].is_host, true);
    assert.equal(state.control_mode, "owner");
  });
});

test("host approval gates joining and host-only playback is enforced", async () => {
  const { service } = createService();
  const code = service.allocateRoom();
  const room = service.rooms.get(code);
  const host = new FakeSocket();
  const guest = new FakeSocket();
  service.attach(room, host);
  service.attach(room, guest);
  await service.handleMessage(host, frame("create_room", { username: "Host" }));
  await service.handleMessage(guest, frame("join_room", { room_code: code, username: "Guest" }));
  const request = host.take("join_request").payload;
  assert.ok(request.user_id);
  await service.handleMessage(guest, frame("playback_action", { action: "play", position: 400 }));
  assert.equal(guest.take("error").payload.code, "not_in_room");
  await service.handleMessage(host, frame("approve_join", { user_id: request.user_id }));
  const approved = guest.take("join_approved").payload;
  assert.ok(approved.session_token);
  await service.handleMessage(guest, frame("playback_action", { action: "play", position: 400 }));
  assert.equal(guest.take("error").payload.code, "permission_denied");
  await service.handleMessage(host, frame("playback_action", { action: "play", position: 400 }));
  const broadcast = guest.take("sync_playback").payload;
  assert.equal(broadcast.position, 400);
  assert.equal(broadcast.from_user_id, approved.state.host_id);
  assert.equal(broadcast.server_time, 1_000);
});

test("everyone mode shares queue changes, sync snapshots, suggestions, and presence", async () => {
  const { service } = createService();
  const code = service.allocateRoom();
  const room = service.rooms.get(code);
  const host = new FakeSocket();
  const guest = new FakeSocket();
  service.attach(room, host);
  service.attach(room, guest);
  await service.handleMessage(host, frame("create_room", { username: "Host" }));
  await service.handleMessage(guest, frame("join_room", { room_code: code, username: "Guest" }));
  const request = host.take("join_request").payload;
  await service.handleMessage(host, frame("approve_join", { user_id: request.user_id }));
  await service.handleMessage(host, frame("set_control_mode", { control_mode: "everyone" }));
  await service.handleMessage(guest, frame("playback_action", { action: "queue_add", track_info: { id: "t1", title: "Song", artist: "Artist", duration: 120000 } }));
  assert.equal(host.take("sync_playback").payload.track_info.title, "Song");
  await service.handleMessage(guest, frame("suggest_track", { track_info: { id: "t2", title: "Suggestion", artist: "Guest" } }));
  const suggestion = host.take("suggestion_received").payload;
  assert.equal(suggestion.track_info.suggested_by, "Guest");
  await service.handleMessage(host, frame("approve_suggestion", { suggestion_id: suggestion.suggestion_id }));
  assert.equal(guest.take("suggestion_approved").payload.track_info.id, "t2");
  await service.handleMessage(guest, frame("request_sync"));
  const sync = guest.take("sync_state").payload;
  assert.equal(sync.queue[0].id, "t1");
  assert.equal(sync.control_mode, "everyone");
  guest.readyState = 3;
  service.handleClose(guest);
  assert.equal(host.take("user_disconnected").payload.username, "Guest");
  assert.equal(roomState(room).users[1].is_connected, false);
});

test("reconnect token restores the member and an expired host closes the room", async () => {
  const { service, advance } = createService();
  const code = service.allocateRoom();
  const room = service.rooms.get(code);
  const host = new FakeSocket();
  service.attach(room, host);
  await service.handleMessage(host, frame("create_room", { username: "Host" }));
  const token = host.take("room_created").payload.session_token;
  host.readyState = 3;
  service.handleClose(host);
  const guestSocket = new FakeSocket();
  service.attach(room, guestSocket);
  assert.equal(guestSocket.listenTogether.userId, null);
  const resumedHost = new FakeSocket();
  service.attach(room, resumedHost);
  await service.handleMessage(resumedHost, frame("reconnect", { session_token: token }));
  assert.equal(resumedHost.take("reconnected").payload.is_host, true);
  assert.equal(room.members[0].isConnected, true);
  resumedHost.readyState = 3;
  service.handleClose(resumedHost);
  advance(10 * 60 * 1000 + 1);
  service.expireRooms();
  assert.equal(service.rooms.has(code), false);
});

test("playback validation bounds shared queues and volume", () => {
  const room = initialRoom("ABCD1234", 123);
  assert.equal(applyPlayback(room, { action: "change_track", track_info: { id: "id", title: "Song", duration: 1000 } }, 124), true);
  assert.equal(applyPlayback(room, { action: "change_track", track_info: { title: "missing id" } }), false);
  assert.equal(applyPlayback(room, { action: "seek" }), false);
  assert.equal(applyPlayback(room, { action: "set_volume", volume: 4 }, 125), true);
  assert.equal(syncState(room).volume, 1);
  room.playback.queue = Array.from({ length: 500 }, (_, index) => ({ id: String(index) }));
  assert.equal(applyPlayback(room, { action: "queue_add", track_info: { id: "full", title: "Full" } }), false);
});

test("ping reports server time and lightweight sync omits the queue", async () => {
  const { service } = createService();
  const code = service.allocateRoom();
  const room = service.rooms.get(code);
  room.playback.queue = [{ id: "large-queue", title: "Queue item" }];
  const host = new FakeSocket();
  service.attach(room, host);
  await service.handleMessage(host, frame("create_room", { username: "Host" }));
  await service.handleMessage(host, frame("ping", { client_time: 900 }));
  assert.deepEqual(host.take("pong").payload, {
    client_time: 900,
    server_time: 1_000,
    server_receive_time: 1_000,
    server_send_time: 1_000,
  });
  await service.handleMessage(host, frame("request_sync", { lightweight: true }));
  const snapshot = host.take("sync_state").payload;
  assert.equal(snapshot.queue, null);
  assert.equal(snapshot.volume, null);
  assert.equal(snapshot.last_update, room.playback.last_update);
});

test("per-socket message rate limit closes abusive connections", async () => {
  const { service } = createService();
  const room = service.rooms.get(service.allocateRoom());
  const socket = new FakeSocket();
  service.attach(room, socket);
  for (let index = 0; index < 181; index += 1) await service.handleMessage(socket, frame("ping"));
  assert.equal(socket.take("error").payload.code, "rate_limited");
  assert.equal(socket.readyState, 3);
});

test("HTTP allocation and WebSocket room flow work end to end", async () => {
  const running = await startServer({ port: 0, host: "127.0.0.1" });
  const port = running.server.address().port;
  const origin = `http://127.0.0.1:${port}`;
  let host;
  let guest;
  try {
    const health = await fetch(`${origin}/health`).then((response) => response.json());
    assert.deepEqual(health, { ok: true, service: "xan-listen-together" });
    const allocation = await fetch(`${origin}/api/rooms`, { method: "POST" });
    assert.equal(allocation.status, 201);
    const { room_code: roomCode } = await allocation.json();

    host = new WebSocket(`ws://127.0.0.1:${port}/room/${roomCode}`);
    await new Promise((resolve, reject) => { host.once("open", resolve); host.once("error", reject); });
    const createdPromise = waitForMessage(host, "room_created");
    host.send(frame("create_room", { username: "Host" }));
    const created = await createdPromise;

    guest = new WebSocket(`ws://127.0.0.1:${port}/room/${roomCode}`);
    await new Promise((resolve, reject) => { guest.once("open", resolve); guest.once("error", reject); });
    const requestPromise = waitForMessage(host, "join_request");
    guest.send(frame("join_room", { room_code: roomCode, username: "Guest" }));
    const request = await requestPromise;
    const approvedPromise = waitForMessage(guest, "join_approved");
    host.send(frame("approve_join", { user_id: request.payload.user_id }));
    const approved = await approvedPromise;
    assert.equal(approved.payload.state.host_id, created.payload.user_id);

    const syncPromise = waitForMessage(guest, "sync_playback");
    host.send(frame("playback_action", { action: "play", position: 42_000 }));
    const playback = await syncPromise;
    assert.equal(playback.payload.position, 42_000);
    assert.ok(playback.payload.server_time > 0);
  } finally {
    if (host?.readyState === WebSocket.OPEN) host.close();
    if (guest?.readyState === WebSocket.OPEN) guest.close();
    await running.close();
  }
});
