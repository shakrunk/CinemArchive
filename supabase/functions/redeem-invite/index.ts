// Supabase Edge Function: redeem-invite
// Real server-side gate for invite-only signup: creates an auth.users row
// only when a valid, unredeemed invite code is presented. Uses the service
// role key (admin API) — this is the only place account creation happens;
// src/lib/auth.ts calls signInWithOtp with shouldCreateUser: false, so the
// normal sign-in flow can never create an account on its own.
// Deploy with: supabase functions deploy redeem-invite

import { createClient } from 'jsr:@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)

const corsHeaders: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

// Always responds 200 — supabase-js's functions.invoke() surfaces non-2xx
// responses as a generic FunctionsHttpError and discards the JSON body, so a
// friendly { error } message only reaches the client on a 2xx response.
// src/lib/auth.ts's redeemInvite() checks data?.error to distinguish this
// from the { success: true } happy path.
function fail(message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status: 200,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: corsHeaders })
  }

  try {
    const body = await req.json().catch(() => ({}))
    const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : ''
    const code = typeof body.code === 'string' ? body.code.trim().toUpperCase() : ''

    if (!email || !isValidEmail(email)) return fail('A valid email is required.')
    if (!code) return fail('An invite code is required.')

    const { data: invite, error: inviteError } = await supabase
      .from('invite_codes')
      .select('id, redeemed_by')
      .eq('code', code)
      .maybeSingle()

    if (inviteError) return fail('Could not verify invite code.')
    if (!invite) return fail('Invalid invite code.')
    if (invite.redeemed_by) return fail('This invite code has already been used.')

    const { data: created, error: createError } = await supabase.auth.admin.createUser({
      email,
      email_confirm: true,
    })

    if (createError || !created?.user) {
      const msg = createError?.message ?? ''
      if (/already|exists|registered/i.test(msg)) {
        return fail('An account with this email already exists — sign in instead.')
      }
      return fail(msg || 'Could not create account.')
    }

    // Claim the code atomically — only succeeds if it's still unredeemed,
    // protecting against a race between two concurrent redemptions.
    const { data: redeemedRows, error: redeemError } = await supabase
      .from('invite_codes')
      .update({ redeemed_by: created.user.id, redeemed_at: new Date().toISOString() })
      .eq('id', invite.id)
      .is('redeemed_by', null)
      .select('id')

    if (redeemError || !redeemedRows || redeemedRows.length === 0) {
      // Lost the race (or write failed) — roll back the just-created account
      // so the code stays genuinely unredeemed for whoever won.
      await supabase.auth.admin.deleteUser(created.user.id)
      return fail('This invite code has already been used.')
    }

    return new Response(JSON.stringify({ success: true }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Internal error'
    return fail(message)
  }
})
