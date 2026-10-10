const ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const ROOM_CODE_LENGTH = 8;
const MAX_FRAME_BYTES = 64 * 1024;
const MAX_USERNAME_LENGTH = 32;
const MAX_CHAT_LENGTH = 500;
const MAX_ROOM_MEMBERS = 32;
const MAX_PENDING_JOIN_REQUESTS = 32;
const MAX_QUEUE_ITEMS = 500;
const MAX_TRACK_SUGGESTIONS = 100;
const RECONNECT_GRACE_MS = 10 * 60 * 1000;
const ALLOCATION_TTL_MS = 2 * 60 * 1000;
const EMPTY_PLAYBACK = () => ({
  current_track: null,
  is_playing: false,
  position: 0,
  last_update: Date.now(),
  volume: 1,
  queue: [],
  control_mode: "owner",
  expires_at: 0,
  extensions_used: 0,
});

function json(data, status = 200, extraHeaders = {}) {
  return Response.json(data, {
    status,
    headers: { "cache-control": "no-store", ...extraHeaders },
  });
}

function randomRoomCode() {
  const bytes = new Uint8Array(ROOM_CODE_LENGTH);
  crypto.getRandomValues(bytes);
  return [...bytes].map((byte) => ROOM_CODE_ALPHABET[byte % ROOM_CODE_ALPHABET.length]).join("");
}

function randomToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function cleanUsername(value) {
  if (typeof value !== "string") return "Guest";
  const clean = value.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, MAX_USERNAME_LENGTH);
  return clean || "Guest";
}

function roomState(room) {
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

function syncState(room) {
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

function normalizeTrack(track, suggestedBy) {
  if (!track || typeof track !== "object") return null;
  const id = typeof track.id === "string" ? track.id.slice(0, 256) : "";
  const title = typeof track.title === "string" ? track.title.trim().slice(0, 300) : "";
  const artist = typeof track.artist === "string" ? track.artist.trim().slice(0, 300) : "";
  if (!id || !title) return null;
  const duration = Number.isFinite(Number(track.duration)) ? Math.max(0, Number(track.duration)) : 0;
  const thumbnail = typeof track.thumbnail === "string" ? track.thumbnail.slice(0, 2048) : null;
  return {
    id,
    title,
    artist,
    album: typeof track.album === "string" ? track.album.slice(0, 300) : null,
    duration,
    thumbnail,
    suggested_by: suggestedBy ?? (typeof track.suggested_by === "string" ? track.suggested_by.slice(0, MAX_USERNAME_LENGTH) : null),
  };
}

function initialRoom(code) {
  return {
    code,
    createdAt: Date.now(),
    hostId: null,
    members: [],
    pending: [],
    suggestions: [],
    buffers: {},
    playback: EMPTY_PLAYBACK(),
  };
}

function parseFrame(data) {
  let text;
  if (typeof data === "string") text = data;
  else if (data instanceof ArrayBuffer) text = new TextDecoder().decode(data);
  else if (ArrayBuffer.isView(data)) text = new TextDecoder().decode(data);
  else throw new Error("Unsupported frame format");
  if (new TextEncoder().encode(text).byteLength > MAX_FRAME_BYTES) throw new Error("Frame too large");
  const message = JSON.parse(text);
  if (!message || typeof message.type !== "string") throw new Error("Invalid message envelope");
  return { type: message.type, payload: message.payload && typeof message.payload === "object" ? message.payload : {} };
}

function applyPlayback(room, payload) {
  const action = payload.action;
  const position = Number.isFinite(Number(payload.position)) ? Math.max(0, Number(payload.position)) : null;
  const now = Date.now();
  switch (action) {
    case "play":
      room.playback.is_playing = true;
      if (position !== null) room.playback.position = position;
      break;
    case "pause":
      room.playback.is_playing = false;
      if (position !== null) room.playback.position = position;
      break;
    case "seek":
      if (position === null) return false;
      room.playback.position = position;
      break;
    case "change_track": {
      const track = normalizeTrack(payload.track_info, null);
      if (!track) return false;
      room.playback.current_track = track;
      room.playback.is_playing = false;
      room.playback.position = 0;
      break;
    }
    case "queue_add": {
      const track = normalizeTrack(payload.track_info, null);
      if (!track) return false;
      room.playback.queue = Array.isArray(room.playback.queue) ? room.playback.queue : [];
      if (room.playback.queue.length >= MAX_QUEUE_ITEMS) return false;
      room.playback.queue = payload.insert_next === true
        ? [track, ...room.playback.queue]
        : [...room.playback.queue, track];
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
      room.playback.queue = payload.queue.map((track) => normalizeTrack(track, null)).filter(Boolean);
      break;
    case "set_volume":
      if (!Number.isFinite(Number(payload.volume))) return false;
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "POST, OPTIONS",
          "access-control-allow-headers": "content-type",
          "access-control-max-age": "86400",
        },
      });
    }

    if (url.pathname === "/health" && request.method === "GET") {
      return json({ ok: true, service: "xan-listen-together" });
    }

    if (url.pathname === "/api/rooms" && request.method === "POST") {
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const code = randomRoomCode();
        const room = env.ROOMS.getByName(code);
        const response = await room.fetch("https://room.internal/allocate", {
          method: "POST",
          headers: { "x-xan-room-code": code },
        });
        if (response.status === 201) {
          return json({ room_code: code }, 201, { "access-control-allow-origin": "*" });
        }
      }
      return json({ error: "room_allocation_failed" }, 503, { "access-control-allow-origin": "*" });
    }

    const roomMatch = url.pathname.match(/^\/room\/([A-Z0-9]{4,12})$/i);
    if (roomMatch && request.headers.get("upgrade")?.toLowerCase() === "websocket") {
      const code = roomMatch[1].toUpperCase();
      const room = env.ROOMS.getByName(code);
      return room.fetch(new Request(`https://room.internal/room/${code}`, {
        method: request.method,
        headers: request.headers,
      }));
    }

    return json({ error: "not_found" }, 404);
  },
};

export class ListenTogetherRoom {
  constructor(ctx) {
    this.ctx = ctx;
    this.sql = ctx.storage.sql;
    this.ready = ctx.blockConcurrencyWhile(() => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS room_state (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        body TEXT NOT NULL
      )`);
    });
  }

  readRoom() {
    const row = this.sql.exec("SELECT body FROM room_state WHERE id = 1").toArray()[0];
    return row ? JSON.parse(row.body) : null;
  }

  saveRoom(room) {
    this.sql.exec(
      "INSERT OR REPLACE INTO room_state (id, body) VALUES (1, ?)",
      JSON.stringify(room),
    );
  }

  async fetch(request) {
    await this.ready;
    const url = new URL(request.url);
    if (url.pathname === "/allocate" && request.method === "POST") {
      if (this.readRoom()) return json({ error: "room_code_in_use" }, 409);
      const code = url.searchParams.get("code");
      // The Worker passes the room code in the path to avoid trusting a body.
      const pathCode = request.headers.get("x-xan-room-code") || code;
      if (!pathCode || !/^[A-Z0-9]{4,12}$/.test(pathCode)) return json({ error: "invalid_room_code" }, 400);
      this.saveRoom(initialRoom(pathCode));
      await this.ctx.storage.setAlarm(Date.now() + ALLOCATION_TTL_MS);
      return json({ ok: true }, 201);
    }

    const pathMatch = url.pathname.match(/^\/room\/([A-Z0-9]{4,12})$/i);
    if (!pathMatch || request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "not_found" }, 404);
    }
    const room = this.readRoom();
    if (!room || room.code !== pathMatch[1].toUpperCase()) return json({ error: "room_not_found" }, 404);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ roomCode: room.code, userId: null });
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    await this.ready;
    const attachment = ws.deserializeAttachment() || {};
    const room = this.readRoom();
    if (!room || attachment.roomCode !== room.code) {
      this.send(ws, "room_closed", { reason: "Room no longer exists" });
      ws.close(1008, "Room no longer exists");
      return;
    }

    let message;
    try {
      message = parseFrame(data);
    } catch (error) {
      this.send(ws, "error", { code: "invalid_message", message: error.message });
      return;
    }

    const payload = message.payload;
    const member = attachment.userId ? room.members.find((item) => item.id === attachment.userId) : null;
    const isHost = Boolean(member?.isHost && member.id === room.hostId);
    const sendError = (code, text) => this.send(ws, "error", { code, message: text });

    if (message.type === "ping") {
      const serverReceiveTime = Date.now();
      const serverSendTime = Date.now();
      this.send(ws, "pong", {
        client_time: Number.isFinite(Number(payload.client_time)) ? Number(payload.client_time) : 0,
        server_time: serverSendTime,
        server_receive_time: serverReceiveTime,
        server_send_time: serverSendTime,
      });
      return;
    }

    if (message.type === "create_room") {
      if (room.hostId) return sendError("room_already_exists", "This room already has a host");
      const host = {
        id: crypto.randomUUID(),
        username: cleanUsername(payload.username),
        isHost: true,
        isConnected: true,
        sessionToken: randomToken(),
        disconnectedAt: null,
      };
      room.hostId = host.id;
      room.members = [host];
      this.bindSocket(ws, room.code, host.id);
      this.saveRoom(room);
      this.send(ws, "room_created", {
        room_code: room.code,
        user_id: host.id,
        session_token: host.sessionToken,
        state: roomState(room),
      });
      return;
    }

    if (message.type === "join_room") {
      if (payload.room_code !== room.code) return sendError("invalid_room_code", "Room code does not match");
      if (!room.hostId) return sendError("room_not_ready", "The room host is not connected yet");
      if (member) return sendError("already_in_room", "You are already in this room");
      const username = cleanUsername(payload.username);
      const existing = attachment.userId && room.pending.find((item) => item.id === attachment.userId);
      if (existing) return;
      if (room.members.length >= MAX_ROOM_MEMBERS) return sendError("room_full", "This room has reached its member limit");
      if (room.pending.length >= MAX_PENDING_JOIN_REQUESTS) return sendError("room_full", "This room has too many pending join requests");
      const pending = { id: crypto.randomUUID(), username };
      room.pending.push(pending);
      this.bindSocket(ws, room.code, pending.id);
      this.saveRoom(room);
      const hostSocket = this.socketFor(room.hostId);
      if (hostSocket) this.send(hostSocket, "join_request", { user_id: pending.id, username });
      return;
    }

    if (message.type === "reconnect") {
      const token = typeof payload.session_token === "string" ? payload.session_token : "";
      const reconnectMember = room.members.find((item) => item.sessionToken === token);
      if (!reconnectMember) return sendError("session_not_found", "This room session has expired");
      const wasDisconnected = !reconnectMember.isConnected;
      const previousSocket = this.socketFor(reconnectMember.id);
      if (previousSocket && previousSocket !== ws) {
        try {
          previousSocket.close(4001, "Session resumed on another connection");
        } catch {
          // The previous socket may already have closed.
        }
      }
      reconnectMember.isConnected = true;
      reconnectMember.disconnectedAt = null;
      this.bindSocket(ws, room.code, reconnectMember.id);
      this.saveRoom(room);
      this.send(ws, "reconnected", {
        room_code: room.code,
        user_id: reconnectMember.id,
        state: roomState(room),
        is_host: reconnectMember.isHost,
      });
      if (wasDisconnected) {
        this.broadcast(room, "user_reconnected", { user_id: reconnectMember.id, username: reconnectMember.username }, reconnectMember.id);
      }
      if (reconnectMember.isHost) {
        for (const pending of room.pending) {
          this.send(ws, "join_request", { user_id: pending.id, username: pending.username });
        }
      }
      return;
    }

    if (message.type === "approve_join") {
      if (!isHost) return sendError("permission_denied", "Only the host can approve requests");
      const pendingIndex = room.pending.findIndex((item) => item.id === payload.user_id);
      if (pendingIndex < 0) return sendError("join_request_not_found", "Join request is no longer available");
      const pending = room.pending.splice(pendingIndex, 1)[0];
      if (room.members.length >= MAX_ROOM_MEMBERS) {
        this.saveRoom(room);
        const guestSocket = this.socketFor(pending.id);
        if (guestSocket) {
          this.send(guestSocket, "join_rejected", { reason: "This room has reached its member limit" });
          guestSocket.close(1000, "Room is full");
        }
        return sendError("room_full", "This room has reached its member limit");
      }
      const guest = {
        id: pending.id,
        username: pending.username,
        isHost: false,
        isConnected: true,
        sessionToken: randomToken(),
        disconnectedAt: null,
      };
      room.members.push(guest);
      this.saveRoom(room);
      const guestSocket = this.socketFor(guest.id);
      if (guestSocket) this.send(guestSocket, "join_approved", {
        room_code: room.code,
        user_id: guest.id,
        session_token: guest.sessionToken,
        state: roomState(room),
      });
      this.broadcast(room, "user_joined", { user_id: guest.id, username: guest.username }, guest.id);
      return;
    }

    if (message.type === "reject_join") {
      if (!isHost) return sendError("permission_denied", "Only the host can reject requests");
      const pendingIndex = room.pending.findIndex((item) => item.id === payload.user_id);
      if (pendingIndex < 0) return;
      const [pending] = room.pending.splice(pendingIndex, 1);
      this.saveRoom(room);
      const guestSocket = this.socketFor(pending.id);
      if (guestSocket) {
        this.send(guestSocket, "join_rejected", { reason: payload.reason || "Request rejected" });
        guestSocket.close(1000, "Join request rejected");
      }
      return;
    }

    if (!member) return sendError("not_in_room", "Join and be approved before sending room messages");

    switch (message.type) {
      case "leave_room": {
        const username = member.username;
        room.members = room.members.filter((item) => item.id !== member.id);
        if (member.isHost) this.electHost(room);
        if (room.members.length === 0) {
          for (const socket of this.ctx.getWebSockets()) {
            if (socket !== ws) {
              this.send(socket, "room_closed", { reason: "The host left the room" });
              socket.close(1000, "Room closed");
            }
          }
          this.sql.exec("DELETE FROM room_state WHERE id = 1");
        } else {
          this.saveRoom(room);
          this.broadcast(room, "user_left", { user_id: member.id, username });
          if (member.isHost && room.hostId) {
            const nextHost = room.members.find((item) => item.id === room.hostId);
            if (nextHost) this.broadcast(room, "host_changed", { new_host_id: nextHost.id, new_host_name: nextHost.username });
          }
        }
        ws.close(1000, "Left room");
        return;
      }
      case "playback_action": {
        const canControl = isHost || room.playback.control_mode === "everyone";
        if (!canControl) return sendError("permission_denied", "Playback control is restricted to the host");
        if (!applyPlayback(room, payload)) return sendError("invalid_playback_action", "Playback action was invalid");
        const playback = { ...payload, from_user_id: member.id, server_time: room.playback.last_update };
        this.saveRoom(room);
        this.broadcast(room, "sync_playback", playback);
        return;
      }
      case "request_sync":
        this.send(ws, "sync_state", payload.lightweight === true
          ? { ...syncState(room), queue: null, volume: null }
          : syncState(room));
        return;
      case "chat": {
        const text = typeof payload.message === "string" ? payload.message.trim().slice(0, MAX_CHAT_LENGTH) : "";
        if (!text) return;
        this.broadcast(room, "chat", {
          user_id: member.id,
          username: member.username,
          message: text,
          timestamp: Date.now(),
          reply_to: payload.reply_to && typeof payload.reply_to === "object" ? {
            username: cleanUsername(payload.reply_to.username),
            message: String(payload.reply_to.message || "").slice(0, MAX_CHAT_LENGTH),
          } : null,
        });
        return;
      }
      case "set_control_mode": {
        if (!isHost) return sendError("permission_denied", "Only the host can change control mode");
        if (!["owner", "everyone"].includes(payload.control_mode)) return sendError("invalid_control_mode", "Control mode must be owner or everyone");
        room.playback.control_mode = payload.control_mode;
        this.saveRoom(room);
        this.broadcast(room, "control_mode_changed", { control_mode: payload.control_mode });
        return;
      }
      case "transfer_host": {
        if (!isHost) return sendError("permission_denied", "Only the host can transfer host role");
        const next = room.members.find((item) => item.id === payload.new_host_id && item.isConnected);
        if (!next || next.id === member.id) return sendError("invalid_host", "Choose a connected room member");
        member.isHost = false;
        next.isHost = true;
        room.hostId = next.id;
        this.saveRoom(room);
        this.broadcast(room, "host_changed", { new_host_id: next.id, new_host_name: next.username });
        return;
      }
      case "kick_user": {
        if (!isHost) return sendError("permission_denied", "Only the host can remove members");
        const target = room.members.find((item) => item.id === payload.user_id && !item.isHost);
        if (!target) return sendError("user_not_found", "Member was not found");
        room.members = room.members.filter((item) => item.id !== target.id);
        this.saveRoom(room);
        const targetSocket = this.socketFor(target.id);
        if (targetSocket) {
          this.send(targetSocket, "kicked", { reason: payload.reason || "Removed by host" });
          targetSocket.close(1008, "Removed by host");
        }
        this.broadcast(room, "user_left", { user_id: target.id, username: target.username });
        return;
      }
      case "buffer_ready": {
        const trackId = typeof payload.track_id === "string" ? payload.track_id.slice(0, 256) : "";
        if (!trackId) return;
        room.buffers[trackId] = Array.isArray(room.buffers[trackId]) ? room.buffers[trackId] : [];
        if (!room.buffers[trackId].includes(member.id)) room.buffers[trackId].push(member.id);
        const waitingFor = room.members.filter((item) => item.isConnected && !room.buffers[trackId].includes(item.id)).map((item) => item.id);
        this.saveRoom(room);
        if (waitingFor.length === 0) {
          delete room.buffers[trackId];
          this.saveRoom(room);
          this.broadcast(room, "buffer_complete", { track_id: trackId });
        } else {
          this.broadcast(room, "buffer_wait", { track_id: trackId, waiting_for: waitingFor });
        }
        return;
      }
      case "suggest_track": {
        if (isHost) return sendError("invalid_suggestion", "The host cannot suggest tracks");
        if (room.suggestions.length >= MAX_TRACK_SUGGESTIONS) {
          return sendError("suggestion_limit", "This room has reached its pending suggestion limit");
        }
        const trackInfo = normalizeTrack(payload.track_info, member.username);
        if (!trackInfo) return sendError("invalid_suggestion", "Track details were invalid");
        const suggestion = {
          suggestion_id: crypto.randomUUID(),
          from_user_id: member.id,
          from_username: member.username,
          track_info: trackInfo,
        };
        room.suggestions.push(suggestion);
        this.saveRoom(room);
        const hostSocket = this.socketFor(room.hostId);
        if (hostSocket) this.send(hostSocket, "suggestion_received", suggestion);
        return;
      }
      case "approve_suggestion": {
        if (!isHost) return sendError("permission_denied", "Only the host can approve suggestions");
        const index = room.suggestions.findIndex((item) => item.suggestion_id === payload.suggestion_id);
        if (index < 0) return sendError("suggestion_not_found", "Suggestion is no longer available");
        const [suggestion] = room.suggestions.splice(index, 1);
        this.saveRoom(room);
        this.broadcast(room, "suggestion_approved", {
          suggestion_id: suggestion.suggestion_id,
          track_info: suggestion.track_info,
        });
        return;
      }
      case "reject_suggestion": {
        if (!isHost) return sendError("permission_denied", "Only the host can reject suggestions");
        const index = room.suggestions.findIndex((item) => item.suggestion_id === payload.suggestion_id);
        if (index < 0) return;
        room.suggestions.splice(index, 1);
        this.saveRoom(room);
        this.broadcast(room, "suggestion_rejected", {
          suggestion_id: payload.suggestion_id,
          reason: typeof payload.reason === "string" ? payload.reason.slice(0, 200) : null,
        });
        return;
      }
      case "extend_session":
        if (!isHost) return sendError("permission_denied", "Only the host can extend the room");
        // Rooms currently have no hard expiry; this verb is accepted for older clients.
        return;
      default:
        return sendError("unknown_message", "This message type is not supported");
    }
  }

  async webSocketClose(ws) {
    await this.ready;
    const attachment = ws.deserializeAttachment() || {};
    const room = this.readRoom();
    if (!room || !attachment.userId) return;
    const pendingIndex = room.pending.findIndex((item) => item.id === attachment.userId);
    if (pendingIndex >= 0) {
      room.pending.splice(pendingIndex, 1);
      this.saveRoom(room);
      return;
    }
    const member = room.members.find((item) => item.id === attachment.userId);
    if (!member || !member.isConnected) return;
    // A reconnect can replace the socket before the old socket's close event
    // arrives. That stale close must not mark the newly connected session away.
    const hasReplacementSocket = this.ctx.getWebSockets().some((socket) => {
      if (socket === ws) return false;
      const other = socket.deserializeAttachment();
      return other?.roomCode === room.code && other?.userId === member.id;
    });
    if (hasReplacementSocket) return;
    member.isConnected = false;
    member.disconnectedAt = Date.now();
    this.saveRoom(room);
    this.broadcast(room, "user_disconnected", { user_id: member.id, username: member.username }, member.id);
    await this.ctx.storage.setAlarm(Date.now() + RECONNECT_GRACE_MS);
  }

  async webSocketError(ws) {
    return this.webSocketClose(ws);
  }

  async alarm() {
    await this.ready;
    const room = this.readRoom();
    if (!room) return;
    if (!room.hostId && Date.now() - room.createdAt >= ALLOCATION_TTL_MS) {
      this.sql.exec("DELETE FROM room_state WHERE id = 1");
      return;
    }
    const cutoff = Date.now() - RECONNECT_GRACE_MS;
    const expired = room.members.filter((member) => !member.isConnected && member.disconnectedAt && member.disconnectedAt <= cutoff);
    for (const member of expired) {
      if (member.isHost) {
        for (const socket of this.ctx.getWebSockets()) {
          this.send(socket, "room_closed", { reason: "The host did not reconnect" });
          socket.close(1000, "Room expired");
        }
        this.sql.exec("DELETE FROM room_state WHERE id = 1");
        return;
      }
      room.members = room.members.filter((item) => item.id !== member.id);
      this.broadcast(room, "user_left", { user_id: member.id, username: member.username });
    }
    this.saveRoom(room);
    const next = room.members.filter((member) => !member.isConnected && member.disconnectedAt).reduce((min, item) => Math.min(min, item.disconnectedAt + RECONNECT_GRACE_MS), Infinity);
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next);
  }

  bindSocket(ws, roomCode, userId) {
    ws.serializeAttachment({ roomCode, userId });
  }

  socketFor(userId) {
    const sockets = this.ctx.getWebSockets();
    for (let index = sockets.length - 1; index >= 0; index -= 1) {
      if (sockets[index].deserializeAttachment()?.userId === userId) return sockets[index];
    }
    return null;
  }

  send(ws, type, payload) {
    try {
      ws.send(JSON.stringify({ type, payload: payload ?? null }));
    } catch {
      // A socket can close between the presence snapshot and send.
    }
  }

  broadcast(room, type, payload, excludeUserId = null) {
    for (const socket of this.ctx.getWebSockets()) {
      const attachment = socket.deserializeAttachment();
      if (attachment?.roomCode === room.code && attachment.userId && attachment.userId !== excludeUserId) {
        this.send(socket, type, payload);
      }
    }
  }

  electHost(room) {
    const next = room.members.find((item) => item.isConnected) || room.members[0] || null;
    room.members.forEach((item) => { item.isHost = item.id === next?.id; });
    room.hostId = next?.id || null;
  }
}

export const __test = {
  applyPlayback,
  cleanUsername,
  initialRoom,
  parseFrame,
  roomState,
};
