import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'
import type { Role } from './api'
import { AccountDialog } from './AccountDialog'

type Slot = { id?: string; name: string; capacityKwh: number; isAvailable: boolean }
type Hours = { dayOfWeek: number; opensAt: string; closesAt: string }
type NodeDraft = { name: string; address: string; latitude: number; longitude: number; powerCapacityKw: number; batterySlots: Slot[]; schedule: Hours[] }
type GridNode = NodeDraft & { id: string; revision: number; isActive: boolean; timeZone: string; activeReservationCount: number }
const days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const fresh = (): NodeDraft => ({ name: '', address: '', latitude: 6.9271, longitude: 79.8612, powerCapacityKw: 10,
  batterySlots: [{ name: 'Battery 1', capacityKwh: 10, isAvailable: true }],
  schedule: [1, 2, 3, 4, 5].map(dayOfWeek => ({ dayOfWeek, opensAt: '08:00', closesAt: '18:00' })) })

export function NodeManagement({ token, role, onUnauthorized }: { token: string; role: Role; onUnauthorized: () => void }) {
  const [nodes, setNodes] = useState<GridNode[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')
  const [editing, setEditing] = useState<GridNode | 'new' | null>(null)
  const [draft, setDraft] = useState<NodeDraft>(fresh)
  const [confirm, setConfirm] = useState<GridNode | null>(null)
  const [reload, setReload] = useState(0)
  const admin = role === 'BACKOFFICE'
  const locked = editing !== null && editing !== 'new' && editing.activeReservationCount > 0

  useEffect(() => {
    let current = true
    api<GridNode[]>('/nodes', token).then(result => {
      if (current) { setNodes(result); setError('') }
    }).catch(reason => {
      if (!current) return
      if (reason instanceof ApiError && reason.status === 401) onUnauthorized()
      else setError(reason instanceof Error ? reason.message : 'Unable to load grid nodes.')
    }).finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [token, onUnauthorized, reload])

  function failure(reason: unknown) {
    if (reason instanceof ApiError && reason.status === 401) onUnauthorized()
    else setError(reason instanceof Error ? reason.message : 'The operation could not be completed.')
  }
  function open(node: GridNode | 'new') {
    setError(''); setNotice(''); setEditing(node)
    setDraft(node === 'new' ? fresh() : structuredClone(node))
  }
  function replace(node: GridNode) {
    setNodes(previous => [...previous.filter(item => item.id !== node.id), node].sort((a, b) => a.name.localeCompare(b.name)))
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      const path = editing === 'new' ? '/nodes' : `/nodes/${(editing as GridNode).id}`
      const result = await api<GridNode>(path, token, editing === 'new' ? 'POST' : 'PUT', {
        ...draft, ...(editing !== 'new' && editing ? { revision: editing.revision } : {}),
      })
      replace(result); setEditing(null); setNotice('Grid node saved successfully.')
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  async function changeStatus(node: GridNode) {
    setBusy(true); setError(''); setNotice('')
    try {
      replace(await api<GridNode>(`/nodes/${node.id}/status`, token, 'PATCH', { isActive: !node.isActive, revision: node.revision }))
      setConfirm(null); setNotice(`${node.name} ${node.isActive ? 'deactivated' : 'reactivated'}.`)
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  async function availability(node: GridNode, slot: Slot) {
    setBusy(true); setError(''); setNotice('')
    try {
      replace(await api<GridNode>(`/nodes/${node.id}/slots/${slot.id}/availability`, token, 'PATCH', { isAvailable: !slot.isAvailable, revision: node.revision }))
      setNotice('Battery-slot availability updated.')
    } catch (reason) { failure(reason) } finally { setBusy(false) }
  }
  function updateSlot(index: number, values: Partial<Slot>) {
    setDraft(previous => ({ ...previous, batterySlots: previous.batterySlots.map((slot, i) => i === index ? { ...slot, ...values } : slot) }))
  }
  function toggleDay(dayOfWeek: number) {
    setDraft(previous => ({ ...previous, schedule: previous.schedule.some(day => day.dayOfWeek === dayOfWeek)
      ? previous.schedule.filter(day => day.dayOfWeek !== dayOfWeek)
      : [...previous.schedule, { dayOfWeek, opensAt: '08:00', closesAt: '18:00' }].sort((a, b) => a.dayOfWeek - b.dayOfWeek) }))
  }
  function updateHours(dayOfWeek: number, values: Partial<Hours>) {
    setDraft(previous => ({ ...previous, schedule: previous.schedule.map(day => day.dayOfWeek === dayOfWeek ? { ...day, ...values } : day) }))
  }
  const shown = nodes.filter(node => (filter === 'all' || (filter === 'active' ? node.isActive : !node.isActive)) &&
    `${node.name} ${node.address}`.toLowerCase().includes(search.toLowerCase()))

  return <section aria-label="Microgrid node management">
    <div className="d-flex flex-wrap justify-content-between gap-3 mb-4">
      <div><h2 className="h3">Microgrid nodes</h2><p className="text-secondary mb-0">{admin ? 'Manage grid hubs, storage capacity and operating hours.' : 'Review grid hubs and update battery-slot availability.'}</p></div>
      {admin && <button className="btn btn-primary align-self-start" onClick={() => open('new')} disabled={busy}>+ Create node</button>}
    </div>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {notice && <div className="alert alert-success" role="status">{notice}</div>}
    <div className="row g-3 mb-4">
      <div className="col-md-4"><div className="panel p-3"><span className="text-secondary">Active hubs</span><strong className="d-block fs-3">{nodes.filter(node => node.isActive).length}</strong></div></div>
      <div className="col-md-4"><div className="panel p-3"><span className="text-secondary">Installed power</span><strong className="d-block fs-3">{nodes.filter(node => node.isActive).reduce((sum, node) => sum + node.powerCapacityKw, 0).toLocaleString()} kW</strong></div></div>
      <div className="col-md-4"><div className="panel p-3"><span className="text-secondary">Enabled battery slots</span><strong className="d-block fs-3">{nodes.filter(node => node.isActive).reduce((sum, node) => sum + node.batterySlots.filter(slot => slot.isAvailable).length, 0)}</strong></div></div>
    </div>
    <div className="d-flex flex-wrap gap-2 mb-4">
      <input type="search" aria-label="Search grid nodes" className="form-control node-search" placeholder="Search by hub name or address" value={search} onChange={event => setSearch(event.target.value)} />
      <select className="form-select w-auto" aria-label="Filter node status" value={filter} onChange={event => setFilter(event.target.value)}><option value="all">All nodes</option><option value="active">Active</option><option value="inactive">Inactive</option></select>
      <button className="btn btn-outline-secondary" disabled={busy || loading} onClick={() => { setLoading(true); setReload(value => value + 1) }}>Refresh nodes</button>
    </div>
    {loading ? <p role="status">Loading grid nodes...</p> : shown.length === 0 ? <div className="panel p-5 text-center"><h3 className="h5">No grid nodes found</h3><p className="text-secondary mb-0">{nodes.length === 0 ? (admin ? 'Create your first hub to define its location, battery slots and schedule.' : 'Backoffice has not created any hubs yet.') : 'Try another search or status filter.'}</p></div> :
      <div className="row g-4">{shown.map(node => <div className="col-xl-6" key={node.id}><article className="panel p-4 h-100">
        <div className="d-flex justify-content-between gap-3"><h3 className="h4">{node.name}</h3><span className={`badge align-self-start ${node.isActive ? 'text-bg-success' : 'text-bg-secondary'}`}>{node.isActive ? 'Active' : 'Inactive'}</span></div>
        <p className="text-secondary mb-1">{node.address}</p><p className="small text-secondary">GPS: {node.latitude}, {node.longitude} <a href={`https://www.google.com/maps?q=${node.latitude},${node.longitude}`} target="_blank" rel="noreferrer">View location</a></p>
        <div className="d-flex gap-4 my-3"><div><strong>{node.powerCapacityKw} kW</strong><div className="small text-secondary">Power capacity</div></div><div><strong>{node.batterySlots.reduce((sum, slot) => sum + slot.capacityKwh, 0).toLocaleString()} kWh</strong><div className="small text-secondary">Battery storage</div></div><div><strong>{node.activeReservationCount}</strong><div className="small text-secondary">Active reservations</div></div></div>
        <h4 className="h6 mt-4">Battery slots</h4><ul className="list-group list-group-flush mb-3">{node.batterySlots.map(slot => <li className="list-group-item px-0 d-flex justify-content-between align-items-center gap-2" key={slot.id}><span>{slot.name} <span className="small text-secondary">({slot.capacityKwh} kWh)</span><span className="d-block small">{slot.isAvailable ? 'Available for scheduling' : 'Unavailable'}</span></span><button className="btn btn-sm btn-outline-secondary" aria-label={`${slot.isAvailable ? 'Disable' : 'Enable'} ${slot.name} at ${node.name}`} disabled={busy || !node.isActive || node.activeReservationCount > 0} onClick={() => void availability(node, slot)}>{slot.isAvailable ? 'Mark unavailable' : 'Make available'}</button></li>)}</ul>
        <details className="mb-3"><summary className="fw-semibold">Weekly operating hours</summary><p className="small text-secondary mt-2">All times: {node.timeZone}. Unlisted days are closed.</p><ul className="small">{node.schedule.map(day => <li key={day.dayOfWeek}>{days[day.dayOfWeek]}: {day.opensAt} - {day.closesAt}</li>)}</ul></details>
        {node.activeReservationCount > 0 && <p className="small text-warning-emphasis">Active reservations prevent deactivation and changes to capacity, slots or schedules.</p>}
        {admin && <div className="d-flex gap-2"><button className="btn btn-outline-secondary" disabled={busy} onClick={() => open(node)}>Edit node</button><button className={`btn ${node.isActive ? 'btn-outline-danger' : 'btn-outline-success'}`} disabled={busy || (node.isActive && node.activeReservationCount > 0)} onClick={() => { setError(''); setConfirm(node) }}>{node.isActive ? 'Deactivate node' : 'Reactivate node'}</button></div>}
      </article></div>)}</div>}
    {editing && <AccountDialog titleId="node-editor-title" busy={busy} onClose={() => setEditing(null)}><h2 id="node-editor-title" className="h4">{editing === 'new' ? 'Create grid node' : 'Edit grid node'}</h2>
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      <form onSubmit={save}>
        <label className="form-label" htmlFor="node-name">Hub name</label><input autoFocus id="node-name" className="form-control mb-3" required minLength={2} maxLength={120} value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} />
        <label className="form-label" htmlFor="node-address">Address</label><input id="node-address" className="form-control mb-3" required minLength={2} maxLength={300} value={draft.address} onChange={event => setDraft({ ...draft, address: event.target.value })} />
        <div className="row g-2 mb-3"><div className="col-6"><label className="form-label" htmlFor="node-lat">Latitude</label><input id="node-lat" type="number" step="any" min={-90} max={90} required className="form-control" value={Number.isFinite(draft.latitude) ? draft.latitude : ''} onChange={event => setDraft({ ...draft, latitude: event.target.valueAsNumber })} /></div><div className="col-6"><label className="form-label" htmlFor="node-lon">Longitude</label><input id="node-lon" type="number" step="any" min={-180} max={180} required className="form-control" value={Number.isFinite(draft.longitude) ? draft.longitude : ''} onChange={event => setDraft({ ...draft, longitude: event.target.valueAsNumber })} /></div></div>
        {locked && <p className="alert alert-warning small">Only name, address and GPS can change while this node has active reservations.</p>}
        <fieldset disabled={locked || busy}>
          <label className="form-label" htmlFor="node-power">Power capacity (kW)</label><input id="node-power" type="number" min="0.01" max="100000000" step="any" required className="form-control mb-3" value={Number.isFinite(draft.powerCapacityKw) ? draft.powerCapacityKw : ''} onChange={event => setDraft({ ...draft, powerCapacityKw: event.target.valueAsNumber })} />
          <h3 className="h6">Battery storage slots</h3>
          {draft.batterySlots.map((slot, index) => <div className="border rounded p-3 mb-2" key={slot.id ?? `new-${index}`}>
            <label className="form-label" htmlFor={`slot-name-${index}`}>Slot {index + 1} name</label><input id={`slot-name-${index}`} className="form-control mb-2" required maxLength={60} value={slot.name} onChange={event => updateSlot(index, { name: event.target.value })} />
            <label className="form-label" htmlFor={`slot-capacity-${index}`}>Storage capacity (kWh)</label><input id={`slot-capacity-${index}`} type="number" min="0.01" max="100000000" step="any" required className="form-control mb-2" value={Number.isFinite(slot.capacityKwh) ? slot.capacityKwh : ''} onChange={event => updateSlot(index, { capacityKwh: event.target.valueAsNumber })} />
            <label className="form-check-label"><input className="form-check-input me-2" type="checkbox" checked={slot.isAvailable} onChange={event => updateSlot(index, { isAvailable: event.target.checked })} />Available for scheduling</label>
            <button type="button" className="btn btn-sm btn-link text-danger d-block px-0" disabled={draft.batterySlots.length === 1} onClick={() => setDraft({ ...draft, batterySlots: draft.batterySlots.filter((_, i) => i !== index) })}>Remove slot</button>
          </div>)}
          <button type="button" className="btn btn-sm btn-outline-secondary mb-4" disabled={draft.batterySlots.length >= 200} onClick={() => setDraft({ ...draft, batterySlots: [...draft.batterySlots, { name: `Battery ${draft.batterySlots.length + 1}`, capacityKwh: 10, isAvailable: true }] })}>+ Add battery slot</button>
          <h3 className="h6">Weekly schedule</h3><p className="small text-secondary">Asia/Colombo time. Select at least one day. Opening and closing must be on the same day.</p>
          {days.map((name, index) => { const day = draft.schedule.find(value => value.dayOfWeek === index); return <div className="mb-3" key={name}><label className="form-check-label mb-1"><input type="checkbox" className="form-check-input me-2" checked={Boolean(day)} onChange={() => toggleDay(index)} />{name}</label>{day && <div className="d-flex gap-2"><input type="time" className="form-control" required aria-label={`${name} opening time`} value={day.opensAt} onChange={event => updateHours(index, { opensAt: event.target.value })} /><input type="time" className="form-control" required aria-label={`${name} closing time`} value={day.closesAt} onChange={event => updateHours(index, { closesAt: event.target.value })} /></div>}</div> })}
        </fieldset>
        <div className="d-flex justify-content-end gap-2 mt-4"><button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={() => setEditing(null)}>Cancel</button><button className="btn btn-primary" disabled={busy || draft.schedule.length === 0}>{busy ? 'Saving...' : 'Save node'}</button></div>
      </form>
    </AccountDialog>}
    {confirm && <AccountDialog titleId="node-confirm-title" busy={busy} onClose={() => setConfirm(null)}><h2 id="node-confirm-title" className="h4">{confirm.isActive ? 'Deactivate' : 'Reactivate'} {confirm.name}?</h2><p>{confirm.isActive ? 'The node will be hidden from prosumers. Existing history will be retained. Deactivation is blocked if active reservations exist.' : 'The node will be visible to prosumers again, with its saved schedule and battery-slot availability.'}</p>{error && <div className="alert alert-danger" role="alert">{error}</div>}<div className="d-flex justify-content-end gap-2"><button className="btn btn-outline-secondary" disabled={busy} onClick={() => setConfirm(null)}>Cancel</button><button className="btn btn-primary" disabled={busy} onClick={() => void changeStatus(confirm)}>{busy ? 'Saving...' : 'Confirm node status'}</button></div></AccountDialog>}
  </section>
}
