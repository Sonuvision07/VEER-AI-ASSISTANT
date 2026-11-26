/// <reference types="vite/client" />


declare global { interface Window { puter?: { auth: { isSignedIn(): boolean; signIn(): Promise<unknown>; signOut(): Promise<void> }; ai: { listModels(provider?: string): Promise<Array<{ id: string; puterId?: string; provider: string; name?: string }>>; chat(messages: Array<{ role: string; content: string }>, options: { model: string; stream: boolean; signal?: AbortSignal }): Promise<AsyncIterable<{ type?: string; text?: string; message?: string }>> } } } }
export {};
