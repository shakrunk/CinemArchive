import { useState } from 'react'
import { cn, fmtCurrency } from 'src/lib/utils'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { SegmentedToggle } from 'src/components/ui/segmented-toggle'
import { Eyebrow } from 'src/components/ui/typography'
import { setMoviegoingBudget, setMoviegoingPlan } from 'src/lib/moviegoingPrefsSync'
import { shareRecap } from 'src/lib/moviegoingRecapImage'
import {
  budgetSummary, moviegoingRecap, parseBudgetAmount, parsePlanCap, planSummary, recapFields, recapSummary, recapYears,
  MAX_BUDGET_AMOUNT, MAX_PLAN_CAP,
  type BudgetPeriod, type BudgetState, type BudgetStatus, type MoviegoingBudget, type MoviegoingTrip, type PlanPeriod,
  type PlanState, type PlanStatus, type SubscriptionPlan,
} from 'src/store/moviegoingRules'

// The owner-only half of "At the movies": the budget (#209), subscription plan (#211) and yearly
// recap (#216). All three read ticket prices or outings, which are owner-private, so the panel
// renders nothing from here in a shared or friend view.

const STATE_TONE: Record<BudgetState | PlanState, string> = {
  under: 'text-amber-bright',
  near: 'text-amber',
  at_cap: 'text-amber',
  over: 'text-destructive',
}
const BAR_TONE: Record<BudgetState | PlanState, string> = {
  under: 'bg-amber-deep',
  near: 'bg-amber',
  at_cap: 'bg-amber',
  over: 'bg-destructive',
}

function Meter({ label, fraction, state }: { label: string; fraction: number; state: BudgetState | PlanState }) {
  const percent = Math.round(Math.min(Math.max(fraction, 0), 1) * 100)
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className="h-1.5 w-full overflow-hidden rounded-full bg-secondary/40"
    >
      <div className={cn('h-full rounded-full', BAR_TONE[state])} style={{ width: `${percent}%` }} />
    </div>
  )
}

/** The two status lines under the card: only what is set, with the state spelled out in words. */
export function MoviegoingStatusLines({ budget, plan }: { budget: BudgetStatus | null; plan: PlanStatus | null }) {
  if (!budget && !plan) return null
  return (
    <div className="flex flex-col gap-0.5">
      {budget && (
        <p className={cn('text-xs', STATE_TONE[budget.state])}>
          Budget · {budget.budget.period === 'month' ? 'this month' : 'this year'}: {budgetSummary(budget, fmtCurrency)}
        </p>
      )}
      {plan && (
        <p className={cn('text-xs', STATE_TONE[plan.state])}>
          Plan · {plan.plan.period === 'week' ? 'this week' : 'this month'}: {planSummary(plan)}
        </p>
      )}
    </div>
  )
}

export function BudgetSection({ status, current }: { status: BudgetStatus | null; current: MoviegoingBudget | null }) {
  // The form shows the saved budget until the person edits it, so a copy that arrives from
  // another device is picked up without resetting an edit in progress.
  const [draft, setDraft] = useState<{ amount: string; period: BudgetPeriod } | null>(null)
  const amountText = draft?.amount ?? (current ? current.amount.toFixed(2) : '')
  const period = draft?.period ?? current?.period ?? 'month'
  const amount = parseBudgetAmount(amountText)
  const showError = amountText.trim() !== '' && amount === null
  return (
    <section aria-label="Budget" className="space-y-3">
      <h3 className="font-serif text-lg text-paper">Budget</h3>
      {status && (
        <div className="space-y-1.5">
          <p className={cn('text-sm', STATE_TONE[status.state])}>{budgetSummary(status, fmtCurrency)}</p>
          <Meter label={budgetSummary(status, fmtCurrency)} fraction={status.fraction} state={status.state} />
        </div>
      )}
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          if (amount !== null) void setMoviegoingBudget({ amount, period }).then(() => setDraft(null))
        }}
      >
        <label className="block space-y-1">
          <Eyebrow as="span">Amount</Eyebrow>
          <Input
            inputMode="decimal"
            value={amountText}
            onChange={(event) => setDraft({ amount: event.target.value, period })}
            aria-invalid={showError}
            aria-describedby={showError ? 'budget-amount-error' : undefined}
            placeholder="40.00"
          />
        </label>
        {showError && (
          <p id="budget-amount-error" role="alert" className="text-sm text-destructive">
            Enter an amount above 0 and up to {MAX_BUDGET_AMOUNT.toLocaleString()}
          </p>
        )}
        <SegmentedToggle<BudgetPeriod>
          ariaLabel="Budget period"
          value={period}
          onChange={(next) => setDraft({ amount: amountText, period: next })}
          options={[{ value: 'month', label: 'Per month' }, { value: 'year', label: 'Per year' }]}
        />
        <p className="text-xs text-paper-faint">Counts ticket prices on outings, by calendar month or year. Only you can see this.</p>
        <div className="flex gap-2">
          <Button type="submit" size="sm" disabled={amount === null}>{current ? 'Save budget' : 'Set budget'}</Button>
          {current && (
            <Button type="button" size="sm" variant="ghost" onClick={() => void setMoviegoingBudget(null).then(() => setDraft(null))}>Remove budget</Button>
          )}
        </div>
      </form>
    </section>
  )
}

export function PlanSection({ status, current }: { status: PlanStatus | null; current: SubscriptionPlan | null }) {
  const [draft, setDraft] = useState<{ cap: string; period: PlanPeriod } | null>(null)
  const capText = draft?.cap ?? (current ? String(current.cap) : '')
  const period = draft?.period ?? current?.period ?? 'week'
  const cap = parsePlanCap(capText)
  const showError = capText.trim() !== '' && cap === null
  return (
    <section aria-label="Subscription plan" className="space-y-3">
      <h3 className="font-serif text-lg text-paper">Subscription plan</h3>
      {status && (
        <div className="space-y-1.5">
          <p className={cn('text-sm', STATE_TONE[status.state])}>{planSummary(status)}</p>
          <Meter label={planSummary(status)} fraction={status.used / status.plan.cap} state={status.state} />
        </div>
      )}
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          if (cap !== null) void setMoviegoingPlan({ cap, period }).then(() => setDraft(null))
        }}
      >
        <label className="block space-y-1">
          <Eyebrow as="span">Outings allowed</Eyebrow>
          <Input
            inputMode="numeric"
            value={capText}
            onChange={(event) => setDraft({ cap: event.target.value, period })}
            aria-invalid={showError}
            aria-describedby={showError ? 'plan-cap-error' : undefined}
            placeholder="4"
          />
        </label>
        {showError && (
          <p id="plan-cap-error" role="alert" className="text-sm text-destructive">
            Enter a whole number from 1 to {MAX_PLAN_CAP}
          </p>
        )}
        <SegmentedToggle<PlanPeriod>
          ariaLabel="Plan period"
          value={period}
          onChange={(next) => setDraft({ cap: capText, period: next })}
          options={[{ value: 'week', label: 'Per week' }, { value: 'month', label: 'Per month' }]}
        />
        <p className="text-xs text-paper-faint">Counts every cinema trip, Monday to Sunday for a week or by calendar month. Only you can see this.</p>
        <div className="flex gap-2">
          <Button type="submit" size="sm" disabled={cap === null}>{current ? 'Save plan' : 'Set plan'}</Button>
          {current && (
            <Button type="button" size="sm" variant="ghost" onClick={() => void setMoviegoingPlan(null).then(() => setDraft(null))}>Remove plan</Button>
          )}
        </div>
      </form>
    </section>
  )
}

export function RecapSection({ trips, initialYear }: { trips: MoviegoingTrip[]; initialYear: number }) {
  const years = recapYears(trips)
  const [year, setYear] = useState(initialYear)
  const [notice, setNotice] = useState('')
  const recap = moviegoingRecap(trips, year)
  if (!recap) return null
  return (
    <section aria-label="Your year at the movies" className="space-y-3">
      <h3 className="font-serif text-lg text-paper">Your year at the movies</h3>
      {years.length > 1 && (
        <label className="flex items-center gap-2">
          <Eyebrow as="span">Year</Eyebrow>
          <select
            value={year}
            onChange={(event) => { setYear(Number(event.target.value)); setNotice('') }}
            className="h-9 rounded-md border border-input bg-background px-2 text-sm text-paper"
          >
            {years.map((option) => <option key={option} value={option}>{option}</option>)}
          </select>
        </label>
      )}
      <div className="space-y-3 rounded-lg border border-amber/30 p-4" role="group" aria-label={`${recap.year} recap`}>
        <Eyebrow as="p">{recap.year} · at the movies</Eyebrow>
        <p className="flex items-end gap-2">
          <span className="stat-num text-[40px] leading-none" style={{ color: 'var(--amber-bright)' }}>{recap.tripCount}</span>
          <span className="pb-1 text-paper">{recap.tripCount === 1 ? 'trip' : 'trips'}</span>
        </p>
        <dl className="space-y-2">
          {recapFields(recap, fmtCurrency).map((field) => (
            <div key={field.label}>
              <dt><Eyebrow as="span">{field.label}</Eyebrow></dt>
              <dd className="text-paper">{field.value}</dd>
            </div>
          ))}
        </dl>
      </div>
      <div className="flex items-center gap-3">
        <Button
          type="button"
          size="sm"
          onClick={() => {
            void shareRecap(recap, fmtCurrency).then((result) => {
              setNotice(result === 'downloaded' ? 'Image saved.' : result === 'unavailable' ? recapSummary(recap, fmtCurrency) : '')
            })
          }}
        >
          Share
        </Button>
        <p role="status" className="min-w-0 text-xs text-paper-faint">{notice}</p>
      </div>
    </section>
  )
}
