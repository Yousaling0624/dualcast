declare module 'cloudflare:sockets' {
  export interface Socket {
    readable: ReadableStream<Uint8Array>
    writable: WritableStream<Uint8Array>
    close(): Promise<void>
    closed: Promise<void>
  }
  export interface SocketOptions {
    secureTransport?: 'off' | 'start' | 'on'
    allowHalfOpen?: boolean
  }
  export function connect(address: string, options?: SocketOptions): Socket
}
