import React from 'react'
// Reuse the previous OpenMove icons (journey implementation)
// to render distinct glyphs for bikeshare (and other modes).
// This mirrors the style you shared from your earlier version.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
import OpenMoveModeIcon from './icons/openmove-mode-icon'

export default function NoiModeIcon({ mode, ...props }: { mode: string }) {
  if (!mode) return null
  return <OpenMoveModeIcon mode={mode} {...props} />
}
