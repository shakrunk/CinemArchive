// Shared across all Edge Functions — CORS headers, the OPTIONS preflight
// guard, and the Error-to-message extraction used in each function's
// top-level catch block.

export function buildCorsHeaders(methods: string): Record<string, string> {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': methods,
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  }
}

export function handleOptions(req: Request, headers: Record<string, string>): Response | null {
  return req.method === 'OPTIONS' ? new Response(null, { headers }) : null
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Internal error'
}
