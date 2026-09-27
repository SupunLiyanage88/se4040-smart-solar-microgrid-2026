import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'
import type { Role } from './api'
import { AccountDialog } from './AccountDialog'

type ReservationStatus = 'PENDING' | 'APPROVED' | 'COMPLETED' | 'CANCELLED' | 'REJECTED'
type Direction = 'DROP_OFF' | 'CHARGING'

type Reservation = {
  id: string; prosumerNic: string; nodeId: string; slotId: string; bookingSlotId: string
  status: ReservationStatus; direction: Direction; requestedKwh: number
  startsAtUtc: string; endsAtUtc: string
  qrToken: string | null
  createdAtUtc: string; updatedAtUtc: string
}

// Hub fields used to pick a node and a battery slot.
type PickerSlot = { id: string; name: string; capacityKwh: number; isAvailable: boolean }
type PickerHours = { dayOfWeek: number; opensAt: string; closesAt: string }
type PickerNode = { id: string; name: string; address: string; isActive: boolean; batterySlots: PickerSlot[]; schedule: PickerHours[] }

type ReservationDraft = {
  nodeId: string; slotId: string; direction: Direction; requestedKwh: number
  startsAtLocal: string
  endsAtLocal: string
  prosumerNic: string
}

type Summary = { pendingCount: number; approvedFutureCount: number }
type View = 'current' | 'pending' | 'history' | 'all'

// Sri Lanka time is UTC+5:30. The form converts it before saving.
const dayNames = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const toUtcIso = (local: string) => new Date(`${local}:00+05:30`).toISOString()
const colomboPart = (utcIso: string, type: Intl.DateTimeFormatPartTypes) =>
  new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Colombo', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    .formatToParts(new Date(utcIso)).find(part => part.type === type)?.value ?? ''
const toLocalInputValue = (utcIso: string) => `${colomboPart(utcIso, 'year')}-${colomboPart(utcIso, 'month')}-${colomboPart(utcIso, 'day')}T${colomboPart(utcIso, 'hour')}:${colomboPart(utcIso, 'minute')}`
const formatLocal = (utcIso: string) => new Date(utcIso).toLocaleString('en-LK', { timeZone: 'Asia/Colombo' })

function freshDraft(): ReservationDraft {
  return { nodeId: '', slotId: '', direction: 'DROP_OFF', requestedKwh: 1, startsAtLocal: '', endsAtLocal: '', prosumerNic: '' }
}

const statusBadge: Record<ReservationStatus, string> = {
  PENDING: 'text-bg-warning', APPROVED: 'text-bg-success', COMPLETED: 'text-bg-info',
  CANCELLED: 'text-bg-secondary', REJECTED: 'text-bg-danger',
}
const statusLabel: Record<ReservationStatus, string> = {
  PENDING: 'Pending', APPROVED: 'Approved', COMPLETED: 'Completed',
  CANCELLED: 'Cancelled', REJECTED: 'Rejected',
}

export function ReservationManagement({ token, role, onUnauthorized }: { token: string; role: Role; onUnauthorized: () => void }) {
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [summary, setSummary] = useState<Summary>({ pendingCount: 0, approvedFutureCount: 0 })
  const [nodes, setNodes] = useState<PickerNode[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [view, setView] = useState<View>('current')
  const [search, setSearch] = useState('')
  const [searchTerm, setSearchTerm] = useState('')
  const [approvedOnly, setApprovedOnly] = useState(false)
  const [editing, setEditing] = useState<Reservation | 'new' | null>(null)
  const [draft, setDraft] = useState<ReservationDraft>(freshDraft)
  const [confirmCancel, setConfirmCancel] = useState<Reservation | null>(null)
  const [completing, setCompleting] = useState(false)
  const [qrTokenInput, setQrTokenInput] = useState('')
  const [reload, setReload] = useState(0)
  const admin = role === 'BACKOFFICE'
  const gridOperator = role === 'GRID_OPERATOR'

  useEffect(() => {
    let current = true
    Promise.all([
      api<Reservation[]>(`/reservations?view=${view}`, token),
      api<Summary>('/reservations/summary', token),
    ]).then(([list, sum]) => { if (current) { setReservations(list); setSummary(sum); setError('') } })
      .catch(reason => {
        if (!current) return
        if (reason instanceof ApiError && reason.status === 401) onUnauthorized()
        else setError(reason instanceof Error ? reason.message : 'Unable to load reservations.')
      }).finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [token, onUnauthorized, view, reload])

  // Load hubs so the table and the form can show names and opening hours.
  useEffect(() => {
    let current = true
    api<PickerNode[]>('/nodes', token).then(result => { if (current) setNodes(result) }).catch(() => { /* show ids when hub names are unavailable */ })
    return () => { current = false }
  }, [token])

  function failure(reason: unknown) {
    if (reason instanceof ApiError && reason.status === 401) onUnauthorized()
    else setError(reason instanceof Error ? reason.message : 'The operation could not be completed.')
  }
  function nodeName(nodeId: string) { return nodes.find(n => n.id === nodeId)?.name ?? nodeId }
  function slotName(nodeId: string, slotId: string) {
    return nodes.find(n => n.id === nodeId)?.batterySlots.find(s => s.id === slotId)?.name ?? slotId
  }
  function replace(reservation: Reservation) {
    setReservations(previous => [...previous.filter(r => r.id !== reservation.id), reservation]
      .sort((a, b) => a.startsAtUtc.localeCompare(b.startsAtUtc)))
  }
  function open(reservation: Reservation | 'new') {
    setError(''); setNotice(''); setEditing(reservation)
    setDraft(reservation === 'new' ? freshDraft() : {
      nodeId: reservation.nodeId, slotId: reservation.slotId, direction: reservation.direction,
      requestedKwh: reservation.requestedKwh, startsAtLocal: toLocalInputValue(reservation.startsAtUtc),
      endsAtLocal: toLocalInputValue(reservation.endsAtUtc), prosumerNic: reservation.prosumerNic,
    })
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      const body: Record<string, unknown> = {
        nodeId: draft.nodeId, slotId: draft.slotId, direction: draft.direction,
        requestedKwh: draft.requestedKwh, startsAtUtc: toUtcIso(draft.startsAtLocal), endsAtUtc: toUtcIso(draft.endsAtLocal),
      }
      if (editing === 'new') body.prosumerNic = draft.prosumerNic
      const path = editing === 'new' ? '/reservations' : `/reservations/${(editing as Reservation).id}`
      const result = await api<Reservation>(path, token, editing === 'new' ? 'POST' : 'PUT', body)
      replace(result); setEditing(null); setNotice(editing === 'new' ? 'Reservation created successfully.' : 'Reservation updated successfully.')
      setReload(v => v + 1)
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  async function decide(reservation: Reservation, decision: 'APPROVE' | 'REJECT') {
    setBusy(true); setError(''); setNotice('')
    try {
      const result = await api<Reservation>(`/reservations/${reservation.id}/decision`, token, 'POST', { decision })
      replace(result); setNotice(`Reservation ${decision === 'APPROVE' ? 'approved' : 'rejected'}.`); setReload(v => v + 1)
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  async function cancel(reservation: Reservation) {
    setBusy(true); setError(''); setNotice('')
    try {
      const result = await api<Reservation>(`/reservations/${reservation.id}/cancel`, token, 'POST')
      replace(result); setConfirmCancel(null); setNotice('Reservation cancelled.'); setReload(v => v + 1)
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  async function completeTransaction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      await api('/reservations/complete', token, 'POST', { qrToken: qrTokenInput })
      setCompleting(false); setQrTokenInput(''); setNotice('Transaction completed successfully.'); setReload(v => v + 1)
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }

  const selectedNode = nodes.find(n => n.id === draft.nodeId)
  const pickerNodes = nodes.filter(n => n.isActive || n.id === draft.nodeId)
  const changeable = (r: Reservation) => r.status === 'PENDING' || r.status === 'APPROVED'
  const term = searchTerm.trim().toLowerCase()
  const shown = reservations.filter(r => {
    if (approvedOnly && (r.status !== 'APPROVED' || new Date(r.startsAtUtc) <= new Date())) return false
    if (!term) return true
    return r.prosumerNic.toLowerCase().includes(term)
      || nodeName(r.nodeId).toLowerCase().includes(term)
      || slotName(r.nodeId, r.slotId).toLowerCase().includes(term)
  })

  return <section aria-label="Reservation management">
    <div className="d-flex flex-wrap justify-content-between gap-3 mb-4">
      <div><h2 className="h3">Reservations</h2><p className="text-secondary mb-0">{admin ? 'Book, change and cancel energy slots.' : 'Review bookings, approve them, and mark a transfer done.'}</p></div>
      <div className="d-flex gap-2 align-self-start">
        {admin && <button className="btn btn-primary" onClick={() => open('new')} disabled={busy}>+ Create reservation</button>}
        {gridOperator && <button className="btn btn-outline-primary" onClick={() => { setError(''); setNotice(''); setQrTokenInput(''); setCompleting(true) }} disabled={busy}>Mark transfer done</button>}
      </div>
    </div>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {notice && <div className="alert alert-success" role="status">{notice}</div>}

    <div className="row g-3 mb-4">
      <div className="col-md-6"><button className={`stat-card panel w-100 text-start ${view === 'pending' && !approvedOnly ? 'selected' : ''}`} onClick={() => { setApprovedOnly(false); setView('pending') }} aria-pressed={view === 'pending' && !approvedOnly}>
        <span>Pending decisions</span><strong>{summary.pendingCount}</strong><span className="small">View pending</span>
      </button></div>
      <div className="col-md-6"><button className={`stat-card panel w-100 text-start ${approvedOnly ? 'selected' : ''}`} onClick={() => { setApprovedOnly(true); setView('current') }} aria-pressed={approvedOnly}>
        <span>Approved (upcoming)</span><strong>{summary.approvedFutureCount}</strong><span className="small">View approved</span>
      </button></div>
    </div>

    <div className="d-flex flex-wrap gap-2 mb-4">
      <select className="form-select w-auto" aria-label="Filter reservations" value={approvedOnly ? 'approved' : view} onChange={event => { if (event.target.value === 'approved') return; setApprovedOnly(false); setView(event.target.value as View) }}>
        {approvedOnly && <option value="approved">Approved (upcoming)</option>}
        <option value="current">Current</option>
        <option value="pending">Pending</option>
        <option value="history">History</option>
        <option value="all">All</option>
      </select>
      <input type="search" className="form-control reservation-search" aria-label="Search reservations" placeholder="Search by prosumer NIC, hub or slot"
        value={search} onChange={event => setSearch(event.target.value)}
        onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); setSearchTerm(search) } }}
        onBlur={() => setSearchTerm(search)} />
      <button className="btn btn-outline-secondary" disabled={busy || loading} onClick={() => { setLoading(true); setReload(v => v + 1) }}>Refresh</button>
    </div>

    {loading ? <p role="status">Loading reservations...</p> : shown.length === 0 ? <div className="panel p-5 text-center"><h3 className="h5">No reservations found</h3><p className="text-secondary mb-0">Try another search or view filter.</p></div> :
      <div className="table-responsive"><table className="table align-middle">
        <thead><tr><th>Prosumer</th><th>Node / Slot</th><th>Direction</th><th>kWh</th><th>Starts (Sri Lanka)</th><th>Ends (Sri Lanka)</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{shown.map(r => <tr key={r.id}>
          <td className="text-nowrap">{r.prosumerNic}</td>
          <td>{nodeName(r.nodeId)}<div className="small text-secondary">{slotName(r.nodeId, r.slotId)}</div></td>
          <td>{r.direction === 'DROP_OFF' ? 'Drop-off' : 'Charging'}</td>
          <td>{r.requestedKwh.toLocaleString()}</td>
          <td className="text-nowrap">{formatLocal(r.startsAtUtc)}</td>
          <td className="text-nowrap">{formatLocal(r.endsAtUtc)}</td>
          <td><span className={`badge ${statusBadge[r.status]}`}>{statusLabel[r.status]}</span></td>
          <td><div className="d-flex gap-2 flex-wrap">
            {r.status === 'PENDING' && <>
              <button className="btn btn-sm btn-outline-success" disabled={busy} onClick={() => void decide(r, 'APPROVE')}>Approve</button>
              <button className="btn btn-sm btn-outline-danger" disabled={busy} onClick={() => void decide(r, 'REJECT')}>Reject</button>
            </>}
            {admin && changeable(r) && <button className="btn btn-sm btn-outline-secondary" disabled={busy} onClick={() => open(r)}>Edit</button>}
            {changeable(r) && <button className="btn btn-sm btn-outline-danger" disabled={busy} onClick={() => { setError(''); setConfirmCancel(r) }}>Cancel</button>}
          </div></td>
        </tr>)}</tbody>
      </table></div>}

    {editing && <AccountDialog titleId="reservation-editor-title" busy={busy} onClose={() => setEditing(null)}>
      <h2 id="reservation-editor-title" className="h4">{editing === 'new' ? 'Create reservation' : 'Edit reservation'}</h2>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      <form onSubmit={save}>
        <label className="form-label" htmlFor="reservation-node">Grid node</label>
        <select id="reservation-node" className="form-select mb-3" required value={draft.nodeId}
          onChange={event => setDraft({ ...draft, nodeId: event.target.value, slotId: '' })}>
          <option value="">Select a node</option>
          {pickerNodes.map(n => <option key={n.id} value={n.id}>{n.name} — {n.address}{n.isActive ? '' : ' (inactive)'}</option>)}
        </select>
        <label className="form-label" htmlFor="reservation-slot">Battery slot</label>
        <select id="reservation-slot" className="form-select mb-3" required disabled={!selectedNode} value={draft.slotId}
          onChange={event => setDraft({ ...draft, slotId: event.target.value })}>
          <option value="">Select a slot</option>
          {selectedNode?.batterySlots.map(s => <option key={s.id} value={s.id} disabled={!s.isAvailable}>{s.name} ({s.capacityKwh} kWh){!s.isAvailable ? ' — unavailable' : ''}</option>)}
        </select>
        <label className="form-label" htmlFor="reservation-direction">Direction</label>
        <select id="reservation-direction" className="form-select mb-3" required value={draft.direction}
          onChange={event => setDraft({ ...draft, direction: event.target.value as Direction })}>
          <option value="DROP_OFF">Drop-off (feed into grid)</option>
          <option value="CHARGING">Charging (draw from grid)</option>
        </select>
        <label className="form-label" htmlFor="reservation-kwh">Requested kWh</label>
        <input id="reservation-kwh" type="number" min="0.01" step="any" required className="form-control mb-3"
          value={draft.requestedKwh} onChange={event => setDraft({ ...draft, requestedKwh: event.target.valueAsNumber })} />
        <div className="row g-2 mb-3">
          <div className="col-6"><label className="form-label" htmlFor="reservation-start">Starts (Sri Lanka time)</label>
            <input id="reservation-start" type="datetime-local" required className="form-control" value={draft.startsAtLocal}
              onChange={event => setDraft({ ...draft, startsAtLocal: event.target.value })} /></div>
          <div className="col-6"><label className="form-label" htmlFor="reservation-end">Ends (Sri Lanka time)</label>
            <input id="reservation-end" type="datetime-local" required className="form-control" value={draft.endsAtLocal}
              onChange={event => setDraft({ ...draft, endsAtLocal: event.target.value })} /></div>
        </div>
        {selectedNode && <p className="small text-secondary">{(selectedNode.schedule ?? []).length === 0 ? 'This hub has no opening hours.' : `Open ${(selectedNode.schedule ?? []).map(hours => `${dayNames[hours.dayOfWeek]} ${hours.opensAt}–${hours.closesAt}`).join(', ')}.`} Must be inside these hours.</p>}
        {editing !== 'new' && <p className="small text-secondary">Changes need 12 hours' notice.</p>}
        {editing === 'new' ? <><label className="form-label" htmlFor="reservation-nic">Prosumer NIC</label>
          <input id="reservation-nic" className="form-control mb-3" required pattern="([0-9]{12}|[0-9]{9}[vVxX])" title="12 digits, or 9 digits followed by V or X"
            value={draft.prosumerNic} onChange={event => setDraft({ ...draft, prosumerNic: event.target.value })} /></> :
          <p className="small text-secondary">Prosumer NIC {(editing as Reservation).prosumerNic}. The reservation owner cannot be changed here.</p>}
        <div className="d-flex justify-content-end gap-2 mt-4">
          <button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={() => setEditing(null)}>Cancel</button>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Saving...' : 'Save reservation'}</button>
        </div>
      </form>
    </AccountDialog>}

    {confirmCancel && <AccountDialog titleId="cancel-confirm-title" busy={busy} onClose={() => setConfirmCancel(null)}>
      <h2 id="cancel-confirm-title" className="h4">Cancel reservation?</h2>
      <p>Cancellation needs 12 hours' notice.</p>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      <div className="d-flex justify-content-end gap-2">
        <button className="btn btn-outline-secondary" disabled={busy} onClick={() => setConfirmCancel(null)}>Keep it</button>
        <button className="btn btn-primary" disabled={busy} onClick={() => void cancel(confirmCancel)}>{busy ? 'Cancelling...' : 'Cancel reservation'}</button>
      </div>
    </AccountDialog>}

    {completing && <AccountDialog titleId="complete-title" busy={busy} onClose={() => setCompleting(false)}>
      <h2 id="complete-title" className="h4">Mark transfer done</h2>
      <p className="text-secondary">Paste the code from the prosumer's phone.</p>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      <form onSubmit={completeTransaction}>
        <label className="form-label" htmlFor="qr-token">QR token</label>
        <input id="qr-token" className="form-control mb-3" required autoFocus value={qrTokenInput} onChange={event => setQrTokenInput(event.target.value)} />
        <div className="d-flex justify-content-end gap-2">
          <button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={() => setCompleting(false)}>Cancel</button>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Saving...' : 'Mark transfer done'}</button>
        </div>
      </form>
    </AccountDialog>}
  </section>
}
