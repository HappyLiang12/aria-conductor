import { useEffect, useRef, useState, useCallback } from 'react';
import type { WsEvent } from '../types';

interface UseWebSocketReturn {
  lastMessage: WsEvent | null;
  isConnected: boolean;
  send: (data: string) => void;
}

// Minimal STOMP frame helpers
function stompFrame(command: string, headers: Record<string, string> = {}, body = ''): string {
  let frame = command + '\n';
  for (const [k, v] of Object.entries(headers)) {
    frame += `${k}:${v}\n`;
  }
  frame += '\n' + body + '\0';
  return frame;
}

function parseStompFrame(raw: string): { command: string; headers: Record<string, string>; body: string } | null {
  const nullIdx = raw.indexOf('\0');
  const data = nullIdx >= 0 ? raw.substring(0, nullIdx) : raw;
  const lines = data.split('\n');
  if (lines.length < 1) return null;
  const command = lines[0];
  const headers: Record<string, string> = {};
  let i = 1;
  for (; i < lines.length; i++) {
    if (lines[i] === '') break;
    const colon = lines[i].indexOf(':');
    if (colon > 0) {
      headers[lines[i].substring(0, colon)] = lines[i].substring(colon + 1);
    }
  }
  const body = lines.slice(i + 1).join('\n');
  return { command, headers, body };
}

export function useWebSocket(url = 'ws://localhost:8080/ws/events'): UseWebSocketReturn {
  const [lastMessage, setLastMessage] = useState<WsEvent | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttempt = useRef(0);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const connect = useCallback(() => {
    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        // Send STOMP CONNECT frame
        ws.send(stompFrame('CONNECT', { 'accept-version': '1.2', host: 'localhost' }));
      };

      ws.onmessage = (event) => {
        try {
          const frame = parseStompFrame(event.data as string);
          if (!frame) return;

          if (frame.command === 'CONNECTED') {
            setIsConnected(true);
            reconnectAttempt.current = 0;
            // Subscribe to events topic
            ws.send(stompFrame('SUBSCRIBE', { id: 'sub-0', destination: '/topic/events' }));
          } else if (frame.command === 'MESSAGE') {
            const parsed: WsEvent = JSON.parse(frame.body);
            setLastMessage(parsed);
          }
        } catch {
          console.warn('[WS] Failed to parse STOMP frame');
        }
      };

      ws.onclose = () => {
        setIsConnected(false);
        wsRef.current = null;
        scheduleReconnect();
      };

      ws.onerror = () => {
        ws.close();
      };
    } catch {
      scheduleReconnect();
    }
  }, [url]);

  const scheduleReconnect = useCallback(() => {
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempt.current), 30000);
    reconnectAttempt.current += 1;
    reconnectTimer.current = setTimeout(() => {
      connect();
    }, delay);
  }, [connect]);

  const send = useCallback((data: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(stompFrame('SEND', { destination: '/app/events' }, data));
    }
  }, []);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.close();
      }
    };
  }, [connect]);

  return { lastMessage, isConnected, send };
}
