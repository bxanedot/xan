import test from "node:test";
import assert from "node:assert/strict";
import worker, { ListenTogetherRoom, __test } from "../src/index.mjs";

class FakeSql {
  row = null;
  exec(query, ...bindings) {
    if (query.startsWith("CREATE TABLE")) return { toArray: () => [] };
    if (query.startsWith("SELECT body")) return { toArray: () => this.row ? [{ body: this.row }] : [] };
    if (query.startsWith("INSERT OR REPLACE")) {
      this.row = bindings[0];
      return { toArray: () => [] };
    }
    if (query.startsWith("DELETE FROM room_state")) {
      this.row = null;
      return { toArray: () => [] };
    }
    throw new Error(`Unexpected SQL: ${query}`);
  }
}

class FakeSocket {
  messages = [];
  attachment = null;
  closed = false;
  send(message) { this.messages.push(JSON.parse(message)); }
  close() { this.closed = true; }
  serializeAttachment(value) { this.attachment = structuredClone(value); }
  deserializeAttachment() { return structuredClone(this.attachment); }
}

function makeRoom() {
  const sockets = [];
  const sql = new FakeSql();
  const ctx = {
    storage: {
      sql,
      async setAlarm() {},
    },
    blockConcurrencyWhile(callback) { return Promise.resolve(callback()); },
    acceptWebSocket(socket) { sockets.push(socket); },
    getWebSockets() { return sockets; },
  };
  const room = new ListenTogetherRoom(ctx);
  return { room, sockets };
}

function socket(room, sockets, code) {
  const value = new FakeSocket();
  sockets.push(value);
  value.serializeAttachment({ roomCode: code, userId: null });
  return value;
}

function frame(type, payload = {}) {
  return JSON.stringify({ type, payload });
}

function lastMessage(socket, type) {
  return [...socket.messages].reverse().find((message) => message.type === type);
}

test("room state preserves the mobile app's snake_case shape", () => {
  const room = __test.initialRoom("ABCD2345");
  room.hostId = "host";
  room.members.push({ id: "host", username: "Host", isHost: true, isConnected: true });
  const state = __test.roomState(room);
  assert.equal(state.room_code, "ABCD2345");
  assert.equal(state.users[0].user_id, "host");
  assert.equal(state.users[0].is_host, true);
  assert.equal(state.control_mode, "owner");
});

test("playback actions update server state and reject malformed tracks", () => {
  const room = __test.initialRoom("ABCD2345");
  assert.equal(__test.applyPlayback(room, { action: "change_track", track_info: { id: "id", title: "Song", artist: "Artist", duration: 9000 } }), true);
  assert.equal(room.playback.current_track.title, "Song");
  assert.equal(__test.applyPlayback(room, { action: "change_track", track_info: { title: "Missing id" } }), false);
  assert.equal(__test.applyPlayback(room, { action: "seek" }), false);
});

test("only the host can approve room joins and control default playback", async () => {
  const { room, sockets } = makeRoom();
  await room.fetch(new Request("https://room.internal/allocate", { method: "POST", headers: { "x-xan-room-code": "ABCD2345" } }));
  const hostSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(hostSocket, frame("create_room", { username: "Host" }));
  const hostId = lastMessage(hostSocket, "room_created").payload.user_id;

  const guestSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(guestSocket, frame("join_room", { room_code: "ABCD2345", username: "Guest" }));
  const joinRequest = lastMessage(hostSocket, "join_request").payload;
  assert.equal(joinRequest.username, "Guest");

  await room.webSocketMessage(guestSocket, frame("approve_join", { user_id: hostId }));
  assert.equal(lastMessage(guestSocket, "error").payload.code, "permission_denied");

  await room.webSocketMessage(hostSocket, frame("approve_join", { user_id: joinRequest.user_id }));
  assert.ok(lastMessage(guestSocket, "join_approved"));

  await room.webSocketMessage(guestSocket, frame("playback_action", { action: "play", position: 500 }));
  assert.equal(lastMessage(guestSocket, "error").payload.code, "permission_denied");

  await room.webSocketMessage(hostSocket, frame("playback_action", { action: "play", position: 500 }));
  assert.equal(lastMessage(guestSocket, "sync_playback").payload.from_user_id, hostId);
});

test("reconnecting a session replaces its old socket without a false disconnect", async () => {
  const { room, sockets } = makeRoom();
  await room.fetch(new Request("https://room.internal/allocate", { method: "POST", headers: { "x-xan-room-code": "ABCD2345" } }));
  const oldSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(oldSocket, frame("create_room", { username: "Host" }));
  const created = lastMessage(oldSocket, "room_created").payload;

  const resumedSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(resumedSocket, frame("reconnect", { session_token: created.session_token }));
  assert.equal(oldSocket.closed, true);
  assert.ok(lastMessage(resumedSocket, "reconnected"));

  await room.webSocketClose(oldSocket);
  const state = __test.roomState(room.readRoom());
  assert.equal(state.users[0].is_connected, true);
  assert.equal(state.users[0].user_id, created.user_id);
});

test("rooms cap pending requests and approved members", async () => {
  const { room, sockets } = makeRoom();
  await room.fetch(new Request("https://room.internal/allocate", { method: "POST", headers: { "x-xan-room-code": "ABCD2345" } }));
  const hostSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(hostSocket, frame("create_room", { username: "Host" }));

  const pendingSockets = [];
  for (let index = 0; index < 32; index += 1) {
    const guestSocket = socket(room, sockets, "ABCD2345");
    pendingSockets.push(guestSocket);
    await room.webSocketMessage(guestSocket, frame("join_room", { room_code: "ABCD2345", username: `Guest ${index}` }));
  }

  const overflow = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(overflow, frame("join_room", { room_code: "ABCD2345", username: "Overflow" }));
  assert.equal(lastMessage(overflow, "error").payload.code, "room_full");

  const pending = room.readRoom().pending;
  for (let index = 0; index < 31; index += 1) {
    await room.webSocketMessage(hostSocket, frame("approve_join", { user_id: pending[index].id }));
  }
  assert.equal(room.readRoom().members.length, 32);
  await room.webSocketMessage(hostSocket, frame("approve_join", { user_id: pending[31].id }));
  assert.equal(lastMessage(hostSocket, "error").payload.code, "room_full");
  assert.equal(lastMessage(pendingSockets[31], "join_rejected").payload.reason, "This room has reached its member limit");
  assert.equal(room.readRoom().pending.length, 0);

  const lateGuest = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(lateGuest, frame("join_room", { room_code: "ABCD2345", username: "Late" }));
  assert.equal(lastMessage(lateGuest, "error").payload.code, "room_full");
});

test("queue additions stop at the shared queue limit", () => {
  const room = __test.initialRoom("ABCD2345");
  room.playback.queue = Array.from({ length: 500 }, (_, index) => ({ id: String(index) }));
  assert.equal(__test.applyPlayback(room, {
    action: "queue_add",
    track_info: { id: "new", title: "New song" },
  }), false);
  assert.equal(room.playback.queue.length, 500);
});

test("pending track suggestions stop at the room limit", async () => {
  const { room, sockets } = makeRoom();
  await room.fetch(new Request("https://room.internal/allocate", { method: "POST", headers: { "x-xan-room-code": "ABCD2345" } }));
  const hostSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(hostSocket, frame("create_room", { username: "Host" }));
  const guestSocket = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(guestSocket, frame("join_room", { room_code: "ABCD2345", username: "Guest" }));
  const joinRequest = lastMessage(hostSocket, "join_request").payload;
  await room.webSocketMessage(hostSocket, frame("approve_join", { user_id: joinRequest.user_id }));

  for (let index = 0; index < 100; index += 1) {
    await room.webSocketMessage(guestSocket, frame("suggest_track", {
      track_info: { id: `track-${index}`, title: `Song ${index}` },
    }));
  }
  await room.webSocketMessage(guestSocket, frame("suggest_track", {
    track_info: { id: "overflow", title: "Overflow" },
  }));

  assert.equal(room.readRoom().suggestions.length, 100);
  assert.equal(lastMessage(guestSocket, "error").payload.code, "suggestion_limit");
});

test("room messages reject oversized frames and sanitize usernames", () => {
  assert.equal(__test.cleanUsername("  Guest\u0000 Name  "), "Guest Name");
  assert.throws(() => __test.parseFrame("x".repeat(70 * 1024)), /Frame too large/);
});

test("HTTP routes allocate a code and expose a health check", async () => {
  const allocations = [];
  const env = {
    ROOMS: {
      getByName(code) {
        return {
          async fetch(input, init) {
            const request = new Request(input, init);
            allocations.push({ code, header: request.headers.get("x-xan-room-code") });
            return new Response(null, { status: 201 });
          },
        };
      },
    },
  };
  const allocation = await worker.fetch(new Request("https://sync.example/api/rooms", { method: "POST" }), env);
  assert.equal(allocation.status, 201);
  const body = await allocation.json();
  assert.match(body.room_code, /^[A-Z0-9]{8}$/);
  assert.deepEqual(allocations, [{ code: body.room_code, header: body.room_code }]);

  const health = await worker.fetch(new Request("https://sync.example/health"), env);
  assert.deepEqual(await health.json(), { ok: true, service: "xan-listen-together" });
});

test("clock pings return timestamps and lightweight sync omits shared queue data", async () => {
  const { room, sockets } = makeRoom();
  await room.fetch(new Request("https://room.internal/allocate", { method: "POST", headers: { "x-xan-room-code": "ABCD2345" } }));
  const host = socket(room, sockets, "ABCD2345");
  await room.webSocketMessage(host, frame("create_room", { username: "Host" }));
  await room.webSocketMessage(host, frame("ping", { client_time: 100 }));
  const pong = lastMessage(host, "pong").payload;
  assert.equal(pong.client_time, 100);
  assert.ok(pong.server_time > 0);
  assert.ok(pong.server_receive_time > 0);
  assert.ok(pong.server_send_time >= pong.server_receive_time);
  await room.webSocketMessage(host, frame("request_sync", { lightweight: true }));
  assert.equal(lastMessage(host, "sync_state").payload.queue, null);
  assert.equal(lastMessage(host, "sync_state").payload.volume, null);
});
