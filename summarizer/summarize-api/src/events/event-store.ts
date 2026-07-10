export interface JobEvent {
  type: "status" | "chunk" | "meta" | "metrics" | "done" | "error";
  data: unknown;
  timestamp: string;
}

interface EventBuffer {
  events: JobEvent[];
  listeners: Set<(event: JobEvent) => void>;
  createdAt: number;
}

const MAX_EVENTS = 2000;
const CLEANUP_INTERVAL_MS = 5 * 60 * 1000;
const MAX_AGE_MS = 30 * 60 * 1000;

export class EventStore {
  private buffers = new Map<string, EventBuffer>();
  private cleanupTimer: ReturnType<typeof setInterval>;

  constructor() {
    this.cleanupTimer = setInterval(() => this.cleanup(), CLEANUP_INTERVAL_MS);
  }

  addEvent(jobId: string, event: JobEvent): void {
    const buffer = this.getOrCreateBuffer(jobId);
    if (buffer.events.length >= MAX_EVENTS) {
      buffer.events.shift();
    }
    buffer.events.push(event);
    for (const listener of buffer.listeners) {
      listener(event);
    }
  }

  subscribe(jobId: string, callback: (event: JobEvent) => void): () => void {
    const buffer = this.getOrCreateBuffer(jobId);

    for (const event of buffer.events) {
      callback(event);
    }

    buffer.listeners.add(callback);

    return () => {
      buffer.listeners.delete(callback);
    };
  }

  getEvents(jobId: string): JobEvent[] {
    const buffer = this.buffers.get(jobId);
    return buffer ? [...buffer.events] : [];
  }

  cleanup(): void {
    const now = Date.now();
    for (const [jobId, buffer] of this.buffers) {
      if (buffer.listeners.size === 0 && now - buffer.createdAt > MAX_AGE_MS) {
        this.buffers.delete(jobId);
      }
    }
  }

  dispose(): void {
    clearInterval(this.cleanupTimer);
  }

  private getOrCreateBuffer(jobId: string): EventBuffer {
    let buffer = this.buffers.get(jobId);
    if (!buffer) {
      buffer = { events: [], listeners: new Set(), createdAt: Date.now() };
      this.buffers.set(jobId, buffer);
    }
    return buffer;
  }
}

export const eventStore = new EventStore();
