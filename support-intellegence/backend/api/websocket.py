"""
WebSocket manager — fan-out broadcast to all connected subscribers.
Supports topic-based filtering by app_id, team_id, severity.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Optional
from fastapi import WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)


class WebSocketConnection:
    def __init__(self, ws: WebSocket, subscriptions: dict):
        self.ws = ws
        self.subscriptions = subscriptions  # {type: set_of_ids}


class WebSocketManager:
    """Manages all active WebSocket connections with subscription filtering."""

    def __init__(self):
        self._connections: list[WebSocketConnection] = []
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket) -> WebSocketConnection:
        await ws.accept()
        conn = WebSocketConnection(ws, {
            "app_ids": set(),
            "team_ids": set(),
            "severities": set(),
            "subscribe_all": False,
        })
        async with self._lock:
            self._connections.append(conn)
        logger.debug("WebSocket connected — total: %d", len(self._connections))
        return conn

    async def disconnect(self, conn: WebSocketConnection) -> None:
        async with self._lock:
            self._connections.remove(conn)
        logger.debug("WebSocket disconnected — total: %d", len(self._connections))

    async def broadcast(self, payload: dict) -> None:
        """Broadcast payload to all connected clients matching subscription filters."""
        if not self._connections:
            return

        dead = []
        for conn in list(self._connections):
            if self._matches_subscription(conn, payload):
                try:
                    await conn.ws.send_text(json.dumps(payload, default=str))
                except Exception:
                    dead.append(conn)

        if dead:
            async with self._lock:
                for conn in dead:
                    self._connections.discard(conn) if hasattr(self._connections, 'discard') \
                        else (self._connections.remove(conn) if conn in self._connections else None)

    async def handle_client_messages(self, conn: WebSocketConnection) -> None:
        """Process subscription messages from client."""
        try:
            while True:
                raw = await conn.ws.receive_text()
                try:
                    msg = json.loads(raw)
                    await self._process_subscription(conn, msg)
                except json.JSONDecodeError:
                    pass
        except WebSocketDisconnect:
            pass

    async def _process_subscription(self, conn: WebSocketConnection, msg: dict) -> None:
        msg_type = msg.get("type", "")

        if msg_type == "subscribe_apps":
            conn.subscriptions["app_ids"].update(msg.get("app_ids", []))
        elif msg_type == "subscribe_team":
            conn.subscriptions["team_ids"].add(msg.get("team_id", ""))
        elif msg_type == "subscribe_issues":
            conn.subscriptions["severities"].update(msg.get("severity", []))
        elif msg_type == "subscribe_all":
            conn.subscriptions["subscribe_all"] = True
        elif msg_type == "unsubscribe":
            conn.subscriptions = {"app_ids": set(), "team_ids": set(), "severities": set(), "subscribe_all": False}

        await conn.ws.send_text(json.dumps({"type": "ack", "subscribed": True}))

    @staticmethod
    def _matches_subscription(conn: WebSocketConnection, payload: dict) -> bool:
        subs = conn.subscriptions
        if subs.get("subscribe_all"):
            return True

        app_id = payload.get("app_id") or payload.get("issue", {}).get("app_id")
        if app_id and app_id in subs.get("app_ids", set()):
            return True

        severity = payload.get("severity") or payload.get("issue", {}).get("severity")
        if severity and severity in subs.get("severities", set()):
            return True

        # Default: send everything if no explicit subscriptions set
        if not any([subs.get("app_ids"), subs.get("team_ids"), subs.get("severities")]):
            return True

        return False

    @property
    def connection_count(self) -> int:
        return len(self._connections)
