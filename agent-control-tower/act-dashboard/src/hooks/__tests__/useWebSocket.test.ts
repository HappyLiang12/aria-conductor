import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../useWebSocket';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  url: string;
  readyState = FakeWebSocket.CONNECTING;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
}

function frame(command: string, headers: Record<string, string> = {}, body = ''): string {
  let f = command + '\n';
  for (const [k, v] of Object.entries(headers)) f += `${k}:${v}\n`;
  return f + '\n' + body + '\0';
}

function lastSocket(): FakeWebSocket {
  return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
}

/** Open the socket and complete the STOMP handshake. */
function handshake(ws: FakeWebSocket) {
  act(() => {
    ws.readyState = FakeWebSocket.OPEN;
    ws.onopen?.();
    ws.onmessage?.({ data: frame('CONNECTED', { version: '1.2' }) });
  });
}

describe('useWebSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('opens a socket to the given URL and sends a STOMP CONNECT frame on open', () => {
    renderHook(() => useWebSocket('ws://test-host/ws/events'));
    expect(FakeWebSocket.instances).toHaveLength(1);
    const ws = lastSocket();
    expect(ws.url).toBe('ws://test-host/ws/events');

    act(() => {
      ws.readyState = FakeWebSocket.OPEN;
      ws.onopen?.();
    });
    expect(ws.sent).toHaveLength(1);
    expect(ws.sent[0]).toBe('CONNECT\naccept-version:1.2\nhost:localhost\n\n\0');
  });

  it('derives the default URL from window.location', () => {
    renderHook(() => useWebSocket());
    const { protocol, host } = window.location;
    const wsProto = protocol === 'https:' ? 'wss:' : 'ws:';
    expect(lastSocket().url).toBe(`${wsProto}//${host}/ws/events`);
  });

  it('marks connected and subscribes to /topic/events after CONNECTED frame', () => {
    const { result } = renderHook(() => useWebSocket('ws://t/ws'));
    const ws = lastSocket();
    expect(result.current.isConnected).toBe(false);

    handshake(ws);

    expect(result.current.isConnected).toBe(true);
    expect(ws.sent[1]).toBe('SUBSCRIBE\nid:sub-0\ndestination:/topic/events\n\n\0');
  });

  it('parses MESSAGE frames into lastMessage', () => {
    const { result } = renderHook(() => useWebSocket('ws://t/ws'));
    const ws = lastSocket();
    handshake(ws);

    const event = { type: 'run.started', payload: { runId: 'r-1' }, timestamp: '2026-01-01T00:00:00Z' };
    act(() => {
      ws.onmessage?.({
        data: frame('MESSAGE', { destination: '/topic/events' }, JSON.stringify(event)),
      });
    });
    expect(result.current.lastMessage).toEqual(event);
  });

  it('ignores MESSAGE frames with malformed JSON bodies', () => {
    const { result } = renderHook(() => useWebSocket('ws://t/ws'));
    const ws = lastSocket();
    handshake(ws);

    act(() => {
      ws.onmessage?.({ data: frame('MESSAGE', {}, '{broken json') });
    });
    expect(result.current.lastMessage).toBeNull();
    expect(result.current.isConnected).toBe(true);
  });

  it('send() wraps payload in a STOMP SEND frame only when the socket is open', () => {
    const { result } = renderHook(() => useWebSocket('ws://t/ws'));
    const ws = lastSocket();

    // not open yet — nothing is sent
    act(() => {
      result.current.send('ignored');
    });
    expect(ws.sent).toHaveLength(0);

    handshake(ws);
    act(() => {
      result.current.send('hello');
    });
    expect(ws.sent[2]).toBe('SEND\ndestination:/app/events\n\nhello\0');
  });

  it('reconnects with exponential backoff after the socket closes', () => {
    const { result } = renderHook(() => useWebSocket('ws://t/ws'));
    const first = lastSocket();
    handshake(first);
    expect(result.current.isConnected).toBe(true);

    // server drops the connection → first delay is 1000ms
    act(() => {
      first.close();
    });
    expect(result.current.isConnected).toBe(false);
    expect(FakeWebSocket.instances).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(FakeWebSocket.instances).toHaveLength(2);

    // second drop → delay doubles to 2000ms
    act(() => {
      lastSocket().close();
    });
    act(() => {
      vi.advanceTimersByTime(1999);
    });
    expect(FakeWebSocket.instances).toHaveLength(2);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(FakeWebSocket.instances).toHaveLength(3);
  });

  it('a successful handshake resets the backoff counter', () => {
    renderHook(() => useWebSocket('ws://t/ws'));
    handshake(lastSocket());

    act(() => {
      lastSocket().close();
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(FakeWebSocket.instances).toHaveLength(2);

    // reconnect succeeds → attempt counter resets to 0
    handshake(lastSocket());
    act(() => {
      lastSocket().close();
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(FakeWebSocket.instances).toHaveLength(3);
  });

  it('closes the socket without reconnecting on unmount', () => {
    const { unmount } = renderHook(() => useWebSocket('ws://t/ws'));
    const ws = lastSocket();
    handshake(ws);

    unmount();
    expect(ws.readyState).toBe(FakeWebSocket.CLOSED);

    act(() => {
      vi.advanceTimersByTime(60000);
    });
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});
