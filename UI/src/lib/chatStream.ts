export interface StreamEvent {
  type: 'connected' | 'text.delta' | 'session.updated' | 'done' | 'error';
  sessionId?: string;
  content?: string;
  title?: string;
  message?: string;
}

export async function streamChat(
  payload: any,
  onEvent: (event: StreamEvent) => void,
  onDone: () => void,
  onError: (error: string) => void
) {
  try {
    const response = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No reader available');

    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const jsonStr = line.slice(6);
          if (jsonStr === '[DONE]') {
            onDone();
            return;
          }
          try {
            const event: StreamEvent = JSON.parse(jsonStr);
            onEvent(event);
            if (event.type === 'done') {
              onDone();
              return;
            }
          } catch (e) {
            console.error('Error parsing stream chunk', e);
          }
        }
      }
    }
  } catch (err: any) {
    onError(err.message || 'Stream connection failed');
  }
}
