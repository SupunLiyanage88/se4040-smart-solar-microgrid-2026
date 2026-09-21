import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

export function AccountDialog({ titleId, busy, onClose, children }: { titleId: string; busy: boolean; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])
  return <dialog ref={dialog} className="panel dialog-panel p-4" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); if (!busy) onClose() }}>{children}</dialog>
}
