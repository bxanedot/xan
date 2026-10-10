import { createServer } from "node:http";
import { randomBytes, randomInt, randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const ROOM_CODE_LENGTH = 8;
const MAX_FRAME_BYTES = 64 * 1024;
const MAX_USERNAME_LENGTH = 32;
const MAX_CHAT_LENGTH = 500;
const MAX_ROOM_MEMBERS = 32;
const MAX_PENDING_JOIN_REQUESTS = 32;
const MAX_QUEUE_ITEMS = 500;
const MAX_TRACK_SUGGESTIONS = 100;
const MAX_ACTIVE_ROOMS = 100;
const MAX_MESSAGES_PER_MINUTE = 180;
const RECONNECT_GRACE_MS = 10 * 60 * 1000;
const ALLOCATION_TTL_MS = 2 * 60 * 1000;
const serverWallClockAnchor = Date.now();
const serverMonotonicAnchor = performance.now();

function serverNow() {
  return Math.floor(serverWallClockAnchor + performance.now() - serverMonotonicAnchor);
}

const emptyPlayback = (now = Date.now()) => ({
  current_track: null,
  is_playing: false,
  position: 0,
  last_update: now,
  volume: 1,
  queue: [],
  control_mode: "owner",
  expires_at: 0,
  extensions_used: 0,
});

export function cleanUsername(value) {
  if (typeof value !== "string") return "Guest";
  return value.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, MAX_USERNAME_LENGTH) || "Guest";
}

export function normalizeTrack(track, suggestedBy = null) {
  if (!track || typeof track !== "object" || Array.isArray(track)) return null;
  const id = typeof track.id === "string" ? track.id.slice(0, 256) : "";
  const title = typeof track.title === "string" ? track.title.trim().slice(0, 300) : "";
  const artist = typeof track.artist === "string" ? track.artist.trim().slice(0, 300) : "";
  if (!id || !title) return null;
  const duration = Number.isFinite(Number(track.duration)) ? Math.max(0, Number(track.duration)) : 0;
  return {
    id,
    title,
    artist,
    album: typeof track.album === "string" ? track.album.slice(0, 300) : null,
    duration,
    thumbnail: typeof track.thumbnail === "string" ? track.thumbnail.slice(0, 2048) : null,
    suggested_by: suggestedBy ?? (typeof track.suggested_by === "string" ? track.suggested_by.slice(0, MAX_USERNAME_LENGTH) : null),
  };
}

export function initialRoom(code, now = Date.now()) {
  return {
    code,
    createdAt: now,
    hostId: null,
    members: [],
    pending: [],
    suggestions: [],
    buffers: {},
    playback: emptyPlayback(now),
    sockets: new Set(),
  };
}

export function roomState(room) {
  return {
    room_code: room.code,
    host_id: room.hostId,
    users: room.members.map(({ id, username, isHost, isConnected }) => ({
      user_id: id,
      username,
      is_host: isHost,
      is_connected: isConnected,
    })),
    ...room.playback,
  };
}

export function syncState(room) {
  return {
    current_track: room.playback.current_track,
    is_playing: room.playback.is_playing,
    position: room.playback.position,
    last_update: room.playback.last_update,
    queue: room.playback.queue,
    volume: room.playback.volume,
    control_mode: room.playback.control_mode,
    expires_at: room.playback.expires_at,
  };
}

function lightweightSyncState(room) {
  return { ...syncState(room), queue: null, volume: null };
}

export function applyPlayback(room, payload, now = Date.now()) {
  const action = payload.action;
  const position = payload.position != null && Number.isFinite(Number(payload.position))
    ? Math.max(0, Number(payload.position)) : null;
  switch (action) {
    case "play":
    case "pause":
      room.playback.is_playing = action === "play";
      if (position !== null) room.playback.position = position;
      break;
    case "seek":
      if (position === null) return false;
      room.playback.position = position;
      break;
    case "change_track": {
      const track = normalizeTrack(payload.track_info);
      if (!track) return false;
      room.playback.current_track = track;
      room.playback.is_playing = false;
      room.playback.position = 0;
      break;
    }
    case "queue_add": {
      const track = normalizeTrack(payload.track_info);
      if (!track || room.playback.queue.length >= MAX_QUEUE_ITEMS) return false;
      room.playback.queue = payload.insert_next === true
        ? [track, ...room.playback.queue] : [...room.playback.queue, track];
      break;
    }
    case "queue_remove":
      if (typeof payload.track_id !== "string") return false;
      room.playback.queue = room.playback.queue.filter((track) => track.id !== payload.track_id);
      break;
    case "queue_clear":
      room.playback.queue = [];
      break;
    case "sync_queue":
      if (!Array.isArray(payload.queue) || payload.queue.length > MAX_QUEUE_ITEMS) return false;
      room.playback.queue = payload.queue.map((track) => normalizeTrack(track)).filter(Boolean);
      break;
    case "set_volume":
      if (payload.volume == null || !Number.isFinite(Number(payload.volume))) return false;
      room.playback.volume = Math.max(0, Math.min(1, Number(payload.volume)));
      break;
    case "skip_next":
    case "skip_prev":
      if (position !== null) room.playback.position = position;
      break;
    default:
      return false;
  }
  room.playback.last_update = now;
  return true;
}

function randomRoomCode() {
  return Array.from({ length: ROOM_CODE_LENGTH }, () => ROOM_CODE_ALPHABET[randomInt(ROOM_CODE_ALPHABET.length)]).join("");
}

function randomToken() {
  return randomBytes(32).toString("hex");
}

function parseFrame(data) {
  const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
  if (bytes.byteLength > MAX_FRAME_BYTES) throw new Error("Frame too large");
  const message = JSON.parse(bytes.toString("utf8"));
  if (!message || typeof message.type !== "string") throw new Error("Invalid message envelope");
  return { type: message.type, payload: message.payload && typeof message.payload === "object" ? message.payload : {} };
}

/** In-memory room coordinator. Rooms are intentionally ephemeral and belong to one server process. */
export class ListenTogetherService {
  constructor({ now = serverNow, codeFactory = randomRoomCode, tokenFactory = randomToken } = {}) {
    this.now = now;
    this.codeFactory = codeFactory;
    this.tokenFactory = tokenFactory;
    this.rooms = new Map();
    this.allocationWindowStartedAt = this.now();
    this.allocationsInWindow = 0;
  }

  allocateRoom() {
    this.expireRooms();
    const now = this.now();
    if (now - this.allocationWindowStartedAt >= 60_000) {
      this.allocationWindowStartedAt = now;
      this.allocationsInWindow = 0;
    }
    if (this.rooms.size >= MAX_ACTIVE_ROOMS || this.allocationsInWindow >= 60) return null;
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const code = this.codeFactory();
      if (!this.rooms.has(code)) {
        this.rooms.set(code, initialRoom(code, this.now()));
        this.allocationsInWindow += 1;
        return code;
      }
    }
    return null;
  }

  attach(room, socket) {
    room.sockets.add(socket);
    socket.listenTogether = { roomCode: room.code, userId: null };
    socket.rateWindowStartedAt = this.now();
    socket.messagesInWindow = 0;
  }

  send(socket, type, payload = {}) {
    if (!socket || socket.readyState !== 1) return false;
    try {
      socket.send(JSON.stringify({ type, payload }));
      return true;
    } catch {
      return false;
    }
  }

  broadcast(room, type, payload, excludeUserId = null) {
    for (const socket of room.sockets) {
      const attachment = socket.listenTogether;
      if (attachment?.userId && attachment.userId !== excludeUserId) this.send(socket, type, payload);
    }
  }

  socketFor(room, userId) {
    for (const socket of room.sockets) if (socket.listenTogether?.userId === userId && socket.readyState === 1) return socket;
    return null;
  }

  closeSocket(socket, code = 1000, reason = "") {
    try { socket.close(code, reason); } catch { /* already closed */ }
  }

  removeRoom(room) {
    for (const socket of room.sockets) {
      this.send(socket, "room_closed", { reason: "Room is no longer available" });
      this.closeSocket(socket, 1000, "Room closed");
    }
    this.rooms.delete(room.code);
  }

  expireRooms() {
    const now = this.now();
    for (const room of this.rooms.values()) {
      if (!room.hostId && now - room.createdAt >= ALLOCATION_TTL_MS) {
        this.removeRoom(room);
        continue;
      }
      const expired = room.members.filter((member) => !member.isConnected && member.disconnectedAt && now - member.disconnectedAt >= RECONNECT_GRACE_MS);
      if (expired.some((member) => member.isHost)) {
        this.removeRoom(room);
        continue;
      }
      for (const member of expired) {
        room.members = room.members.filter((candidate) => candidate.id !== member.id);
        this.broadcast(room, "user_left", { user_id: member.id, username: member.username });
      }
    }
  }

  async handleMessage(socket, data) {
    const attachment = socket.listenTogether ?? {};
    const room = this.rooms.get(attachment.roomCode);
    if (!room) {
      this.send(socket, "room_closed", { reason: "Room no longer exists" });
      this.closeSocket(socket, 1008, "Room no longer exists");
      return;
    }

    const currentTime = this.now();
    if (currentTime - socket.rateWindowStartedAt >= 60_000) {
      socket.rateWindowStartedAt = currentTime;
      socket.messagesInWindow = 0;
    }
    socket.messagesInWindow += 1;
    if (socket.messagesInWindow > MAX_MESSAGES_PER_MINUTE) {
      this.send(socket, "error", { code: "rate_limited", message: "Too many messages; reconnect in a minute" });
      this.closeSocket(socket, 1008, "Message rate limit exceeded");
      return;
    }

    let message;
    try { message = parseFrame(data); }
    catch (error) {
      this.send(socket, "error", { code: "invalid_message", message: error.message });
      return;
    }
    const { type, payload } = message;
    const member = attachment.userId ? room.members.find((item) => item.id === attachment.userId) : null;
    const isHost = Boolean(member?.isHost && member.id === room.hostId);
    const sendError = (code, text) => this.send(socket, "error", { code, message: text });

    if (type === "ping") {
      const serverReceiveTime = this.now();
      const serverSendTime = this.now();
      return this.send(socket, "pong", {
        client_time: Number.isFinite(Number(payload.client_time)) ? Number(payload.client_time) : 0,
        server_time: serverSendTime,
        server_receive_time: serverReceiveTime,
        server_send_time: serverSendTime,
      });
    }
    if (type === "create_room") {
      if (room.hostId) return sendError("room_already_exists", "This room already has a host");
      const host = { id: randomUUID(), username: cleanUsername(payload.username), isHost: true, isConnected: true, sessionToken: this.tokenFactory(), disconnectedAt: null };
      room.hostId = host.id;
      room.members = [host];
      socket.listenTogether = { roomCode: room.code, userId: host.id };
      return this.send(socket, "room_created", { room_code: room.code, user_id: host.id, session_token: host.sessionToken, state: roomState(room) });
    }
    if (type === "join_room") {
      if (typeof payload.room_code !== "string" || payload.room_code.toUpperCase() !== room.code) return sendError("invalid_room_code", "Room code does not match");
      if (!room.hostId) return sendError("room_not_ready", "The room host is not connected yet");
      if (member) return sendError("already_in_room", "You are already in this room");
      const pendingMember = attachment.userId && room.pending.find((item) => item.id === attachment.userId);
      if (pendingMember) return;
      if (room.members.length >= MAX_ROOM_MEMBERS || room.pending.length >= MAX_PENDING_JOIN_REQUESTS) return sendError("room_full", "This room cannot accept more participants right now");
      const pending = { id: randomUUID(), username: cleanUsername(payload.username) };
      room.pending.push(pending);
      socket.listenTogether = { roomCode: room.code, userId: pending.id };
      const hostSocket = this.socketFor(room, room.hostId);
      if (hostSocket) this.send(hostSocket, "join_request", { user_id: pending.id, username: pending.username });
      return;
    }
    if (type === "reconnect") {
      const token = typeof payload.session_token === "string" ? payload.session_token : "";
      const reconnectMember = room.members.find((item) => item.sessionToken === token);
      if (!reconnectMember) return sendError("session_not_found", "This room session has expired");
      const wasDisconnected = !reconnectMember.isConnected;
      const previousSocket = this.socketFor(room, reconnectMember.id);
      if (previousSocket && previousSocket !== socket) this.closeSocket(previousSocket, 4001, "Session resumed on another connection");
      reconnectMember.isConnected = true;
      reconnectMember.disconnectedAt = null;
      socket.listenTogether = { roomCode: room.code, userId: reconnectMember.id };
      this.send(socket, "reconnected", { room_code: room.code, user_id: reconnectMember.id, state: roomState(room), is_host: reconnectMember.isHost });
      if (wasDisconnected) this.broadcast(room, "user_reconnected", { user_id: reconnectMember.id, username: reconnectMember.username }, reconnectMember.id);
      if (reconnectMember.isHost) for (const pending of room.pending) this.send(socket, "join_request", { user_id: pending.id, username: pending.username });
      return;
    }
    if (type === "approve_join") {
      if (!isHost) return sendError("permission_denied", "Only the host can approve requests");
      const index = room.pending.findIndex((item) => item.id === payload.user_id);
      if (index < 0) return sendError("join_request_not_found", "Join request is no longer available");
      const pending = room.pending.splice(index, 1)[0];
      if (room.members.length >= MAX_ROOM_MEMBERS) {
        const guestSocket = this.socketFor(room, pending.id);
        if (guestSocket) { this.send(guestSocket, "join_rejected", { reason: "This room has reached its member limit" }); this.closeSocket(guestSocket, 1000, "Room is full"); }
        return sendError("room_full", "This room has reached its member limit");
      }
      const guest = { id: pending.id, username: pending.username, isHost: false, isConnected: true, sessionToken: this.tokenFactory(), disconnectedAt: null };
      room.members.push(guest);
      const guestSocket = this.socketFor(room, guest.id);
      if (guestSocket) this.send(guestSocket, "join_approved", { room_code: room.code, user_id: guest.id, session_token: guest.sessionToken, state: roomState(room) });
      this.broadcast(room, "user_joined", { user_id: guest.id, username: guest.username }, guest.id);
      return;
    }
    if (type === "reject_join") {
      if (!isHost) return sendError("permission_denied", "Only the host can reject requests");
      const index = room.pending.findIndex((item) => item.id === payload.user_id);
      if (index < 0) return;
      const [pending] = room.pending.splice(index, 1);
      const guestSocket = this.socketFor(room, pending.id);
      if (guestSocket) { this.send(guestSocket, "join_rejected", { reason: payload.reason || "Request rejected" }); this.closeSocket(guestSocket, 1000, "Join request rejected"); }
      return;
    }
    if (!member) return sendError("not_in_room", "Join and be approved before sending room messages");

    switch (type) {
      case "leave_room": {
        const username = member.username;
        room.members = room.members.filter((candidate) => candidate.id !== member.id);
        if (member.isHost) this.electHost(room);
        if (room.members.length === 0 || !room.hostId) {
          this.removeRoom(room);
        } else {
          this.broadcast(room, "user_left", { user_id: member.id, username });
          if (member.isHost) {
            const next = room.members.find((candidate) => candidate.id === room.hostId);
            if (next) this.broadcast(room, "host_changed", { new_host_id: next.id, new_host_name: next.username });
          }
        }
        this.closeSocket(socket, 1000, "Left room");
        return;
      }
      case "playback_action": {
        if (!isHost && room.playback.control_mode !== "everyone") return sendError("permission_denied", "Playback control is restricted to the host");
        if (!applyPlayback(room, payload, this.now())) return sendError("invalid_playback_action", "Playback action was invalid");
        this.broadcast(room, "sync_playback", { ...payload, from_user_id: member.id, server_time: room.playback.last_update });
        return;
      }
      case "request_sync": return this.send(socket, "sync_state", payload.lightweight === true ? lightweightSyncState(room) : syncState(room));
      case "chat": {
        const text = typeof payload.message === "string" ? payload.message.trim().slice(0, MAX_CHAT_LENGTH) : "";
        if (!text) return;
        const reply = payload.reply_to && typeof payload.reply_to === "object" ? {
          username: cleanUsername(payload.reply_to.username),
          message: String(payload.reply_to.message || "").slice(0, MAX_CHAT_LENGTH),
        } : null;
        return this.broadcast(room, "chat", { user_id: member.id, username: member.username, message: text, timestamp: this.now(), reply_to: reply });
      }
      case "set_control_mode": {
        if (!isHost) return sendError("permission_denied", "Only the host can change control mode");
        if (!["owner", "everyone"].includes(payload.control_mode)) return sendError("invalid_control_mode", "Control mode must be owner or everyone");
        room.playback.control_mode = payload.control_mode;
        return this.broadcast(room, "control_mode_changed", { control_mode: payload.control_mode });
      }
      case "transfer_host": {
        if (!isHost) return sendError("permission_denied", "Only the host can transfer host role");
        const next = room.members.find((candidate) => candidate.id === payload.new_host_id && candidate.isConnected);
        if (!next || next.id === member.id) return sendError("invalid_host", "Choose a connected room member");
        member.isHost = false;
        next.isHost = true;
        room.hostId = next.id;
        return this.broadcast(room, "host_changed", { new_host_id: next.id, new_host_name: next.username });
      }
      case "kick_user": {
        if (!isHost) return sendError("permission_denied", "Only the host can remove members");
        const target = room.members.find((candidate) => candidate.id === payload.user_id && !candidate.isHost);
        if (!target) return sendError("user_not_found", "Member was not found");
        room.members = room.members.filter((candidate) => candidate.id !== target.id);
        const targetSocket = this.socketFor(room, target.id);
        if (targetSocket) { this.send(targetSocket, "kicked", { reason: payload.reason || "Removed by host" }); this.closeSocket(targetSocket, 1008, "Removed by host"); }
        this.broadcast(room, "user_left", { user_id: target.id, username: target.username });
        return;
      }
      case "buffer_ready": {
        const trackId = typeof payload.track_id === "string" ? payload.track_id.slice(0, 256) : "";
        if (!trackId) return;
        room.buffers[trackId] ??= [];
        if (!room.buffers[trackId].includes(member.id)) room.buffers[trackId].push(member.id);
        const waitingFor = room.members.filter((candidate) => candidate.isConnected && !room.buffers[trackId].includes(candidate.id)).map((candidate) => candidate.id);
        if (waitingFor.length === 0) { delete room.buffers[trackId]; return this.broadcast(room, "buffer_complete", { track_id: trackId }); }
        return this.broadcast(room, "buffer_wait", { track_id: trackId, waiting_for: waitingFor });
      }
      case "suggest_track": {
        if (isHost) return sendError("invalid_suggestion", "The host cannot suggest tracks");
        if (room.suggestions.length >= MAX_TRACK_SUGGESTIONS) return sendError("suggestion_limit", "This room has reached its pending suggestion limit");
        const trackInfo = normalizeTrack(payload.track_info, member.username);
        if (!trackInfo) return sendError("invalid_suggestion", "Track details were invalid");
        const suggestion = { suggestion_id: randomUUID(), from_user_id: member.id, from_username: member.username, track_info: trackInfo };
        room.suggestions.push(suggestion);
        const hostSocket = this.socketFor(room, room.hostId);
        if (hostSocket) this.send(hostSocket, "suggestion_received", suggestion);
        return;
      }
      case "approve_suggestion": {
        if (!isHost) return sendError("permission_denied", "Only the host can approve suggestions");
        const index = room.suggestions.findIndex((item) => item.suggestion_id === payload.suggestion_id);
        if (index < 0) return sendError("suggestion_not_found", "Suggestion is no longer available");
        const [suggestion] = room.suggestions.splice(index, 1);
        return this.broadcast(room, "suggestion_approved", { suggestion_id: suggestion.suggestion_id, track_info: suggestion.track_info });
      }
      case "reject_suggestion": {
        if (!isHost) return sendError("permission_denied", "Only the host can reject suggestions");
        const index = room.suggestions.findIndex((item) => item.suggestion_id === payload.suggestion_id);
        if (index < 0) return;
        room.suggestions.splice(index, 1);
        return this.broadcast(room, "suggestion_rejected", { suggestion_id: payload.suggestion_id, reason: typeof payload.reason === "string" ? payload.reason.slice(0, 200) : null });
      }
      case "extend_session":
        if (!isHost) return sendError("permission_denied", "Only the host can extend the room");
        return;
      default: return sendError("unknown_message", "This message type is not supported");
    }
  }

  handleClose(socket) {
    const attachment = socket.listenTogether;
    if (!attachment) return;
    const room = this.rooms.get(attachment.roomCode);
    if (!room) return;
    room.sockets.delete(socket);
    if (!attachment.userId) return;
    const pendingIndex = room.pending.findIndex((item) => item.id === attachment.userId);
    if (pendingIndex >= 0) { room.pending.splice(pendingIndex, 1); return; }
    const member = room.members.find((item) => item.id === attachment.userId);
    if (!member || !member.isConnected || this.socketFor(room, member.id)) return;
    member.isConnected = false;
    member.disconnectedAt = this.now();
    this.broadcast(room, "user_disconnected", { user_id: member.id, username: member.username }, member.id);
  }

  electHost(room) {
    const next = room.members.find((member) => member.isConnected) || room.members[0] || null;
    for (const member of room.members) member.isHost = member.id === next?.id;
    room.hostId = next?.id || null;
  }
}

function writeJson(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

export async function startServer({ port = Number(process.env.PORT || 7860), host = "0.0.0.0" } = {}) {
  const { WebSocketServer } = await import("ws");
  const service = new ListenTogetherService();
  const server = createServer((request, response) => {
    const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
    if (request.method === "GET" && url.pathname === "/health") return writeJson(response, 200, { ok: true, service: "xan-listen-together" });
    if (request.method === "POST" && url.pathname === "/api/rooms") {
      const roomCode = service.allocateRoom();
      return roomCode
        ? writeJson(response, 201, { room_code: roomCode })
        : writeJson(response, 503, { error: "room_allocation_failed" });
    }
    return writeJson(response, 404, { error: "not_found" });
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 20_000;
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES, perMessageDeflate: false });
  server.on("upgrade", (request, socket, head) => {
    const match = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`).pathname.match(/^\/room\/([A-Z0-9]{4,12})$/i);
    const room = match && service.rooms.get(match[1].toUpperCase());
    if (!room) {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      service.attach(room, ws);
      wss.emit("connection", ws, request);
    });
  });
  wss.on("connection", (socket) => {
    socket.isAlive = true;
    socket.on("pong", () => { socket.isAlive = true; });
    socket.on("message", (data) => { void service.handleMessage(socket, data); });
    socket.on("close", () => service.handleClose(socket));
    socket.on("error", () => service.handleClose(socket));
  });
  const heartbeat = setInterval(() => {
    service.expireRooms();
    for (const socket of wss.clients) {
      if (socket.isAlive === false) { socket.terminate(); continue; }
      socket.isAlive = false;
      socket.ping();
    }
  }, 30_000);
  heartbeat.unref();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, resolve);
  });
  return { server, wss, service, close: () => new Promise((resolve) => {
    clearInterval(heartbeat);
    for (const socket of wss.clients) socket.close(1001, "Server shutting down");
    wss.close(() => server.close(resolve));
  }) };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  startServer().then(({ server }) => {
    console.log(`XAN Listen Together server listening on ${server.address().address}:${server.address().port}`);
  }).catch((error) => {
    console.error("Could not start Listen Together server:", error);
    process.exitCode = 1;
  });
}
